"""Address dashboard accounting, complete history and read-only HTTP regressions."""
import asyncio
import ipaddress
import json
import tempfile
import time
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest.mock import Mock

from ledger import Ledger
from server import Pool
from test_pool import ADDRESS1, ADDRESS2, MESSAGE, PK, TESTNET_ADDRESS


class MinerAccountingTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.ledger = Ledger(str(Path(self.folder.name) / "pool.sqlite"))
        self.sequence = 0

    def tearDown(self):
        self.ledger.db.close()
        self.folder.cleanup()

    def share(self, address=ADDRESS1, worker="rig", weight=1, created=None):
        self.sequence += 1
        ident = self.ledger.share(MESSAGE, str(self.sequence), address, worker, weight)
        if created is not None:
            with self.ledger.db:
                self.ledger.db.execute("UPDATE shares SET created=? WHERE id=?", (created, ident))
        return ident

    def credit(self, reward, addresses=(ADDRESS1,)):
        for address in addresses:
            end = self.share(address)
        self.ledger.block({"msg": MESSAGE, "h": self.sequence, "pk": PK}, str(self.sequence), end)
        block = self.ledger.blocks()[-1]
        self.ledger.settle(block, "block-" + str(self.sequence), reward)

    def payout(self, txid, amounts, fees=None, status="confirmed", created=None):
        gross = {address: value + fees[address] for address, value in amounts.items()} if fees is not None else None
        self.ledger.prepare_payout({"id": txid, "secretFixture": "not public"}, amounts, gross, fees)
        self.ledger.payout_status(txid, status)
        if created is not None:
            with self.ledger.db:
                self.ledger.db.execute("UPDATE payouts SET created=? WHERE id=?", (created, txid))

    def test_complete_address_history_and_totals_exceed_global_latest_100(self):
        self.credit((1 << 55) + 34, (ADDRESS1, ADDRESS2))
        exact = (1 << 53) + 7
        self.payout("exact", {ADDRESS1: exact, ADDRESS2: 107}, {ADDRESS1: 3, ADDRESS2: 5}, created=1)
        for index in range(120):
            self.payout("older-" + str(index), {ADDRESS1: 101}, {ADDRESS1: 2}, created=2 + index)
        for index in range(110):
            self.payout("other-" + str(index), {ADDRESS2: 103}, {ADDRESS2: 7}, created=1000 + index)
        self.assertTrue(all(ADDRESS1 not in json.loads(p["amounts"]) for p in self.ledger.stats()["payouts"]))
        summary = self.ledger.miner_summary(ADDRESS1)
        paid = summary["account"]["confirmedPaid"]
        self.assertEqual(paid["count"], 121)
        self.assertEqual(paid["netNanoZYRX"], str(exact + 120 * 101))
        self.assertEqual(paid["feeNanoZYRX"], str(3 + 120 * 2))
        self.assertEqual(paid["grossNanoZYRX"], str(exact + 3 + 120 * 103))
        last = self.ledger.miner_payouts(ADDRESS1, 20, 120, confirmations=8)
        self.assertEqual(last["total"], 121)
        self.assertEqual(last["items"][0]["id"], "exact")
        self.assertEqual(last["items"][0]["netNanoZYRX"], str(exact))
        self.assertEqual(last["items"][0]["requiredConfirmations"], 8)
        public = json.dumps(last)
        self.assertNotIn(ADDRESS2, public)
        self.assertNotIn("secretFixture", public)
        self.assertNotIn("raw", public)

    def test_reserved_confirmed_and_legacy_fees_are_distinct(self):
        self.credit(10000)
        self.payout("paid-new", {ADDRESS1: 97}, {ADDRESS1: 3})
        self.payout("paid-legacy", {ADDRESS1: 101})
        self.payout("prepared-new", {ADDRESS1: 193}, {ADDRESS1: 7}, "prepared")
        self.payout("broadcast-legacy", {ADDRESS1: 203}, status="broadcast")
        with self.ledger.db:
            self.ledger.db.execute("INSERT INTO fee_adjustments VALUES(?,?,?,?)", ("fixture", ADDRESS1, 11, "{}"))
            self.ledger.db.execute("UPDATE balances SET amount=amount+11 WHERE address=?", (ADDRESS1,))
        before = self.history()
        account = self.ledger.miner_summary(ADDRESS1)["account"]
        self.assertEqual(account["creditedNanoZYRX"], "10000")
        self.assertEqual(account["feeRefundNanoZYRX"], "11")
        self.assertEqual(account["lifetimeCreditNanoZYRX"], "10011")
        self.assertEqual(account["unpaidNanoZYRX"], "9407")
        self.assertEqual(account["reservedBalanceDebitNanoZYRX"], "403")
        self.assertEqual(account["confirmedPaid"]["netNanoZYRX"], "198")
        self.assertEqual(account["pendingPayout"]["netNanoZYRX"], "396")
        self.assertEqual(account["pendingPayout"]["knownGrossNanoZYRX"], "200")
        self.assertEqual(account["pendingPayout"]["knownFeeNanoZYRX"], "7")
        self.assertEqual(account["confirmedPaid"]["legacyNetNanoZYRX"], "101")
        self.assertIsNone(account["confirmedPaid"]["grossNanoZYRX"])
        self.assertIsNone(account["pendingPayout"]["feeNanoZYRX"])
        self.assertIsNone(account["immatureEstimatedNanoZYRX"])
        legacy = next(p for p in self.ledger.miner_payouts(ADDRESS1)["items"] if p["id"] == "paid-legacy")
        self.assertEqual(legacy["feePolicy"], "legacy-prepaid-unknown")
        self.assertIsNone(legacy["grossNanoZYRX"])
        self.assertIsNone(legacy["numConfirmations"])
        self.assertEqual(self.history(), before)

    def test_integer_lifetime_credit_aggregate_can_exceed_sqlite_int64(self):
        reward = (1 << 62) + 3
        for index in range(3):
            self.credit(reward)
            self.payout("large-" + str(index), {ADDRESS1: reward - 1}, {ADDRESS1: 1})
        summary = self.ledger.miner_summary(ADDRESS1)
        self.assertEqual(summary["account"]["creditedNanoZYRX"], str(3 * reward))
        self.assertEqual(summary["account"]["confirmedPaid"]["grossNanoZYRX"], str(3 * reward))
        self.assertEqual(summary["account"]["unpaidNanoZYRX"], "0")

    def test_durable_workers_window_and_current_prop_weights(self):
        now = 100000
        settled = self.share(worker="old", weight=1 << 120, created=now - 90000)
        self.ledger.block({"msg": MESSAGE, "h": 1, "pk": PK}, "settled", settled)
        self.ledger.settle(self.ledger.blocks()[0], "canonical", 100)
        self.share(worker="offline", weight=11, created=now - 601)
        self.share(worker="rig", weight=17, created=now - 599)
        end = self.share(worker="rig", weight=23, created=now - 1)
        self.share(ADDRESS2, "outlier", 1 << 100, now - 1)
        self.ledger.block({"msg": MESSAGE, "h": 2, "pk": PK}, "candidate", end)
        result = self.ledger.miner_summary(ADDRESS1, {"rig": 2, "waiting": 1}, now=now)
        mining = result["mining"]
        self.assertEqual(mining["acceptedShares"], 4)
        self.assertEqual(mining["acceptedShares24h"], 3)
        self.assertEqual(mining["windowAcceptedShares"], 2)
        self.assertAlmostEqual(mining["estimatedHashrate"], 40 / 600)
        self.assertEqual(mining["currentRound"]["minerWeightExact"], "51")
        self.assertEqual(mining["currentRound"]["poolWeightExact"], str((1 << 100) + 51))
        self.assertEqual(mining["pendingCandidateBlocks"], 1)
        self.assertIsNone(mining["rejectedShares"])
        workers = {w["name"]: w for w in result["workers"]}
        self.assertTrue(workers["rig"]["active"])
        self.assertEqual(workers["rig"]["connectedSessions"], 2)
        self.assertFalse(workers["offline"]["active"])
        self.assertFalse(workers["waiting"]["active"])
        self.assertEqual(workers["old"]["estimatedHashrate"], 0)
        self.assertEqual(workers["offline"]["lastShare"], now - 601)
        self.assertNotIn("outlier", workers)
        self.assertEqual(result["connectedWorkers"], 3)
        self.assertEqual(result["workerCount"], 4)

    def test_snapshot_pagination_is_stable_when_new_payouts_arrive(self):
        self.credit(1000)
        for index in range(7):
            self.payout(str(index), {ADDRESS1: 10}, {ADDRESS1: 1}, created=100)
        first = self.ledger.miner_payouts(ADDRESS1, 3)
        self.assertEqual([p["id"] for p in first["items"]], ["6", "5", "4"])
        self.payout("new", {ADDRESS1: 10}, {ADDRESS1: 1}, created=101)
        second = self.ledger.miner_payouts(ADDRESS1, 3, 3, int(first["snapshot"]))
        self.assertEqual([p["id"] for p in second["items"]], ["3", "2", "1"])
        self.assertEqual(second["total"], 7)
        self.assertTrue(second["hasMore"])
        self.assertEqual(second["previousOffset"], 0)
        self.assertEqual(second["nextOffset"], 6)
        refreshed = self.ledger.miner_payouts(ADDRESS1, 3)
        self.assertEqual(refreshed["total"], 8)
        self.assertEqual(refreshed["items"][0]["id"], "new")
        self.assertEqual(self.ledger.miner_payouts(ADDRESS1, snapshot=0)["items"], [])

    def test_pagination_bounds_and_unknown_account_are_read_only(self):
        for kwargs in ({"limit": 0}, {"limit": 101}, {"limit": True}, {"offset": -1},
                       {"offset": 1000001}, {"snapshot": -1}, {"snapshot": 1 << 63}):
            with self.assertRaises(ValueError):
                self.ledger.miner_payouts(ADDRESS1, **kwargs)
        unknown = self.ledger.miner_summary(ADDRESS2)
        self.assertFalse(unknown["known"])
        self.assertEqual(unknown["account"]["lifetimeCreditNanoZYRX"], "0")
        self.assertEqual(unknown["workers"], [])
        self.assertEqual(self.ledger.miner_payouts(ADDRESS2)["total"], 0)
        self.assertEqual(self.ledger.balances(), {})

    def test_public_snapshot_does_not_wait_for_ledger_writer_lock(self):
        self.credit(100)
        with ThreadPoolExecutor(max_workers=1) as executor:
            with self.ledger.lock:
                future = executor.submit(self.ledger.miner_summary, ADDRESS1)
                self.assertEqual(future.result(timeout=2)["account"]["unpaidNanoZYRX"], "100")

    def history(self):
        return {table: [tuple(row) for row in self.ledger.db.execute("SELECT * FROM " + table)]
                for table in ("shares", "blocks", "credits", "balances", "payouts", "fee_adjustments", "meta")}


class MinerHTTPTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.pool = object.__new__(Pool)
        self.pool.ledger = Ledger(str(Path(self.folder.name) / "pool.sqlite"))
        self.pool.config = {"addressPrefix": 80, "confirmations": 8, "minimumPayoutNano": 1000000000,
                            "payoutFeeNano": 1000000, "network": "devnet"}
        self.pool.clients = set()
        self.pool.allowed = [ipaddress.ip_network("127.0.0.0/8")]
        self.pool.work = {"h": 10}
        self.pool.info = {"fullHeight": 9}
        self.pool.error = None
        self.pool.checked_at = time.monotonic()
        self.pool.node = Mock()
        self.pool.node.rpc = Mock(side_effect=AssertionError("HTTP must not perform node RPC"))
        self.server = await asyncio.start_server(self.pool.http, "127.0.0.1", 0, limit=8192)
        self.port = self.server.sockets[0].getsockname()[1]

    async def asyncTearDown(self):
        self.server.close()
        await self.server.wait_closed()
        self.pool.ledger.db.close()
        self.folder.cleanup()

    async def request(self, path, method="GET"):
        reader, writer = await asyncio.open_connection("127.0.0.1", self.port)
        writer.write(f"{method} {path} HTTP/1.1\r\nHost: localhost\r\n\r\n".encode())
        await writer.drain()
        response = await asyncio.wait_for(reader.read(), 3)
        writer.close()
        await writer.wait_closed()
        header, body = response.split(b"\r\n\r\n", 1)
        return header.decode(), body

    async def test_get_head_unknown_address_and_no_node_or_database_mutation(self):
        before = self.pool.ledger.db.total_changes
        header, body = await self.request("/api/miner/" + ADDRESS1)
        self.assertTrue(header.startswith("HTTP/1.1 200"))
        data = json.loads(body)
        self.assertFalse(data["known"])
        self.assertEqual(data["minimumPayoutNanoZYRX"], "1000000000")
        self.assertIn("Cache-Control: no-store", header)
        header, body = await self.request("/api/miner/" + ADDRESS1 + "/payouts", "HEAD")
        self.assertTrue(header.startswith("HTTP/1.1 200"))
        self.assertEqual(body, b"")
        self.pool.node.rpc.assert_not_called()
        self.assertEqual(self.pool.ledger.db.total_changes, before)

    async def test_address_and_query_validation_rejects_injection_and_wrong_network(self):
        bad = [ADDRESS1[:-1] + "1", TESTNET_ADDRESS, "'OR1=1", "a" * 101, ADDRESS1 + ".rig"]
        targets = ["/api/miner/" + address for address in bad]
        base = "/api/miner/" + ADDRESS1 + "/payouts"
        targets += [base + query for query in ("?limit=0", "?limit=101", "?offset=-1", "?offset=1000001",
            "?snapshot=9223372036854775808", "?limit=1&limit=2", "?limit=1&foo=2", "?limit=%31",
            "?limit=1&offset=0&snapshot=0&extra=1", "?offset=1;DROP_TABLE", "?offset=", "?limit=1#fragment")]
        targets += ["/api/miner/" + ADDRESS1 + "?limit=1", "/api/miner/" + ADDRESS1 + "/payouts?" + "x" * 500]
        for target in targets:
            with self.subTest(target=target):
                header, body = await self.request(target)
                self.assertTrue(header.startswith("HTTP/1.1 400"), header)
                self.assertIn("error", json.loads(body))
        self.assertEqual(self.pool.ledger.db.execute("SELECT COUNT(*) FROM payouts").fetchone()[0], 0)

    async def test_post_is_rejected_and_routes_do_not_write(self):
        header, body = await self.request("/api/miner/" + ADDRESS1, "POST")
        self.assertTrue(header.startswith("HTTP/1.1 405"))
        self.assertIn("Allow: GET, HEAD", header)
        self.assertEqual(json.loads(body)["error"], "Use GET or HEAD")
        header, body = await self.request("/unknown")
        self.assertTrue(header.startswith("HTTP/1.1 404"))
        self.assertEqual(self.pool.ledger.balances(), {})

    async def test_worker_connections_are_taken_from_actual_authorized_clients(self):
        class Worker:
            def __init__(self, address, name, authorized=True):
                self.username = address + "." + name if authorized else None
                self.address, self.worker = address, name
        self.pool.clients.update({Worker(ADDRESS1, "rig"), Worker(ADDRESS1, "rig"),
                                  Worker(ADDRESS2, "other"), Worker(ADDRESS1, "unauthorized", False)})
        self.pool.ledger.share(MESSAGE, "share", ADDRESS1, "rig", 600)
        header, body = await self.request("/api/miner/" + ADDRESS1)
        self.assertTrue(header.startswith("HTTP/1.1 200"))
        data = json.loads(body)
        self.assertEqual(data["connectedWorkers"], 2)
        self.assertEqual(len(data["workers"]), 1)
        self.assertEqual(data["workers"][0]["connectedSessions"], 2)
        self.assertTrue(data["workers"][0]["active"])
        self.assertEqual(data["workers"][0]["estimatedHashrate"], 1)


if __name__ == "__main__":
    unittest.main()
