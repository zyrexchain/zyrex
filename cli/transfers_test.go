package main

import (
	"bufio"
	"encoding/json"
	"math"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

const fixtureAddress = "ZRX-test-fixture"

func signedFixture(id string, amount, fee int64) map[string]any {
	return map[string]any{"id": id,
		"inputs":     []map[string]any{{"boxId": strings.Repeat("b", 64), "spendingProof": map[string]any{"proofBytes": "11", "extension": map[string]any{}}}},
		"dataInputs": []any{}, "outputs": []map[string]any{
			{"value": amount, "ergoTree": "1000", "assets": []any{}, "creationHeight": 614400, "additionalRegisters": map[string]any{}},
			{"value": fee, "ergoTree": legacyFeeScript, "assets": []any{}, "creationHeight": 614400, "additionalRegisters": map[string]any{}}}}
}

type feeServer struct {
	t                                     *testing.T
	server                                *httptest.Server
	id                                    string
	amount                                int64
	currentFee                            int64
	recommendation                        func(int64) int64
	quoteStatus                           int
	broadcastStatus                       int
	observed                              bool
	generated, quoted, checked, broadcast int
	bodies                                []string
	extraOutputs                          []map[string]any
	paymentAssets, feeAssets              []any
	ownedAddresses                        []string
	confirmedTransaction                  any
}

func newFeeServer(t *testing.T, amount int64) *feeServer {
	fixture := &feeServer{t: t, id: strings.Repeat("a", 64), amount: amount,
		recommendation: func(int64) int64 { return minimumFeeNano }, ownedAddresses: []string{"ZRX-owned-fixture"}}
	fixture.server = httptest.NewServer(http.HandlerFunc(fixture.handle))
	t.Cleanup(fixture.server.Close)
	t.Setenv("ZYREX_API_KEY", "private-test-fixture")
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	return fixture
}

func (f *feeServer) signed() map[string]any {
	transaction := signedFixture(f.id, f.amount, f.currentFee)
	outputs := transaction["outputs"].([]map[string]any)
	if f.paymentAssets != nil {
		outputs[0]["assets"] = f.paymentAssets
	}
	if f.feeAssets != nil {
		outputs[1]["assets"] = f.feeAssets
	}
	transaction["outputs"] = append(outputs, f.extraOutputs...)
	return transaction
}

func (f *feeServer) handle(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path == "/info" {
		json.NewEncoder(w).Encode(map[string]string{"network": "testnet", "genesisBlockId": testnetGenesis})
		return
	}
	if r.URL.Path == "/script/addressToTree/"+fixtureAddress {
		json.NewEncoder(w).Encode(map[string]string{"tree": "1000"})
		return
	}
	if r.URL.Path == "/script/addressToTree/ZRX-owned-fixture" {
		json.NewEncoder(w).Encode(map[string]string{"tree": "2000"})
		return
	}
	if r.Header.Get("api_key") != "private-test-fixture" {
		f.t.Error("missing wallet credential")
	}
	switch r.URL.Path {
	case "/wallet/transaction/generate":
		f.generated++
		var body struct {
			Requests []struct {
				Value int64 `json:"value"`
			} `json:"requests"`
			Fee int64 `json:"fee"`
		}
		if json.NewDecoder(r.Body).Decode(&body) != nil || len(body.Requests) != 1 || body.Requests[0].Value != f.amount {
			f.t.Error("incorrect integer transfer request")
		}
		f.currentFee = body.Fee
		json.NewEncoder(w).Encode(f.signed())
	case "/transactions/feeQuote":
		f.quoted++
		if f.quoteStatus != 0 {
			w.WriteHeader(f.quoteStatus)
			return
		}
		json.NewEncoder(w).Encode(feeQuote{1, formatDecimalInteger(f.recommendation(f.currentFee)), "1000000", 321, 98304,
			f.id, "recent-inclusions", legacyFeeScript, formatDecimalInteger(f.currentFee)})
	case "/transactions/check":
		f.checked++
		json.NewEncoder(w).Encode(f.id)
	case "/transactions":
		f.broadcast++
		var transaction json.RawMessage
		if json.NewDecoder(r.Body).Decode(&transaction) != nil {
			f.t.Error("invalid broadcast JSON")
		}
		f.bodies = append(f.bodies, string(transaction))
		if f.broadcastStatus != 0 {
			w.WriteHeader(f.broadcastStatus)
			return
		}
		json.NewEncoder(w).Encode(f.id)
	case "/wallet/transactionById":
		if r.URL.Query().Get("id") != f.id && f.confirmedTransaction == nil {
			f.t.Error("incorrect wallet history lookup ID")
		}
		if f.confirmedTransaction != nil {
			json.NewEncoder(w).Encode(f.confirmedTransaction)
		} else {
			w.WriteHeader(404)
		}
	case "/wallet/addresses":
		json.NewEncoder(w).Encode(f.ownedAddresses)
	case "/wallet/status":
		json.NewEncoder(w).Encode(map[string]string{"changeAddress": f.ownedAddresses[0]})
	case "/transactions/unconfirmed/byTransactionId/" + strings.Repeat("a", 64):
		if f.observed {
			json.NewEncoder(w).Encode(signedFixture(f.id, f.amount, f.currentFee))
		} else {
			w.WriteHeader(404)
		}
	default:
		f.t.Errorf("unexpected request %s", r.URL.Path)
		w.WriteHeader(400)
	}
}

func formatDecimalInteger(value int64) string { raw, _ := json.Marshal(value); return string(raw) }

func TestAutomaticFeeRepricesUsingExactSignedQuote(t *testing.T) {
	fixture := newFeeServer(t, 9007199254740993)
	fixture.recommendation = func(int64) int64 { return 2000000 }
	result, err := run([]string{"--node", fixture.server.URL, "send", fixtureAddress, "9007199.254740993"})
	if err != nil || strings.TrimSpace(string(result)) != `"`+fixture.id+`"` || fixture.generated != 2 || fixture.broadcast != 1 ||
		fixture.currentFee != 2000000 {
		t.Fatalf("auto transfer: %s %v; signs=%d broadcasts=%d", result, err, fixture.generated, fixture.broadcast)
	}
}

func TestUnstableAndOverflowFeeNeverBroadcast(t *testing.T) {
	for _, overflow := range []bool{false, true} {
		t.Run(formatDecimalInteger(boolInteger(overflow)), func(t *testing.T) {
			fixture := newFeeServer(t, 1000000000)
			fixture.recommendation = func(fee int64) int64 {
				if overflow {
					return math.MaxInt64
				}
				return fee + minimumFeeNano
			}
			if _, err := run([]string{"--node", fixture.server.URL, "send", fixtureAddress, "1"}); err == nil {
				t.Fatal("unbounded fee accepted")
			}
			if fixture.broadcast != 0 || fixture.generated > 4 {
				t.Fatal("unstable fee was broadcast or signing was unbounded")
			}
		})
	}
}

func boolInteger(value bool) int64 {
	if value {
		return 1
	}
	return 0
}

func TestOlderNodeFloorAndTransientQuoteFailure(t *testing.T) {
	for _, status := range []int{404, 405, 500} {
		t.Run(formatDecimalInteger(int64(status)), func(t *testing.T) {
			fixture := newFeeServer(t, 1000000000)
			fixture.quoteStatus = status
			_, err := run([]string{"--node", fixture.server.URL, "send", fixtureAddress, "1"})
			if status < 500 && (err != nil || fixture.currentFee != minimumFeeNano || fixture.broadcast != 1) {
				t.Fatal(err)
			}
			if status == 500 && (err == nil || fixture.broadcast != 0) {
				t.Fatal("server failure was silently treated as an old node")
			}
		})
	}
}

func TestAutomaticFeeLimitAndExplicitOverride(t *testing.T) {
	fixture := newFeeServer(t, 1000000000)
	fixture.recommendation = func(int64) int64 { return 11000000 }
	args := []string{"--node", fixture.server.URL, "send", fixtureAddress, "1"}
	if _, err := run(args); err == nil || !strings.Contains(err.Error(), "exceeds") {
		t.Fatal("Auto fee ceiling was ignored")
	}
	if fixture.broadcast != 0 || fixture.generated != 1 {
		t.Fatal("Excessive automatic fee was persisted or broadcast")
	}
	if _, err := run(append(args, "--max-fee", "0.02")); err != nil {
		t.Fatal(err)
	}
	if fixture.currentFee != 11000000 || fixture.broadcast != 1 {
		t.Fatal("Explicit Auto ceiling override failed")
	}
}

func TestUnknownBroadcastPreservesSignedTransactionAndRetryIdentity(t *testing.T) {
	fixture := newFeeServer(t, 1000000000)
	fixture.broadcastStatus = 503
	args := []string{"--node", fixture.server.URL, "send", fixtureAddress, "1", "--fee", "0.003"}
	if _, err := run(args); err == nil || !strings.Contains(err.Error(), fixture.id) {
		t.Fatal("unknown submission must retain its ID")
	}
	c, _ := newClient(fixture.server.URL, "private-test-fixture", "testnet")
	path, _ := c.preparedPath()
	original, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	info, _ := os.Stat(path)
	if info.Mode().Perm() != 0600 {
		t.Fatal("signed recovery is not owner-only")
	}
	if _, err = run(args); err == nil || fixture.generated != 1 {
		t.Fatal("a pending transfer was silently signed again")
	}
	fixture.broadcastStatus = 0
	// Higher new recommendations must not reprice a previously signed uncertain transaction.
	fixture.recommendation = func(int64) int64 { return 9000000 }
	if _, err = run([]string{"--node", fixture.server.URL, "retry"}); err != nil {
		t.Fatal(err)
	}
	if fixture.generated != 1 || fixture.currentFee != 3000000 || fixture.broadcast != 2 || fixture.bodies[0] != fixture.bodies[1] {
		t.Fatal("retry changed or regenerated the signed transaction")
	}
	if _, err = os.Stat(path); !os.IsNotExist(err) {
		t.Fatal("acknowledged transfer recovery record remains")
	}
	if !strings.Contains(string(original), `"feeNano":3000000`) {
		t.Fatal("saved fee lost integer precision")
	}
}

func TestAlreadyObservedRetryDoesNotBroadcast(t *testing.T) {
	fixture := newFeeServer(t, 1000000000)
	fixture.broadcastStatus = 503
	if _, err := run([]string{"--node", fixture.server.URL, "send", fixtureAddress, "1"}); err == nil {
		t.Fatal("fixture did not fail")
	}
	fixture.observed = true
	if _, err := run([]string{"--node", fixture.server.URL, "retry"}); err != nil {
		t.Fatal(err)
	}
	if fixture.generated != 1 || fixture.broadcast != 1 {
		t.Fatal("already observed transfer was sent again")
	}
}

func TestRecoveredTransactionValidationRejectsAlteredMetadata(t *testing.T) {
	fixture := newFeeServer(t, 1000000000)
	c, _ := newClient(fixture.server.URL, "private-test-fixture", "testnet")
	tx, _ := json.Marshal(signedFixture(fixture.id, fixture.amount, 3000000))
	prepared := &preparedTransfer{1, "testnet", testnetGenesis, fixtureAddress, 123, 3000000, fixture.id, tx}
	fixture.currentFee = 3000000
	if err := c.checkPrepared(prepared); err == nil {
		t.Fatal("modified recipient amount accepted")
	}
	prepared.Amount = fixture.amount
	prepared.Fee = 4000000
	if err := c.checkPrepared(prepared); err == nil {
		t.Fatal("modified fee accepted")
	}
	prepared.Fee = 3000000
	prepared.Genesis = devnetGenesis
	if err := c.checkPrepared(prepared); err == nil {
		t.Fatal("modified network accepted")
	}
	if fixture.broadcast != 0 {
		t.Fatal("invalid recovered transaction was broadcast")
	}
}

func TestPrivateRecoveryRejectsSymlinksAndTrailingJSON(t *testing.T) {
	directory := t.TempDir()
	path := filepath.Join(directory, "prepared.json")
	if err := os.WriteFile(path, []byte(`{} {}`), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := loadPrepared(path); err == nil {
		t.Fatal("trailing record accepted")
	}
	alias := filepath.Join(directory, "alias.json")
	if err := os.Symlink(path, alias); err != nil {
		t.Fatal(err)
	}
	if _, err := loadPrepared(alias); err == nil {
		t.Fatal("symbolic recovery path accepted")
	}
}

func TestTransferLockProcessHelper(t *testing.T) {
	if os.Getenv("ZYREX_TRANSFER_LOCK_HELPER") != "1" {
		return
	}
	lock, err := lockPrepared(os.Getenv("ZYREX_TRANSFER_PATH"))
	if err != nil {
		t.Fatal(err)
	}
	defer lock.Close()
	os.Stdout.WriteString("locked\n")
	bufio.NewReader(os.Stdin).ReadString('\n')
}

func TestConcurrentProcessCannotSendOrRetryDuringRecovery(t *testing.T) {
	fixture := newFeeServer(t, 1000000000)
	fixture.currentFee = minimumFeeNano
	c, _ := newClient(fixture.server.URL, "private-test-fixture", "testnet")
	path, _ := c.preparedPath()
	tx, _ := json.Marshal(signedFixture(fixture.id, fixture.amount, minimumFeeNano))
	prepared := &preparedTransfer{1, "testnet", testnetGenesis, fixtureAddress, fixture.amount, minimumFeeNano, fixture.id, tx}
	if err := savePrepared(path, prepared); err != nil {
		t.Fatal(err)
	}
	before, _ := os.ReadFile(path)
	command := exec.Command(os.Args[0], "-test.run=^TestTransferLockProcessHelper$")
	command.Env = append(os.Environ(), "ZYREX_TRANSFER_LOCK_HELPER=1", "ZYREX_TRANSFER_PATH="+path)
	stdin, _ := command.StdinPipe()
	stdout, _ := command.StdoutPipe()
	if err := command.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		stdin.Close()
		if command.ProcessState == nil {
			command.Process.Kill()
			command.Wait()
		}
	})
	line, err := bufio.NewReader(stdout).ReadString('\n')
	if err != nil || line != "locked\n" {
		t.Fatal("child did not acquire the persistent transaction lock", err, line)
	}
	for _, commandArgs := range [][]string{
		{"--node", fixture.server.URL, "send", fixtureAddress, "1"}, {"--node", fixture.server.URL, "retry"},
	} {
		if _, err := run(commandArgs); err == nil || !strings.Contains(err.Error(), "another wallet transfer") {
			t.Fatal("concurrent process was allowed to mutate pending recovery", err)
		}
	}
	after, _ := os.ReadFile(path)
	if string(before) != string(after) || fixture.generated != 0 || fixture.broadcast != 0 || fixture.checked != 0 {
		t.Fatal("blocked commands signed, checked, broadcast or modified pending recovery")
	}
	stdin.Write([]byte("release\n"))
	stdin.Close()
	if err := command.Wait(); err != nil {
		t.Fatal(err)
	}
	if _, err := run([]string{"--node", fixture.server.URL, "retry"}); err != nil {
		t.Fatal(err)
	}
	if fixture.broadcast != 1 || fixture.generated != 0 {
		t.Fatal("recovery after lock release did not preserve the signed transaction")
	}
	if info, err := os.Stat(filepath.Join(filepath.Dir(path), "prepared-transfer.lock")); err != nil || info.Mode().Perm() != 0600 {
		t.Fatal("persistent owner-only lock was removed or has unsafe permissions")
	}
}

