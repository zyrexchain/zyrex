#!/usr/bin/env python3
"""Exercise the real LAN Stratum pool with CPU proof search, then verify payouts.

This does not certify GPU miner compatibility. It uses the Miningcore wire format.
"""
import argparse
import asyncio
import concurrent.futures
import json
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "pool"))
from pow import hit


def search(msg, height, prefix, start, count, target):
    message = bytes.fromhex(msg)
    for suffix in range(start, start + count):
        nonce = bytes.fromhex(prefix) + suffix.to_bytes(6, "big")
        score = hit(message, nonce, height)
        if score < target:
            return nonce.hex(), score
    return None


def api(url, key=None):
    request = urllib.request.Request(url, headers={"api_key": key} if key else {})
    with urllib.request.urlopen(request, timeout=15) as response:
        return json.load(response)


class Miner:
    def __init__(self, host, address, name, executor):
        self.host, self.address, self.name, self.executor = host, address, name, executor
        self.username = address + "." + name
        self.job = None
        self.prefix = None
        self.pending = {}
        self.seq = 0
        self.accepted = 0
        self.errors = []
        self.duplicate_verified = False
        self.next_nonce = 0
        self.writer = None

    async def send(self, method, params):
        self.seq += 1
        future = asyncio.get_running_loop().create_future()
        self.pending[self.seq] = future
        self.writer.write((json.dumps({"id": self.seq, "method": method, "params": params}) + "\n").encode())
        await self.writer.drain()
        return await asyncio.wait_for(future, 20)

    async def receive(self, reader):
        while line := await reader.readline():
            message = json.loads(line)
            if message.get("method") == "mining.notify":
                p = message["params"]
                self.job = {"id": p[0], "h": p[1], "msg": p[2], "target": int(p[6])}
            elif message.get("id") in self.pending:
                self.pending.pop(message["id"]).set_result(message)

    async def run(self, stop):
        reader, self.writer = await asyncio.open_connection(self.host, 3333)
        receive = asyncio.create_task(self.receive(reader))
        try:
            subscribed = await self.send("mining.subscribe", ["Zyrex-CPU-Stratum-smoke/1.0"])
            assert subscribed["error"] is None and subscribed["result"][2] == 6
            self.prefix = subscribed["result"][1]
            authorized = await self.send("mining.authorize", [self.username, "x"])
            assert authorized["result"] is True
            print("Authorized", self.username, flush=True)
            while not stop.is_set():
                if not self.job:
                    await asyncio.sleep(0.1)
                    continue
                job = dict(self.job)
                start = self.next_nonce
                self.next_nonce += 4096
                result = await asyncio.get_running_loop().run_in_executor(
                    self.executor, search, job["msg"], job["h"], self.prefix, start, 4096, job["target"])
                if not result or job["h"] != self.job["h"]:
                    continue
                nonce, score = result
                params = [self.username, job["id"], nonce[4:], "undefined", nonce]
                submitted = await self.send("mining.submit", params)
                if submitted["result"]:
                    self.accepted += 1
                    print(f"{self.name}: share accepted at height {job['h']} ({self.accepted})", flush=True)
                    if not self.duplicate_verified:
                        repeated = await self.send("mining.submit", params)
                        assert repeated["error"][0] in (21, 22)
                        self.duplicate_verified = True
                elif submitted.get("error", [None])[0] != 21:
                    self.errors.append(submitted["error"])
                    raise RuntimeError(submitted["error"])
        finally:
            receive.cancel()
            await asyncio.gather(receive, return_exceptions=True)
            self.writer.close()
            await self.writer.wait_closed()


