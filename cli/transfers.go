package main

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"syscall"
)

const minimumFeeNano int64 = 1000000
const preparedFileLimit = 2 * 1024 * 1024

var transactionID = regexp.MustCompile(`^[0-9a-f]{64}$`)

// Pinned public testnet fee proposition: reward delay is five blocks.
const legacyFeeScript = "1005040004000e351002040a08cd0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798ea02d192" +
	"a39a8cc7a701730073011001020402d19683030193a38cc7b2a57300000193c2b2a57301007473027303830108cdeeac93b1a57304"

type preparedTransfer struct {
	Version     int             `json:"version"`
	Network     string          `json:"network"`
	Genesis     string          `json:"genesis"`
	Address     string          `json:"recipient"`
	Amount      int64           `json:"amountNano"`
	Fee         int64           `json:"feeNano"`
	ID          string          `json:"id"`
	Transaction json.RawMessage `json:"transaction"`
}

type feeQuote struct {
	Version int    `json:"quoteVersion"`
	Fee     string `json:"feeNano"`
	Minimum string `json:"minimumFeeNano"`
	Size    int    `json:"txSize"`
	Maximum int    `json:"maxTransactionSize"`
	ID      string `json:"txId"`
	Basis   string `json:"basis"`
	Script  string `json:"feeScriptHex"`
	Paid    string `json:"paidFeeNano"`
}

func (c *client) signedQuote(transaction json.RawMessage, id string) (*feeQuote, error) {
	raw, err := c.request("/transactions/feeQuote?waitTime=1", transaction, true)
	if err != nil {
		var status nodeHTTPError
		if errors.As(err, &status) && (status == 404 || status == 405) {
			return nil, nil
		}
		return nil, err
	}
	var quote feeQuote
	if err = json.Unmarshal(raw, &quote); err != nil {
		return nil, errors.New("invalid automatic fee quote")
	}
	fee, err := strconv.ParseInt(quote.Fee, 10, 64)
	minimum, floorErr := strconv.ParseInt(quote.Minimum, 10, 64)
	if err != nil || floorErr != nil || minimum < 0 || fee < minimum || quote.Version != 1 || quote.ID != id ||
		quote.Size < 1 || quote.Size > quote.Maximum || quote.Maximum > 1048576 {
		return nil, errors.New("invalid automatic fee quote")
	}
	if len(quote.Script) < 2 || len(quote.Script) > 4096 || len(quote.Script)%2 != 0 ||
		!regexp.MustCompile(`^[0-9a-f]+$`).MatchString(quote.Script) {
		return nil, errors.New("invalid fee script in automatic fee quote")
	}
	return &quote, nil
}

func (c *client) prepare(address string, amount int64, requestedFee string, maximumFee int64) (*preparedTransfer, error) {
	automatic := strings.EqualFold(requestedFee, "auto")
	fee := minimumFeeNano
	var err error
	if !automatic {
		fee, err = units(requestedFee)
		if err != nil {
			return nil, err
		}
	}
	for attempt := 0; attempt < 4; attempt++ {
		if amount <= 0 || fee < minimumFeeNano || amount > math.MaxInt64-fee {
			return nil, errors.New("amount plus fee exceeds the supported range, or fee is below 0.001 ZYRX")
		}
		transaction, err := c.request("/wallet/transaction/generate", map[string]any{
			"requests": []map[string]any{{"address": address, "value": amount}}, "fee": fee}, true)
		if err != nil {
			return nil, err
		}
		var identity struct {
			ID string `json:"id"`
		}
		if err = json.Unmarshal(transaction, &identity); err != nil || !transactionID.MatchString(identity.ID) {
			return nil, errors.New("node returned an invalid signed transaction ID")
		}
		basis, size := "manual", 0
		if automatic {
			quote, err := c.signedQuote(transaction, identity.ID)
			if err != nil {
				return nil, err
			}
			recommendation := minimumFeeNano
			if quote != nil {
				recommendation, _ = strconv.ParseInt(quote.Fee, 10, 64)
				size, basis = quote.Size, quote.Basis
				if recommendation < minimumFeeNano {
					recommendation = minimumFeeNano
				}
			} else {
				basis = "minimum fee (older node has no fee quote)"
			}
			if recommendation > maximumFee {
				return nil, fmt.Errorf("automatic fee exceeds %s ZYRX; nothing was submitted; wait or explicitly set --max-fee/--fee",
					formatNano(maximumFee))
			}
			if recommendation > fee {
				fee = recommendation
				continue
			}
		}
		prepared := &preparedTransfer{1, c.network, c.genesis, address, amount, fee, identity.ID, transaction}
		if err = c.checkPrepared(prepared); err != nil {
			return nil, err
		}
		fmt.Fprintf(os.Stderr, "Network fee: %s ZYRX (%s", formatNano(fee), basis)
		if size > 0 {
			fmt.Fprintf(os.Stderr, ", %d signed bytes", size)
		}
		fmt.Fprintf(os.Stderr, "); transaction %s\n", prepared.ID)
		return prepared, nil
	}
	return nil, errors.New("automatic fee did not stabilize; nothing was submitted; try again or use --fee FEE")
}

