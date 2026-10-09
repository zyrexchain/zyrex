"""Protocol, PoW, integer accounting and recovery checks for the LAN pool."""
import asyncio
import ipaddress
import importlib.util
import io
import json
import sqlite3
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from ledger import Ledger, allocate_integer
from node import Node, payout_plan
from pow import MAX_TARGET, address_bytes, calc_n, hit, validate_miner_address
from server import Pool, validate_config

ADDRESS1 = "Cz5n9QxdK3EPBp9Ggux2dYMySVyUmLksYYPyj9dEhnYYTyW81NFQ"
ADDRESS2 = "Cz3eBQNXVSVQndNM817Ev6R8zS1Yr3E1sT5Hk1RhhpenT5aSwkCQ"
MESSAGE = "548c3e602a8f36f8f2738f5f643b02425038044d98543a51cabaa9785e7e864f"
PK = "0201941e4163eae332959cf06743b28c6b9426772da7faf5a5d5a4ad8e3653595c"
TESTNET_ADDRESS = "ZRXAcmmjjm7wvmcWVqMSi8R5jcdCRQX99aLPymp1xVLWd8JatTPQV94"
FEE_SCRIPT = "1001"


def payment(txid, amounts, fee):
    outputs = [{"value": value, "ergoTree": "0008cd" + address_bytes(address)[1:].hex()}
               for address, value in amounts.items()]
    outputs.append({"value": fee, "ergoTree": FEE_SCRIPT})
    return {"id": txid, "outputs": outputs}


class PowTests(unittest.TestCase):
    def test_native_testnet_address_brand_network_and_checksum(self):
        self.assertEqual(validate_miner_address(TESTNET_ADDRESS, 64), TESTNET_ADDRESS)
        self.assertEqual(address_bytes(TESTNET_ADDRESS).hex(),
            "4102764393903f6555d6409620ea84dd60592f70ad5d4218562bcff63fe2e4403a09")
        for encoded in (TESTNET_ADDRESS[3:], 'ZRX' + ADDRESS1, TESTNET_ADDRESS[:-1] + '1'):
            with self.assertRaises(ValueError):
                validate_miner_address(encoded, 64)
        for prefix in (0, 48, 80):
            with self.assertRaises(ValueError):
                validate_miner_address(TESTNET_ADDRESS, prefix)

    def test_upstream_miner_vector(self):
        # The node's AutolykosPowSchemeSpec contains this independent miner vector.
        self.assertEqual(hit(bytes.fromhex(MESSAGE), bytes.fromhex("0000000000003105"), 614400),
            int("0002fcb113fe65e5754959872dfdbffea0489bf830beb4961ddc0e9e66a1412a", 16))
        self.assertEqual(calc_n(614399), 67108864)
        self.assertEqual(calc_n(614400), 70464240)
        self.assertEqual(calc_n(4198400), 2143944600)
        self.assertEqual(calc_n(9216000), 2143944600)

    def test_addresses_use_only_zyrex_devnet(self):
        self.assertEqual(validate_miner_address(ADDRESS1), ADDRESS1)
        with self.assertRaises(ValueError):
            validate_miner_address(ADDRESS1[:-1] + "1")
        with self.assertRaises(ValueError):
            validate_miner_address(ADDRESS1, 0)
        with self.assertRaises(ValueError):
            validate_miner_address("9fRAWhDxEsTcdb8PhGNrZfwqa65v7bTZZ5wxPBd2QfEu14Y59aM")


class NodeReadinessTests(unittest.TestCase):
    def setUp(self):
        self.node = object.__new__(Node)
        self.node.config = {"genesisId": "genesis"}
        self.node.pk = None
        self.node.wallet = {"password": "test fixture"}
        self.info = {"genesisBlockId": "genesis", "network": "devnet", "fullHeight": 10,
                     "headersHeight": 10, "maxPeerHeight": 11,
                     "bestHeaderId": "applied", "bestFullHeaderId": "applied"}
        responses = {"/info": self.info, "/wallet/status": {"isUnlocked": True},
                     "/mining/rewardPublicKey": {"rewardPubkey": PK},
                     "/mining/rewardAddress": {"rewardAddress": "test fixture"}}
        self.node.rpc = Mock(side_effect=lambda path: responses[path])

    def test_higher_peer_height_does_not_stall_applied_best_chain(self):
        with patch("node.address_bytes", return_value=b"\x53script"):
            self.assertEqual(self.node.ready()["fullHeight"], 10)

    def test_testnet_identity_and_reward_script_are_pinned(self):
        self.node.config.update(network="testnet", addressPrefix=64)
        self.info["network"] = "testnet"
        with patch("node.address_bytes", return_value=b"\x43script"):
            self.assertEqual(self.node.ready()["network"], "testnet")
        with patch("node.address_bytes", return_value=b"\x53script"):
            with self.assertRaises(RuntimeError):
                self.node.ready()
        self.info["network"] = "devnet"
        with self.assertRaises(RuntimeError):
            self.node.ready()


    def test_unapplied_header_or_foreign_genesis_still_blocks_mining(self):
        self.info["bestFullHeaderId"] = "previous"
        with self.assertRaises(RuntimeError):
            self.node.ready()
        self.info["bestFullHeaderId"] = "applied"
        self.info["genesisBlockId"] = "foreign-chain"
        with self.assertRaises(RuntimeError):
            self.node.ready()


