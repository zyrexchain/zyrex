"""Revocable Stratum jobs and bounded, honest work-result diagnostics."""
import asyncio
import json
import logging
import secrets
import time
from collections import Counter

from pow import MAX_TARGET

LOG = logging.getLogger("zyrex-pool")


SOLUTION_ACK_SECONDS = 20


class StratumError(Exception):
    def __init__(self, code, message, reason=None):
        self.code = code
        self.reason = reason
        super().__init__(message)


class IssuedJob(tuple):
    """Keep the advertised target while allowing queued verification to lose its lease."""
    def __new__(cls, work, target, weight):
        job = super().__new__(cls, (dict(work), target, weight))
        job.created = time.monotonic()
        job.superseded_at = None
        job.invalid_reason = None
        return job

    def invalidate(self, reason):
        self.invalid_reason = self.invalid_reason or reason


class WorkResults:
    """Process-lifetime counters distinguish obsolete work from malformed or invalid shares."""
    def __init__(self):
        self.accepted = 0
        self.stale = Counter()
        self.rejected = Counter()
        self.notifications = 0
        self.coalesced = 0
        self.native_submissions = 0
        self.native_uncertain = 0

    def reject(self, code, reason):
        if code == 21:
            self.stale[reason or "unknown_job"] += 1
        else:
            names = {20: "malformed", 22: "duplicate", 23: "low_difficulty", 24: "unauthorized",
                     25: "unsubscribed", 26: "resource_limit"}
            self.rejected[names.get(code, "other")] += 1

    def stats(self):
        return {"scope": "since-pool-start", "accepted": self.accepted, "stale": dict(self.stale),
                "rejected": dict(self.rejected), "notifications": self.notifications,
                "coalescedNotifications": self.coalesced, "nativeSubmissionAttempts": self.native_submissions,
                "uncertainNativeResponses": self.native_uncertain}