func formatNano(value int64) string {
	text := fmt.Sprintf("%d.%09d", value/1000000000, value%1000000000)
	return strings.TrimRight(strings.TrimRight(text, "0"), ".")
}

func (c *client) checkPrepared(prepared *preparedTransfer) error {
	if err := c.validatePreparedIntent(prepared); err != nil {
		return err
	}
	result, err := c.request("/transactions/check", prepared.Transaction, true)
	if err != nil {
		return err
	}
	var id string
	if json.Unmarshal(result, &id) != nil || id != prepared.ID {
		return errors.New("node checked a different transaction ID")
	}
	return nil
}

func (c *client) validatePreparedIntent(prepared *preparedTransfer) error {
	if err := c.validateRecoveryRecord(prepared); err != nil {
		return err
	}
	quote, err := c.signedQuote(prepared.Transaction, prepared.ID)
	if err != nil {
		return err
	}
	feeScript := legacyFeeScript
	if c.network == "devnet" {
		feeScript = strings.Replace(legacyFeeScript, "0e351002040a08cd", "0e351002040608cd", 1)
	}
	if quote != nil {
		feeScript = quote.Script
	}
	recipient, err := c.request("/script/addressToTree/"+url.PathEscape(prepared.Address), nil, false)
	if err != nil {
		return err
	}
	var script struct {
		Tree string `json:"tree"`
	}
	if json.Unmarshal(recipient, &script) != nil || script.Tree == "" {
		return errors.New("invalid recipient script")
	}
	var transaction struct {
		Inputs  []json.RawMessage `json:"inputs"`
		Outputs []struct {
			Value  int64           `json:"value"`
			Tree   string          `json:"ergoTree"`
			Assets json.RawMessage `json:"assets"`
		} `json:"outputs"`
	}
	if json.Unmarshal(prepared.Transaction, &transaction) != nil || len(transaction.Inputs) == 0 {
		return errors.New("invalid signed transaction outputs or inputs")
	}
	if len(transaction.Outputs) < 2 {
		return errors.New("signed transaction has no payment and fee outputs")
	}
	needed := false
	for _, output := range transaction.Outputs[1:] {
		if output.Tree != feeScript {
			needed = true
		}
	}
	owned := map[string]bool{}
	if needed {
		owned, err = c.walletChangeScripts(prepared.Address)
		if err != nil {
			return err
		}
	}
	paidFee := int64(0)
	for index, output := range transaction.Outputs {
		if output.Value <= 0 {
			return errors.New("invalid signed output amount")
		}
		bare := len(output.Assets) == 0
		if !bare {
			var assets []json.RawMessage
			if json.Unmarshal(output.Assets, &assets) != nil || bytes.Equal(bytes.TrimSpace(output.Assets), []byte("null")) {
				return errors.New("invalid signed output assets")
			}
			bare = len(assets) == 0
		}
		if index == 0 {
			if output.Tree != script.Tree || output.Value != prepared.Amount || !bare {
				return errors.New("signed payment output does not match reviewed recipient and amount")
			}
		} else if output.Tree == feeScript {
			if !bare {
				return errors.New("network fee output contains unexpected assets")
			}
			if paidFee > math.MaxInt64-output.Value {
				return errors.New("signed fee output overflow")
			}
			paidFee += output.Value
		} else if !owned[output.Tree] {
			return errors.New("signed transaction contains an additional external payment or unverified change")
		}
	}
	if paidFee != prepared.Fee {
		return errors.New("signed transaction does not match its recipient, amount or fee")
	}
	if quote != nil {
		actual, err := strconv.ParseInt(quote.Paid, 10, 64)
		if err != nil || actual != paidFee {
			return errors.New("node fee quote does not match signed fee outputs")
		}
	}
	return nil
}

