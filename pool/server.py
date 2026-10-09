#!/usr/bin/env python3
"""Zyrex pool: Autolykos Stratum v1, CPU share verification, durable PROP payouts."""
import argparse
import asyncio
import ipaddress
import json
import logging
import re
import secrets
import signal
import sqlite3
import time
from collections import OrderedDict
from decimal import Decimal, InvalidOperation
from pathlib import Path
from urllib.parse import parse_qsl, urlsplit

from ledger import Ledger
from node import Node
from pow import DIFF1, MAX_TARGET, hit, validate_miner_address

LOG = logging.getLogger("zyrex-pool")


class StratumError(Exception):
    def __init__(self, code, message):
        self.code = code
        super().__init__(message)


class Client:
    def __init__(self, pool, reader, writer, prefix):
        self.pool, self.reader, self.writer = pool, reader, writer
        self.prefix = prefix
        self.subscribed = False
        self.username = None
        self.address = None
        self.worker = None
        self.jobs = OrderedDict()
        self.write_lock = asyncio.Lock()
        self.hashes = pool.config["initialShareHashes"]
        self.last_share = None
        self.intervals = []
        self.last_retarget = time.monotonic()
        self.accepted = 0
        self.rejected = 0
        self.invalid_requests = 0
        self.last_send = 0
        self.static_difficulty = None

    async def send(self, message):
        async with self.write_lock:
            self.writer.write((json.dumps(message, separators=(",", ":")) + "\n").encode())
            await asyncio.wait_for(self.writer.drain(), 5)

    async def response(self, ident, result, error=None):
        await self.send({"id": ident, "result": result, "error": error})

    async def notify(self, method, params):
        await self.send({"id": None, "method": method, "params": params})

    async def job(self, clean):
        if not self.subscribed or not self.username or not self.pool.ready():
            return
        work = self.pool.work
        # Block solutions must always qualify as shares, including this young devnet.
        target = max(work["b"], MAX_TARGET // max(1, self.hashes))
        weight = (1 << 256) // target
        job_id = secrets.token_hex(8)
        self.jobs[job_id] = (dict(work), target, weight)
        while len(self.jobs) > 4:
            self.jobs.popitem(last=False)
        await self.notify("mining.set_difficulty", [1])
        await self.notify("mining.notify", [job_id, work["h"], work["msg"], "", "", 4, str(target), "", clean])
        self.last_send = time.monotonic()

    async def request(self, request):
        if not isinstance(request, dict) or "id" not in request:
            raise StratumError(20, "Invalid JSON-RPC request")
        ident = request["id"]
        method, params = request.get("method"), request.get("params", [])
        if not isinstance(params, list):
            raise StratumError(20, "params must be an array")
        if method == "mining.subscribe":
            if self.subscribed:
                raise StratumError(20, "Already subscribed")
            self.subscribed = True
            await self.response(ident, [[["mining.set_difficulty", self.prefix],
                                          ["mining.notify", self.prefix]], self.prefix, 6])
            await self.job(True)
        elif method == "mining.authorize":
            if len(params) < 1 or not isinstance(params[0], str) or len(params[0]) > 200:
                raise StratumError(24, "Use ZYREX_ADDRESS.worker")
            if self.username:
                raise StratumError(24, "Already authorized")
            address, _, worker = params[0].partition(".")
            try:
                validate_miner_address(address, self.pool.config["addressPrefix"])
            except ValueError as error:
                raise StratumError(24, str(error)) from error
            if not re.fullmatch(r"[a-zA-Z0-9_-]{0,64}", worker):
                raise StratumError(24, "Worker name must use letters, digits, _ or -")
            password = params[1] if len(params) > 1 else "x"
            if isinstance(password, str) and password.startswith("d="):
                try:
                    difficulty = Decimal(password[2:])
                    maximum = Decimal(1 << 50) * DIFF1 / Decimal(1 << 256)
                    if not difficulty.is_finite() or difficulty <= 0 or difficulty > maximum:
                        raise ValueError("Invalid difficulty")
                    hashes = int(difficulty * Decimal(1 << 256) / DIFF1)
                    if not 1 <= hashes <= 1 << 50:
                        raise ValueError("Difficulty out of range")
                except (InvalidOperation, ValueError) as error:
                    raise StratumError(24, "Invalid static difficulty") from error
                self.hashes = hashes
                self.static_difficulty = difficulty
            self.username, self.address, self.worker = params[0], address, worker
            await self.response(ident, True)
            LOG.info("Authorized %s", self.username)
            await self.job(True)
        elif method == "mining.submit":
            await self.submit(ident, params)
        elif method in ("mining.extranonce.subscribe", "mining.suggest_difficulty"):
            await self.response(ident, True)
        elif method == "mining.configure":
            await self.response(ident, {})
        elif method == "mining.get_transactions":
            await self.response(ident, [])
        else:
            raise StratumError(20, "Unsupported method")

    async def submit(self, ident, params):
        if not self.subscribed:
            raise StratumError(25, "Not subscribed")
        if not self.username or not params or params[0] != self.username:
            raise StratumError(24, "Unauthorized worker")
        if len(params) not in (3, 5) or not all(isinstance(p, str) for p in params):
            raise StratumError(20, "Expected worker, job, extranonce2, ntime, nonce")
        if not self.pool.ready() or params[1] not in self.jobs:
            raise StratumError(21, "Stale or unknown job")
        work, target, weight = self.jobs[params[1]]
        if work["h"] != self.pool.work["h"] or work["h"] <= self.pool.solved_height:
            raise StratumError(21, "Stale job height")
        nonce_hex = params[-1].lower()
        if not re.fullmatch(r"[0-9a-f]{16}", nonce_hex) or not nonce_hex.startswith(self.prefix):
            raise StratumError(20, "Nonce must contain the assigned extranonce1 and be 8 bytes")
        # Miningcore miners send the full nonce as the fifth parameter. The second
        # extranonce and ntime fields are placeholders, as required by the compatible mining wire format.
        score = await asyncio.to_thread(hit, bytes.fromhex(work["msg"]), bytes.fromhex(nonce_hex), work["h"])
        if score >= target:
            raise StratumError(23, "Low difficulty share")
        if not self.pool.ready() or work["h"] != self.pool.work["h"] or work["h"] <= self.pool.solved_height:
            raise StratumError(21, "Stale job height")
        try:
            share_id = self.pool.ledger.share(work["msg"], nonce_hex, self.address, self.worker, weight)
        except sqlite3.IntegrityError as error:
            raise StratumError(22, "Duplicate share") from error
        self.accepted += 1
        now = time.monotonic()
        if self.last_share is not None:
            self.intervals.append(now - self.last_share)
        self.last_share = now
        # A durable submission record also covers an RPC timeout after the node accepted.
        if score < work["b"]:
            self.pool.ledger.block(work, nonce_hex, share_id)
            try:
                async with self.pool.submit_lock:
                    await asyncio.to_thread(self.pool.node.rpc, "/mining/solution", {"n": nonce_hex, "pk": work["pk"]})
                    self.pool.solved_height = max(self.pool.solved_height, work["h"])
                LOG.info("Block submitted: height=%s worker=%s nonce=%s", work["h"], self.username, nonce_hex)
                self.pool.refresh.set()
            except (OSError, ValueError, TimeoutError) as error:
                LOG.warning("Block RPC response uncertain at height %s: %s", work["h"], type(error).__name__)
        await self.response(ident, True)
        if self.static_difficulty is None and now - self.last_retarget >= 30 and len(self.intervals) >= 4:
            average = sum(self.intervals) / len(self.intervals)
            # Retarget toward one share / 10 seconds, bounded 4x per adjustment.
            factor = max(0.25, min(4, 10 / max(0.01, average)))
            self.hashes = max(1, min(1 << 50, int(weight * factor)))
            self.intervals.clear()
            self.last_retarget = now
            await self.job(False)

    async def run(self):
        try:
            while True:
                line = await asyncio.wait_for(self.reader.readline(), 600)
                if not line:
                    break
                request = None
                try:
                    request = json.loads(line)
                    await self.request(request)
                except (ValueError, TypeError, StratumError) as error:
                    self.rejected += 1
                    code = error.code if isinstance(error, StratumError) else 20
                    message = str(error) if isinstance(error, StratumError) else "Invalid JSON-RPC request"
                    ident = request.get("id") if isinstance(request, dict) else None
                    await self.response(ident, False, [code, message, None])
                    # Stale work is normal while a GPU drains a batch after a new
                    # block. Duplicate retries can also follow a lost response.
                    if code not in (21, 22):
                        self.invalid_requests += 1
                    if self.invalid_requests >= 50 and self.invalid_requests > self.accepted:
                        break
        except (ConnectionError, OSError, asyncio.TimeoutError, ValueError):
            pass
        finally:
            self.pool.clients.discard(self)
            self.writer.close()
            await self.writer.wait_closed()


class Pool:
    def __init__(self, config):
        self.config = config
        self.node = Node(config)
        self.ledger = Ledger(config["database"])
        self.clients = set()
        self.work = None
        self.info = None
        self.checked_at = 0
        self.error = "Waiting for node"
        self.payout_error = None
        self.submit_lock = asyncio.Lock()
        self.refresh = asyncio.Event()
        self.allowed = [ipaddress.ip_network(cidr) for cidr in config["allowedNetworks"]]
        self.extra_nonce = secrets.randbelow(65536)
        self.last_reconcile = 0
        self.reconcile_task = None
        self.solved_height = 0

    def ready(self):
        return self.work is not None and self.error is None and time.monotonic() - self.checked_at < 15

    def permitted(self, writer):
        peer = writer.get_extra_info("peername")
        return peer and any(ipaddress.ip_address(peer[0]) in net for net in self.allowed)

    async def accept(self, reader, writer):
        if not self.permitted(writer) or len(self.clients) >= 64:
            writer.close()
            await writer.wait_closed()
            return
        active = {c.prefix for c in self.clients}
        while True:
            self.extra_nonce = (self.extra_nonce + 1) % 65536
            prefix = f"{self.extra_nonce:04x}"
            if prefix not in active:
                break
        client = Client(self, reader, writer, prefix)
        self.clients.add(client)
        await client.run()

    async def broadcast(self, clean):
        results = await asyncio.gather(*(c.job(clean) for c in tuple(self.clients)), return_exceptions=True)
        for result in results:
            if isinstance(result, Exception):
                LOG.warning("Worker notification failed: %s", type(result).__name__)

    async def update(self):
        while True:
            try:
                if time.monotonic() - self.checked_at >= 5 or self.info is None:
                    self.info = await asyncio.to_thread(self.node.ready)
                    self.ledger.bind_network(self.config["genesisId"], self.node.pk)
                    self.checked_at = time.monotonic()
                work = await asyncio.to_thread(self.node.rpc, "/mining/candidate")
                work["b"] = int(work["b"])
                if work["pk"] != self.node.pk or len(bytes.fromhex(work["msg"])) != 32 or not 0 < work["b"] <= MAX_TARGET:
                    raise ValueError("Invalid node work template")
                changed = not self.work or work["msg"] != self.work["msg"]
                self.work = work
                self.error = None
                if changed:
                    LOG.info("New job at height %s", work["h"])
                    await self.broadcast(True)
                elif any(c.subscribed and c.username and time.monotonic() - c.last_send >= 20 for c in self.clients):
                    await self.broadcast(False)
                if time.monotonic() - self.last_reconcile >= 5 and \
                        (self.reconcile_task is None or self.reconcile_task.done()):
                    self.reconcile_task = asyncio.create_task(self.reconcile(self.info["fullHeight"]))
                    self.last_reconcile = time.monotonic()
            except Exception as error:
                self.error = type(error).__name__ + ": " + str(error)
                # RPC error bodies can include generated requests. Do not log them.
                LOG.warning("Node/payout maintenance retry: %s", type(error).__name__)
            try:
                await asyncio.wait_for(self.refresh.wait(), 0.25)
                self.refresh.clear()
            except asyncio.TimeoutError:
                pass

    async def reconcile(self, height):
        # Wallet/history RPCs must not delay job updates or PoW submissions.
        try:
            await asyncio.to_thread(self.node.reconcile, self.ledger, height)
            self.payout_error = None
        except Exception as error:
            self.payout_error = type(error).__name__ + ": " + str(error)
            LOG.warning("Payout maintenance retry: %s", type(error).__name__)

    async def miner_api(self, target):
        """Address-only, read-only accounting. No node or wallet RPC is performed here."""
        if len(target) > 512 or "%" in target or "#" in target:
            raise ValueError("Invalid miner API request")
        route = urlsplit(target)
        match = re.fullmatch(r"/api/miner/([^/]{1,100})(/payouts)?", route.path)
        if not match:
            raise ValueError("Use /api/miner/ADDRESS or /api/miner/ADDRESS/payouts")
        address = validate_miner_address(match[1], self.config["addressPrefix"])
        pairs = parse_qsl(route.query, keep_blank_values=True, strict_parsing=True, max_num_fields=3)
        query = dict(pairs)
        if len(query) != len(pairs):
            raise ValueError("Duplicate pagination parameters")
        confirmations = self.config["confirmations"]
        if match[2]:
            if set(query) - {"limit", "offset", "snapshot"}:
                raise ValueError("Unknown pagination parameter")
            values = {}
            for name, value in query.items():
                if not re.fullmatch(r"0|[1-9][0-9]{0,18}", value):
                    raise ValueError("Pagination parameters must be bounded nonnegative integers")
                values[name] = int(value)
            return await asyncio.to_thread(self.ledger.miner_payouts, address,
                                           values.get("limit", 20), values.get("offset", 0),
                                           values.get("snapshot"), confirmations)
        if query:
            raise ValueError("The miner summary does not accept query parameters")
        connected = {}
        for client in tuple(self.clients):
            if client.username and client.address == address:
                connected[client.worker] = connected.get(client.worker, 0) + 1
        result = await asyncio.to_thread(self.ledger.miner_summary, address, connected, confirmations)
        result.update({"coin": "ZYRX", "network": self.config.get("network", "devnet"), "ready": self.ready(),
                       "nodeHeight": self.info["fullHeight"] if self.info else None,
                       "confirmations": confirmations, "payoutScheme": "PROP", "poolFeePercent": 0,
                       "minimumPayoutNanoZYRX": str(self.config["minimumPayoutNano"]),
                       "payoutFeeNanoZYRX": str(self.config["payoutFeeNano"]),
                       "feePaidBy": "miners", "payoutFeePolicy": "actual-transaction-v1"})
        return result

    async def http(self, reader, writer):
        try:
            if not self.permitted(writer):
                return
            header = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), 5)
            first = header.decode("ascii").split("\r\n")[0].split(" ")
            valid_request = len(first) == 3 and first[2] in ("HTTP/1.0", "HTTP/1.1")
            method = first[0] if valid_request else ""
            path = first[1] if valid_request else ""
            status, content_type = "200 OK", "application/json"
            if not valid_request or not path.startswith("/") or path.startswith("//"):
                status, body = "400 Bad Request", b'{"error":"Invalid HTTP request"}'
            elif method not in ("GET", "HEAD"):
                status, body = "405 Method Not Allowed", b'{"error":"Use GET or HEAD"}'
            elif path.startswith("/api/miner/"):
                try:
                    result = await self.miner_api(path)
                    body = json.dumps(result, separators=(",", ":")).encode()
                    if len(body) > 262144:
                        status, body = "503 Service Unavailable", b'{"error":"Miner response exceeds its size limit"}'
                except ValueError as error:
                    status, body = "400 Bad Request", json.dumps({"error": str(error)}).encode()
                except sqlite3.Error:
                    status, body = "503 Service Unavailable", b'{"error":"Accounting is temporarily unavailable"}'
            elif path in ("/api/stats", "/health"):
                result = await asyncio.to_thread(self.ledger.stats)
                result.pop("gpuValidation", None)
                result.update({"coin": "ZYRX", "network": self.config.get("network", "devnet"), "ready": self.ready(),
                    "nodeHeight": self.info["fullHeight"] if self.info else None,
                    "candidateHeight": self.work["h"] if self.work else None,
                    "connectedWorkers": len([c for c in self.clients if c.username]),
                    "poolFeePercent": 0, "payoutScheme": "PROP",
                    "confirmations": self.config["confirmations"], "stratumUrl": self.config["publicStratumUrl"],
                    "minimumPayoutNano": self.config["minimumPayoutNano"],
                    "payoutFeeNano": self.config["payoutFeeNano"], "feePaidBy": "miners",
                    "payoutFeePolicy": "actual-transaction-v1",
                    "nodeError": self.error, "payoutError": self.payout_error,
                    "poolAddress": self.node.wallet.get("address")})
                body = json.dumps(result).encode()
                if path == "/health" and not self.ready():
                    status = "503 Service Unavailable"
            elif path == "/":
                body = Path(__file__).with_name("index.html").read_bytes()
                content_type = "text/html; charset=utf-8"
            elif path == "/logo.png":
                body = Path(__file__).with_name("logo.png").read_bytes()
                content_type = "image/png"
            else:
                status, body = "404 Not Found", b'{}'
            writer.write((f"HTTP/1.1 {status}\r\nContent-Type: {content_type}\r\nContent-Length: {len(body)}\r\n"
                          "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nAllow: GET, HEAD\r\n"
                          "Connection: close\r\n\r\n").encode() + (body if method != "HEAD" else b""))
            await asyncio.wait_for(writer.drain(), 5)
        except (OSError, ValueError, asyncio.TimeoutError, asyncio.IncompleteReadError, asyncio.LimitOverrunError):
            pass
        finally:
            writer.close()
            await writer.wait_closed()

    async def run(self):
        stop = asyncio.Event()
        loop = asyncio.get_running_loop()
        for sig in (signal.SIGINT, signal.SIGTERM):
            loop.add_signal_handler(sig, stop.set)
        stratum = await asyncio.start_server(self.accept, "0.0.0.0", self.config["stratumPort"], limit=8192)
        http = await asyncio.start_server(self.http, "0.0.0.0", self.config["httpPort"], limit=8192)
        update = asyncio.create_task(self.update())
        LOG.info("Zyrex Stratum listening on %s", self.config["publicStratumUrl"])
        async with stratum, http:
            await stop.wait()
        update.cancel()
        await asyncio.gather(update, return_exceptions=True)
        if self.reconcile_task:
            self.reconcile_task.cancel()
            await asyncio.gather(self.reconcile_task, return_exceptions=True)
        for client in tuple(self.clients):
            client.writer.close()