class ConfigurationTests(unittest.TestCase):
    def test_network_and_reward_maturity_must_match(self):
        valid = {"network": "testnet", "addressPrefix": 64, "minerRewardDelay": 5,
                 "confirmations": 8, "minimumPayoutNano": 1_000_000_000,
                 "payoutFeeNano": 1_000_000, "feeScriptHex": FEE_SCRIPT}
        validate_config(valid)
        for update in ({"addressPrefix": 80}, {"network": "mainnet"}, {"confirmations": 5},
                       {"minimumPayoutNano": 0}, {"minerRewardDelay": -1},
                       {"payoutFeeNano": 0}, {"payoutFeeNano": True}, {"payoutFeeNano": 1_000_000_000},
                       {"feeScriptHex": ""}, {"feeScriptHex": "10xx"}, {"feeScriptHex": "AA"}):
            with self.assertRaises(ValueError):
                validate_config(dict(valid, **update))

    def test_native_fee_script_fragments_normalize_and_invalid_pieces_are_rejected(self):
        valid = {"network": "testnet", "addressPrefix": 64, "minerRewardDelay": 5,
                 "confirmations": 8, "minimumPayoutNano": 1_000_000_000, "payoutFeeNano": 1_000_000}
        config = dict(valid, feeScriptHex=["10", "01"])
        validate_config(config)
        self.assertEqual(config["feeScriptHex"], FEE_SCRIPT)
        for fragments in ([], [""], ["10", ""], ["10", None], ["10", 1], [["10"]], [True],
                          ["1", "001"], ["AA", "01"], ["10", "xx"], ["10", "  "], ["00" * 2049]):
            with self.assertRaises(ValueError):
                validate_config(dict(valid, feeScriptHex=fragments))


class PreparationTests(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location("prepare_pool", Path(__file__).resolve().parents[1] / "scripts/pool-prepare.py")
        self.prepare = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.prepare)
        self.folder = tempfile.TemporaryDirectory()
        self.prepare.FOLDER = Path(self.folder.name)

    def tearDown(self):
        self.folder.cleanup()

    def test_configuration_pins_saved_key_and_retains_api_secret(self):
        self.prepare.save(self.prepare.FOLDER / "wallet.json", json.dumps({"miningPublicKey": PK}))
        with patch("sys.argv", ["pool-prepare.py", "config"]), patch("builtins.print"):
            self.prepare.main()
            before = (self.prepare.FOLDER / "api-key").read_text()
            self.prepare.main()
        self.assertEqual(before, (self.prepare.FOLDER / "api-key").read_text())
        config = (self.prepare.FOLDER / "node.conf").read_text()
        self.assertIn('zyrex.node.miningPubKeyHex = "' + PK + '"', config)
        self.assertNotIn(before, config)
        self.assertEqual((self.prepare.FOLDER / "api-key").stat().st_mode & 0o777, 0o600)

    def test_fresh_wallet_waits_for_candidate_before_pinning(self):
        info = {"peersCount": 1, "fullHeight": 12, "headersHeight": 12}
        candidate_calls = 0
        def api(path, payload=None):
            nonlocal candidate_calls
            if path == "/wallet/status":
                return {"isInitialized": False, "isUnlocked": True}
            if path == "/wallet/init":
                return {"mnemonic": "unit test fixture"}
            if path == "/wallet/addresses":
                return [ADDRESS1]
            if path == "/info":
                return info
            if path == "/mining/candidate":
                candidate_calls += 1
                if candidate_calls == 1:
                    raise OSError("Miner has not started yet")
                return {"pk": PK, "h": 13}
            raise AssertionError(path)
        with patch("sys.argv", ["pool-prepare.py", "wallet"]), patch("builtins.print"), \
                patch.object(self.prepare, "api", side_effect=api), patch.object(self.prepare.time, "sleep"), \
                patch.object(self.prepare.urllib.request, "urlopen", side_effect=lambda *a, **k: io.BytesIO(json.dumps(info).encode())):
            self.prepare.main()
        saved = json.loads((self.prepare.FOLDER / "wallet.json").read_text())
        self.assertEqual(saved["miningPublicKey"], PK)
        self.assertEqual(candidate_calls, 2)