func (c *client) walletChangeScripts(recipient string) (map[string]bool, error) {
	raw, err := c.request("/wallet/addresses", nil, true)
	if err != nil {
		return nil, err
	}
	var addresses []string
	if json.Unmarshal(raw, &addresses) != nil || len(addresses) == 0 || len(addresses) > 5000 {
		return nil, errors.New("wallet ownership addresses exceed supported bounds")
	}
	known := map[string]bool{}
	for _, address := range addresses {
		known[address] = true
	}
	candidates := map[string]bool{addresses[0]: true}
	if known[recipient] {
		candidates[recipient] = true
	}
	if len(addresses) > 1 {
		raw, err = c.request("/wallet/status", nil, true)
		if err != nil {
			return nil, err
		}
		var status struct {
			Change string `json:"changeAddress"`
		}
		if json.Unmarshal(raw, &status) != nil {
			return nil, errors.New("invalid wallet change address")
		}
		if known[status.Change] {
			candidates[status.Change] = true
		}
	}
	// Native single-payment generation sends all residual coin and token outputs to its current change address.
	owned := map[string]bool{}
	for address := range candidates {
		raw, err = c.request("/script/addressToTree/"+url.PathEscape(address), nil, false)
		if err != nil {
			return nil, err
		}
		var script struct {
			Tree string `json:"tree"`
		}
		if json.Unmarshal(raw, &script) != nil || script.Tree == "" {
			return nil, errors.New("invalid wallet change script")
		}
		owned[script.Tree] = true
	}
	return owned, nil
}

func (c *client) validateRecoveryRecord(prepared *preparedTransfer) error {
	if prepared.Version != 1 || prepared.Network != c.network || prepared.Genesis != c.genesis ||
		prepared.Amount <= 0 || prepared.Fee < minimumFeeNano || prepared.Amount > math.MaxInt64-prepared.Fee ||
		!transactionID.MatchString(prepared.ID) || len(prepared.Transaction) > preparedFileLimit || !json.Valid(prepared.Transaction) {
		return errors.New("saved transaction does not match the selected network or supported bounds")
	}
	if len(prepared.Address) < 1 || len(prepared.Address) > 4096 || (c.network == "testnet" && !strings.HasPrefix(prepared.Address, "ZRX")) {
		return errors.New("saved transaction has an invalid recipient")
	}
	var identity struct {
		ID string `json:"id"`
	}
	if err := json.Unmarshal(prepared.Transaction, &identity); err != nil || identity.ID != prepared.ID {
		return errors.New("saved signed transaction ID does not match its recovery record")
	}
	return nil
}

func (c *client) preparedPath() (string, error) {
	configuration, err := os.UserConfigDir()
	if err != nil {
		return "", err
	}
	root := filepath.Join(configuration, "zyrex-cli")
	for _, directory := range []string{root, filepath.Join(root, c.network)} {
		if err = os.MkdirAll(directory, 0700); err != nil {
			return "", err
		}
		info, err := os.Lstat(directory)
		if err != nil || !info.IsDir() || info.Mode()&os.ModeSymlink != 0 || info.Mode().Perm()&0077 != 0 {
			return "", errors.New("transaction recovery directory must be a real owner-only directory")
		}
		if owner, ok := info.Sys().(*syscall.Stat_t); !ok || owner.Uid != uint32(os.Getuid()) {
			return "", errors.New("transaction recovery directory belongs to another user")
		}
	}
	return filepath.Join(root, c.network, "prepared-transfer.json"), nil
}

