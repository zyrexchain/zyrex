"""Durable PROP accounting in integer nanoZYRX and integer expected hashes."""
import json
import sqlite3
import time
import threading
from functools import wraps
from pathlib import Path
from pow import address_bytes

MAX_NANO = (1 << 63) - 1
FEE_POLICY = "actual-transaction-v1"
HASHRATE_WINDOW = 600


class ExactSum:
    """Keep lifetime amounts and share weights outside SQLite's signed-int64 sum."""
    def __init__(self):
        self.total = 0

    def step(self, value):
        if value is not None:
            self.total += int(value)

    def finalize(self):
        return str(self.total)


def positive_nano(value):
    if type(value) is not int or not 0 < value <= MAX_NANO:
        raise ValueError("Amounts must be positive integer nanoZYRX")
    return value


def allocate_integer(total, weights):
    """Deterministic largest-remainder allocation without floating point."""
    if type(total) is not int or total < 0 or not weights or any(
            type(weight) is not int or weight <= 0 for weight in weights.values()):
        raise ValueError("Invalid integer allocation")
    denominator = sum(weights.values())
    amounts = {address: total * weight // denominator for address, weight in weights.items()}
    residues = sorted(weights, key=lambda address: (-(total * weights[address] % denominator), address))
    for address in residues[:total - sum(amounts.values())]:
        amounts[address] += 1
    return amounts


def synchronized(method):
    @wraps(method)
    def call(self, *args, **kwargs):
        with self.lock:
            return method(self, *args, **kwargs)
    return call


def read_snapshot(method):
    """Give public accounting reads a consistent WAL snapshot without holding the writer lock."""
    @wraps(method)
    def call(self, *args, **kwargs):
        if self.filename == ":memory:":
            with self.lock:
                return method(self, *args, **kwargs)
        db = sqlite3.connect(Path(self.filename).resolve().as_uri() + "?mode=ro", uri=True, timeout=5)
        try:
            db.row_factory = sqlite3.Row
            db.create_aggregate("exact_sum", 1, ExactSum)
            db.execute("PRAGMA query_only=ON")
            db.execute("BEGIN")
            view = object.__new__(type(self))
            view.db = db
            return method(view, *args, **kwargs)
        finally:
            db.close()
    return call


class Ledger:
    def __init__(self, filename):
        self.filename = filename
        self.lock = threading.RLock()
        self.db = sqlite3.connect(filename, check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.create_aggregate("exact_sum", 1, ExactSum)
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA synchronous=FULL")
        self.db.executescript("""
            CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS shares(
                id INTEGER PRIMARY KEY, msg TEXT NOT NULL, nonce TEXT NOT NULL,
                address TEXT NOT NULL, worker TEXT NOT NULL, weight TEXT NOT NULL,
                created REAL NOT NULL, round INTEGER, UNIQUE(msg,nonce));
            CREATE TABLE IF NOT EXISTS blocks(
                id INTEGER PRIMARY KEY, height INTEGER NOT NULL, msg TEXT NOT NULL,
                nonce TEXT NOT NULL, pk TEXT NOT NULL, end_share INTEGER NOT NULL,
                status TEXT NOT NULL DEFAULT 'submitted', hash TEXT,
                reward INTEGER NOT NULL DEFAULT 0, created REAL NOT NULL,
                UNIQUE(msg,nonce));
            CREATE TABLE IF NOT EXISTS credits(
                block INTEGER NOT NULL, address TEXT NOT NULL, amount INTEGER NOT NULL,
                PRIMARY KEY(block,address));
            CREATE TABLE IF NOT EXISTS balances(address TEXT PRIMARY KEY, amount INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS payouts(
                id TEXT PRIMARY KEY, raw TEXT NOT NULL, amounts TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'prepared', created REAL NOT NULL);
            CREATE TABLE IF NOT EXISTS fee_adjustments(
                policy TEXT NOT NULL, address TEXT NOT NULL, amount INTEGER NOT NULL,
                details TEXT NOT NULL, PRIMARY KEY(policy,address));
            CREATE INDEX IF NOT EXISTS shares_created ON shares(created);
            CREATE INDEX IF NOT EXISTS shares_address_worker ON shares(address,worker,created);
            CREATE INDEX IF NOT EXISTS shares_round_address ON shares(round,address,id);
            CREATE INDEX IF NOT EXISTS credits_address ON credits(address,block);
            CREATE INDEX IF NOT EXISTS fee_adjustments_address ON fee_adjustments(address);
            CREATE INDEX IF NOT EXISTS payouts_created ON payouts(created);
        """)
        # Additive schema upgrade: historical signed payments and credits are immutable.
        columns = {row[1] for row in self.db.execute("PRAGMA table_info(payouts)")}
        for name in ("gross", "fees"):
            if name not in columns:
                self.db.execute("ALTER TABLE payouts ADD COLUMN " + name + " TEXT")
        self.db.commit()

    @synchronized
    def bind_network(self, genesis, pk):
        identity = genesis + ":" + pk
        row = self.db.execute("SELECT value FROM meta WHERE key='identity'").fetchone()
        if row and row[0] != identity:
            raise RuntimeError("Pool database belongs to another chain or mining wallet")
        with self.db:
            self.db.execute("INSERT OR IGNORE INTO meta VALUES('identity',?)", (identity,))

    @synchronized
    def halt(self, reason):
        with self.db:
            self.db.execute("INSERT OR REPLACE INTO meta VALUES('halt',?)", (reason,))

    @synchronized
    def halted(self):
        row = self.db.execute("SELECT value FROM meta WHERE key='halt'").fetchone()
        return row[0] if row else None

    @synchronized
    def share(self, msg, nonce, address, worker, weight):
        with self.db:
            cursor = self.db.execute(
                "INSERT INTO shares(msg,nonce,address,worker,weight,created) VALUES(?,?,?,?,?,?)",
                (msg, nonce, address, worker, str(weight), time.time()))
        return cursor.lastrowid

    @synchronized
    def block(self, work, nonce, share_id):
        with self.db:
            self.db.execute("""INSERT INTO blocks(height,msg,nonce,pk,end_share,created)
                VALUES(?,?,?,?,?,?)""", (work["h"], work["msg"], nonce, work["pk"], share_id, time.time()))

    @synchronized
    def blocks(self):
        return [dict(row) for row in self.db.execute("SELECT * FROM blocks ORDER BY height,id")]

    @synchronized
    def orphan(self, block_id):
        with self.db:
            self.db.execute("UPDATE blocks SET status='orphaned' WHERE id=?", (block_id,))

    @synchronized
    def settle(self, block, block_hash, reward, legacy_fee=0):
        """Freeze a round only when a block is canonical and mature; orphan work carries on.

        New rounds credit the entire reward. The optional fee recreates historical
        fixtures; production deducts fees only when preparing an actual payment.
        """
        positive_nano(reward)
        if type(legacy_fee) is not int or legacy_fee < 0 or reward <= legacy_fee:
            raise ValueError("Reward cannot cover the payout transaction fee")
        with self.db:
            status = self.db.execute("SELECT status FROM blocks WHERE id=?", (block["id"],)).fetchone()[0]
            if status != "submitted":
                return
            rows = self.db.execute("SELECT * FROM shares WHERE round IS NULL AND id<=?", (block["end_share"],)).fetchall()
            weights = {}
            for row in rows:
                weights[row["address"]] = weights.get(row["address"], 0) + int(row["weight"])
            total = sum(weights.values())
            if total == 0:
                raise RuntimeError("Cannot settle a block with no shares")
            amounts = allocate_integer(reward - legacy_fee, weights)
            for address, amount in amounts.items():
                self.db.execute("INSERT INTO credits VALUES(?,?,?)", (block["id"], address, amount))
                self.db.execute("""INSERT INTO balances VALUES(?,?) ON CONFLICT(address)
                    DO UPDATE SET amount=amount+excluded.amount""", (address, amount))
            self.db.execute("UPDATE shares SET round=? WHERE round IS NULL AND id<=?", (block["id"], block["end_share"]))
            self.db.execute("UPDATE blocks SET status='confirmed',hash=?,reward=? WHERE id=?",
                            (block_hash, reward, block["id"]))

    @synchronized
    def balances(self):
        return {row[0]: row[1] for row in self.db.execute("SELECT * FROM balances ORDER BY address")}

    @synchronized
    def prepare_payout(self, tx, amounts, gross_amounts=None, fees=None):
        """Persist signed transaction BEFORE sending; retries always use the same txid."""
        if not isinstance(tx, dict) or not isinstance(tx.get("id"), str) or not tx["id"] or not amounts:
            raise ValueError("Missing signed payment or recipients")
        for amount in amounts.values():
            positive_nano(amount)
        gross = dict(amounts) if gross_amounts is None else dict(gross_amounts)
        charges = {address: 0 for address in amounts} if fees is None else dict(fees)
        if set(gross) != set(amounts) or set(charges) != set(amounts):
            raise ValueError("Payment accounting recipients differ")
        for address, amount in gross.items():
            positive_nano(amount)
            charge = charges[address]
            if type(charge) is not int or charge < 0 or amount != amounts[address] + charge:
                raise ValueError("Payment fee does not match its gross debit")
        with self.db:
            for address, amount in gross.items():
                cursor = self.db.execute("UPDATE balances SET amount=amount-? WHERE address=? AND amount>=?",
                                         (amount, address, amount))
                if cursor.rowcount != 1:
                    raise RuntimeError("Payout exceeds credited balance")
            self.db.execute("INSERT INTO payouts(id,raw,amounts,created,gross,fees) VALUES(?,?,?,?,?,?)",
                            (tx["id"], json.dumps(tx), json.dumps(amounts), time.time(),
                             None if gross_amounts is None else json.dumps(gross),
                             None if fees is None else json.dumps(charges)))

    @synchronized
    def migrate_payout_fees(self, fee_script):
        """Refund unused historical fee reserves once, with an append-only audit journal.

        All historical signed payments reserve their actual native fee, including
        prepared payments. Unknown scripts or inconsistent accounting fail closed.
        """
        if not isinstance(fee_script, str) or not fee_script or len(fee_script) % 2:
            raise ValueError("Missing pinned native fee script")
        try:
            bytes.fromhex(fee_script)
        except ValueError as error:
            raise ValueError("Invalid pinned native fee script") from error
        identity = self.db.execute("SELECT value FROM meta WHERE key='identity'").fetchone()
        if not identity:
            raise RuntimeError("Pin the pool network before changing fee accounting")
        existing = self.db.execute("SELECT value FROM meta WHERE key='fee_policy'").fetchone()
        if existing:
            audit = json.loads(existing[0])
            if audit.get("policy") != FEE_POLICY or audit.get("identity") != identity[0] or \
                    audit.get("feeScript") != fee_script:
                raise RuntimeError("Fee accounting identity or native fee script changed")
            return audit
        with self.db:
            burdens = {}
            reserved = 0
            historical_balances = {}
            for block in self.blocks():
                credits = {row[0]: row[1] for row in self.db.execute(
                    "SELECT address,amount FROM credits WHERE block=?", (block["id"],))}
                if block["status"] != "confirmed":
                    if credits:
                        raise RuntimeError("Unconfirmed block has historical credits")
                    continue
                reward = positive_nano(block["reward"])
                if not credits or any(type(value) is not int or value < 0 for value in credits.values()):
                    raise RuntimeError("Invalid historical round credits")
                reserve = reward - sum(credits.values())
                if reserve < 0 or reserve >= reward:
                    raise RuntimeError("Invalid historical fee reserve")
                weights = {}
                for row in self.db.execute("SELECT address,weight FROM shares WHERE round=?", (block["id"],)):
                    weight = int(row[1])
                    if weight <= 0:
                        raise RuntimeError("Invalid historical share weight")
                    weights[row[0]] = weights.get(row[0], 0) + weight
                if allocate_integer(reward - reserve, weights) != credits:
                    raise RuntimeError("Historical credits disagree with share weights")
                full_credits = allocate_integer(reward, weights)
                for address, amount in credits.items():
                    historical_balances[address] = historical_balances.get(address, 0) + amount
                    charge = full_credits[address] - amount
                    if charge < 0:
                        raise RuntimeError("Historical rounding produced an ambiguous negative fee burden")
                    burdens[address] = burdens.get(address, 0) + charge
                reserved += reserve
            spent = 0
            for payout in self.payouts():
                if payout["gross"] is not None or payout["fees"] is not None:
                    raise RuntimeError("New fee accounting appeared before its migration journal")
                if payout["status"] not in ("prepared", "broadcast", "confirmed"):
                    raise RuntimeError("Unknown historical payout status")
                raw = json.loads(payout["raw"])
                amounts = json.loads(payout["amounts"])
                outputs = raw.get("outputs") if isinstance(raw, dict) else None
                if not isinstance(raw, dict) or raw.get("id") != payout["id"] or \
                        not isinstance(outputs, list) or not outputs or not isinstance(amounts, dict) or not amounts:
                    raise RuntimeError("Malformed historical signed payout")
                recipient_scripts = {address: "0008cd" + address_bytes(address)[1:].hex() for address in amounts}
                allowed_scripts = set(recipient_scripts.values()) | {fee_script, "0008cd" + identity[0].rsplit(":", 1)[1]}
                actual_fee = 0
                for output in outputs:
                    if not isinstance(output, dict) or output.get("ergoTree") not in allowed_scripts or output.get("assets", []):
                        raise RuntimeError("Malformed historical payout output")
                    value = positive_nano(output.get("value"))
                    if output["ergoTree"] == fee_script:
                        actual_fee += value
                if actual_fee <= 0:
                    raise RuntimeError("Historical payout has no pinned native fee output")
                for address, amount in amounts.items():
                    positive_nano(amount)
                    script = recipient_scripts[address]
                    if sum(output["value"] for output in outputs if output["ergoTree"] == script) != amount:
                        raise RuntimeError("Historical signed payout recipients do not match accounting")
                    historical_balances[address] = historical_balances.get(address, 0) - amount
                spent += actual_fee
            actual_balances = self.balances()
            if any(type(amount) is not int or amount < 0 for amount in actual_balances.values()) or \
                    any(amount < 0 for amount in historical_balances.values()) or \
                    {a: n for a, n in actual_balances.items() if n} != {a: n for a, n in historical_balances.items() if n}:
                raise RuntimeError("Historical balances disagree with immutable credits and payments")
            if spent > reserved:
                raise RuntimeError("Historical payout fees exceed reserved miner fees")
            refund = reserved - spent
            weights = {address: amount for address, amount in burdens.items() if amount > 0}
            refunds = allocate_integer(refund, weights) if refund else {}
            audit = {"policy": FEE_POLICY, "identity": identity[0], "feeScript": fee_script,
                     "reservedNanoZYRX": reserved, "spentNanoZYRX": spent,
                     "refundedNanoZYRX": refund, "created": time.time()}
            for address, amount in refunds.items():
                if amount == 0:
                    continue
                positive_nano(amount)
                current = self.balances().get(address, 0)
                if current < 0 or current + amount > MAX_NANO:
                    raise RuntimeError("Historical fee refund would overflow a miner balance")
                self.db.execute("INSERT INTO fee_adjustments VALUES(?,?,?,?)",
                                (FEE_POLICY, address, amount, json.dumps(audit)))
                self.db.execute("""INSERT INTO balances VALUES(?,?) ON CONFLICT(address)
                    DO UPDATE SET amount=amount+excluded.amount""", (address, amount))
            self.db.execute("INSERT INTO meta VALUES('fee_policy',?)", (json.dumps(audit),))
            return audit

    @synchronized
    def payouts(self):
        return [dict(row) for row in self.db.execute("SELECT * FROM payouts ORDER BY created")]

    @synchronized
    def payout_status(self, txid, status):
        with self.db:
            self.db.execute("UPDATE payouts SET status=? WHERE id=?", (status, txid))

    @staticmethod
    def _miner_payout_join():
        # Parameterized JSON keys select only this recipient, including batched payments.
        return """FROM payouts p JOIN json_each(p.amounts) n ON n.key=?
            LEFT JOIN json_each(p.gross) g ON g.key=n.key
            LEFT JOIN json_each(p.fees) f ON f.key=n.key"""

    @staticmethod
    def _payment_totals(rows):
        count = sum(row["count"] for row in rows)
        legacy_count = sum(row["legacyCount"] for row in rows)
        known_gross = sum(int(row["knownGross"]) for row in rows)
        known_fee = sum(int(row["knownFee"]) for row in rows)
        return {"count": count, "netNanoZYRX": str(sum(int(row["net"]) for row in rows)),
                "grossNanoZYRX": None if legacy_count else str(known_gross),
                "feeNanoZYRX": None if legacy_count else str(known_fee),
                "knownGrossNanoZYRX": str(known_gross), "knownFeeNanoZYRX": str(known_fee),
                "legacyNetNanoZYRX": str(sum(int(row["legacyNet"]) for row in rows)),
                "legacyCount": legacy_count}

    def _miner_payouts(self, address, limit, offset, snapshot, confirmations):
        if type(limit) is not int or not 1 <= limit <= 100 or type(offset) is not int or not 0 <= offset <= 1_000_000:
            raise ValueError("Use limit 1..100 and offset 0..1000000")
        if snapshot is None:
            snapshot = self.db.execute("SELECT COALESCE(MAX(rowid),0) FROM payouts").fetchone()[0]
        if type(snapshot) is not int or not 0 <= snapshot <= MAX_NANO:
            raise ValueError("Invalid payout history snapshot")
        join = self._miner_payout_join()
        total = self.db.execute("SELECT COUNT(*) " + join + " WHERE p.rowid<=?", (address, snapshot)).fetchone()[0]
        rows = self.db.execute("""SELECT p.id,p.status,p.created,n.value net,g.value gross,f.value fee """ + join +
                               " WHERE p.rowid<=? ORDER BY p.created DESC,p.rowid DESC LIMIT ? OFFSET ?",
                               (address, snapshot, limit, offset)).fetchall()
        items = []
        for row in rows:
            known = row["gross"] is not None and row["fee"] is not None
            items.append({"id": row["id"], "status": row["status"], "created": row["created"],
                          "netNanoZYRX": str(row["net"]),
                          "grossNanoZYRX": str(row["gross"]) if known else None,
                          "feeNanoZYRX": str(row["fee"]) if known else None,
                          "feePolicy": FEE_POLICY if known else "legacy-prepaid-unknown",
                          "numConfirmations": None, "requiredConfirmations": confirmations,
                          "blockId": None, "inclusionHeight": None})
        has_more = offset + len(items) < total
        return {"address": address, "items": items, "total": total, "limit": limit, "offset": offset,
                "hasMore": has_more, "hasPrevious": offset > 0,
                "nextOffset": offset + limit if has_more else None,
                "previousOffset": max(0, offset - limit) if offset > 0 else None, "snapshot": str(snapshot)}

    @read_snapshot
    def miner_payouts(self, address, limit=20, offset=0, snapshot=None, confirmations=None):
        """Read complete address history without exposing signed transactions or other recipients."""
        return self._miner_payouts(address, limit, offset, snapshot, confirmations)

    @read_snapshot
    def miner_summary(self, address, connected=None, confirmations=None, now=None):
        """Read durable accounting; connected worker counts are an event-loop snapshot."""
        now = time.time() if now is None else now
        connected = connected or {}
        window_start = now - HASHRATE_WINDOW
        credit = self.db.execute("SELECT COALESCE(exact_sum(amount),'0'),COUNT(*) FROM credits WHERE address=?",
                                 (address,)).fetchone()
        refund = self.db.execute("SELECT COALESCE(exact_sum(amount),'0') FROM fee_adjustments WHERE address=?",
                                 (address,)).fetchone()[0]
        balance = self.db.execute("SELECT amount FROM balances WHERE address=?", (address,)).fetchone()
        payments = self.db.execute("""SELECT p.status,COUNT(*) count,COALESCE(exact_sum(n.value),'0') net,
            COALESCE(exact_sum(CASE WHEN g.value IS NOT NULL AND f.value IS NOT NULL THEN g.value END),'0') knownGross,
            COALESCE(exact_sum(CASE WHEN g.value IS NOT NULL AND f.value IS NOT NULL THEN f.value END),'0') knownFee,
            SUM(CASE WHEN g.value IS NULL OR f.value IS NULL THEN 1 ELSE 0 END) legacyCount,
            COALESCE(exact_sum(CASE WHEN g.value IS NULL OR f.value IS NULL THEN n.value END),'0') legacyNet,
            COALESCE(exact_sum(COALESCE(g.value,n.value)),'0') debit """ + self._miner_payout_join() +
                                   " GROUP BY p.status", (address,)).fetchall()
        confirmed = [row for row in payments if row["status"] == "confirmed"]
        pending = [row for row in payments if row["status"] in ("prepared", "broadcast")]
        shares = self.db.execute("""SELECT COUNT(*) accepted,MIN(created) first,MAX(created) last,
            SUM(CASE WHEN created>=? THEN 1 ELSE 0 END) recent,
            SUM(CASE WHEN created>=? THEN 1 ELSE 0 END) day,
            COALESCE(exact_sum(CASE WHEN created>=? THEN weight END),'0') hashes
            FROM shares WHERE address=?""", (window_start, now - 86400, window_start, address)).fetchone()
        worker_count = self.db.execute("SELECT COUNT(DISTINCT worker) FROM shares WHERE address=?", (address,)).fetchone()[0]
        workers = {}
        rows = self.db.execute("""SELECT worker,COUNT(*) accepted,MIN(created) first,MAX(created) last,
            SUM(CASE WHEN created>=? THEN 1 ELSE 0 END) recent,
            COALESCE(exact_sum(CASE WHEN created>=? THEN weight END),'0') hashes
            FROM shares WHERE address=? GROUP BY worker ORDER BY last DESC,worker LIMIT 100""",
                               (window_start, window_start, address)).fetchall()
        for row in rows:
            workers[row["worker"]] = {"name": row["worker"], "acceptedShares": row["accepted"],
                "windowAcceptedShares": row["recent"], "firstShare": row["first"], "lastShare": row["last"],
                "estimatedHashrate": int(row["hashes"]) / HASHRATE_WINDOW,
                "windowExpectedHashesExact": row["hashes"], "connectedSessions": connected.get(row["worker"], 0)}
        # An authorized worker can be connected before it submits its first share.
        unseen_connected = []
        for name, sessions in connected.items():
            if name not in workers:
                row = self.db.execute("""SELECT COUNT(*) accepted,MIN(created) first,MAX(created) last,
                    SUM(CASE WHEN created>=? THEN 1 ELSE 0 END) recent,
                    COALESCE(exact_sum(CASE WHEN created>=? THEN weight END),'0') hashes
                    FROM shares WHERE address=? AND worker=?""", (window_start, window_start, address, name)).fetchone()
                workers[name] = {"name": name, "acceptedShares": row["accepted"],
                    "windowAcceptedShares": row["recent"] or 0, "firstShare": row["first"], "lastShare": row["last"],
                    "estimatedHashrate": int(row["hashes"]) / HASHRATE_WINDOW,
                    "windowExpectedHashesExact": row["hashes"], "connectedSessions": sessions}
                if not row["accepted"]:
                    unseen_connected.append(name)
        for worker in workers.values():
            worker["active"] = worker["connectedSessions"] > 0 and worker["windowAcceptedShares"] > 0
        current = self.db.execute("""SELECT COUNT(*) accepted,
            COALESCE(exact_sum(CASE WHEN address=? THEN weight END),'0') minerWeight,
            COALESCE(exact_sum(weight),'0') poolWeight,
            SUM(CASE WHEN address=? THEN 1 ELSE 0 END) minerShares FROM shares WHERE round IS NULL""",
                                  (address, address)).fetchone()
        miner_weight, pool_weight = int(current["minerWeight"]), int(current["poolWeight"])
        candidates = self.db.execute("""SELECT COUNT(*) FROM blocks b WHERE status='submitted'
            AND EXISTS(SELECT 1 FROM shares s WHERE s.address=? AND s.round IS NULL AND s.id<=b.end_share)""",
                                    (address,)).fetchone()[0]
        recent = self._miner_payouts(address, 5, 0, None, confirmations)
        known = bool(shares["accepted"] or credit[1] or balance or payments or connected)
        return {"address": address, "known": known, "generatedAt": now,
                "account": {"creditedNanoZYRX": credit[0], "creditedBlocks": credit[1], "feeRefundNanoZYRX": refund,
                    "lifetimeCreditNanoZYRX": str(int(credit[0]) + int(refund)),
                    "unpaidNanoZYRX": str(balance[0] if balance else 0),
                    "reservedBalanceDebitNanoZYRX": str(sum(int(row["debit"]) for row in pending)),
                    "confirmedPaid": self._payment_totals(confirmed), "pendingPayout": self._payment_totals(pending),
                    "immatureEstimatedNanoZYRX": None, "immatureEstimateAvailable": False,
                    "immatureEstimateNote": "Submitted block rewards are not yet measured or credited and may be orphaned."},
                "mining": {"acceptedShares": shares["accepted"], "acceptedShares24h": shares["day"] or 0,
                    "windowAcceptedShares": shares["recent"] or 0, "firstShare": shares["first"], "lastShare": shares["last"],
                    "estimatedHashrate": int(shares["hashes"]) / HASHRATE_WINDOW,
                    "hashrateWindowSeconds": HASHRATE_WINDOW, "windowExpectedHashesExact": shares["hashes"],
                    "rejectedShares": None, "pendingCandidateBlocks": candidates,
                    "currentRound": {"acceptedShares": current["minerShares"] or 0,
                        "minerWeightExact": str(miner_weight), "poolWeightExact": str(pool_weight),
                        "sharePercent": miner_weight * 100 / pool_weight if pool_weight else 0}},
                "workers": sorted(workers.values(), key=lambda worker: (-worker["connectedSessions"],
                                -(worker["lastShare"] or 0), worker["name"])),
                "workerCount": worker_count + len(unseen_connected),
                "workersTruncated": worker_count > len(workers) - len(unseen_connected),
                "connectedWorkers": sum(connected.values()), "recentPayouts": recent["items"],
                "payoutCount": recent["total"], "payoutsLink": "/api/miner/" + address + "/payouts"}

    @synchronized
    def gpu_validation(self):
        row = self.db.execute("SELECT value FROM meta WHERE key='gpu_validation'").fetchone()
        if not row:
            return None
        evidence = json.loads(row[0])
        identity = self.db.execute("SELECT value FROM meta WHERE key='identity'").fetchone()
        if not identity or evidence.get("identity") != identity[0]:
            return None
        block = self.db.execute("SELECT status FROM blocks WHERE hash=?", (evidence.get("blockId"),)).fetchone()
        payout = self.db.execute("SELECT status,amounts FROM payouts WHERE id=?", (evidence.get("payoutId"),)).fetchone()
        if not block or block[0] != "confirmed" or not payout or payout[0] != "confirmed":
            return None
        if json.loads(payout[1]).get(evidence.get("address"), 0) <= 0:
            return None
        return evidence

    @synchronized
    def stats(self):
        now = time.time()
        rows = self.db.execute("SELECT weight FROM shares WHERE created>?", (now - 600,)).fetchall()
        count = self.db.execute("SELECT COUNT(*) FROM shares").fetchone()[0]
        first = self.db.execute("SELECT MIN(created) FROM shares").fetchone()[0]
        interval = min(600, max(1, now - (first or now)))
        workers = [dict(row) for row in self.db.execute("""SELECT address,worker,COUNT(*) accepted,
            MAX(created) lastShare FROM shares GROUP BY address,worker ORDER BY lastShare DESC LIMIT 100""")]
        blocks = self.blocks()
        payouts = [{k: v for k, v in row.items() if k != "raw"} for row in self.payouts()]
        for payout in payouts:
            for field in ("amounts", "gross", "fees"):
                values = json.loads(payout[field]) if payout[field] is not None else None
                payout[field + "Exact"] = {a: str(n) for a, n in values.items()} if values is not None else None
        marker = self.db.execute("SELECT value FROM meta WHERE key='fee_policy'").fetchone()
        migration = json.loads(marker[0]) if marker else None
        if migration:
            migration = {key: migration[key] for key in (
                "policy", "reservedNanoZYRX", "spentNanoZYRX", "refundedNanoZYRX")}
        refunds = {row[0]: row[1] for row in self.db.execute(
            "SELECT address,amount FROM fee_adjustments WHERE policy=? ORDER BY address", (FEE_POLICY,))}
        balances = self.balances()
        return {"acceptedShares": count, "estimatedHashrate": sum(int(row[0]) for row in rows) / interval,
                "blocks": blocks[-100:], "balancesNanoZYRX": balances, "payouts": payouts[-100:],
                "workers": workers, "halted": self.halted(), "gpuValidation": self.gpu_validation(),
                "feeMigration": migration, "feeRefundsNanoZYRX": refunds,
                "balancesNanoZYRXExact": {a: str(n) for a, n in balances.items()},
                "feeRefundsNanoZYRXExact": {a: str(n) for a, n in refunds.items()}}