def validate_config(config):
    network = config.get("network", "devnet")
    expected = {"devnet": 80, "testnet": 64}.get(network)
    if expected is None or config["addressPrefix"] != expected:
        raise ValueError("Pool network and Zyrex address prefix must match")
    delay = config.get("minerRewardDelay", 3)
    if not isinstance(delay, int) or delay < 0 or config["confirmations"] < max(5, delay + 2):
        raise ValueError("Pool confirmations must exceed consensus reward maturity")
    minimum = config["minimumPayoutNano"]
    fee = config.get("payoutFeeNano")
    if type(minimum) is not int or not 1_000_000 <= minimum <= (1 << 63) - 1:
        raise ValueError("Minimum payout is below the supported transaction amount")
    if type(fee) is not int or not 0 < fee <= (1 << 63) - 1 or minimum < fee + 1_000_000:
        raise ValueError("Payout threshold must cover its fee and a supported recipient output")
    script = config.get("feeScriptHex")
    if isinstance(script, list):
        if not script or any(not isinstance(part, str) or not part or len(part) % 2 for part in script):
            raise ValueError("Native fee script fragments must be nonempty byte-aligned hex strings")
        script = "".join(script)
    if not isinstance(script, str) or not script or len(script) % 2 or len(script) > 4096:
        raise ValueError("Pin the native network transaction fee script")
    try:
        decoded = bytes.fromhex(script)
    except ValueError as error:
        raise ValueError("Invalid native transaction fee script") from error
    if decoded.hex() != script:
        raise ValueError("Native transaction fee script must be canonical lowercase hex")
    config["feeScriptHex"] = script


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True)
    args = parser.parse_args()
    config = json.loads(Path(args.config).read_text())
    validate_config(config)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    asyncio.run(Pool(config).run())


if __name__ == "__main__":
    main()