func TestObservedRecoveryStillRequiresSignedIDBinding(t *testing.T) {
	fixture := newFeeServer(t, 1000000000)
	fixture.observed = true
	c, _ := newClient(fixture.server.URL, "private-test-fixture", "testnet")
	path, _ := c.preparedPath()
	tx, _ := json.Marshal(signedFixture(strings.Repeat("b", 64), fixture.amount, minimumFeeNano))
	prepared := &preparedTransfer{1, "testnet", testnetGenesis, fixtureAddress, fixture.amount, minimumFeeNano, fixture.id, tx}
	if err := savePrepared(path, prepared); err != nil {
		t.Fatal(err)
	}
	if _, err := run([]string{"--node", fixture.server.URL, "retry"}); err == nil {
		t.Fatal("corrupt recovery ID was silently cleared")
	}
	if _, err := os.Stat(path); err != nil {
		t.Fatal("corrupt recovery file was removed")
	}
}

func TestRecoveryCleanupNeverDeletesAReplacement(t *testing.T) {
	directory := t.TempDir()
	path := filepath.Join(directory, "prepared-transfer.json")
	tx, _ := json.Marshal(signedFixture(strings.Repeat("a", 64), 1000000000, minimumFeeNano))
	old := &preparedTransfer{1, "testnet", testnetGenesis, fixtureAddress, 1000000000, minimumFeeNano, strings.Repeat("a", 64), tx}
	changed := *old
	changed.ID = strings.Repeat("b", 64)
	changed.Transaction, _ = json.Marshal(signedFixture(changed.ID, 1000000000, minimumFeeNano))
	if err := savePrepared(path, &changed); err != nil {
		t.Fatal(err)
	}
	if err := clearPrepared(path, old); err == nil {
		t.Fatal("replacement recovery record was cleared by a previous acknowledgement")
	}
	current, err := loadPrepared(path)
	if err != nil || current.ID != changed.ID {
		t.Fatal("replacement signed recovery was not retained")
	}
}