func loadPrepared(path string) (*preparedTransfer, error) {
	descriptor, err := syscall.Open(path, syscall.O_RDONLY|syscall.O_NOFOLLOW, 0)
	if err != nil {
		return nil, err
	}
	file := os.NewFile(uintptr(descriptor), path)
	defer file.Close()
	info, err := file.Stat()
	if err != nil {
		return nil, err
	}
	if !info.Mode().IsRegular() || info.Mode().Perm()&0077 != 0 || info.Size() > preparedFileLimit {
		return nil, errors.New("transaction recovery file must be bounded, regular and owner-only")
	}
	if owner, ok := info.Sys().(*syscall.Stat_t); !ok || owner.Uid != uint32(os.Getuid()) {
		return nil, errors.New("transaction recovery file belongs to another user")
	}
	raw, err := io.ReadAll(io.LimitReader(file, preparedFileLimit+1))
	if err != nil {
		return nil, err
	}
	if len(raw) > preparedFileLimit {
		return nil, errors.New("transaction recovery file exceeds its size limit")
	}
	var prepared preparedTransfer
	decoder := json.NewDecoder(bytes.NewReader(raw))
	decoder.DisallowUnknownFields()
	if err = decoder.Decode(&prepared); err != nil {
		return nil, errors.New("invalid signed transaction recovery record")
	}
	if decoder.Decode(new(any)) != io.EOF {
		return nil, errors.New("invalid trailing recovery data")
	}
	return &prepared, nil
}

func savePrepared(path string, prepared *preparedTransfer) error {
	raw, err := json.Marshal(prepared)
	if err != nil || len(raw) > preparedFileLimit {
		return errors.New("signed transaction recovery record exceeds its size limit")
	}
	temporary, err := os.CreateTemp(filepath.Dir(path), ".prepared-*")
	if err != nil {
		return err
	}
	defer os.Remove(temporary.Name())
	if _, err = temporary.Write(raw); err != nil {
		temporary.Close()
		return err
	}
	if err = temporary.Sync(); err != nil {
		temporary.Close()
		return err
	}
	if err = temporary.Close(); err != nil {
		return err
	}
	// An exclusive hard-link publishes the complete file without overwriting an uncertain transfer.
	if err = os.Link(temporary.Name(), path); err != nil {
		return err
	}
	return syncDirectory(filepath.Dir(path))
}

func syncDirectory(path string) error {
	directory, err := os.Open(path)
	if err != nil {
		return err
	}
	defer directory.Close()
	return directory.Sync()
}

func lockPrepared(path string) (*os.File, error) {
	lockPath := filepath.Join(filepath.Dir(path), "prepared-transfer.lock")
	descriptor, err := syscall.Open(lockPath, syscall.O_RDWR|syscall.O_CREAT|syscall.O_NOFOLLOW|syscall.O_CLOEXEC, 0600)
	if err != nil {
		return nil, err
	}
	file := os.NewFile(uintptr(descriptor), lockPath)
	info, err := file.Stat()
	if err != nil {
		file.Close()
		return nil, err
	}
	owner, owned := info.Sys().(*syscall.Stat_t)
	if !info.Mode().IsRegular() || info.Mode().Perm() != 0600 || info.Size() != 0 || !owned ||
		owner.Uid != uint32(os.Getuid()) || owner.Nlink != 1 {
		file.Close()
		return nil, errors.New("transaction lock must be an empty owner-only regular file")
	}
	if err = syscall.Flock(descriptor, syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
		file.Close()
		return nil, errors.New("another wallet transfer or retry is running; the saved transaction was not changed")
	}
	// The kernel releases this lock on close or process exit. Its persistent file is never deleted.
	return file, nil
}

