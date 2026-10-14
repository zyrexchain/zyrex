#!/usr/bin/env python3
"""Executable regressions for the repository's legacy Python wallet client."""
import copy
import importlib.machinery
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parent.parent
LOADER = importlib.machinery.SourceFileLoader("zyrex_wallet_client", str(ROOT / "bin/zyrex-wallet"))
SPEC = importlib.util.spec_from_loader(LOADER.name, LOADER)
WALLET = importlib.util.module_from_spec(SPEC)
LOADER.exec_module(WALLET)
IDENTIFIER = "a" * 64


class NativeFixture:
    def __init__(self):
        self.generated = 0
        self.broadcasts = []
        self.fee = 1_000_000
        self.recommend = lambda _: 1_000_000
        self.quote_status = None
        self.unknown = False
        self.observed = False
        self.amount = 1_000_000_001
        self.extra_outputs = []
        self.payment_assets = []
        self.fee_assets = []
        self.owned_addresses = ["ZRX-owned-fixture"]
        self.confirmed = None

    def transaction(self):
        return {"id": IDENTIFIER, "inputs": [{"boxId": "b" * 64, "spendingProof": {"proofBytes": "11", "extension": {}}}],
                "dataInputs": [], "outputs": [
                    {"value": self.amount, "ergoTree": "1000", "assets": self.payment_assets,
                     "creationHeight": 614400, "additionalRegisters": {}},
                    {"value": self.fee, "ergoTree": WALLET.FEE_SCRIPT, "assets": self.fee_assets,
                     "creationHeight": 614400, "additionalRegisters": {}}] + self.extra_outputs}

    def request(self, node, key, path, payload=None):
        if path == "/wallet/transaction/generate":
            self.generated += 1
            self.fee = payload["fee"]
            assert payload["requests"][0]["value"] == self.amount
            return self.transaction()
        if path == "/transactions/feeQuote?waitTime=1":
            if self.quote_status:
                raise WALLET.NodeHTTPError(self.quote_status)
            assert payload["inputs"] == self.transaction()["inputs"]
            assert payload["outputs"] == self.transaction()["outputs"]
            return {"quoteVersion": 1, "feeNano": str(self.recommend(self.fee)), "minimumFeeNano": "1000000",
                    "maxTransactionSize": 98304, "txSize": 321, "paidFeeNano": str(self.fee),
                    "txId": IDENTIFIER, "feeScriptHex": WALLET.FEE_SCRIPT}
        if path.startswith("/script/addressToTree/"):
            assert key is None
            return {"tree": "2000" if path.endswith("ZRX-owned-fixture") else "1000"}
        if path == "/wallet/addresses":
            return self.owned_addresses
        if path == "/wallet/status":
            return {"changeAddress": self.owned_addresses[0]}
        if path == "/transactions/check":
            assert payload == self.transaction()
            return IDENTIFIER
        if path == "/transactions":
            self.broadcasts.append(copy.deepcopy(payload))
            if self.unknown:
                raise WALLET.NodeHTTPError(503)
            return IDENTIFIER
        if path.startswith("/wallet/transactionById?id="):
            if self.confirmed is not None:
                return self.confirmed
            raise WALLET.NodeHTTPError(404)
        if path == "/transactions/unconfirmed/byTransactionId/" + IDENTIFIER:
            if self.observed:
                return self.transaction()
            raise WALLET.NodeHTTPError(404)
        raise AssertionError("Unexpected fixture endpoint: " + path)