func TestTransferIntentRejectsExtraExternalPaymentsAndAssets(t *testing.T) {
	for _, attack := range []string{"duplicate", "external", "recipient-assets", "fee-assets"} {
		t.Run(attack, func(t *testing.T) {
			fixture := newFeeServer(t, 1000000000)
			switch attack {
			case "duplicate":
				fixture.extraOutputs = []map[string]any{{"value": fixture.amount, "ergoTree": "1000"}}
			case "external":
				fixture.extraOutputs = []map[string]any{{"value": int64(123), "ergoTree": "3000"}}
			case "recipient-assets":
				fixture.paymentAssets = []any{map[string]any{"tokenId": strings.Repeat("c", 64), "amount": 1}}
			case "fee-assets":
				fixture.feeAssets = []any{map[string]any{"tokenId": strings.Repeat("c", 64), "amount": 1}}
			}
			if _, err := run([]string{"--node", fixture.server.URL, "send", fixtureAddress, "1"}); err == nil {
				t.Fatal("unreviewed external debit or asset transfer was accepted")
			}
			if fixture.broadcast != 0 || fixture.checked != 0 {
				t.Fatal("invalid intent reached native validation or broadcast")
			}
		})
	}
}

func TestTransferIntentKeepsOwnedTokenChangeAndSelfTransfers(t *testing.T) {
	for _, self := range []bool{false, true} {
		t.Run(formatDecimalInteger(boolInteger(self)), func(t *testing.T) {
			fixture := newFeeServer(t, 1000000000)
			tree := "2000"
			if self {
				fixture.ownedAddresses = []string{fixtureAddress}
				tree = "1000"
			}
			fixture.extraOutputs = []map[string]any{{"value": fixture.amount, "ergoTree": tree,
				"assets": []any{map[string]any{"tokenId": strings.Repeat("c", 64), "amount": 5}}}}
			if _, err := run([]string{"--node", fixture.server.URL, "send", fixtureAddress, "1"}); err != nil {
				t.Fatal(err)
			}
			if fixture.broadcast != 1 {
				t.Fatal("legitimate owned change was rejected")
			}
		})
	}
}