class ClientJobs:
    """One coalescing sender per connection; old target leases stay independently verifiable."""
    def retire_jobs(self, reason, predicate=None):
        for ident, job in tuple(self.jobs.items()):
            if predicate is None or predicate(job):
                job.invalidate(reason)
                self.retired_jobs[ident] = reason
                self.jobs.pop(ident, None)
        while len(self.retired_jobs) > 32:
            self.retired_jobs.popitem(last=False)

    def schedule_job(self, clean):
        if self.pending_notification:
            self.pool.work_results.coalesced += 1
        self.pending_notification = True
        self.pending_clean = self.pending_clean or clean
        if self.notification_task is None or self.notification_task.done():
            self.notification_task = asyncio.create_task(self.send_jobs())

    async def send_jobs(self):
        try:
            while self.pending_notification:
                clean = self.pending_clean
                self.pending_notification = self.pending_clean = False
                await self.job(clean)
        except (ConnectionError, OSError, asyncio.TimeoutError):
            self.writer.close()
            LOG.warning("Worker notification timed out or disconnected")

    async def job(self, clean):
        async with self.job_lock:
            await self.issue_job(clean)

    async def issue_job(self, clean):
        if not self.subscribed or not self.username or not self.pool.ready():
            return
        work = self.pool.work
        if work["h"] <= self.pool.solved_height or work.get("nodeSolved"):
            return
        # Block solutions must always qualify as shares, including this young devnet.
        target = max(work["b"], MAX_TARGET // max(1, self.hashes))
        weight = (1 << 256) // target
        if clean:
            self.retire_jobs("clean_job")
        elif self.jobs:
            latest = next(reversed(self.jobs.values()))
            if latest[0]["msg"] == work["msg"] and latest[1] == target:
                return
        now = time.monotonic()
        for job in self.jobs.values():
            if job.superseded_at is None:
                job.superseded_at = now
        job_id = secrets.token_hex(8)
        self.jobs[job_id] = IssuedJob(work, target, weight)
        while len(self.jobs) > 16:
            _, job = next(iter(self.jobs.items()))
            self.retire_jobs("job_cache_limit", lambda candidate: candidate is job)
        # Write the two frames together so a concurrent new tip cannot interleave its notification.
        async with self.write_lock:
            frames = [{"id": None, "method": "mining.set_difficulty", "params": [1]},
                      {"id": None, "method": "mining.notify",
                       "params": [job_id, work["h"], work["msg"], "", "", 4, str(target), "", clean]}]
            self.writer.write("".join(json.dumps(frame, separators=(",", ":")) + "\n" for frame in frames).encode())
            await asyncio.wait_for(self.writer.drain(), 5)
        self.last_send = time.monotonic()
        self.pool.work_results.notifications += 1


def candidate_details(work):
    """Validate the optional atomic native cache snapshot; legacy nodes keep strict current-only work."""
    if "workVersion" not in work:
        return False
    messages = work.get("activeMessages")
    if type(work["workVersion"]) is not int or work["workVersion"] != 1 or not hexadecimal(work.get("parentId"), 64) \
            or type(work.get("nodeSolved")) is not bool or not isinstance(messages, list) or not 1 <= len(messages) <= 2 \
            or any(not hexadecimal(message, 64) for message in messages) or len(set(messages)) != len(messages) \
            or work.get("msg") != messages[0]:
        raise ValueError("Malformed native mining cache snapshot")
    return True


def hexadecimal(value, length):
    return isinstance(value, str) and len(value) == length and all(c in "0123456789abcdef" for c in value)


def candidate_work(work, expected_pk):
    if not isinstance(work, dict) or type(work.get("h")) is not int or not 2 <= work["h"] <= (1 << 31) - 1 \
            or not hexadecimal(work.get("msg"), 64) or not hexadecimal(work.get("pk"), 66) \
            or work["pk"][:2] not in ("02", "03") or work["pk"] != expected_pk:
        raise ValueError("Invalid native work identity")
    target = work.get("b")
    if type(target) is not int and (not isinstance(target, str) or not 1 <= len(target) <= 78
                                   or any(c not in "0123456789" for c in target)):
        raise ValueError("Invalid native work target")
    target = int(target)
    if not 0 < target <= MAX_TARGET:
        raise ValueError("Invalid native work target")
    work = dict(work, b=target)
    candidate_details(work)
    return work



class PoolJobs:
    """Only native-cached work may enter durable accounting or the serialized solution lane."""
    def check_job(self, job, pending_allowed=False):
        current, pending = self.work, self.pending_solution
        if self.stopping and not pending_allowed:
            raise StratumError(21, "Pool is shutting down", "pool_stopping")
        if job.invalid_reason:
            raise StratumError(21, "Obsolete work: " + job.invalid_reason.replace("_", " "), job.invalid_reason)
        if current is None or self.error is not None \
                or time.monotonic() - self.checked_at >= 15 and not (self.stopping and pending_allowed):
            raise StratumError(21, "Node work is temporarily unavailable", "node_unavailable")
        if job[0]["h"] != current["h"]:
            raise StratumError(21, "Previous block height", "previous_height")
        if job[0]["h"] <= self.solved_height:
            raise StratumError(21, "Block solution is awaiting application", "solution_pending")
        if pending is not None and pending["job"][0]["h"] == job[0]["h"] \
                and not (pending_allowed and pending["job"] is job):
            raise StratumError(21, "Native solution submission is in progress", "solution_pending")
        if current.get("nodeSolved"):
            raise StratumError(21, "Native block solution is awaiting application", "solution_pending")
        if job.superseded_at is not None and time.monotonic() - job.superseded_at > 120:
            raise StratumError(21, "Previous share target has expired", "job_expired")
        if current.get("workVersion") == 1 and job[0].get("workVersion") == 1:
            if job[0]["parentId"] != current["parentId"]:
                raise StratumError(21, "Previous block branch", "branch_replaced")
            if job[0]["pk"] != current["pk"] or job[0]["b"] != current["b"] \
                    or job[0]["msg"] not in current["activeMessages"]:
                raise StratumError(21, "Native mining cache has expired", "template_cache_expired")
        elif job[0]["msg"] != current["msg"]:
            raise StratumError(21, "Mining template was replaced", "template_superseded")

    def install_work(self, work):
        work = candidate_work(work, self.node.pk)
        detailed = candidate_details(work)
        previous = self.work
        changed = not previous or any(work.get(field) != previous.get(field) for field in ("msg", "h", "pk", "b", "parentId"))
        same_parent = detailed and previous and previous.get("workVersion") == 1 \
            and work["parentId"] == previous["parentId"] and work["h"] == previous["h"] \
            and work["pk"] == previous["pk"] and work["b"] == previous["b"]
        clean = not same_parent
        if clean and changed:
            self.pending_solution = None
        if detailed and previous and previous.get("workVersion") == 1 and not same_parent \
                and work["h"] <= self.solved_height:
            self.solved_height = work["h"] - 1
            self.solution_ack_at = self.solution_ack_height = None
        if previous and previous.get("nodeSolved") and not work.get("nodeSolved"):
            changed, clean = True, True
        self.work = work
        if clean and changed:
            self.retire_jobs("previous_height" if previous and work["h"] != previous["h"] else "branch_replaced")
        elif detailed:
            for client in tuple(self.clients):
                client.retire_jobs("template_cache_expired", lambda job: job[0]["msg"] not in work["activeMessages"])
        return changed, clean

    def retire_jobs(self, reason):
        for client in tuple(self.clients):
            client.retire_jobs(reason)

    def record_share(self, work, nonce, address, worker, weight, solution, job=None):
        # Check after executor queuing and the SQLite writer wait, before the accounting admission point.
        with self.ledger.lock:
            if job is not None:
                self.check_job(job)
            ident = self.ledger.share(work["msg"], nonce, address, worker, weight)
            if solution:
                self.ledger.block(work, nonce, ident)
            return ident

    def expire_pending_solution(self, work, fresh_info, now=None):
        pending = self.pending_solution
        if pending is None or pending["rpcPending"] or not fresh_info:
            return False
        now = time.monotonic() if now is None else now
        info = self.info or {}
        height, ident = info.get("fullHeight"), info.get("bestFullHeaderId")
        if type(height) is not int or height < 1 or info.get("headersHeight") != height \
                or not isinstance(ident, str) or not ident or info.get("bestHeaderId") != ident \
                or work.get("nodeSolved") or work.get("h") != height + 1 \
                or work.get("workVersion") == 1 and work.get("parentId") != ident \
                or pending["job"][0]["h"] != work["h"] or now - pending["at"] < SOLUTION_ACK_SECONDS:
            return False
        self.pending_solution = None
        self.retire_jobs("uncertain_solution_retry")
        self.work = None
        return True