class WalletTests(unittest.TestCase):
    def setUp(self):
        self.storage = tempfile.TemporaryDirectory(prefix="zyrex-python-wallet-")
        self.addCleanup(self.storage.cleanup)
        self.environment = patch.dict(os.environ, {"XDG_CONFIG_HOME": self.storage.name})
        self.environment.start()
        self.addCleanup(self.environment.stop)
        self.args = SimpleNamespace(node="http://127.0.0.1:19554", api_key="test-key", network="testnet",
                                    address="ZRX-test-fixture", amount="1.000000001", fee="auto", max_fee="0.01")
        self.fixture = NativeFixture()
        self.transport = patch.object(WALLET, "request", self.fixture.request)
        self.transport.start()
        self.addCleanup(self.transport.stop)

    def test_auto_uses_signed_quote_before_any_broadcast(self):
        self.fixture.recommend = lambda _: 2_000_000
        prepared = WALLET.prepare(self.args)
        self.assertEqual((self.fixture.generated, prepared["feeNano"]), (2, 2_000_000))
        self.assertEqual(self.fixture.broadcasts, [])
        path = WALLET.prepared_path(self.args.network)
        WALLET.save_prepared(path, prepared)
        self.assertEqual(path.stat().st_mode & 0o777, 0o600)
        self.assertEqual(WALLET.broadcast_prepared(self.args, path, prepared), IDENTIFIER)
        self.assertFalse(path.exists())

    def test_manual_fee_and_exact_large_amount(self):
        self.args.fee = "0.003"
        self.args.amount = "9007199.254740993"
        self.fixture.amount = 9_007_199_254_740_993
        prepared = WALLET.prepare(self.args)
        self.assertEqual(prepared["amountNano"], self.fixture.amount)
        self.assertEqual(prepared["feeNano"], 3_000_000)
        self.assertEqual(self.fixture.generated, 1)

    def test_quote_cap_and_unstable_quotes_do_not_submit(self):
        self.fixture.recommend = lambda _: 11_000_000
        with self.assertRaisesRegex(ValueError, "exceeds"):
            WALLET.prepare(self.args)
        self.assertEqual(self.fixture.generated, 1)
        self.fixture.generated = 0
        self.fixture.recommend = lambda fee: fee + 1_000_000
        with self.assertRaisesRegex(ValueError, "stabilize"):
            WALLET.prepare(self.args)
        self.assertEqual(self.fixture.generated, 4)
        self.assertEqual(self.fixture.broadcasts, [])

    def test_unsupported_quote_only_falls_back_for_404_or_405(self):
        for status in (404, 405):
            self.fixture.quote_status = status
            self.assertEqual(WALLET.prepare(self.args)["feeNano"], WALLET.MINIMUM_FEE)
        self.fixture.quote_status = 500
        with self.assertRaises(WALLET.NodeHTTPError):
            WALLET.prepare(self.args)

    def test_unknown_submission_retries_same_signed_payload(self):
        self.args.fee = "0.003"
        prepared = WALLET.prepare(self.args)
        path = WALLET.prepared_path(self.args.network)
        WALLET.save_prepared(path, prepared)
        self.fixture.unknown = True
        with self.assertRaisesRegex(RuntimeError, IDENTIFIER):
            WALLET.broadcast_prepared(self.args, path, prepared)
        self.assertTrue(path.exists())
        self.fixture.unknown = False
        self.fixture.recommend = lambda _: 9_000_000
        self.assertEqual(WALLET.retry_prepared(self.args, path), IDENTIFIER)
        self.assertEqual(self.fixture.broadcasts[0], self.fixture.broadcasts[1])
        self.assertEqual(self.fixture.generated, 1)
        self.assertEqual(self.fixture.fee, 3_000_000)

    def test_already_observed_retry_does_not_broadcast(self):
        prepared = WALLET.prepare(self.args)
        path = WALLET.prepared_path(self.args.network)
        WALLET.save_prepared(path, prepared)
        self.fixture.observed = True
        self.assertEqual(WALLET.retry_prepared(self.args, path), IDENTIFIER)
        self.assertEqual(self.fixture.broadcasts, [])

    def test_recovery_rejects_altered_fee_amount_network_and_symlink(self):
        prepared = WALLET.prepare(self.args)
        for field, bad in (("amountNano", 99), ("feeNano", 2_000_000), ("genesis", WALLET.GENESIS["devnet"])):
            changed = copy.deepcopy(prepared)
            changed[field] = bad
            with self.assertRaises(ValueError):
                WALLET.validate_prepared(self.args, changed)
        path = WALLET.prepared_path(self.args.network)
        WALLET.save_prepared(path, prepared)
        alias = path.with_name("alias.json")
        alias.symlink_to(path)
        with self.assertRaises(OSError):
            WALLET.load_prepared(alias)
        path.chmod(0o644)
        with self.assertRaises(ValueError):
            WALLET.load_prepared(path)

    def test_exact_units_reject_rounding_overflow_and_scientific_notation(self):
        self.assertEqual(WALLET.units("9223372036.854775807"), 2**63 - 1)
        for value in ("9223372036.854775808", "1.0000000001", "1e3", "+1", "NaN", "0"):
            with self.assertRaises(ValueError):
                WALLET.units(value)

    def test_foreign_node_never_receives_credentials_or_password(self):
        requests = []

        def foreign(node, key, path, payload=None):
            requests.append((key, path))
            return {"network": "testnet", "genesisBlockId": "foreign"}

        with patch.object(WALLET, "request", foreign), patch("sys.argv", ["zyrex-wallet", "--network", "testnet", "unlock"]), \
                patch.object(WALLET.getpass, "getpass") as password:
            with self.assertRaisesRegex(ValueError, "identity"):
                WALLET.main()
            password.assert_not_called()
        self.assertEqual(requests, [(None, "/info")])

    def test_other_process_blocks_send_and_retry_without_changing_recovery(self):
        prepared = WALLET.prepare(self.args)
        path = WALLET.prepared_path(self.args.network)
        WALLET.save_prepared(path, prepared)
        before = path.read_bytes()
        program = (
            "import importlib.machinery,sys\n"
            "from pathlib import Path\n"
            "module=importlib.machinery.SourceFileLoader('lock_helper',sys.argv[1]).load_module()\n"
            "with module.transfer_lock(Path(sys.argv[2])):\n"
            " print('locked',flush=True)\n"
            " sys.stdin.readline()\n"
        )
        process = subprocess.Popen([sys.executable, "-c", program, str(ROOT / "bin/zyrex-wallet"), str(path)],
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
        try:
            self.assertEqual(process.stdout.readline(), "locked\n")
            with self.assertRaisesRegex(RuntimeError, "Another wallet transfer"):
                WALLET.send_prepared(self.args, path)
            with self.assertRaisesRegex(RuntimeError, "Another wallet transfer"):
                WALLET.retry_prepared(self.args, path)
            self.assertEqual(path.read_bytes(), before)
            self.assertEqual(self.fixture.generated, 1)
            self.assertEqual(self.fixture.broadcasts, [])
            process.stdin.write("release\n")
            process.stdin.flush()
            self.assertEqual(process.wait(timeout=5), 0)
            self.assertEqual(WALLET.retry_prepared(self.args, path), IDENTIFIER)
            self.assertEqual(self.fixture.generated, 1)
            self.assertEqual(path.with_name("prepared-transfer.lock").stat().st_mode & 0o777, 0o600)
        finally:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=5)
            process.stdin.close()
            process.stdout.close()

    def test_cleanup_of_previous_acknowledgement_preserves_replacement(self):
        prepared = WALLET.prepare(self.args)
        path = WALLET.prepared_path(self.args.network)
        replacement = copy.deepcopy(prepared)
        replacement["id"] = "b" * 64
        replacement["transaction"]["id"] = replacement["id"]
        WALLET.save_prepared(path, replacement)
        with self.assertRaisesRegex(RuntimeError, "accepted"):
            WALLET.broadcast_prepared(self.args, path, prepared)
        self.assertEqual(WALLET.load_prepared(path), replacement)

    def test_observed_id_does_not_clear_mismatched_signed_metadata(self):
        prepared = WALLET.prepare(self.args)
        prepared["transaction"]["id"] = "b" * 64
        self.fixture.observed = True
        path = WALLET.prepared_path(self.args.network)
        WALLET.save_prepared(path, prepared)
        with self.assertRaises(ValueError):
            WALLET.retry_prepared(self.args, path)
        self.assertTrue(path.exists())
        self.assertEqual(self.fixture.broadcasts, [])

    def test_external_duplicate_and_unexpected_assets_are_rejected(self):
        for additional in ({"value": self.fixture.amount, "ergoTree": "1000"}, {"value": 123, "ergoTree": "3000"}):
            self.fixture.extra_outputs = [additional]
            with self.assertRaisesRegex(ValueError, "additional external"):
                WALLET.prepare(self.args)
        self.fixture.extra_outputs = []
        self.fixture.payment_assets = [{"tokenId": "c" * 64, "amount": 1}]
        with self.assertRaisesRegex(ValueError, "payment output"):
            WALLET.prepare(self.args)
        self.fixture.payment_assets = []
        self.fixture.fee_assets = [{"tokenId": "c" * 64, "amount": 1}]
        with self.assertRaisesRegex(ValueError, "unexpected assets"):
            WALLET.prepare(self.args)
        self.assertEqual(self.fixture.broadcasts, [])

    def test_owned_token_change_and_self_transfer_remain_valid(self):
        self.fixture.extra_outputs = [{"value": 123, "ergoTree": "2000", "assets": [{"tokenId": "c" * 64, "amount": 5}]}]
        prepared = WALLET.prepare(self.args)
        self.assertEqual(prepared["transaction"]["outputs"][-1]["assets"][0]["amount"], 5)
        self.fixture.owned_addresses = [self.args.address]
        self.fixture.extra_outputs = [{"value": self.fixture.amount, "ergoTree": "1000", "assets": []}]
        self.assertEqual(WALLET.prepare(self.args)["amountNano"], self.fixture.amount)

    def test_both_corrupted_ids_preserve_original_pending_payload(self):
        for legacy in (False, True):
            with self.subTest(legacy=legacy):
                self.fixture.quote_status = 404 if legacy else None
                prepared = WALLET.prepare(self.args)
                wrong_id = "b" * 64
                prepared["id"] = prepared["transaction"]["id"] = wrong_id
                path = WALLET.prepared_path(self.args.network)
                WALLET.save_prepared(path, prepared)
                known_other = copy.deepcopy(prepared["transaction"])
                known_other["outputs"][0]["value"] += 1
                self.fixture.confirmed = known_other
                with self.assertRaises(ValueError):
                    WALLET.retry_prepared(self.args, path)
                self.assertTrue(path.exists())
                self.assertEqual(self.fixture.broadcasts, [])
                path.unlink()
                self.fixture.confirmed = None

    def test_legacy_observation_binds_canonical_core_and_ignores_enrichment(self):
        self.fixture.quote_status = 404
        prepared = WALLET.prepare(self.args)
        path = WALLET.prepared_path(self.args.network)
        WALLET.save_prepared(path, prepared)
        observed = copy.deepcopy(prepared["transaction"])
        observed["inclusionHeight"] = 501
        observed["outputs"][0]["address"] = self.args.address
        observed["inputs"][0]["value"] = 2_000_000_000
        self.fixture.confirmed = observed
        self.assertEqual(WALLET.retry_prepared(self.args, path), IDENTIFIER)
        self.assertFalse(path.exists())
        self.assertEqual(self.fixture.broadcasts, [])
        for field in ("inputs", "dataInputs", "outputs"):
            broken = copy.deepcopy(prepared["transaction"])
            del broken[field]
            with self.assertRaises(ValueError):
                WALLET.bind_observed(prepared, broken, False)


if __name__ == "__main__":
    unittest.main()