func TestBothCorruptedIDsNeverClearPendingRecovery(t *testing.T) {
	for _, legacy := range []bool{false, true} {
		t.Run(formatDecimalInteger(boolInteger(legacy)), func(t *testing.T) {
			fixture := newFeeServer(t, 1000000000)
			fixture.currentFee = minimumFeeNano
			if legacy {
				fixture.quoteStatus = 404
			}
			c, _ := newClient(fixture.server.URL, "private-test-fixture", "testnet")
			path, _ := c.preparedPath()
			wrongID := strings.Repeat("b", 64)
			tx, _ := json.Marshal(signedFixture(wrongID, fixture.amount, minimumFeeNano))
			prepared := &preparedTransfer{1, "testnet", testnetGenesis, fixtureAddress, fixture.amount, minimumFeeNano, wrongID, tx}
			if err := savePrepared(path, prepared); err != nil {
				t.Fatal(err)
			}
			fixture.confirmedTransaction = signedFixture(wrongID, 2000000000, minimumFeeNano)
			if _, err := run([]string{"--node", fixture.server.URL, "retry"}); err == nil {
				t.Fatal("unrelated known transaction cleared a corrupt signed recovery")
			}
			if _, err := os.Stat(path); err != nil {
				t.Fatal("original signed recovery was deleted")
			}
			if fixture.broadcast != 0 || fixture.generated != 0 || fixture.checked != 0 {
				t.Fatal("corrupt or already-spent recovery was checked as unspent, signed or broadcast")
			}
		})
	}
}