class ReceivingWalletTests(unittest.TestCase):
    def setUp(self):
        path = Path(__file__).resolve().parents[1] / "scripts/pool-smoke.py"
        spec = importlib.util.spec_from_file_location("pool_smoke", path)
        self.smoke = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.smoke)

    def test_lagging_wallet_waits_for_actual_five_confirmations(self):
        replies = [{"numConfirmations": n, "outputs": [{"value": 100, "address": ADDRESS1}]} for n in (0, 4, 5)]
        with patch.object(self.smoke, "api", side_effect=replies) as rpc, patch.object(self.smoke.time, "sleep"):
            result = self.smoke.wait_for_received_payment(19556, "tx", ADDRESS1, 100)
        self.assertEqual(result["numConfirmations"], 5)
        self.assertEqual(rpc.call_count, 3)

    def test_confirmed_wrong_recipient_is_not_accepted(self):
        tx = {"numConfirmations": 5, "outputs": [{"value": 100, "address": ADDRESS2}]}
        with patch.object(self.smoke, "api", return_value=tx):
            with self.assertRaises(AssertionError):
                self.smoke.wait_for_received_payment(19556, "tx", ADDRESS1, 100)

    def test_compact_public_payout_uses_exact_amounts_for_receiving_validation(self):
        value = (1 << 53) + 7
        payout = {"amountsExact": {ADDRESS1: str(value)}, "amounts": json.dumps({ADDRESS1: 1})}
        amounts = self.smoke.payout_amounts(payout)
        self.assertEqual(amounts, {ADDRESS1: value})
        received = {"numConfirmations": 5, "outputs": [{"value": value, "address": ADDRESS1}]}
        with patch.object(self.smoke, "api", return_value=received):
            self.smoke.wait_for_received_payment(19556, "tx", ADDRESS1, amounts[ADDRESS1])
        self.assertEqual(self.smoke.payout_amounts({"amounts": json.dumps({ADDRESS1: value})}), amounts)

    def test_malformed_exact_payout_never_falls_back_to_legacy_amount(self):
        for value in (None, 100, True, 1.5, "-1", "1.5", "0", "1e3"):
            payout = {"amountsExact": {ADDRESS1: value}, "amounts": json.dumps({ADDRESS1: 100})}
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.smoke.payout_amounts(payout)


class AccountingTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.path = str(Path(self.folder.name) / "pool.sqlite")
        self.ledger = Ledger(self.path)
        self.work = {"msg": MESSAGE, "h": 10, "b": 1, "pk": PK}

    def tearDown(self):
        self.ledger.db.close()
        self.folder.cleanup()

    def share(self, address, nonce, weight=1):
        return self.ledger.share(MESSAGE, nonce, address, "worker", weight)

    def found(self, address, nonce, height=10):
        share_id = self.share(address, nonce)
        self.ledger.block(dict(self.work, h=height), nonce, share_id)
        return self.ledger.blocks()[-1]

    def test_weighted_round_and_every_nano_conserved(self):
        self.share(ADDRESS1, "a", 1)
        self.share(ADDRESS2, "b", 2)
        end = self.share(ADDRESS2, "c", 1)
        self.ledger.block(self.work, "c", end)
        block = self.ledger.blocks()[0]
        self.ledger.settle(block, "block", 90_000_000_000, 1_000_000)
        balances = self.ledger.balances()
        self.assertEqual(balances[ADDRESS1], 22_499_750_000)
        self.assertEqual(balances[ADDRESS2], 67_499_250_000)
        self.assertEqual(sum(balances.values()), 89_999_000_000)
        self.ledger.settle(block, "block", 90_000_000_000, 1_000_000)
        self.assertEqual(balances, self.ledger.balances())

    def test_remainders_and_integer_weights_beyond_sqlite_int64(self):
        self.share(ADDRESS1, "a", 1 << 100)
        end = self.share(ADDRESS2, "b", 1 << 100)
        self.ledger.block(self.work, "b", end)
        self.ledger.settle(self.ledger.blocks()[0], "block", 12, 1)
        self.assertEqual(sorted(self.ledger.balances().values()), [5, 6])

    def test_orphan_work_carries_into_next_canonical_round(self):
        orphan = self.found(ADDRESS1, "a")
        self.ledger.orphan(orphan["id"])
        canonical = self.found(ADDRESS2, "b", 11)
        self.ledger.settle(canonical, "block", 21, 1)
        self.assertEqual(self.ledger.balances(), {ADDRESS1: 10, ADDRESS2: 10})

    def test_duplicate_nonce_cannot_change_worker_or_round(self):
        self.share(ADDRESS1, "nonce")
        with self.assertRaises(sqlite3.IntegrityError):
            self.share(ADDRESS2, "nonce")

    def test_prepared_payment_survives_restart_without_double_debit(self):
        block = self.found(ADDRESS1, "a")
        self.ledger.settle(block, "block", 21, 1)
        self.ledger.prepare_payout({"id": "tx", "outputs": []}, {ADDRESS1: 20})
        restarted = Ledger(self.path)
        self.assertEqual(restarted.balances()[ADDRESS1], 0)
        self.assertEqual(restarted.payouts()[0]["status"], "prepared")
        self.assertEqual(json.loads(restarted.payouts()[0]["raw"])["id"], "tx")
        with self.assertRaises(RuntimeError):
            restarted.prepare_payout({"id": "another"}, {ADDRESS1: 20})
        restarted.db.close()

    def test_database_is_pinned_to_wallet_and_genesis(self):
        self.ledger.bind_network("zyrex-genesis", PK)
        self.ledger.bind_network("zyrex-genesis", PK)
        with self.assertRaises(RuntimeError):
            self.ledger.bind_network("foreign-genesis", PK)

    def test_gpu_status_requires_matching_identity_block_and_paid_address(self):
        self.ledger.bind_network("genesis", PK)
        block = self.found(ADDRESS1, "a")
        self.ledger.settle(block, "block", 21, 1)
        self.ledger.prepare_payout({"id": "tx"}, {ADDRESS1: 20})
        evidence = {"identity": "genesis:" + PK, "blockId": "block", "payoutId": "tx", "address": ADDRESS1}
        with self.ledger.db:
            self.ledger.db.execute("INSERT INTO meta VALUES('gpu_validation',?)", (json.dumps(evidence),))
        self.assertIsNone(self.ledger.gpu_validation())
        self.ledger.payout_status("tx", "confirmed")
        self.assertEqual(self.ledger.gpu_validation(), evidence)
        evidence["identity"] = "different-genesis:" + PK
        with self.ledger.db:
            self.ledger.db.execute("UPDATE meta SET value=? WHERE key='gpu_validation'", (json.dumps(evidence),))
        self.assertIsNone(self.ledger.gpu_validation())

    def test_deep_reorg_stops_payments(self):
        block = self.found(ADDRESS1, "a")
        self.ledger.settle(block, "old-canonical", 21, 1)
        node = object.__new__(Node)
        self.ledger.bind_network("genesis", PK)
        node.config = {"genesisId": "genesis", "confirmations": 5, "feeScriptHex": FEE_SCRIPT}
        node.header = Mock(return_value={"id": "different", "powSolutions": {"pk": PK, "n": "a"}})
        node.message = Mock(return_value=MESSAGE)
        node.rpc = Mock(return_value={"genesisBlockId": "genesis", "network": "devnet",
            "fullHeight": 15, "headersHeight": 15, "bestFullHeaderId": "different", "bestHeaderId": "different"})
        node.reconcile(self.ledger, 15)
        self.assertIsNotNone(self.ledger.halted())
        node.rpc.assert_called_once_with("/info")
        self.assertEqual(self.ledger.balances(), {ADDRESS1: 20})
        self.assertIsNone(self.ledger.db.execute("SELECT value FROM meta WHERE key='fee_policy'").fetchone())
        self.assertEqual(self.ledger.db.execute("SELECT COUNT(*) FROM fee_adjustments").fetchone()[0], 0)

    def test_confirmations_before_credit_and_crash_after_broadcast(self):
        block = self.found(ADDRESS1, "a")
        node = object.__new__(Node)
        self.ledger.bind_network("genesis", PK)
        node.config = {"genesisId": "genesis", "confirmations": 5,
                       "payoutFeeNano": 1_000_000, "minimumPayoutNano": 1_000_000_000,
                       "feeScriptHex": FEE_SCRIPT}
        node.reward_script = "reward-script"
        node.header = Mock(return_value={"id": "canonical", "powSolutions": {"pk": PK, "n": "a"}})
        node.message = Mock(return_value=MESSAGE)
        confirmed = False
        tip_height = 13
        broadcasts = []
        def rpc(path, payload=None):
            if path == "/info":
                return {"genesisBlockId": "genesis", "network": "devnet",
                    "fullHeight": tip_height, "headersHeight": tip_height,
                    "bestFullHeaderId": "canonical", "bestHeaderId": "canonical"}
            if path == "/blocks/canonical":
                return {"blockTransactions": {"transactions": [{"outputs": [
                    {"value": 90_000_000_000, "ergoTree": "reward-script"},
                    {"value": 5_000_000_000, "ergoTree": "founder-1"},
                    {"value": 5_000_000_000, "ergoTree": "founder-2"}]}]}}
            if path == "/wallet/balances":
                return {"balance": 90_000_000_000}
            if path == "/wallet/transaction/generate":
                return payment("signed-id", {r["address"]: r["value"] for r in payload["requests"]}, payload["fee"])
            if path == "/transactions":
                broadcasts.append(payload["id"])
                if len(broadcasts) == 1:
                    raise TimeoutError("Node accepted but response was lost")
                return payload["id"]
            if path == "/wallet/transactionById?id=signed-id":
                return {"numConfirmations": 5 if confirmed else 0}
            raise AssertionError(path)
        node.rpc = rpc
        node.reconcile(self.ledger, 13)
        self.assertEqual(self.ledger.balances(), {})
        tip_height = 14
        with self.assertRaises(TimeoutError):
            node.reconcile(self.ledger, 14)
        self.assertEqual(self.ledger.balances()[ADDRESS1], 0)
        tip_height = 15
        node.reconcile(self.ledger, 15)
        self.assertEqual(broadcasts, ["signed-id", "signed-id"])
        confirmed = True
        tip_height = 16
        node.reconcile(self.ledger, 16)
        self.assertEqual(self.ledger.payouts()[0]["status"], "confirmed")
        self.assertEqual(len(self.ledger.payouts()), 1)


class FeeAccountingTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.path = str(Path(self.folder.name) / "pool.sqlite")
        self.ledger = Ledger(self.path)
        self.ledger.bind_network("genesis", PK)
        self.sequence = 0

    def tearDown(self):
        self.ledger.db.close()
        self.folder.cleanup()

    def round(self, reward=90_000_000_000, legacy_fee=0, weights=None):
        self.sequence += 1
        weights = weights or {ADDRESS1: 1}
        for address, weight in weights.items():
            nonce = str(self.sequence) + address
            end = self.ledger.share(MESSAGE, nonce, address, "worker", weight)
        work = {"msg": MESSAGE, "h": self.sequence, "pk": PK}
        self.ledger.block(work, nonce, end)
        block = self.ledger.blocks()[-1]
        self.ledger.settle(block, str(self.sequence), reward, legacy_fee)
        return block

    def history(self):
        return {table: [tuple(row) for row in self.ledger.db.execute("SELECT * FROM " + table)]
                for table in ("credits", "shares", "blocks", "payouts")}

    def test_full_reward_credit_and_only_one_actual_payout_fee(self):
        self.ledger.migrate_payout_fees(FEE_SCRIPT)
        self.round()
        self.round()
        self.assertEqual(self.ledger.balances()[ADDRESS1], 180_000_000_000)
        gross, net, fees = payout_plan(self.ledger.balances(), 1_000_000_000, 1_000_000)
        self.assertEqual(net[ADDRESS1], 179_999_000_000)
        self.ledger.prepare_payout(payment("tx", net, 1_000_000), net, gross, fees)
        self.assertEqual(self.ledger.balances()[ADDRESS1], 0)
        saved = self.ledger.payouts()[0]
        self.assertEqual(json.loads(saved["gross"]), gross)
        self.assertEqual(json.loads(saved["fees"]), {ADDRESS1: 1_000_000})
        self.assertEqual(sum(net.values()) + sum(fees.values()), 180_000_000_000)

    def test_fee_weighting_rounding_and_large_integer_weights(self):
        gross = {ADDRESS1: 3_000_000, ADDRESS2: 9_000_000}
        debits, net, fees = payout_plan(gross, 1_000_000, 7)
        self.assertEqual(fees, {ADDRESS1: 2, ADDRESS2: 5})
        self.assertEqual(sum(net.values()) + sum(fees.values()), sum(debits.values()))
        result = allocate_integer(7, {ADDRESS1: 1 << 100, ADDRESS2: 3 << 100})
        self.assertEqual(result, fees)
        self.assertEqual(allocate_integer(1, {"b": 1, "a": 1}), {"b": 0, "a": 1})

    def test_batch_limit_and_below_threshold_balances_are_preserved(self):
        balances = {str(index).zfill(3): 2_000_000_000 for index in range(60)}
        balances["below"] = 999_999_999
        gross, net, fees = payout_plan(balances, 1_000_000_000, 1_000_000)
        self.assertEqual(len(gross), 50)
        self.assertEqual(set(gross), {str(index).zfill(3) for index in range(50)})
        self.assertEqual(sum(fees.values()), 1_000_000)
        self.assertNotIn("below", net)
        self.assertEqual(balances["below"], 999_999_999)

    def test_dust_recipient_is_excluded_and_fee_is_reallocated(self):
        gross, net, fees = payout_plan({ADDRESS1: 1_000_000, ADDRESS2: 2_000_000}, 1_000_000, 1_000_000)
        self.assertEqual(gross, {ADDRESS2: 2_000_000})
        self.assertEqual(net, {ADDRESS2: 1_000_000})
        self.assertEqual(fees, {ADDRESS2: 1_000_000})
        self.assertEqual(payout_plan({ADDRESS1: 1_000_000}, 1_000_000, 1_000_000), ({}, {}, {}))

    def test_invalid_amounts_and_fee_equations_cannot_change_balances(self):
        self.round(reward=100)
        before = self.ledger.balances()
        for net, gross, fee in ((0, 1, 1), (-1, 1, 2), (True, 2, 1), (1.5, 2, 0.5),
                                (1, 3, 1), (1, 1, -1), (1, True, 0)):
            with self.assertRaises(ValueError):
                self.ledger.prepare_payout({"id": "bad"}, {ADDRESS1: net}, {ADDRESS1: gross}, {ADDRESS1: fee})
            self.assertEqual(self.ledger.balances(), before)
            self.assertEqual(self.ledger.payouts(), [])
        with self.assertRaises(ValueError):
            self.ledger.prepare_payout({"id": "bad"}, {ADDRESS1: 1}, {ADDRESS2: 1}, {ADDRESS1: 0})

    def test_atomic_rollback_on_partial_insufficiency_and_duplicate_transaction(self):
        self.round(reward=100, weights={ADDRESS1: 1, ADDRESS2: 1})
        before = self.ledger.balances()
        with self.assertRaises(RuntimeError):
            self.ledger.prepare_payout({"id": "bad"}, {ADDRESS1: 39, ADDRESS2: 59},
                                       {ADDRESS1: 40, ADDRESS2: 60}, {ADDRESS1: 1, ADDRESS2: 1})
        self.assertEqual(self.ledger.balances(), before)
        self.ledger.prepare_payout({"id": "good"}, {ADDRESS1: 9}, {ADDRESS1: 10}, {ADDRESS1: 1})
        before = self.ledger.balances()
        with self.assertRaises(sqlite3.IntegrityError):
            self.ledger.prepare_payout({"id": "good"}, {ADDRESS1: 9}, {ADDRESS1: 10}, {ADDRESS1: 1})
        self.assertEqual(self.ledger.balances(), before)
        self.assertEqual(len(self.ledger.payouts()), 1)

    def test_prepared_gross_debit_and_fees_survive_restart(self):
        self.round(reward=3_000_000)
        gross, net, fees = payout_plan(self.ledger.balances(), 2_000_000, 1_000_000)
        self.ledger.prepare_payout(payment("saved", net, 1_000_000), net, gross, fees)
        restarted = Ledger(self.path)
        try:
            self.assertEqual(restarted.balances()[ADDRESS1], 0)
            self.assertEqual(restarted.payouts()[0]["status"], "prepared")
            self.assertEqual(json.loads(restarted.payouts()[0]["fees"]), fees)
            self.assertEqual(json.loads(restarted.payouts()[0]["gross"]), gross)
        finally:
            restarted.db.close()

    def test_historical_reserve_refund_uses_actual_fee_and_preserves_signed_history(self):
        self.round(legacy_fee=1_000_000)
        self.round(legacy_fee=1_000_000)
        amount = 89_999_000_000
        self.ledger.prepare_payout(payment("old", {ADDRESS1: amount}, 300_000), {ADDRESS1: amount})
        before = self.history()
        audit = self.ledger.migrate_payout_fees(FEE_SCRIPT)
        self.assertEqual(audit["reservedNanoZYRX"], 2_000_000)
        self.assertEqual(audit["spentNanoZYRX"], 300_000)
        self.assertEqual(audit["refundedNanoZYRX"], 1_700_000)
        self.assertEqual(self.ledger.balances()[ADDRESS1], 90_000_700_000)
        self.assertEqual(self.history(), before)
        self.assertEqual(self.ledger.stats()["feeRefundsNanoZYRX"], {ADDRESS1: 1_700_000})
        self.assertEqual(self.ledger.migrate_payout_fees(FEE_SCRIPT), audit)
        self.assertEqual(self.ledger.balances()[ADDRESS1], 90_000_700_000)
        restarted = Ledger(self.path)
        try:
            self.assertEqual(restarted.migrate_payout_fees(FEE_SCRIPT), audit)
            self.assertEqual(restarted.balances()[ADDRESS1], 90_000_700_000)
        finally:
            restarted.db.close()

    def test_refund_weighting_and_all_saved_payout_statuses_reserve_their_actual_fee(self):
        self.round(legacy_fee=1_000_000, weights={ADDRESS1: 1, ADDRESS2: 3})
        for index, status in enumerate(("prepared", "broadcast", "confirmed")):
            txid = str(index)
            self.ledger.prepare_payout(payment(txid, {ADDRESS1: 1_000_000}, 100_000), {ADDRESS1: 1_000_000})
            self.ledger.payout_status(txid, status)
        audit = self.ledger.migrate_payout_fees(FEE_SCRIPT)
        self.assertEqual(audit["spentNanoZYRX"], 300_000)
        self.assertEqual(self.ledger.stats()["feeRefundsNanoZYRX"], {ADDRESS1: 175_000, ADDRESS2: 525_000})
        self.assertEqual(sum(self.ledger.balances().values()) + 3_000_000 + 300_000, 90_000_000_000)

    def test_migration_requires_network_pin_and_refuses_script_change(self):
        fresh = Ledger(str(Path(self.folder.name) / "fresh.sqlite"))
        try:
            with self.assertRaises(RuntimeError):
                fresh.migrate_payout_fees(FEE_SCRIPT)
        finally:
            fresh.db.close()
        self.ledger.migrate_payout_fees(FEE_SCRIPT)
        with self.assertRaises(RuntimeError):
            self.ledger.migrate_payout_fees("1002")

    def test_halted_pool_preserves_credits_and_cannot_migrate_or_issue_payments(self):
        self.round(legacy_fee=1_000_000)
        end = self.ledger.share(MESSAGE, "pending", ADDRESS1, "worker", 1)
        self.ledger.block({"msg": MESSAGE, "h": 2, "pk": PK}, "pending", end)
        self.ledger.halt("Operator review required")
        before, balances = self.history(), self.ledger.balances()
        node = object.__new__(Node)
        node.config = {"confirmations": 5, "feeScriptHex": FEE_SCRIPT}
        node.header, node.rpc = Mock(), Mock()
        node.reconcile(self.ledger, 100)
        node.header.assert_not_called()
        node.rpc.assert_not_called()
        self.assertEqual(self.history(), before)
        self.assertEqual(self.ledger.balances(), balances)
        self.assertIsNone(self.ledger.db.execute("SELECT value FROM meta WHERE key='fee_policy'").fetchone())
        self.assertEqual(self.ledger.db.execute("SELECT COUNT(*) FROM fee_adjustments").fetchone()[0], 0)

    def test_unknown_negative_or_excess_historical_fee_fails_without_refund(self):
        self.round(legacy_fee=1_000_000)
        amounts = {ADDRESS1: 1_000_000}
        good = payment("old", amounts, 500_000)
        self.ledger.prepare_payout(good, amounts)
        before = self.ledger.balances()
        for raw in (payment("old", amounts, 1_000_001), payment("old", amounts, -1),
                    dict(good, outputs=good["outputs"][:-1]),
                    dict(good, outputs=[dict(good["outputs"][0], value=999_999), good["outputs"][1]]),
                    dict(good, outputs=good["outputs"] + [{"value": 1, "ergoTree": "unknown"}]),
                    dict(good, outputs=[good["outputs"][0], dict(good["outputs"][1], assets=[{}])])):
            with self.ledger.db:
                self.ledger.db.execute("UPDATE payouts SET raw=? WHERE id='old'", (json.dumps(raw),))
            with self.assertRaises((ValueError, RuntimeError)):
                self.ledger.migrate_payout_fees(FEE_SCRIPT)
            self.assertEqual(self.ledger.balances(), before)
            self.assertIsNone(self.ledger.db.execute("SELECT value FROM meta WHERE key='fee_policy'").fetchone())
            self.assertEqual(self.ledger.db.execute("SELECT COUNT(*) FROM fee_adjustments").fetchone()[0], 0)

    def test_malformed_legacy_credits_fail_and_journal_collision_rolls_back(self):
        block = self.round(legacy_fee=1_000_000)
        with self.ledger.db:
            self.ledger.db.execute("UPDATE credits SET amount=amount+2 WHERE block=?", (block["id"],))
        before = self.ledger.balances()
        with self.assertRaises(RuntimeError):
            self.ledger.migrate_payout_fees(FEE_SCRIPT)
        self.assertEqual(self.ledger.balances(), before)
        with self.ledger.db:
            self.ledger.db.execute("UPDATE credits SET amount=amount-2 WHERE block=?", (block["id"],))
            self.ledger.db.execute("INSERT INTO fee_adjustments VALUES(?,?,?,?)", ("actual-transaction-v1", ADDRESS1, 1, "{}"))
        with self.assertRaises(sqlite3.IntegrityError):
            self.ledger.migrate_payout_fees(FEE_SCRIPT)
        self.assertEqual(self.ledger.balances(), before)
        self.assertIsNone(self.ledger.db.execute("SELECT value FROM meta WHERE key='fee_policy'").fetchone())

    def test_negative_legacy_fee_burden_is_refused_instead_of_debiting_a_miner(self):
        # Hamilton rounding is not monotonic: this historical round needs review.
        self.round(reward=5, legacy_fee=1, weights={ADDRESS1: 5, ADDRESS2: 3, TESTNET_ADDRESS: 1})
        before = self.ledger.balances()
        with self.assertRaises(RuntimeError):
            self.ledger.migrate_payout_fees(FEE_SCRIPT)
        self.assertEqual(self.ledger.balances(), before)
        self.assertIsNone(self.ledger.db.execute("SELECT value FROM meta WHERE key='fee_policy'").fetchone())

    def test_public_exact_amount_maps_preserve_values_above_javascript_integer_range(self):
        self.ledger.migrate_payout_fees(FEE_SCRIPT)
        self.round(reward=(1 << 53) + 7)
        gross, net, fees = payout_plan(self.ledger.balances(), 1_000_000_000, 1_000_000)
        self.ledger.prepare_payout(payment("exact", net, 1_000_000), net, gross, fees)
        stats = self.ledger.stats()
        self.assertEqual(stats["payouts"][0]["grossExact"][ADDRESS1], str((1 << 53) + 7))
        self.assertEqual(stats["payouts"][0]["amountsExact"][ADDRESS1], str((1 << 53) + 7 - 1_000_000))
        self.assertEqual(stats["payouts"][0]["feesExact"][ADDRESS1], "1000000")
        self.assertEqual(stats["balancesNanoZYRXExact"][ADDRESS1], "0")

    def test_native_generated_payout_cannot_redirect_or_overcharge_miners(self):
        node = object.__new__(Node)
        node.config = {"feeScriptHex": FEE_SCRIPT}
        amounts = {ADDRESS1: 2_000_000}
        good = payment("new", amounts, 1_000_000)
        node.validate_payout(good, amounts, 1_000_000)
        for bad in (payment("new", amounts, 2_000_000), payment("new", {ADDRESS2: 2_000_000}, 1_000_000),
                    dict(good, outputs=good["outputs"] + [{"value": 1_000_000, "ergoTree": "unknown"}])):
            with self.assertRaises(RuntimeError):
                node.validate_payout(bad, amounts, 1_000_000)
        node.wallet = {"address": ADDRESS2}
        change = {"value": 3_000_000, "ergoTree": "0008cd" + address_bytes(ADDRESS2)[1:].hex()}
        node.validate_payout(dict(good, outputs=good["outputs"] + [change]), amounts, 1_000_000)


class ProtocolTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.pool = object.__new__(Pool)
        self.pool.config = {"initialShareHashes": 1, "addressPrefix": 80}
        self.pool.clients = set()
        self.pool.extra_nonce = 0
        self.pool.allowed = [ipaddress.ip_network("127.0.0.0/8")]
        self.pool.work = {"msg": MESSAGE, "h": 614400, "b": 1, "pk": PK}
        self.pool.checked_at = time.monotonic()
        self.pool.error = None
        self.pool.submit_lock = asyncio.Lock()
        self.pool.refresh = asyncio.Event()
        self.pool.solved_height = 0
        self.pool.ledger = Ledger(":memory:")
        self.pool.node = Mock()
        self.server = await asyncio.start_server(self.pool.accept, "127.0.0.1", 0)
        self.reader, self.writer = await asyncio.open_connection("127.0.0.1", self.server.sockets[0].getsockname()[1])
        self.seq = 0
        self.notices = []

    async def asyncTearDown(self):
        self.writer.close()
        await self.writer.wait_closed()
        self.server.close()
        await self.server.wait_closed()
        await asyncio.sleep(0.01)
        self.pool.ledger.db.close()

    async def request(self, method, params):
        self.seq += 1
        self.writer.write((json.dumps({"id": self.seq, "method": method, "params": params}) + "\n").encode())
        await self.writer.drain()
        while True:
            message = json.loads(await asyncio.wait_for(self.reader.readline(), 5))
            if message.get("id") == self.seq:
                return message
            self.notices.append(message)

    async def setup_worker(self, password="x"):
        subscribe = await self.request("mining.subscribe", ["test-miner"])
        prefix = subscribe["result"][1]
        self.assertEqual(subscribe["result"][2], 6)
        self.assertTrue((await self.request("mining.authorize", [ADDRESS1 + ".rig", password]))["result"])
        while not any(n.get("method") == "mining.notify" for n in self.notices):
            self.notices.append(json.loads(await asyncio.wait_for(self.reader.readline(), 5)))
        job = [n for n in self.notices if n.get("method") == "mining.notify"][-1]["params"]
        self.assertEqual(job[1:6], [614400, MESSAGE, "", "", 4])
        return prefix, job

    async def test_static_difficulty_sets_target_without_affecting_block_acceptance(self):
        prefix, job = await self.setup_worker("d=0.1")
        client = next(iter(self.pool.clients))
        self.assertGreater(client.hashes, 400_000_000)
        self.assertLess(client.hashes, 450_000_000)
        self.assertLess(int(job[6]), MAX_TARGET // 400_000_000)
        self.pool.work["b"] = MAX_TARGET
        await client.job(True)
        self.assertEqual(next(reversed(client.jobs.values()))[1], MAX_TARGET)

    async def test_nonfinite_or_negative_static_difficulty_cannot_authorize(self):
        for password in ["d=NaN", "d=Infinity", "d=-1", "d=0", "d=1e99999999"]:
            result = await self.request("mining.authorize", [ADDRESS1 + ".rig", password])
            self.assertEqual(result["error"][0], 24)

    async def test_real_tcp_share_duplicate_worker_and_stale_checks(self):
        prefix, job = await self.setup_worker()
        nonce = prefix + "000000000001"
        params = [ADDRESS1 + ".rig", job[0], nonce[4:], "undefined", nonce]
        self.assertTrue((await self.request("mining.submit", params))["result"])
        self.assertEqual((await self.request("mining.submit", params))["error"][0], 22)
        wrong = list(params)
        wrong[0] = ADDRESS2 + ".rig"
        self.assertEqual((await self.request("mining.submit", wrong))["error"][0], 24)
        wrong = list(params)
        wrong[-1] = "ffff000000000001"
        self.assertEqual((await self.request("mining.submit", wrong))["error"][0], 20)
        self.pool.work = dict(self.pool.work, h=614401)
        self.assertEqual((await self.request("mining.submit", params))["error"][0], 21)
        self.assertEqual(self.pool.ledger.stats()["acceptedShares"], 1)

    async def test_low_difficulty_is_rejected_and_not_credited(self):
        for client in self.pool.clients:
            client.hashes = 1 << 50
        prefix, job = await self.setup_worker()
        nonce = prefix + "000000000001"
        result = await self.request("mining.submit", [ADDRESS1 + ".rig", job[0], "1", "0", nonce])
        self.assertEqual(result["error"][0], 23)
        self.assertEqual(self.pool.ledger.stats()["acceptedShares"], 0)

    async def test_block_is_verified_persisted_then_sent_to_node(self):
        self.pool.work["b"] = MAX_TARGET
        prefix, job = await self.setup_worker()
        nonce = prefix + "000000000001"
        result = await self.request("mining.submit", [ADDRESS1 + ".rig", job[0], "1", "0", nonce])
        self.assertTrue(result["result"])
        self.pool.node.rpc.assert_called_once_with("/mining/solution", {"n": nonce, "pk": PK, "msg": MESSAGE})
        self.assertEqual(self.pool.ledger.blocks()[0]["nonce"], nonce)
        result = await self.request("mining.submit", [ADDRESS1 + ".rig", job[0], "2", "0", prefix + "000000000002"])
        self.assertEqual(result["error"][0], 21)
        self.assertEqual(self.pool.node.rpc.call_count, 1)

    async def test_gpu_stale_burst_does_not_disconnect_or_credit(self):
        prefix, job = await self.setup_worker()
        self.pool.work = dict(self.pool.work, h=614401)
        for _ in range(60):
            result = await self.request("mining.submit", [ADDRESS1 + ".rig", job[0], "1", "0", prefix + "000000000001"])
            self.assertEqual(result["error"][0], 21)
        result = await self.request("mining.extranonce.subscribe", [])
        self.assertTrue(result["result"])
        self.assertEqual(self.pool.ledger.stats()["acceptedShares"], 0)

    async def test_invalid_burst_still_disconnects(self):
        await self.setup_worker()
        for _ in range(50):
            self.assertEqual((await self.request("mining.submit", ["wrong", "job", "nonce"]))["error"][0], 24)
        self.assertEqual(await asyncio.wait_for(self.reader.readline(), 5), b"")

    async def test_wallet_maintenance_does_not_block_stratum(self):
        import threading
        release = threading.Event()
        self.pool.node.reconcile.side_effect = lambda *args: release.wait(5)
        maintenance = asyncio.create_task(self.pool.reconcile(10))
        try:
            await self.setup_worker()
            result = await asyncio.wait_for(self.request("mining.extranonce.subscribe", []), 1)
            self.assertTrue(result["result"])
            self.assertFalse(maintenance.done())
        finally:
            release.set()
            await maintenance

    async def test_no_authorization_or_subscription_no_work(self):
        result = await self.request("mining.submit", [ADDRESS1 + ".rig", "none", "nonce"])
        self.assertEqual(result["error"][0], 25)
        self.assertEqual((await self.request("mining.authorize", ["invalid.rig", "x"]))["error"][0], 24)


if __name__ == "__main__":
    unittest.main()
