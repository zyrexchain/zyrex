"""Bounded local TCP bursts with synthetic hits; no GPU or throughput claims."""
import asyncio
import json
import threading
import unittest
from unittest.mock import patch

import test_job_lifecycle as lifecycle
from test_pool import ADDRESS1


class TcpWorker:
    """One reader multiplexes notifications and submission responses on a real socket."""
    def __init__(self, fixture, worker):
        self.fixture, self.worker = fixture, worker
        self.pending, self.jobs = {}, asyncio.Queue()
        self.reader = asyncio.create_task(self.read())

    async def read(self):
        try:
            while line := await self.worker[0][0].readline():
                result = json.loads(line)
                if result.get("method") == "mining.notify":
                    self.jobs.put_nowait(result["params"])
                elif result.get("id") in self.pending:
                    self.pending.pop(result["id"]).set_result(result)
        finally:
            for future in self.pending.values():
                if not future.done():
                    future.set_exception(ConnectionError("Controlled worker connection closed"))
            self.jobs.put_nowait(None)

    async def submit(self, params):
        self.fixture.sequence += 1
        ident = self.fixture.sequence
        future = asyncio.get_running_loop().create_future()
        self.pending[ident] = future
        self.worker[0][1].write((json.dumps({"id": ident, "method": "mining.submit", "params": params}) + "\n").encode())
        await self.worker[0][1].drain()
        return await asyncio.wait_for(future, 3)


class BurstLoadTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        # Compose the existing harness without inheriting or rediscovering its test methods.
        self.fixture = lifecycle.JobLifecycleTests(methodName="runTest")
        await self.fixture.asyncSetUp()
        self.pool = self.fixture.pool
        self.pool.config.update({"confirmations": 8, "minimumPayoutNano": 1_000_000_000,
                                 "payoutFeeNano": 1_000_000, "network": "devnet",
                                 "publicStratumUrl": "stratum+tcp://pool.example:3333"})
        self.pool.payout_error = None
        self.pool.node.wallet = {"address": ADDRESS1}
        # Keep failure cleanup synthetic even if a nested barrier patch exits before queued submissions drain.
        self.synthetic = patch("server.hit", return_value=2)
        self.synthetic.start()
        self.addCleanup(self.synthetic.stop)
        self.pool.resources.limits.update({"activePerIp": 16, "connectBurst": 32, "connectRate": 100})
        self.http = await asyncio.start_server(self.pool.http, "127.0.0.1", 0, limit=8192)
        self.workers = [await self.fixture.worker("burst" + str(index)) for index in range(16)]
        self.sockets = [TcpWorker(self.fixture, worker) for worker in self.workers]
        self.all_sockets = list(self.sockets)
        self.tasks = []
        self.assertEqual(len(self.pool.clients), 16)
        self.assertEqual(self.pool.resources.active.get("127.0.0.1"), 16)
        self.assertEqual(self.pool.resources.pending, {})

    async def asyncTearDown(self):
        for release in self.fixture.releases:
            release.set()
        await asyncio.gather(*self.tasks, return_exceptions=True)
        self.http.close()
        await self.http.wait_closed()
        await self.fixture.wait_for(lambda: not self.pool.resources.header_connections)
        await self.fixture.asyncTearDown()
        await asyncio.gather(*(socket.reader for socket in self.all_sockets), return_exceptions=True)

    def submit(self, index, nonce=1, job=None):
        pair, prefix, original, _ = self.workers[index]
        params = self.fixture.share(prefix, original if job is None else job, "burst" + str(index), nonce)
        task = asyncio.create_task(self.sockets[index].submit(params))
        self.tasks.append(task)
        return task

    def blocked_hit(self):
        started, release = self.fixture.thread_barrier()
        lock, running = threading.Lock(), []

        def hit(*_):
            with lock:
                running.append(1)
                if len(running) == self.pool.lanes["pow"].workers:
                    started.set()
            if not release.wait(5):
                raise TimeoutError("Controlled PoW barrier was not released")
            return 2  # Valid share, below the advertised share target but above the block target.

        return hit, started, release

    async def http_json(self, path):
        pair = await asyncio.open_connection("127.0.0.1", self.http.sockets[0].getsockname()[1])
        self.fixture.connections.append(pair)
        pair[1].write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n").encode())
        await pair[1].drain()
        async with asyncio.timeout(2):
            header = await pair[0].readuntil(b"\r\n\r\n")
            self.assertTrue(header.startswith(b"HTTP/1.1 200 OK\r\n"), header)
            length = next(int(line.partition(b":")[2]) for line in header.split(b"\r\n")
                          if line.lower().startswith(b"content-length:"))
            self.assertLessEqual(length, 262144)
            return json.loads(await pair[0].readexactly(length))

    async def stall_first_wave(self, started):
        lane = self.pool.lanes["pow"]
        self.assertEqual(lane.capacity, 8)
        first = [self.submit(index) for index in range(8)]
        await self.fixture.wait_for(lambda: started.is_set() and lane.inflight == lane.capacity)
        self.assertFalse(any(task.done() for task in first))
        rejected = await asyncio.gather(*(self.submit(index) for index in range(8, 16)))
        self.assertEqual([result["error"][0] for result in rejected], [26] * 8)
        self.assertEqual(lane.inflight, lane.capacity)
        self.assertLessEqual(lane.high_water, lane.capacity)
        self.assertEqual(lane.rejected, 8)
        self.assertEqual(self.pool.work_results.rejected["resource_limit"], 8)
        self.assertEqual(self.pool.ledger.stats()["acceptedShares"], 0)
        await self.fixture.wait_for(lambda: len(self.pool.clients) == 8)
        return first

    async def test_sixteen_workers_bound_capacity_and_keep_http_history_available(self):
        hit, started, release = self.blocked_hit()
        with patch("server.hit", hit):
            admitted = await self.stall_first_wave(started)
            stats, miner, history = await asyncio.gather(
                self.http_json("/api/stats"), self.http_json("/api/miner/" + ADDRESS1),
                self.http_json("/api/miner/" + ADDRESS1 + "/payouts?limit=20&offset=0"))
            self.assertFalse(release.is_set(), "HTTP completes while both controlled PoW workers remain blocked")
            self.assertEqual(stats["connectedWorkers"], 8)
            self.assertEqual(stats["acceptedShares"], 0)
            self.assertEqual(stats["resources"]["workResults"]["rejected"]["resource_limit"], 8)
            self.assertEqual(miner["mining"]["acceptedShares"], 0)
            self.assertEqual(history["items"], [])
            release.set()
            completed = await asyncio.gather(*admitted)
            self.assertEqual([result["result"] for result in completed], [True] * 8)
            self.assertEqual(self.pool.work_results.accepted + self.pool.work_results.rejected["resource_limit"], 16)
            # Capacity rejections ask miners to reconnect; new authenticated sessions resume valid work.
            for index in range(8, 16):
                self.workers[index] = await self.fixture.worker("burst" + str(index))
                self.sockets[index] = TcpWorker(self.fixture, self.workers[index])
                self.all_sockets.append(self.sockets[index])
            retried = await asyncio.gather(*(self.submit(index) for index in range(8, 16)))
            self.assertEqual([result["result"] for result in retried], [True] * 8)
        self.assertEqual(self.pool.work_results.accepted, 16)
        self.assertEqual(self.pool.ledger.stats()["acceptedShares"], 16)
        self.assertEqual(len(self.tasks), 24)
        self.assertLessEqual(self.pool.lanes["pow"].high_water, self.pool.lanes["pow"].capacity)
        self.assertEqual(self.pool.ledger.blocks(), [])
        self.pool.node.rpc.assert_not_called()

    async def test_branch_switch_revokes_queued_burst_without_stale_or_invalid_credit(self):
        hit, started, release = self.blocked_hit()
        with patch("server.hit", hit):
            admitted = await self.stall_first_wave(started)
            replacement = self.fixture.details(dict(self.pool.work, h=614401, msg="b" * 64), parent="c" * 64)
            changed, clean = self.pool.install_work(replacement)
            self.assertTrue(changed and clean)
            await self.pool.broadcast(clean)
            jobs = await asyncio.wait_for(asyncio.gather(*(socket.jobs.get() for socket in self.sockets[:8])), 2)
            self.assertTrue(all(job[-1] for job in jobs))
            release.set()
            completed = await asyncio.gather(*admitted)
            self.assertEqual([result["error"][0] for result in completed], [21] * 8)
        old = await asyncio.gather(*(self.submit(index, nonce=2) for index in range(8)))
        self.assertEqual([result["error"][0] for result in old], [21] * 8)
        with patch("server.hit", return_value=1 << 256) as invalid_hit:
            # Invalid difficulty on current jobs is distinguished from stale work and gets no durable share.
            for index in range(8):
                response = await self.submit(index, nonce=3, job=jobs[index])
                self.assertEqual(response["error"][0], 23)
            self.assertEqual(invalid_hit.call_count, 8)
        self.assertEqual(self.pool.work_results.accepted, 0)
        self.assertEqual(sum(self.pool.work_results.stale.values()), 16)
        self.assertEqual(self.pool.work_results.rejected["low_difficulty"], 8)
        self.assertEqual(len(self.tasks), 32)
        self.assertEqual(self.pool.ledger.stats()["acceptedShares"], 0)
        self.assertEqual(self.pool.ledger.db.execute("SELECT COUNT(*) FROM credits").fetchone()[0], 0)
        self.assertEqual(self.pool.ledger.blocks(), [])
        self.pool.node.rpc.assert_not_called()


if __name__ == "__main__":
    unittest.main()