func TestLegacyObservationBindsCorePayloadAndIgnoresEnrichment(t *testing.T) {
	fixture := newFeeServer(t, 1000000000)
	fixture.currentFee, fixture.quoteStatus = minimumFeeNano, 404
	c, _ := newClient(fixture.server.URL, "private-test-fixture", "testnet")
	path, _ := c.preparedPath()
	base := signedFixture(fixture.id, fixture.amount, minimumFeeNano)
	tx, _ := json.Marshal(base)
	prepared := &preparedTransfer{1, "testnet", testnetGenesis, fixtureAddress, fixture.amount, minimumFeeNano, fixture.id, tx}
	if err := savePrepared(path, prepared); err != nil {
		t.Fatal(err)
	}
	base["inclusionHeight"] = 500
	base["outputs"].([]map[string]any)[0]["address"] = fixtureAddress
	base["inputs"].([]map[string]any)[0]["value"] = 2000000000
	fixture.confirmedTransaction = base
	if _, err := run([]string{"--node", fixture.server.URL, "retry"}); err != nil {
		t.Fatal(err)
	}
	if fixture.checked != 0 || fixture.broadcast != 0 {
		t.Fatal("already confirmed legacy payment was validated as unspent or resent")
	}
	for _, field := range []string{"inputs", "dataInputs", "outputs"} {
		broken := signedFixture(fixture.id, fixture.amount, minimumFeeNano)
		delete(broken, field)
		observed, _ := json.Marshal(broken)
		if err := bindObservedTransaction(prepared, observed, false); err == nil {
			t.Fatal("incomplete canonical payload accepted", field)
		}
	}
}
