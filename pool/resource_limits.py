"""Bounded pool admission, verification queues and trusted client identity parsing."""
import asyncio
import ipaddress
import time
from collections import OrderedDict
from concurrent.futures import ThreadPoolExecutor
from functools import partial


class ResourceBusy(Exception):
    """Admission failed before work was submitted to an executor."""


class TokenBucket:
    def __init__(self, rate, burst, now=None):
        self.rate, self.burst = rate, burst
        self.tokens = float(burst)
        self.updated = time.monotonic() if now is None else now

    def take(self, now=None):
        now = time.monotonic() if now is None else now
        self.tokens = min(self.burst, self.tokens + max(0, now - self.updated) * self.rate)
        self.updated = now
        if self.tokens < 1:
            return False
        self.tokens -= 1
        return True


class WorkLane:
    """Bound both running and queued work; cancellation never releases a live worker."""
    def __init__(self, name, workers, queued):
        self.name, self.workers, self.capacity = name, workers, workers + queued
        self.executor = ThreadPoolExecutor(max_workers=workers, thread_name_prefix="zyrex-" + name)
        self.inflight = 0
        self.high_water = 0
        self.rejected = 0
        self.completed = 0
        self.closed = False

    async def run(self, function, *args, **kwargs):
        if self.closed or self.inflight >= self.capacity:
            self.rejected += 1
            raise ResourceBusy(self.name + " queue is full")
        loop = asyncio.get_running_loop()
        self.inflight += 1
        self.high_water = max(self.high_water, self.inflight)
        try:
            future = self.executor.submit(partial(function, *args, **kwargs))
        except BaseException:
            self.inflight -= 1
            raise

        def finished(_):
            def release():
                self.inflight -= 1
                self.completed += 1
            if not loop.is_closed():
                loop.call_soon_threadsafe(release)
        future.add_done_callback(finished)
        wrapped = asyncio.wrap_future(future)
        wrapped.add_done_callback(lambda result: result.exception() if not result.cancelled() else None)
        return await asyncio.shield(wrapped)

    def stats(self):
        return {"runningAndQueued": self.inflight, "capacity": self.capacity,
                "workers": self.workers, "highWater": self.high_water,
                "rejected": self.rejected, "completed": self.completed}

    def close(self):
        self.closed = True
        self.executor.shutdown(wait=False, cancel_futures=True)


class DuplicateCache:
    """An event-loop-owned, bounded TTL/LRU set also reserves in-flight nonces."""
    def __init__(self, capacity=8192, ttl=180):
        self.capacity, self.ttl = capacity, ttl
        self.entries = OrderedDict()
        self.rejected = 0

    def reserve(self, key, now=None):
        now = time.monotonic() if now is None else now
        while self.entries and next(iter(self.entries.values())) <= now:
            self.entries.popitem(last=False)
        if key in self.entries:
            self.entries.move_to_end(key)
            self.entries[key] = now + self.ttl
            self.rejected += 1
            return False
        self.entries[key] = now + self.ttl
        while len(self.entries) > self.capacity:
            self.entries.popitem(last=False)
        return True

    def release(self, key):
        self.entries.pop(key, None)


DEFAULTS = {
    "pendingConnections": 24, "activeConnections": 64, "pendingPerIp": 4, "activePerIp": 8,
    "handshakeSeconds": 10, "proxyHeaderSeconds": 2, "idleSeconds": 600,
    "connectRate": 2, "connectBurst": 12, "requestRate": 20, "requestBurst": 128,
    "shareRate": 2, "shareBurst": 80, "httpConnections": 24, "httpPerIp": 8,
    "httpRate": 10, "httpBurst": 30, "identityEntries": 4096,
    "powWorkers": 2, "powQueue": 6, "httpWorkers": 2, "httpQueue": 6,
    "duplicateEntries": 8192, "duplicateSeconds": 180,
}


def settings(config):
    supplied = config.get("resourceLimits", {})
    if not isinstance(supplied, dict) or set(supplied) - set(DEFAULTS):
        raise ValueError("Unknown pool resource limit")
    result = dict(DEFAULTS, **supplied)
    for name, value in result.items():
        if type(value) not in (int, float) or not 0 < value <= 100000:
            raise ValueError("Resource limits must be bounded positive numbers")
        if name not in ("handshakeSeconds", "proxyHeaderSeconds", "idleSeconds", "connectRate", "requestRate", "shareRate", "httpRate"):
            if type(value) is not int:
                raise ValueError("Resource capacities must be integer values")
    if result["pendingPerIp"] > result["pendingConnections"] or result["activePerIp"] > result["activeConnections"]:
        raise ValueError("Per-client limits cannot exceed global connection limits")
    if result["httpPerIp"] > result["httpConnections"]:
        raise ValueError("Per-client HTTP limit cannot exceed its global limit")
    if result["handshakeSeconds"] > 30 or result["proxyHeaderSeconds"] > 5:
        raise ValueError("Pool handshake deadlines must be short")
    if result["powWorkers"] > 8 or result["httpWorkers"] > 8:
        raise ValueError("Pool executor worker counts exceed their safe limit")
    return result


