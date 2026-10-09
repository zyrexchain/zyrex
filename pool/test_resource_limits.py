"""Local-only resource abuse regressions with a real controlled Stratum client."""
import asyncio
import ipaddress
import json
import threading
import time
import unittest
from unittest.mock import Mock, patch

from ledger import Ledger
from resource_limits import Admission, DuplicateCache, ResourceBusy, TokenBucket, WorkLane, proxy_identity
from server import Pool
from test_pool import ADDRESS1, MESSAGE, PK


class BudgetTests(unittest.TestCase):
    def test_bucket_refills_and_duplicate_cache_is_bounded_and_expires(self):
        bucket = TokenBucket(2, 3, now=0)
        self.assertTrue(all(bucket.take(now=0) for _ in range(3)))
        self.assertFalse(bucket.take(now=0))
        self.assertTrue(bucket.take(now=0.5))
        cache = DuplicateCache(capacity=2, ttl=5)
        self.assertTrue(cache.reserve(("a", "1"), now=0))
        self.assertFalse(cache.reserve(("a", "1"), now=1))
        self.assertTrue(cache.reserve(("b", "2"), now=2))
        self.assertTrue(cache.reserve(("c", "3"), now=3))
        self.assertEqual(len(cache.entries), 2)
        self.assertTrue(cache.reserve(("b", "2"), now=8))

    def test_identity_table_never_evicts_an_exhausted_bucket_to_reset_it(self):
        admission = Admission({"resourceLimits": {"identityEntries": 2, "connectBurst": 1}})
        self.assertTrue(admission.connect("203.0.113.1"))
        admission.drop(admission.pending, "203.0.113.1")
        self.assertTrue(admission.connect("203.0.113.2"))
        self.assertFalse(admission.connect("203.0.113.3"))
        self.assertFalse(admission.connect("203.0.113.1"))
        self.assertEqual(len(admission.connections), 2)

    def test_strict_proxy_identity_rejects_forgery_ambiguity_and_invalid_family(self):
        self.assertEqual(proxy_identity(b"PROXY TCP4 203.0.113.7 203.0.113.8 20000 3333\r\n"), "203.0.113.7")
        for value in (b"PROXY UNKNOWN\r\n", b"PROXY TCP4 ::1 ::1 20000 3333\r\n",
                      b"PROXY TCP4 0.0.0.0 203.0.113.8 20000 3333\r\n",
                      b"PROXY TCP4 203.0.113.7 203.0.113.8 0 3333\r\n",
                      b"PROXY  TCP4 203.0.113.7 203.0.113.8 20000 3333\r\n"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                proxy_identity(value)


class ExecutorTests(unittest.IsolatedAsyncioTestCase):
    async def test_cancelled_request_keeps_its_worker_reserved_until_completion(self):
        lane = WorkLane("test", 1, 0)
        release = threading.Event()
        started = threading.Event()

        def blocked():
            started.set()
            release.wait(5)
            return 7
        task = asyncio.create_task(lane.run(blocked))
        try:
            while not started.is_set():
                await asyncio.sleep(0.001)
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)
            self.assertEqual(lane.inflight, 1)
            with self.assertRaises(ResourceBusy):
                await lane.run(lambda: 8)
            release.set()
            for _ in range(100):
                if lane.inflight == 0:
                    break
                await asyncio.sleep(0.005)
            self.assertEqual(await lane.run(lambda: 9), 9)
            self.assertEqual(lane.high_water, 1)
        finally:
            release.set()
            lane.close()


class PoolResourceTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.pool = object.__new__(Pool)
        self.pool.config = {"initialShareHashes": 1, "addressPrefix": 80, "confirmations": 8,
                            "minimumPayoutNano": 1000000000, "payoutFeeNano": 1000000,
                            "publicStratumUrl": "stratum+tcp://pool.example:3333",
                            "resourceLimits": {"handshakeSeconds": 0.2, "proxyHeaderSeconds": 0.1}}
        self.pool.clients = set()
        self.pool.extra_nonce = 0
        self.pool.allowed = [ipaddress.ip_network("127.0.0.0/8")]
        self.pool.work = {"msg": MESSAGE, "h": 614400, "b": 1, "pk": PK}
        self.pool.info = {"fullHeight": 614399}
        self.pool.checked_at = time.monotonic()
        self.pool.error = self.pool.payout_error = None
        self.pool.submit_lock = asyncio.Lock()
        self.pool.refresh = asyncio.Event()
        self.pool.solved_height = 0
        self.pool.ledger = Ledger(":memory:")
        self.pool.node = Mock()
        self.pool.node.wallet = {"address": ADDRESS1}
        self.pool.ensure_resources()
        self.stratum = await asyncio.start_server(self.pool.accept, "127.0.0.1", 0, limit=8192)
        self.http = await asyncio.start_server(self.pool.http, "127.0.0.1", 0, limit=8192)
        self.sport = self.stratum.sockets[0].getsockname()[1]
        self.hport = self.http.sockets[0].getsockname()[1]
        self.connections = []
        self.seq = 0

    async def asyncTearDown(self):
        for _, writer in self.connections:
            writer.close()
        await asyncio.gather(*(writer.wait_closed() for _, writer in self.connections), return_exceptions=True)
        self.stratum.close()
        self.http.close()
        await self.stratum.wait_closed()
        await self.http.wait_closed()
        for _ in range(100):
            if not self.pool.clients and not self.pool.resources.header_connections \
                    and not any(lane.inflight for lane in self.pool.lanes.values()):
                break
            await asyncio.sleep(0.01)
        for lane in self.pool.lanes.values():
            lane.close()
        self.pool.ledger.db.close()

    async def connect(self, source="127.0.0.1", port=None, proxy=None):
        pair = await asyncio.open_connection("127.0.0.1", port or self.sport, local_addr=(source, 0))
        self.connections.append(pair)
        if proxy:
            pair[1].write(f"PROXY TCP4 {proxy} 203.0.113.9 20000 3333\r\n".encode())
            await pair[1].drain()
        return pair

    async def request(self, pair, method, params):
        self.seq += 1
        ident = self.seq
        pair[1].write((json.dumps({"id": ident, "method": method, "params": params}) + "\n").encode())
        await pair[1].drain()
        while True:
            response = json.loads(await asyncio.wait_for(pair[0].readline(), 2))
            if response.get("id") == ident:
                return response

    async def worker(self, source="127.0.0.2", proxy=None, password="x"):
        pair = await self.connect(source, proxy=proxy)
        subscribed = await self.request(pair, "mining.subscribe", ["controlled-miner"])
        prefix = subscribed["result"][1]
        self.assertTrue((await self.request(pair, "mining.authorize", [ADDRESS1 + ".control", password]))["result"])
        while True:
            job = json.loads(await asyncio.wait_for(pair[0].readline(), 2))
            if job.get("method") == "mining.notify":
                return pair, prefix, job["params"]

    async def test_idle_connection_flood_cannot_exhaust_active_miners(self):
        attackers = [await self.connect() for _ in range(12)]
        await asyncio.sleep(0.03)
        self.assertLessEqual(self.pool.resources.pending.get("127.0.0.1", 0), 4)
        pair, _, job = await self.worker()
        self.assertEqual(job[1], 614400)
        self.assertEqual(self.pool.resources.active["127.0.0.2"], 1)
        await asyncio.sleep(0.25)
        self.assertEqual(self.pool.resources.pending, {})
        self.assertTrue((await self.request(pair, "mining.extranonce.subscribe", []))["result"])
        self.assertEqual(await asyncio.gather(*(reader.read() for reader, _ in attackers)), [b""] * len(attackers))

    async def test_short_absolute_authorization_deadline_is_not_extended_by_requests(self):
        pair = await self.connect()
        await self.request(pair, "mining.subscribe", [])
        for _ in range(3):
            await asyncio.sleep(0.04)
            await self.request(pair, "mining.get_transactions", [])
        self.assertEqual(await asyncio.wait_for(pair[0].readline(), 0.2), b"")
        self.assertEqual(self.pool.resources.pending, {})

    async def test_proxy_clients_have_independent_limits_and_untrusted_proxy_is_rejected(self):
        self.pool.config["stratumProxyProtocol"] = True
        self.pool.proxy_networks = [ipaddress.ip_network("127.0.0.1/32")]
        attackers = [await self.connect(proxy="203.0.113.1") for _ in range(8)]
        await asyncio.sleep(0.03)
        self.assertEqual(self.pool.resources.pending.get("203.0.113.1"), 4)
        pair, _, job = await self.worker(source="127.0.0.1", proxy="203.0.113.2")
        self.assertEqual(job[1], 614400)
        self.assertEqual(self.pool.resources.active.get("203.0.113.2"), 1)
        untrusted = await self.connect("127.0.0.2", proxy="203.0.113.3")
        try:
            self.assertEqual(await asyncio.wait_for(untrusted[0].read(), 1), b"")
        except ConnectionResetError:
            pass  # Rejecting before consuming untrusted bytes can reset the TCP stream.
        self.assertNotIn("203.0.113.3", self.pool.resources.pending)
        for _, writer in attackers:
            writer.close()
        self.assertTrue((await self.request(pair, "mining.extranonce.subscribe", []))["result"])

    async def test_active_limit_is_separate_and_other_clients_still_get_jobs(self):
        self.pool.resources.limits["activePerIp"] = 1
        await self.worker()
        pair = await self.connect("127.0.0.2")
        await self.request(pair, "mining.subscribe", [])
        denied = await self.request(pair, "mining.authorize", [ADDRESS1 + ".extra", "x"])
        self.assertEqual(denied["error"][0], 26)
        self.assertEqual(await pair[0].read(), b"")
        self.assertEqual((await self.worker("127.0.0.3"))[2][1], 614400)

    async def test_duplicate_is_checked_before_hit_including_after_cache_and_session_reset(self):
        pair, prefix, job = await self.worker()
        nonce = prefix + "000000000001"
        params = [ADDRESS1 + ".control", job[0], nonce[4:], "undefined", nonce]
        with patch("server.hit", return_value=2) as verifier:
            self.assertTrue((await self.request(pair, "mining.submit", params))["result"])
            for _ in range(60):
                self.assertEqual((await self.request(pair, "mining.submit", params))["error"][0], 22)
            self.assertEqual(verifier.call_count, 1)
            self.pool.duplicates.entries.clear()
            self.assertEqual((await self.request(pair, "mining.submit", params))["error"][0], 22)
            self.assertEqual(verifier.call_count, 1)
            pair[1].close()
            await pair[1].wait_closed()
            await asyncio.sleep(0.02)
            self.pool.extra_nonce = int(prefix, 16) - 1
            replacement, next_prefix, next_job = await self.worker()
            self.assertEqual(next_prefix, prefix)
            self.pool.duplicates.entries.clear()
            params[1] = next_job[0]
            self.assertEqual((await self.request(replacement, "mining.submit", params))["error"][0], 22)
            self.assertEqual(verifier.call_count, 1)

    async def test_stale_gpu_burst_is_tolerated_then_request_budget_closes_abuse(self):
        pair, prefix, job = await self.worker()
        self.pool.work = dict(self.pool.work, h=614401)
        params = [ADDRESS1 + ".control", job[0], "0", "0", prefix + "000000000001"]
        for _ in range(60):
            self.assertEqual((await self.request(pair, "mining.submit", params))["error"][0], 21)
        self.assertTrue((await self.request(pair, "mining.extranonce.subscribe", []))["result"])
        self.pool.resources.limits["shareRate"] = 0.01
        client = next(iter(self.pool.clients))
        client.share_budget.tokens = 0
        client.share_budget.rate = 0.01
        self.assertEqual((await self.request(pair, "mining.submit", params))["error"][0], 26)
        self.assertEqual(await pair[0].read(), b"")
        self.assertEqual(self.pool.ledger.stats()["acceptedShares"], 0)

    async def test_http_flood_and_blocked_maintenance_do_not_occupy_pow_or_job_lanes(self):
        release = threading.Event()
        started = threading.Event()
        calls = []

        def blocked_summary(*_):
            calls.append(1)
            started.set()
            release.wait(5)
            return {"known": False}
        self.pool.ledger.miner_summary = blocked_summary
        self.pool.node.reconcile.side_effect = lambda *_: release.wait(5)
        maintenance = asyncio.create_task(self.pool.reconcile(10))
        http_pairs = [await self.connect(port=self.hport) for _ in range(18)]
        for _, writer in http_pairs:
            writer.write(f"GET /api/miner/{ADDRESS1} HTTP/1.1\r\nHost: pool.example\r\n\r\n".encode())
            await writer.drain()
        try:
            while not started.is_set():
                await asyncio.sleep(0.001)
            pair, prefix, job = await self.worker()
            with patch("server.hit", return_value=2):
                nonce = prefix + "000000000001"
                result = await self.request(pair, "mining.submit", [ADDRESS1 + ".control", job[0], "0", "0", nonce])
                self.assertTrue(result["result"])
            self.assertLessEqual(self.pool.lanes["http"].inflight, 8)
            self.assertLessEqual(len(calls), 2)
            self.assertFalse(maintenance.done())
            release.set()
            responses = await asyncio.gather(*(reader.read() for reader, _ in http_pairs))
            self.assertTrue(any(response.startswith(b"HTTP/1.1 503") for response in responses))
            self.assertTrue(any(response.startswith(b"HTTP/1.1 200") for response in responses))
            self.assertEqual(self.pool.ledger.stats()["acceptedShares"], 1)
        finally:
            release.set()
            await maintenance

    async def recovered_solution(self, replacement_height):
        self.pool.info = {"fullHeight": 614399, "bestFullHeaderId": "a" * 64}
        self.pool.work["b"] = (1 << 256) - 1
        pair, prefix, old_job = await self.worker()
        self.pool.solved_height = 614400
        old_params = [ADDRESS1 + ".control", old_job[0], "0", "0", prefix + "000000000001"]
        self.assertFalse(self.pool.apply_info(dict(self.pool.info)))
        self.assertEqual((await self.request(pair, "mining.submit", old_params))["error"][0], 21)
        self.assertEqual(self.pool.solved_height, 614400)
        self.assertEqual(self.pool.ledger.blocks(), [])
        self.pool.node.rpc.assert_not_called()
        self.assertTrue(self.pool.apply_info({"fullHeight": replacement_height, "bestFullHeaderId": "b" * 64}))
        self.assertEqual(self.pool.solved_height, replacement_height)
        self.assertFalse(self.pool.ready())
        client = next(iter(self.pool.clients))
        self.assertEqual(len(client.jobs), 0)
        self.pool.work = {"msg": "c" * 64, "h": replacement_height + 1, "b": (1 << 256) - 1, "pk": PK}
        await client.job(True)
        while True:
            notice = json.loads(await asyncio.wait_for(pair[0].readline(), 2))
            if notice.get("method") == "mining.notify":
                job = notice["params"]
                break
        with patch("server.hit", return_value=2) as verifier:
            params = [ADDRESS1 + ".control", job[0], "0", "0", prefix + "000000000002"]
            self.assertTrue((await self.request(pair, "mining.submit", params))["result"])
            self.assertEqual((await self.request(pair, "mining.submit", params))["error"][0], 21)
            self.assertEqual((await self.request(pair, "mining.submit", old_params))["error"][0], 21)
        self.assertEqual(verifier.call_count, 1)
        self.pool.node.rpc.assert_called_once_with("/mining/solution", {"n": params[-1], "pk": PK, "msg": self.pool.work["msg"]})
        blocks = self.pool.ledger.blocks()
        self.assertEqual(len(blocks), 1)
        self.assertEqual(blocks[0]["height"], replacement_height + 1)
        self.assertEqual(blocks[0]["msg"], self.pool.work["msg"])
        self.assertEqual(self.pool.ledger.stats()["acceptedShares"], 1)

    async def test_canonical_tip_rollback_reopens_solved_height_for_one_new_solution(self):
        await self.recovered_solution(614398)

    async def test_same_height_tip_replacement_reopens_solved_height_for_one_new_solution(self):
        await self.recovered_solution(614399)

    async def test_old_branch_solution_rpc_completion_does_not_restore_its_suppression_marker(self):
        self.pool.info = {"fullHeight": 614399, "bestFullHeaderId": "a" * 64}
        self.pool.work["b"] = (1 << 256) - 1
        pair, prefix, job = await self.worker()
        started, release = threading.Event(), threading.Event()
        self.pool.node.rpc.side_effect = lambda *_: (started.set(), release.wait(5))
        params = [ADDRESS1 + ".control", job[0], "0", "0", prefix + "000000000001"]
        with patch("server.hit", return_value=2):
            submit = asyncio.create_task(self.request(pair, "mining.submit", params))
            try:
                while not started.is_set():
                    await asyncio.sleep(0.001)
                self.assertTrue(self.pool.apply_info({"fullHeight": 614399, "bestFullHeaderId": "b" * 64}))
                self.assertEqual(self.pool.solved_height, 614399)
                release.set()
                self.assertTrue((await submit)["result"])
                self.assertEqual(self.pool.solved_height, 614399)
                self.assertIsNone(self.pool.solution_ack_at)
                self.assertIsNone(self.pool.solution_ack_height)
                self.assertEqual(len(self.pool.ledger.blocks()), 1)
            finally:
                release.set()
                await asyncio.gather(submit, return_exceptions=True)

    async def test_unapplied_native_ack_expires_only_after_fresh_synced_info_and_allows_exact_next_template(self):
        self.pool.info = {"fullHeight": 614399, "headersHeight": 614399,
                          "bestFullHeaderId": "a" * 64, "bestHeaderId": "a" * 64}
        self.pool.work["b"] = (1 << 256) - 1
        pair, prefix, job = await self.worker()
        current = dict(self.pool.work)
        with patch("server.hit", return_value=2):
            first = [ADDRESS1 + ".control", job[0], "0", "0", prefix + "000000000001"]
            self.assertTrue((await self.request(pair, "mining.submit", first))["result"])
            ack = self.pool.solution_ack_at
            self.assertIsNotNone(ack)
            self.assertEqual(self.pool.solution_ack_height, 614400)
            self.assertEqual(self.pool.resource_stats()["pendingSolutionAck"]["height"], 614400)
            second = [ADDRESS1 + ".control", job[0], "0", "0", prefix + "000000000002"]
            self.assertFalse(self.pool.expire_solution_ack(current, True, now=ack + 19.99))
            self.assertEqual((await self.request(pair, "mining.submit", second))["error"][0], 21)
            self.assertFalse(self.pool.expire_solution_ack(current, False, now=ack + 21))
            self.pool.info["headersHeight"] += 1
            self.assertFalse(self.pool.expire_solution_ack(current, True, now=ack + 21))
            self.pool.info["headersHeight"] -= 1
            self.pool.info["bestHeaderId"] = "b" * 64
            self.assertFalse(self.pool.expire_solution_ack(current, True, now=ack + 21))
            self.pool.info["bestHeaderId"] = "a" * 64
            self.assertFalse(self.pool.expire_solution_ack(dict(current, h=614401), True, now=ack + 21))
            current["msg"] = "d" * 64
            self.assertTrue(self.pool.expire_solution_ack(current, True, now=ack + 21))
            self.assertEqual(self.pool.solved_height, 614399)
            self.assertIsNone(self.pool.solution_ack_at)
            self.assertIsNone(self.pool.resource_stats()["pendingSolutionAck"])
            client = next(iter(self.pool.clients))
            self.assertEqual(len(client.jobs), 0)
            self.assertFalse(self.pool.ready())
            self.pool.work = current
            await client.job(True)
            while True:
                message = json.loads(await asyncio.wait_for(pair[0].readline(), 2))
                if message.get("method") == "mining.notify":
                    fresh_job = message["params"]
                    break
            second[1] = fresh_job[0]
            self.assertTrue((await self.request(pair, "mining.submit", second))["result"])
            self.assertEqual((await self.request(pair, "mining.submit", second))["error"][0], 21)
        calls = self.pool.node.rpc.call_args_list
        self.assertEqual(len(calls), 2)
        self.assertEqual(calls[0].args, ("/mining/solution", {"n": first[-1], "pk": PK, "msg": MESSAGE}))
        self.assertEqual(calls[1].args, ("/mining/solution", {"n": second[-1], "pk": PK, "msg": "d" * 64}))
        self.assertEqual(len(self.pool.ledger.blocks()), 2)
        self.assertEqual(self.pool.ledger.balances(), {})

    async def test_update_loop_rebroadcasts_same_work_after_fresh_expired_ack(self):
        info = {"fullHeight": 614399, "headersHeight": 614399,
                "bestFullHeaderId": "a" * 64, "bestHeaderId": "a" * 64}
        self.pool.info = dict(info)
        pair, _, previous_job = await self.worker()
        self.pool.node.pk = PK
        self.pool.node.ready.return_value = dict(info)
        self.pool.node.rpc.return_value = dict(self.pool.work)
        self.pool.config["genesisId"] = "f" * 64
        self.pool.last_reconcile = time.monotonic()
        self.pool.reconcile_task = None
        self.pool.acknowledge_solution(self.pool.work["h"])
        self.pool.solution_ack_at -= 21
        self.pool.checked_at -= 6
        updater = asyncio.create_task(self.pool.update())
        try:
            while True:
                message = json.loads(await asyncio.wait_for(pair[0].readline(), 2))
                if message.get("method") == "mining.notify":
                    fresh = message["params"]
                    break
            self.assertNotEqual(fresh[0], previous_job[0])
            self.assertEqual(fresh[1:3], previous_job[1:3])
            self.assertTrue(fresh[-1])
            self.assertEqual(self.pool.solved_height, 614399)
            self.assertIsNone(self.pool.solution_ack_at)
            self.pool.node.ready.assert_called_once()
            self.pool.node.rpc.assert_called_once_with("/mining/candidate")
            self.assertTrue(self.pool.ready())
            self.assertEqual(self.pool.ledger.blocks(), [])
        finally:
            updater.cancel()
            await asyncio.gather(updater, return_exceptions=True)

    async def test_ack_expiration_does_not_release_a_manually_set_marker_without_native_ack(self):
        self.pool.info = {"fullHeight": 614399, "headersHeight": 614399,
                          "bestFullHeaderId": "a" * 64, "bestHeaderId": "a" * 64}
        self.pool.solved_height = 614400
        pair, prefix, job = await self.worker()
        self.assertFalse(self.pool.expire_solution_ack(self.pool.work, True, now=time.monotonic() + 1000))
        self.assertEqual(self.pool.solved_height, 614400)
        params = [ADDRESS1 + ".control", job[0], "0", "0", prefix + "000000000001"]
        self.assertEqual((await self.request(pair, "mining.submit", params))["error"][0], 21)
        self.pool.node.rpc.assert_not_called()
        self.assertEqual(self.pool.ledger.blocks(), [])

    async def test_applied_tip_clears_pending_ack_without_unblocking_old_height(self):
        self.pool.info = {"fullHeight": 614399, "headersHeight": 614399,
                          "bestFullHeaderId": "a" * 64, "bestHeaderId": "a" * 64}
        self.pool.acknowledge_solution(614400)
        self.assertFalse(self.pool.apply_info({"fullHeight": 614400, "headersHeight": 614400,
                                               "bestFullHeaderId": "b" * 64, "bestHeaderId": "b" * 64}))
        self.assertIsNone(self.pool.solution_ack_at)
        self.assertIsNone(self.pool.solution_ack_height)
        self.assertEqual(self.pool.solved_height, 614400)
        self.assertFalse(self.pool.expire_solution_ack(dict(self.pool.work, h=614401), True,
                                                      now=time.monotonic() + 1000))

    async def test_static_difficulty_does_not_accumulate_unused_retarget_samples(self):
        self.pool.resources.limits.update({"requestBurst": 400, "shareBurst": 400})
        pair, prefix, job = await self.worker(password="d=0.001")
        with patch("server.hit", return_value=2):
            for index in range(150):
                nonce = prefix + f"{index:012x}"
                result = await self.request(pair, "mining.submit", [ADDRESS1 + ".control", job[0], "0", "0", nonce])
                self.assertTrue(result["result"])
        client = next(iter(self.pool.clients))
        self.assertIsNotNone(client.static_difficulty)
        self.assertEqual(client.accepted, 150)
        self.assertEqual(client.intervals, [])
        self.assertIsNotNone(client.last_share)
        self.assertEqual(self.pool.ledger.stats()["acceptedShares"], 150)

    async def test_global_public_stats_stay_bounded_with_many_large_payout_batches(self):
        exact = {ADDRESS1 + str(index): str((1 << 55) + index) for index in range(50)}
        raw = json.dumps(exact)
        payouts = [{"id": str(index), "amounts": raw, "gross": raw, "fees": raw,
                    "amountsExact": dict(exact), "grossExact": dict(exact), "feesExact": dict(exact)}
                   for index in range(100)]
        self.pool.ledger.stats = Mock(return_value={"acceptedShares": 42, "payouts": payouts})
        pair = await self.connect(port=self.hport)
        pair[1].write(b"GET /api/stats HTTP/1.1\r\nHost: pool.example\r\n\r\n")
        await pair[1].drain()
        response = await pair[0].read()
        self.assertTrue(response.startswith(b"HTTP/1.1 200"))
        body = response.split(b"\r\n\r\n", 1)[1]
        self.assertLess(len(body), 262144)
        result = json.loads(body)
        self.assertEqual(result["acceptedShares"], 42)
        self.assertEqual(len(result["payouts"]), 10)
        self.assertEqual([row["id"] for row in result["payouts"]], [str(index) for index in range(90, 100)])
        self.assertEqual(result["payouts"][0]["amountsExact"], exact)
        self.assertNotIn("amounts", result["payouts"][0])
        self.assertEqual(len(payouts), 100)
        self.assertIn("amounts", payouts[0])

    async def test_http_header_handlers_are_bounded_and_forged_identity_is_rejected(self):
        self.pool.resources.limits["httpConnections"] = 4
        slow = [await self.connect(port=self.hport) for _ in range(4)]
        await asyncio.sleep(0.02)
        extra = await self.connect(port=self.hport)
        self.assertTrue((await extra[0].read()).startswith(b"HTTP/1.1 503"))
        self.assertEqual(self.pool.resources.header_connections, 4)
        self.assertEqual((await self.worker())[2][1], 614400)
        for _, writer in slow:
            writer.close()
        await asyncio.sleep(0.02)
        forged = await self.connect(port=self.hport)
        forged[1].write(b"GET /api/stats HTTP/1.1\r\nHost: pool.example\r\nX-Zyrex-Client-IP: 203.0.113.8\r\n\r\n")
        await forged[1].drain()
        self.assertEqual(await forged[0].read(), b"")
        self.assertNotIn("203.0.113.8", self.pool.resources.http_rates)


if __name__ == "__main__":
    unittest.main()
