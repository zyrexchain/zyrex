"""Controlled TCP GPU batches, slow readers and delayed native responses."""
import asyncio
import ipaddress
import json
import signal
import threading
import time
import unittest
from unittest.mock import AsyncMock, Mock, patch

from ledger import Ledger
from node import Node
from pow import MAX_TARGET
from server import Pool
from test_pool import ADDRESS1, MESSAGE, PK


class JobLifecycleTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.pool = object.__new__(Pool)
        self.pool.config = {"initialShareHashes": 1, "addressPrefix": 80, "genesisId": "f" * 64}
        self.pool.clients, self.pool.extra_nonce = set(), 0
        self.pool.allowed = [ipaddress.ip_network("127.0.0.0/8")]
        self.pool.work = {"msg": MESSAGE, "h": 614400, "b": 1, "pk": PK}
        self.pool.info = {"fullHeight": 614399, "headersHeight": 614399,
                          "bestFullHeaderId": "a" * 64, "bestHeaderId": "a" * 64}
        self.pool.checked_at, self.pool.error = time.monotonic(), None
        self.pool.submit_lock, self.pool.refresh = asyncio.Lock(), asyncio.Event()
        self.pool.solved_height = 0
        self.pool.last_reconcile, self.pool.reconcile_task = time.monotonic(), None
        self.pool.ledger = Ledger(":memory:")
        self.pool.node = Mock(pk=PK)
        self.pool.ensure_resources()
        self.server = await asyncio.start_server(self.pool.accept, "127.0.0.1", 0, limit=8192)
        self.connections, self.sequence = [], 0
        self.releases = []

    async def asyncTearDown(self):
        for release in self.releases:
            release.set()
        for _, writer in self.connections:
            writer.close()
        await asyncio.gather(*(writer.wait_closed() for _, writer in self.connections), return_exceptions=True)
        self.server.close()
        await self.server.wait_closed()
        await self.wait_for(lambda: not self.pool.clients and not any(lane.inflight for lane in self.pool.lanes.values()))
        for lane in self.pool.lanes.values():
            lane.close()
        self.pool.ledger.db.close()

    async def wait_for(self, condition):
        async with asyncio.timeout(3):
            while not condition():
                await asyncio.sleep(0.002)

    async def read(self, pair):
        return json.loads(await asyncio.wait_for(pair[0].readline(), 2))

    async def request(self, pair, method, params):
        self.sequence += 1
        ident = self.sequence
        pair[1].write((json.dumps({"id": ident, "method": method, "params": params}) + "\n").encode())
        await pair[1].drain()
        while True:
            result = await self.read(pair)
            if result.get("id") == ident:
                return result

    async def notification(self, pair):
        while True:
            result = await self.read(pair)
            if result.get("method") == "mining.notify":
                return result["params"]

    async def worker(self, suffix="gpu"):
        pair = await asyncio.open_connection("127.0.0.1", self.server.sockets[0].getsockname()[1])
        self.connections.append(pair)
        prefix = (await self.request(pair, "mining.subscribe", ["controlled-gpu"]))["result"][1]
        self.assertTrue((await self.request(pair, "mining.authorize", [ADDRESS1 + "." + suffix, "x"]))["result"])
        job = await self.notification(pair)
        client = next(c for c in self.pool.clients if c.prefix == prefix)
        return pair, prefix, job, client

    def share(self, prefix, job, suffix="gpu", nonce=1):
        return [ADDRESS1 + "." + suffix, job[0], "0", "0", prefix + f"{nonce:012x}"]

    def thread_barrier(self):
        started, release = threading.Event(), threading.Event()
        self.releases.append(release)
        return started, release

    def details(self, work=None, parent=None, active=None, solved=False):
        work = dict(work or self.pool.work)
        return dict(work, workVersion=1, parentId=parent or "a" * 64,
                    activeMessages=active or [work["msg"]], nodeSolved=solved)

    async def test_same_parent_native_cache_keeps_delayed_gpu_solution_and_cleans_new_parent(self):
        self.pool.work["b"] = MAX_TARGET
        self.pool.work = self.details()
        pair, prefix, old, _ = await self.worker()
        replacement = self.details(dict(self.pool.work, msg="b" * 64), active=["b" * 64, MESSAGE])
        changed, clean = self.pool.install_work(replacement)
        self.assertTrue(changed)
        self.assertFalse(clean)
        await self.pool.broadcast(clean)
        new = await self.notification(pair)
        self.assertFalse(new[-1])
        with patch("server.hit", return_value=1):
            self.assertTrue((await self.request(pair, "mining.submit", self.share(prefix, old)))["result"])
            await self.wait_for(lambda: self.pool.solved_height == old[1])
        self.assertEqual(self.pool.ledger.stats()["acceptedShares"], 1)
        self.pool.node.rpc.assert_called_once_with(
            "/mining/solution", {"n": prefix + "000000000001", "pk": PK, "msg": MESSAGE})
        changed, clean = self.pool.install_work(self.details(dict(replacement, h=614401, msg="c" * 64), parent="d" * 64))
        self.assertTrue(changed and clean)
        await self.pool.broadcast(clean)
        self.assertTrue((await self.notification(pair))[-1])
        for job in (old, new):
            self.assertEqual((await self.request(pair, "mining.submit", self.share(prefix, job, nonce=2)))["error"][0], 21)
        self.assertEqual(self.pool.ledger.stats()["acceptedShares"], 1)

    async def test_native_cache_eviction_and_same_height_branch_never_credit_obsolete_template(self):
        self.pool.work = self.details()
        pair, prefix, old, _ = await self.worker()
        changed, clean = self.pool.install_work(self.details(dict(self.pool.work, msg="b" * 64), active=["b" * 64, "e" * 64]))
        self.assertTrue(changed)
        self.assertFalse(clean)
        await self.pool.broadcast(clean)
        new = await self.notification(pair)
        result = await self.request(pair, "mining.submit", self.share(prefix, old))
        self.assertEqual(result["error"][0], 21)
        self.assertEqual(self.pool.work_results.stale["template_cache_expired"], 1)
        changed, clean = self.pool.install_work(self.details(dict(self.pool.work, msg="c" * 64), parent="d" * 64))
        self.assertTrue(changed and clean)
        await self.pool.broadcast(clean)
        self.assertTrue((await self.notification(pair))[-1])
        self.assertEqual((await self.request(pair, "mining.submit", self.share(prefix, new)))["error"][0], 21)
        self.assertEqual(self.pool.ledger.stats()["acceptedShares"], 0)

    async def test_previous_advertised_share_target_survives_retarget_without_changing_weight(self):
        pair, prefix, old, client = await self.worker()
        client.hashes = 100
        await client.job(False)
        new = await self.notification(pair)
        self.assertFalse(new[-1])
        with patch("server.hit", return_value=int(old[6]) // 2):
            self.assertTrue((await self.request(pair, "mining.submit", self.share(prefix, old)))["result"])
            self.assertEqual((await self.request(pair, "mining.submit", self.share(prefix, new, nonce=2)))["error"][0], 23)
        row = dict(self.pool.ledger.db.execute("SELECT * FROM shares").fetchone())
        self.assertEqual(int(row["weight"]), (1 << 256) // int(old[6]))
        self.assertEqual(self.pool.ledger.stats()["acceptedShares"], 1)

    async def test_legacy_native_response_keeps_conservative_clean_template_switch(self):
        pair, prefix, old, _ = await self.worker()
        changed, clean = self.pool.install_work(dict(self.pool.work, msg="b" * 64))
        self.assertTrue(changed and clean)
        await self.pool.broadcast(clean)
        self.assertTrue((await self.notification(pair))[-1])
        self.assertEqual((await self.request(pair, "mining.submit", self.share(prefix, old)))["error"][0], 21)
        self.assertEqual(self.pool.ledger.stats()["acceptedShares"], 0)

    async def test_malformed_native_cache_metadata_fails_closed_before_job_installation(self):
        original = dict(self.pool.work)
        good = self.details(dict(self.pool.work, msg="b" * 64))
        for changes in ({"workVersion": True}, {"workVersion": 2}, {"parentId": "broken"},
                        {"activeMessages": []}, {"activeMessages": [MESSAGE]},
                        {"activeMessages": ["b" * 64, "b" * 64]}, {"activeMessages": [MESSAGE, "b" * 64]},
                        {"nodeSolved": "false"}, {"h": True}, {"h": "614400"}, {"b": 2.5}, {"b": True},
                        {"b": "1e9"}, {"pk": "00" * 33}, {"msg": "not-a-header-hash"}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.pool.install_work(dict(good, **changes))
            self.assertEqual(self.pool.work, original)

    async def test_disconnect_after_durable_admission_cannot_cancel_native_solution(self):
        self.pool.work["b"] = MAX_TARGET
        pair, prefix, job, client = await self.worker()
        with patch.object(client, "response", AsyncMock(side_effect=ConnectionResetError("Controlled disconnect"))), \
                patch("server.hit", return_value=1):
            self.sequence += 1
            pair[1].write((json.dumps({"id": self.sequence, "method": "mining.submit",
                                      "params": self.share(prefix, job)}) + "\n").encode())
            await pair[1].drain()
            self.assertEqual(await asyncio.wait_for(pair[0].readline(), 1), b"")
            await self.wait_for(lambda: self.pool.solution_task is not None and self.pool.solution_task.done())
        self.pool.node.rpc.assert_called_once_with(
            "/mining/solution", {"n": prefix + "000000000001", "pk": PK, "msg": MESSAGE})
        self.assertEqual(self.pool.ledger.stats()["acceptedShares"], 1)
        self.assertEqual(len(self.pool.ledger.blocks()), 1)

    async def test_slow_share_ack_cannot_delay_solution_or_hold_global_admission_lock(self):
        self.pool.work["b"] = MAX_TARGET
        pair, prefix, job, client = await self.worker()
        entered, release = asyncio.Event(), asyncio.Event()

        async def blocked_ack(*args):
            entered.set()
            await release.wait()

        with patch.object(client, "response", blocked_ack), patch("server.hit", return_value=1):
            self.sequence += 1
            pair[1].write((json.dumps({"id": self.sequence, "method": "mining.submit",
                                      "params": self.share(prefix, job)}) + "\n").encode())
            await pair[1].drain()
            await asyncio.wait_for(entered.wait(), 0.5)
            await self.wait_for(lambda: self.pool.solved_height == job[1])
            self.assertFalse(self.pool.submit_lock.locked())
            self.assertEqual(self.pool.node.rpc.call_count, 1)
            release.set()

    async def test_sigterm_drains_a_durable_write_before_solution_and_executor_shutdown(self):
        self.pool.work["b"] = MAX_TARGET
        self.pool.config.update({"stratumPort": 0, "httpPort": 0,
                                 "publicStratumUrl": "stratum+tcp://pool.example:3333"})
        pair, prefix, job, _ = await self.worker()
        original = self.pool.record_share
        committed, release = self.thread_barrier()

        def delayed_commit(*args):
            result = original(*args)
            committed.set()
            release.wait(3)
            return result

        handlers = {}
        loop = asyncio.get_running_loop()

        async def no_updates():
            await asyncio.sleep(100)

        with patch.object(loop, "add_signal_handler", lambda signum, callback: handlers.update({signum: callback})), \
                patch.object(self.pool, "record_share", delayed_commit), \
                patch.object(self.pool, "update", no_updates), \
                patch("server.hit", return_value=1):
            running = asyncio.create_task(self.pool.run())
            await self.wait_for(lambda: signal.SIGTERM in handlers)
            self.sequence += 1
            pair[1].write((json.dumps({"id": self.sequence, "method": "mining.submit",
                                      "params": self.share(prefix, job)}) + "\n").encode())
            await pair[1].drain()
            await self.wait_for(committed.is_set)
            handlers[signal.SIGTERM]()
            await asyncio.sleep(0.03)
            self.assertFalse(running.done())
            self.assertFalse(self.pool.lanes["solutions"].closed)
            release.set()
            await asyncio.wait_for(running, 3)
        self.assertTrue(self.pool.stopping)
        self.assertEqual(self.pool.ledger.stats()["acceptedShares"], 1)
        self.assertEqual(len(self.pool.ledger.blocks()), 1)
        self.assertEqual(self.pool.node.rpc.call_count, 1)
        self.assertTrue(self.pool.solution_task.done())
        self.assertEqual(self.pool.client_tasks, set())
        self.assertTrue(all(lane.closed for lane in self.pool.lanes.values()))

    async def test_identical_refresh_keeps_job_id_and_delayed_gpu_batch_valid(self):
        pair, prefix, job, client = await self.worker()
        client.last_send -= 100
        for _ in range(12):
            await client.job(False)
        self.assertEqual(list(client.jobs), [job[0]])
        with self.assertRaises(asyncio.TimeoutError):
            await asyncio.wait_for(pair[0].readline(), 0.05)
        self.assertTrue((await self.request(pair, "mining.submit", self.share(prefix, job)))["result"])
        self.assertEqual(self.pool.work_results.notifications, 1)
        self.assertEqual(self.pool.ledger.stats()["acceptedShares"], 1)

    async def test_update_loop_polls_without_periodic_job_id_churn(self):
        pair, _, job, client = await self.worker()
        self.pool.node.work.return_value = dict(self.pool.work)
        client.last_send -= 100
        updater = asyncio.create_task(self.pool.update())
        try:
            with self.assertRaises(asyncio.TimeoutError):
                await asyncio.wait_for(pair[0].readline(), 0.6)
            self.assertGreaterEqual(self.pool.node.work.call_count, 3)
            self.assertEqual(list(client.jobs), [job[0]])
        finally:
            updater.cancel()
            await asyncio.gather(updater, return_exceptions=True)

    async def test_uncertain_solution_never_duplicates_rpc_and_requires_fresh_unsolved_recovery(self):
        self.pool.work["b"] = MAX_TARGET
        self.pool.work = self.details()
        pair, prefix, job, client = await self.worker()
        self.pool.node.rpc.side_effect = TimeoutError("Controlled lost RPC response")
        with patch("server.hit", return_value=1):
            self.assertTrue((await self.request(pair, "mining.submit", self.share(prefix, job)))["result"])
            await self.wait_for(lambda: self.pool.pending_solution is not None and not self.pool.pending_solution["rpcPending"])
            pending = self.pool.pending_solution
            self.assertIsNone(self.pool.solution_ack_at)
            self.assertEqual((await self.request(pair, "mining.submit", self.share(prefix, job, nonce=2)))["error"][0], 21)
            self.assertFalse(self.pool.expire_pending_solution(self.pool.work, True, now=pending["at"] + 19.99))
            self.assertFalse(self.pool.expire_pending_solution(self.pool.work, False, now=pending["at"] + 21))
            self.assertFalse(self.pool.expire_pending_solution(dict(self.pool.work, nodeSolved=True), True,
                                                               now=pending["at"] + 21))
            self.assertFalse(self.pool.expire_pending_solution(dict(self.pool.work, parentId="b" * 64), True,
                                                               now=pending["at"] + 21))
            fresh = self.details(dict(self.pool.work, msg="b" * 64))
            self.assertTrue(self.pool.expire_pending_solution(fresh, True, now=pending["at"] + 21))
            self.assertEqual(self.pool.node.rpc.call_count, 1)
            self.assertEqual(self.pool.ledger.stats()["acceptedShares"], 1)
            self.assertEqual(len(self.pool.ledger.blocks()), 1)
            self.assertEqual(list(client.jobs), [])
            self.pool.node.rpc.side_effect = None
            changed, clean = self.pool.install_work(fresh)
            self.assertTrue(changed and clean)
            await self.pool.broadcast(clean)
            new = await self.notification(pair)
            self.assertTrue((await self.request(pair, "mining.submit", self.share(prefix, new, nonce=2)))["result"])
            await self.wait_for(lambda: self.pool.solved_height == new[1])
        self.assertEqual(self.pool.node.rpc.call_count, 2)
        self.assertEqual(len(self.pool.ledger.blocks()), 2)
        self.assertEqual(self.pool.work_results.native_uncertain, 1)

    async def test_node_work_query_is_opt_in_and_never_uses_wallet_or_solution_rpc(self):
        node = object.__new__(Node)
        node.rpc = Mock(return_value=self.details())
        self.assertEqual(node.work()["workVersion"], 1)
        node.rpc.assert_called_once_with("/mining/candidate?details=true")

    async def test_inflight_gpu_batch_is_rejected_after_new_height_without_credit(self):
        pair, prefix, job, client = await self.worker()
        started, release = self.thread_barrier()

        def verify(*args):
            started.set()
            release.wait(3)
            return 1

        with patch("server.hit", verify):
            submitted = asyncio.create_task(self.request(pair, "mining.submit", self.share(prefix, job)))
            await self.wait_for(started.is_set)
            self.pool.work = dict(self.pool.work, h=614401, msg="b" * 64)
            await self.pool.broadcast(True)
            await self.wait_for(lambda: job[0] not in client.jobs)
            release.set()
            result = await submitted
        self.assertEqual(result["error"][0], 21)
        self.assertEqual(self.pool.ledger.stats()["acceptedShares"], 0)
        self.assertEqual(sum(self.pool.work_results.stale.values()), 1)
        self.assertEqual(self.pool.node.rpc.call_count, 0)

    async def test_queued_accounting_wait_cannot_admit_a_job_invalidated_before_write(self):
        pair, prefix, job, client = await self.worker()
        started, release = self.thread_barrier()

        def wait_writer():
            started.set()
            release.wait(3)

        blocker = asyncio.create_task(self.pool.lanes["writes"].run(wait_writer))
        await self.wait_for(started.is_set)
        with patch("server.hit", return_value=1):
            submitted = asyncio.create_task(self.request(pair, "mining.submit", self.share(prefix, job)))
            await self.wait_for(lambda: self.pool.lanes["writes"].inflight == 2)
            self.pool.work = dict(self.pool.work, h=614401, msg="c" * 64)
            await self.pool.broadcast(True)
            await self.wait_for(lambda: job[0] not in client.jobs)
            release.set()
            await blocker
            result = await submitted
        self.assertEqual(result["error"][0], 21)
        self.assertEqual(self.pool.ledger.stats()["acceptedShares"], 0)
        self.assertEqual(self.pool.ledger.blocks(), [])

    async def test_durable_share_ack_does_not_wait_for_native_rpc_and_second_solution_never_records(self):
        self.pool.work["b"] = MAX_TARGET
        first, prefix1, job1, _ = await self.worker("first")
        second, prefix2, job2, _ = await self.worker("second")
        started, release = self.thread_barrier()

        def submit(*args):
            started.set()
            release.wait(3)
            return None

        self.pool.node.rpc.side_effect = submit
        with patch("server.hit", return_value=1):
            accepted = await asyncio.wait_for(
                self.request(first, "mining.submit", self.share(prefix1, job1, "first")), 0.5)
            await self.wait_for(started.is_set)
            self.assertTrue(accepted["result"])
            self.assertEqual(len(self.pool.ledger.blocks()), 1)
            # The same GPU's request reader remains responsive while its node submission is blocked.
            self.assertTrue((await asyncio.wait_for(self.request(first, "mining.extranonce.subscribe", []), 0.5))["result"])
            other = await asyncio.wait_for(
                self.request(second, "mining.submit", self.share(prefix2, job2, "second")), 0.5)
            self.assertEqual(other["error"][0], 21)
            release.set()
            await self.wait_for(lambda: self.pool.solved_height == job1[1])
        self.assertEqual(self.pool.node.rpc.call_count, 1)
        self.assertEqual(len(self.pool.ledger.blocks()), 1)
        self.assertEqual(self.pool.ledger.stats()["acceptedShares"], 1)
        self.assertEqual(self.pool.work_results.stale["solution_pending"], 1)

    async def test_slow_notification_reader_does_not_delay_next_job_for_other_gpu(self):
        slow, _, _, client = await self.worker("slow")
        fast, _, _, _ = await self.worker("fast")
        entered, release = asyncio.Event(), asyncio.Event()

        async def stalled_drain():
            entered.set()
            await release.wait()

        with patch.object(client.writer, "drain", stalled_drain):
            self.pool.work = dict(self.pool.work, h=614401, msg="b" * 64)
            await self.pool.broadcast(True)
            await asyncio.wait_for(entered.wait(), 0.5)
            first = await asyncio.wait_for(self.notification(fast), 0.5)
            self.assertEqual(first[1], 614401)
            for height in (614402, 614403, 614404):
                self.pool.work = dict(self.pool.work, h=height, msg=f"{height:064x}")
                await self.pool.broadcast(True)
                await asyncio.sleep(0)
            self.assertFalse(client.notification_task.done())
            release.set()
            await asyncio.wait_for(client.notification_task, 1)
        final = None
        for _ in range(2):
            final = await self.notification(slow)
        self.assertEqual(final[1], 614404)
        self.assertGreater(self.pool.work_results.coalesced, 0)


if __name__ == "__main__":
    unittest.main()