class Admission:
    def __init__(self, config):
        self.limits = settings(config)
        self.pending = {}
        self.active = {}
        self.http = {}
        self.header_connections = 0
        self.connections = OrderedDict()
        self.http_rates = OrderedDict()
        self.denied_connections = 0
        self.denied_http = 0
        self.denied_requests = 0
        self.denied_shares = 0

    def bucket(self, table, identity, rate, burst):
        now = time.monotonic()
        bucket = table.pop(identity, None)
        if bucket is None:
            # Never evict an unexpired exhausted client bucket to reset its budget.
            while len(table) >= self.limits["identityEntries"]:
                oldest, candidate = next(iter(table.items()))
                if now - candidate.updated < max(60, burst / rate):
                    return None
                table.pop(oldest)
            bucket = TokenBucket(rate, burst)
        table[identity] = bucket
        return bucket

    def connect(self, identity):
        l = self.limits
        bucket = self.bucket(self.connections, identity, l["connectRate"], l["connectBurst"])
        if bucket is None or not bucket.take() or sum(self.pending.values()) >= l["pendingConnections"] \
                or self.pending.get(identity, 0) >= l["pendingPerIp"]:
            self.denied_connections += 1
            return False
        self.pending[identity] = self.pending.get(identity, 0) + 1
        return True

    def activate(self, identity):
        l = self.limits
        if sum(self.active.values()) >= l["activeConnections"] or self.active.get(identity, 0) >= l["activePerIp"]:
            self.denied_connections += 1
            return False
        self.drop(self.pending, identity)
        self.active[identity] = self.active.get(identity, 0) + 1
        return True

    @staticmethod
    def drop(table, identity):
        count = table.get(identity, 0)
        if count <= 1:
            table.pop(identity, None)
        else:
            table[identity] = count - 1

    def admit_http(self, identity):
        l = self.limits
        bucket = self.bucket(self.http_rates, identity, l["httpRate"], l["httpBurst"])
        if bucket is None or not bucket.take() or self.http.get(identity, 0) >= l["httpPerIp"]:
            self.denied_http += 1
            return False
        self.http[identity] = self.http.get(identity, 0) + 1
        return True

    def stats(self):
        return {"pendingConnections": sum(self.pending.values()), "activeConnections": sum(self.active.values()),
                "httpConnections": self.header_connections, "deniedConnections": self.denied_connections,
                "deniedHttp": self.denied_http, "deniedRequests": self.denied_requests,
                "deniedShares": self.denied_shares}


def networks(config, name):
    value = config.get(name, [])
    if not isinstance(value, list):
        raise ValueError(name + " must be an explicit list of trusted transport networks")
    result = [ipaddress.ip_network(cidr, strict=True) for cidr in value]
    if any(network.prefixlen == 0 for network in result):
        raise ValueError("Trusting every proxy source would allow forged client identities")
    return result


def trusted(identity, ranges):
    return any(ipaddress.ip_address(identity) in network for network in ranges)


def proxy_identity(line):
    if len(line) > 108 or not line.endswith(b"\r\n"):
        raise ValueError("Invalid PROXY header length")
    fields = line[:-2].decode("ascii").split(" ")
    if len(fields) != 6 or fields[0] != "PROXY" or fields[1] not in ("TCP4", "TCP6"):
        raise ValueError("Require an explicit PROXY v1 TCP identity")
    if "%" in fields[2] or "%" in fields[3]:
        raise ValueError("PROXY identities cannot contain scoped IPv6 addresses")
    source, destination = ipaddress.ip_address(fields[2]), ipaddress.ip_address(fields[3])
    version = 4 if fields[1] == "TCP4" else 6
    if source.version != version or destination.version != version or source.is_unspecified or source.is_multicast:
        raise ValueError("Invalid PROXY address")
    if any(not port.isdigit() or not 1 <= int(port) <= 65535 for port in fields[4:]):
        raise ValueError("Invalid PROXY port")
    return str(source)