async def main(args):
    addresses = [api(f"http://127.0.0.1:{port}/wallet/addresses", "hello")[0] for port in (19556, 19557)]
    # Public addresses are safe to read; wallet APIs require auth even for address lists.
    start_stats = api(f"http://{args.host}:8088/api/stats")
    stop = asyncio.Event()
    with concurrent.futures.ProcessPoolExecutor(max_workers=2) as executor:
        miners = [Miner(args.host, address, "cpu-smoke-" + str(i + 1), executor) for i, address in enumerate(addresses)]
        tasks = [asyncio.create_task(m.run(stop)) for m in miners]
        deadline = time.monotonic() + args.timeout
        success = False
        try:
            while time.monotonic() < deadline:
                for task in tasks:
                    if task.done():
                        task.result()
                stats = await asyncio.to_thread(api, f"http://{args.host}:8088/api/stats")
                confirmed = [p for p in stats["payouts"] if p["status"] == "confirmed"]
                rewarded = {address for p in confirmed for address in json.loads(p["amounts"])}
                if len(confirmed) > len([p for p in start_stats["payouts"] if p["status"] == "confirmed"]) and \
                        all(m.accepted > 0 and m.duplicate_verified for m in miners) and set(addresses) <= rewarded:
                    success = True
                    break
                await asyncio.sleep(2)
        finally:
            stop.set()
            for task in tasks:
                task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
        if not success:
            raise RuntimeError("Timed out waiting for a confirmed pool payment to both miners")
    verify(args, [{"address": m.address, "acceptedShares": m.accepted,
                   "duplicateRejected": m.duplicate_verified} for m in miners])


def verify(args, miners):
    stats = api(f"http://{args.host}:8088/api/stats")
    restarted = False
    if args.restart:
        subprocess.run(["docker", "compose", "-f", "docker-compose.yml", "-f", "docker-compose.pool.yml",
                        "restart", "pool", "poolnode"], check=True)
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            try:
                after = api(f"http://{args.host}:8088/api/stats")
                if after["ready"] and after["nodeHeight"] >= stats["nodeHeight"]:
                    assert after["acceptedShares"] == stats["acceptedShares"]
                    assert {(b["msg"], b["nonce"]) for b in after["blocks"]} == \
                        {(b["msg"], b["nonce"]) for b in stats["blocks"]}
                    assert {p["id"] for p in stats["payouts"]} <= {p["id"] for p in after["payouts"]}
                    assert after["poolAddress"] == stats["poolAddress"]
                    stats, restarted = after, True
                    break
            except OSError:
                pass
            time.sleep(1)
        assert restarted, "Pool failed to recover after restart"
    addresses = [m["address"] for m in miners]
    received = []
    genesis = json.loads(Path("src/main/resources/genesis/devnet.json").read_text())
    founder_scripts = [o["ergoTree"] for o in genesis["blockTransactions"]["transactions"][0]["outputs"][2:]]
    checked_blocks = []
    for block in stats["blocks"]:
        if block["status"] != "confirmed":
            continue
        full = api("http://127.0.0.1:19555/blocks/" + block["hash"])
        outputs = full["blockTransactions"]["transactions"][0]["outputs"]
        subsidy = 100_000_000_000 >> ((block["height"] - 1) // 432000)
        assert [o["value"] for o in outputs[1:]] == [subsidy - 2 * (subsidy // 20), subsidy // 20, subsidy // 20]
        assert [o["ergoTree"] for o in outputs[2:]] == founder_scripts
        checked_blocks.append({"height": block["height"], "blockId": block["hash"],
                               "payoutsNanoZYRX": [o["value"] for o in outputs[1:]]})
    for port, address in zip((19556, 19557), addresses):
        payments = []
        for payout in stats["payouts"]:
            amounts = json.loads(payout["amounts"])
            if payout["status"] != "confirmed" or address not in amounts:
                continue
            tx = api(f"http://127.0.0.1:{port}/wallet/transactionById?id={payout['id']}", "hello")
            assert tx["numConfirmations"] >= 5
            assert any(o["value"] == amounts[address] and o["address"] == address for o in tx["outputs"])
            payments.append(payout["id"])
        assert payments
        received.append({"address": address, "confirmedPayments": payments})
    report = {"stratumUrl": stats["stratumUrl"], "wireProtocol": "Ergo/Miningcore Stratum v1",
              "gpuVerified": False, "height": stats["nodeHeight"],
              "miners": miners, "restartVerified": restarted,
              "blocks": stats["blocks"], "payouts": stats["payouts"], "receivingWallets": received,
              "consensusPayouts": checked_blocks}
    Path(args.report).write_text(json.dumps(report, indent=2) + "\n")
    print("Stratum proof search, canonical pool blocks and payments to two miners verified:", args.report, flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--timeout", type=int, default=900)
    parser.add_argument("--report", default=".local/pool-smoke.json")
    parser.add_argument("--restart", action="store_true")
    parser.add_argument("--verify-only", action="store_true", help="Recheck an existing CPU report against the live chain")
    args = parser.parse_args()
    if args.verify_only:
        verify(args, json.loads(Path(args.report).read_text())["miners"])
    else:
        asyncio.run(main(args))
