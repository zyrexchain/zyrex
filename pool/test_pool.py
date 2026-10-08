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

from ledger import Ledger
from node import Node
from pow import MAX_TARGET, address_bytes, calc_n, hit, validate_miner_address
from server import Pool, validate_config

ADDRESS1 = "Cz5n9QxdK3EPBp9Ggux2dYMySVyUmLksYYPyj9dEhnYYTyW81NFQ"
ADDRESS2 = "Cz3eBQNXVSVQndNM817Ev6R8zS1Yr3E1sT5Hk1RhhpenT5aSwkCQ"
MESSAGE = "548c3e602a8f36f8f2738f5f643b02425038044d98543a51cabaa9785e7e864f"
PK = "0201941e4163eae332959cf06743b28c6b9426772da7faf5a5d5a4ad8e3653595c"
TESTNET_ADDRESS = "ZRXAcmmjjm7wvmcWVqMSi8R5jcdCRQX99aLPymp1xVLWd8JatTPQV94"


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
        self.info["genesisBlockId"] = "ergo"
        with self.assertRaises(RuntimeError):
            self.node.ready()


class ConfigurationTests(unittest.TestCase):
    def test_network_and_reward_maturity_must_match(self):
        valid = {"network": "testnet", "addressPrefix": 64, "minerRewardDelay": 5,
                 "confirmations": 8, "minimumPayoutNano": 1_000_000_000}
        validate_config(valid)
        for update in ({"addressPrefix": 80}, {"network": "mainnet"}, {"confirmations": 5},
                       {"minimumPayoutNano": 0}, {"minerRewardDelay": -1}):
            with self.assertRaises(ValueError):
                validate_config(dict(valid, **update))


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
            self.ledger.bind_network("ergo-genesis", PK)

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
        node.config = {"confirmations": 5}
        node.header = Mock(return_value={"id": "different", "powSolutions": {"pk": PK, "n": "a"}})
        node.rpc = Mock()
        node.reconcile(self.ledger, 15)
        self.assertIsNotNone(self.ledger.halted())
        node.rpc.assert_not_called()

    def test_confirmations_before_credit_and_crash_after_broadcast(self):
        block = self.found(ADDRESS1, "a")
        node = object.__new__(Node)
        node.config = {"confirmations": 5, "payoutFeeNano": 1_000_000, "minimumPayoutNano": 1_000_000_000}
        node.reward_script = "reward-script"
        node.header = Mock(return_value={"id": "canonical", "powSolutions": {"pk": PK, "n": "a"}})
        confirmed = False
        broadcasts = []
        def rpc(path, payload=None):
            if path == "/blocks/canonical":
                return {"blockTransactions": {"transactions": [{"outputs": [
                    {"value": 90_000_000_000, "ergoTree": "reward-script"},
                    {"value": 5_000_000_000, "ergoTree": "founder-1"},
                    {"value": 5_000_000_000, "ergoTree": "founder-2"}]}]}}
            if path == "/wallet/balances":
                return {"balance": 90_000_000_000}
            if path == "/wallet/transaction/generate":
                return {"id": "signed-id", "outputs": payload["requests"]}
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
        with self.assertRaises(TimeoutError):
            node.reconcile(self.ledger, 14)
        self.assertEqual(self.ledger.balances()[ADDRESS1], 0)
        node.reconcile(self.ledger, 15)
        self.assertEqual(broadcasts, ["signed-id", "signed-id"])
        confirmed = True
        node.reconcile(self.ledger, 16)
        self.assertEqual(self.ledger.payouts()[0]["status"], "confirmed")
        self.assertEqual(len(self.ledger.payouts()), 1)


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
        self.pool.node.rpc.assert_called_once_with("/mining/solution", {"n": nonce, "pk": PK})
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
