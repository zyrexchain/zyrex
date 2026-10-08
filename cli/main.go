package main

import (
	"bytes"
	"crypto/tls"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"math"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"
)

var version = "0.1.0-testnet"

const testnetGenesis = "0f6e2d9181f10231dafa9e1aa3eb297218204e2e2c38f3c65ae0ab367629d336"
const devnetGenesis = "bb994c82d023bf2cf92f88be6f9f997f280b5fb76c3f8dac875f0458dcc51531"

func units(value string) (int64, error) {
	parts := strings.Split(value, ".")
	if len(parts) > 2 || parts[0] == "" {
		return 0, errors.New("amount must be a positive decimal with at most 9 decimal places")
	}
	for _, part := range parts {
		if part == "" {
			return 0, errors.New("invalid decimal amount")
		}
		for _, digit := range part {
			if digit < '0' || digit > '9' {
				return 0, errors.New("invalid decimal amount")
			}
		}
	}
	whole, err := strconv.ParseInt(parts[0], 10, 64)
	if err != nil || whole > math.MaxInt64/1000000000 {
		return 0, errors.New("amount exceeds the protocol integer range")
	}
	fraction := int64(0)
	if len(parts) == 2 {
		if len(parts[1]) > 9 {
			return 0, errors.New("amount has more than 9 decimal places")
		}
		fraction, err = strconv.ParseInt(parts[1]+strings.Repeat("0", 9-len(parts[1])), 10, 64)
		if err != nil {
			return 0, err
		}
	}
	amount := whole * 1000000000
	if amount > math.MaxInt64-fraction {
		return 0, errors.New("amount exceeds the protocol integer range")
	}
	amount += fraction
	if amount <= 0 {
		return 0, errors.New("amount must be positive")
	}
	return amount, nil
}

func privateFile(path string) (string, error) {
	file, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer file.Close()
	stat, err := file.Stat()
	if err != nil {
		return "", err
	}
	if !stat.Mode().IsRegular() || stat.Mode().Perm()&0077 != 0 {
		return "", errors.New("credential file must be a regular file readable only by its owner")
	}
	if stat.Size() > 16384 {
		return "", errors.New("credential file is too large")
	}
	data, err := io.ReadAll(io.LimitReader(file, 16385))
	if err != nil {
		return "", err
	}
	if len(data) > 16384 {
		return "", errors.New("credential file is too large")
	}
	text := strings.TrimSpace(string(data))
	if text == "" {
		return "", errors.New("credential file is empty")
	}
	return text, nil
}

type client struct {
	endpoint, key, network, genesis string
	http                            *http.Client
}

func newClient(endpoint, key, network string) (*client, error) {
	parsed, err := url.Parse(endpoint)
	if err != nil || parsed.Host == "" || (parsed.Scheme != "http" && parsed.Scheme != "https") ||
		parsed.User != nil || parsed.RawQuery != "" || parsed.Fragment != "" || (parsed.Path != "" && parsed.Path != "/") {
		return nil, errors.New("node must be an HTTP or HTTPS origin without credentials or a path")
	}
	genesis := testnetGenesis
	if network == "devnet" {
		genesis = devnetGenesis
	} else if network != "testnet" {
		return nil, errors.New("unsupported Zyrex network")
	}
	transport := &http.Transport{Proxy: nil, TLSClientConfig: &tls.Config{MinVersion: tls.VersionTLS12}}
	return &client{strings.TrimRight(endpoint, "/"), key, network, genesis, &http.Client{
		Transport: transport, Timeout: 60 * time.Second,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}}, nil
}

func (c *client) request(path string, payload any, authenticated bool) (json.RawMessage, error) {
	var body io.Reader
	method := "GET"
	if payload != nil {
		encoded, err := json.Marshal(payload)
		if err != nil {
			return nil, err
		}
		body = bytes.NewReader(encoded)
		method = "POST"
	}
	request, err := http.NewRequest(method, c.endpoint+path, body)
	if err != nil {
		return nil, err
	}
	request.Header.Set("Content-Type", "application/json")
	if authenticated {
		request.Header.Set("api_key", c.key)
	}
	response, err := c.http.Do(request)
	if err != nil {
		return nil, fmt.Errorf("cannot reach Zyrex node: %w", err)
	}
	defer response.Body.Close()
	data, err := io.ReadAll(io.LimitReader(response.Body, 4*1024*1024+1))
	if err != nil {
		return nil, err
	}
	if len(data) > 4*1024*1024 {
		return nil, errors.New("node response exceeds the supported size")
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return nil, fmt.Errorf("node returned HTTP %d", response.StatusCode)
	}
	if len(bytes.TrimSpace(data)) == 0 {
		data = []byte("null")
	}
	if !json.Valid(data) {
		return nil, errors.New("node returned invalid JSON")
	}
	return data, nil
}

func (c *client) verify() (json.RawMessage, error) {
	info, err := c.request("/info", nil, false)
	if err != nil {
		return nil, err
	}
	var identity struct {
		Network string `json:"network"`
		Genesis string `json:"genesisBlockId"`
	}
	if err = json.Unmarshal(info, &identity); err != nil {
		return nil, err
	}
	if identity.Network != c.network || identity.Genesis != c.genesis {
		return nil, errors.New("node identity does not match the pinned Zyrex network")
	}
	return info, nil
}

