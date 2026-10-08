package main

import (
	"encoding/json"
	"math"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestExactAmounts(t *testing.T) {
	valid := map[string]int64{"0.000000001": 1, "1.234567891": 1234567891, "100": 100000000000,
		"9223372036.854775807": math.MaxInt64}
	for input, want := range valid {
		got, err := units(input)
		if err != nil || got != want {
			t.Fatalf("%s: %d, %v", input, got, err)
		}
	}
	for _, input := range []string{"0", "-1", "NaN", "1e9", "1.0000000001", "9223372036.854775808", "9223372037", "1.", ".1"} {
		if _, err := units(input); err == nil {
			t.Fatalf("accepted invalid amount %s", input)
		}
	}
}

func TestForeignNodeNeverReceivesCredential(t *testing.T) {
	calls := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		if r.Header.Get("api_key") != "" {
			t.Error("credential sent before node identity verification")
		}
		w.Write([]byte(`{"network":"testnet","genesisBlockId":"foreign"}`))
	}))
	defer server.Close()
	t.Setenv("ZYREX_API_KEY", "private-test-fixture")
	if _, err := run([]string{"--node", server.URL, "balance"}); err == nil {
		t.Fatal("foreign node accepted")
	}
	if calls != 1 {
		t.Fatalf("unexpected wallet request; calls=%d", calls)
	}
}

func TestSignedTransferRequestPreservesEveryNano(t *testing.T) {
	calls := 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		if r.URL.Path == "/info" {
			if r.Header.Get("api_key") != "" {
				t.Error("public identity request contained credential")
			}
			json.NewEncoder(w).Encode(map[string]string{"network": "testnet", "genesisBlockId": testnetGenesis})
			return
		}
		if r.URL.Path != "/wallet/transaction/send" || r.Method != "POST" {
			t.Error("incorrect signing endpoint")
		}
		if r.Header.Get("api_key") != "private-test-fixture" {
			t.Error("missing authenticated wallet request")
		}
		var body struct {
			Requests []struct {
				Address string
				Value   int64
			}
			Fee int64
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			t.Fatal(err)
		}
		if len(body.Requests) != 1 || body.Requests[0].Value != 1234567891 || body.Fee != 1000000 {
			t.Error("integer amounts altered")
		}
		w.Write([]byte(`"signed-transaction-id"`))
	}))
	defer server.Close()
	t.Setenv("ZYREX_API_KEY", "private-test-fixture")
	result, err := run([]string{"--node", server.URL, "send", "ZRX-test-fixture", "1.234567891", "--fee", "0.001"})
	if err != nil || string(result) != `"signed-transaction-id"` || calls != 2 {
		t.Fatalf("transfer failed: %s %v", result, err)
	}
}

func TestRedirectDoesNotForwardWalletCredential(t *testing.T) {
	called := false
	redirect := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { called = true }))
	defer redirect.Close()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, redirect.URL, http.StatusTemporaryRedirect)
	}))
	defer server.Close()
	c, err := newClient(server.URL, "private-test-fixture", "testnet")
	if err != nil {
		t.Fatal(err)
	}
	if _, err = c.request("/wallet/status", nil, true); err == nil || !strings.Contains(err.Error(), "307") {
		t.Fatal("redirect accepted")
	}
	if called {
		t.Fatal("credential was forwarded to redirect destination")
	}
}