func clearPrepared(path string, expected *preparedTransfer) error {
	current, err := loadPrepared(path)
	if err != nil {
		return err
	}
	actual, _ := json.Marshal(current)
	original, _ := json.Marshal(expected)
	if !bytes.Equal(actual, original) {
		return errors.New("the saved signed transaction changed; its recovery record is preserved")
	}
	if err = os.Remove(path); err != nil {
		return err
	}
	return syncDirectory(filepath.Dir(path))
}

func (c *client) broadcastPrepared(path string, prepared *preparedTransfer) (json.RawMessage, error) {
	result, err := c.request("/transactions", prepared.Transaction, true)
	if err != nil {
		return nil, fmt.Errorf("submission result may be unknown for %s; signed transaction saved at %s; check history, then use retry: %w",
			prepared.ID, path, err)
	}
	var id string
	if json.Unmarshal(result, &id) != nil || id != prepared.ID {
		return nil, fmt.Errorf("node did not acknowledge %s; saved signed transaction is preserved; check history before retry", prepared.ID)
	}
	if err = clearPrepared(path, prepared); err != nil {
		return nil, fmt.Errorf("transaction %s accepted; recovery record could not be removed: %w", id, err)
	}
	return result, nil
}

func (c *client) sendPrepared(address string, amount int64, requestedFee string, maximumFee int64) (json.RawMessage, error) {
	path, err := c.preparedPath()
	if err != nil {
		return nil, err
	}
	lock, err := lockPrepared(path)
	if err != nil {
		return nil, err
	}
	defer lock.Close()
	if _, err = os.Lstat(path); !errors.Is(err, os.ErrNotExist) {
		return nil, errors.New("a saved transaction exists; check it with retry before preparing another transfer")
	}
	prepared, err := c.prepare(address, amount, requestedFee, maximumFee)
	if err != nil {
		return nil, err
	}
	if err = savePrepared(path, prepared); err != nil {
		return nil, err
	}
	return c.broadcastPrepared(path, prepared)
}

func (c *client) retryPrepared() (json.RawMessage, error) {
	path, err := c.preparedPath()
	if err != nil {
		return nil, err
	}
	lock, err := lockPrepared(path)
	if err != nil {
		return nil, err
	}
	defer lock.Close()
	prepared, err := loadPrepared(path)
	if err != nil {
		return nil, err
	}
	if err = c.validatePreparedIntent(prepared); err != nil {
		return nil, err
	}
	nativeBound, err := c.bindRecoveryIdentity(prepared)
	if err != nil {
		return nil, err
	}
	observed := false
	confirmed, err := c.request("/wallet/transactionById?id="+prepared.ID, nil, true)
	if err != nil {
		var status nodeHTTPError
		if !errors.As(err, &status) || status != 404 {
			return nil, err
		}
	} else {
		if err = bindObservedTransaction(prepared, confirmed, nativeBound); err != nil {
			return nil, err
		}
		observed = true
	}
	if !observed {
		pending, err := c.request("/transactions/unconfirmed/byTransactionId/"+prepared.ID, nil, true)
		if err != nil {
			var status nodeHTTPError
			if errors.As(err, &status) && status == 404 {
				pending = json.RawMessage("null")
			} else {
				return nil, err
			}
		}
		if !bytes.Equal(bytes.TrimSpace(pending), []byte("null")) {
			if err = bindObservedTransaction(prepared, pending, nativeBound); err != nil {
				return nil, err
			}
			observed = true
		}
	}
	if observed {
		if err = clearPrepared(path, prepared); err != nil {
			return nil, err
		}
		fmt.Fprintln(os.Stderr, "Saved transaction already observed; it was not broadcast again.")
		return json.Marshal(prepared.ID)
	}
	if err = c.checkPrepared(prepared); err != nil {
		return nil, err
	}
	fmt.Fprintf(os.Stderr, "Retrying the same signed transaction %s; fee remains %s ZYRX\n", prepared.ID, formatNano(prepared.Fee))
	return c.broadcastPrepared(path, prepared)
}