func usage() {
	fmt.Fprint(os.Stderr, "Zyrex CLI wallet\nUsage: zyrex-cli [options] COMMAND [arguments]\n\n")
	fmt.Fprintln(os.Stderr, "Commands: info, status, init, restore, unlock, lock, addresses, address, balance")
	fmt.Fprintln(os.Stderr, "          send ZRX_ADDRESS AMOUNT [--fee FEE]\n          version")
	fmt.Fprintln(os.Stderr, "Options: --node URL, --network testnet|devnet, --api-key-file FILE")
	fmt.Fprintln(os.Stderr, "         --password-file FILE, --mnemonic-file FILE")
	fmt.Fprintln(os.Stderr, "Environment: ZYREX_NODE_URL, ZYREX_API_KEY. Amounts are ZYRX.")
	fmt.Fprintln(os.Stderr, "Wallet operations require your local Zyrex node. Secrets stay in that node's wallet.")
}

func run(args []string) (json.RawMessage, error) {
	flags := flag.NewFlagSet("zyrex-cli", flag.ContinueOnError)
	flags.SetOutput(os.Stderr)
	flags.Usage = usage
	endpoint := os.Getenv("ZYREX_NODE_URL")
	if endpoint == "" {
		endpoint = "http://127.0.0.1:19554"
	}
	node := flags.String("node", endpoint, "Zyrex node API origin")
	network := flags.String("network", "testnet", "Zyrex network")
	keyFile := flags.String("api-key-file", "", "Private API credential file")
	passwordFile := flags.String("password-file", "", "Private wallet password file")
	mnemonicFile := flags.String("mnemonic-file", "", "Private mnemonic file")
	if err := flags.Parse(args); err != nil {
		return nil, err
	}
	rest := flags.Args()
	if len(rest) == 0 {
		usage()
		return nil, errors.New("a command is required")
	}
	command := rest[0]
	if command == "version" {
		return json.Marshal(map[string]string{"name": "Zyrex CLI", "version": version})
	}
	paths := map[string]string{"info": "/info", "status": "/wallet/status", "addresses": "/wallet/addresses",
		"address": "/wallet/deriveNextKey", "balance": "/wallet/balances", "init": "/wallet/init", "restore": "/wallet/restore",
		"unlock": "/wallet/unlock", "lock": "/wallet/lock", "send": "/wallet/transaction/send"}
	path, known := paths[command]
	if !known {
		return nil, fmt.Errorf("unknown command: %s", command)
	}
	if command != "send" && len(rest) != 1 {
		return nil, errors.New("unexpected command arguments")
	}
	key := os.Getenv("ZYREX_API_KEY")
	if *keyFile != "" {
		var err error
		key, err = privateFile(*keyFile)
		if err != nil {
			return nil, err
		}
	}
	c, err := newClient(*node, key, *network)
	if err != nil {
		return nil, err
	}
	info, err := c.verify()
	if err != nil {
		return nil, err
	}
	if command == "info" {
		return info, nil
	}
	if key == "" {
		return nil, errors.New("set ZYREX_API_KEY or --api-key-file for wallet operations")
	}
	var payload any
	if command == "init" || command == "restore" || command == "unlock" {
		password := ""
		if *passwordFile != "" {
			password, err = privateFile(*passwordFile)
		} else {
			password, err = readSecret("Wallet password: ")
		}
		if err != nil {
			return nil, err
		}
		if password == "" {
			return nil, errors.New("wallet password must not be empty")
		}
		body := map[string]any{"pass": password}
		if command == "restore" {
			mnemonic := ""
			if *mnemonicFile != "" {
				mnemonic, err = privateFile(*mnemonicFile)
			} else {
				mnemonic, err = readSecret("Wallet mnemonic: ")
			}
			if err != nil {
				return nil, err
			}
			if mnemonic == "" {
				return nil, errors.New("wallet mnemonic must not be empty")
			}
			body["mnemonic"] = mnemonic
			body["usePre1627KeyDerivation"] = false
		}
		payload = body
	} else if command == "send" {
		if len(rest) < 3 {
			return nil, errors.New("send requires a Zyrex address and amount")
		}
		sendFlags := flag.NewFlagSet("send", flag.ContinueOnError)
		fee := sendFlags.String("fee", "0.001", "Fee in ZYRX")
		if err = sendFlags.Parse(rest[3:]); err != nil {
			return nil, err
		}
		if len(sendFlags.Args()) != 0 {
			return nil, errors.New("unexpected send arguments")
		}
		if !strings.HasPrefix(rest[1], "ZRX") && *network == "testnet" {
			return nil, errors.New("testnet recipient must be a ZRX address")
		}
		amount, err := units(rest[2])
		if err != nil {
			return nil, err
		}
		cost, err := units(*fee)
		if err != nil {
			return nil, err
		}
		payload = map[string]any{"requests": []map[string]any{{"address": rest[1], "value": amount}}, "fee": cost}
	}
	return c.request(path, payload, true)
}

func main() {
	result, err := run(os.Args[1:])
	if errors.Is(err, flag.ErrHelp) {
		return
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "Error:", err)
		os.Exit(1)
	}
	var output bytes.Buffer
	if err = json.Indent(&output, result, "", "  "); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	fmt.Println(output.String())
}
