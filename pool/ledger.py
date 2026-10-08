"""Durable PROP accounting in integer nanoZYRX and integer expected hashes."""
import json
import sqlite3
import time
import threading
from functools import wraps


def synchronized(method):
    @wraps(method)
    def call(self, *args, **kwargs):
        with self.lock:
            return method(self, *args, **kwargs)
    return call


class Ledger:
    def __init__(self, filename):
        self.lock = threading.RLock()
        self.db = sqlite3.connect(filename, check_same_thread=False)
        self.db.row_factory = sqlite3.Row
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
            CREATE INDEX IF NOT EXISTS shares_created ON shares(created);
        """)

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
    def settle(self, block, block_hash, reward, fee):
        """Freeze a round only when a block is canonical and mature; orphan work carries on.

        The transaction fee is divided across miners; the pool charges no commission.
        Largest remainder allocation conserves every nanoZYRX.
        """
        if reward <= fee:
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
            budget = reward - fee
            amounts = {address: budget * weight // total for address, weight in weights.items()}
            residues = sorted(weights, key=lambda a: (-(budget * weights[a] % total), a))
            for address in residues[:budget - sum(amounts.values())]:
                amounts[address] += 1
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
    def prepare_payout(self, tx, amounts):
        """Persist signed transaction BEFORE sending; retries always use the same txid."""
        with self.db:
            for address, amount in amounts.items():
                cursor = self.db.execute("UPDATE balances SET amount=amount-? WHERE address=? AND amount>=?",
                                         (amount, address, amount))
                if cursor.rowcount != 1:
                    raise RuntimeError("Payout exceeds credited balance")
            self.db.execute("INSERT INTO payouts(id,raw,amounts,created) VALUES(?,?,?,?)",
                            (tx["id"], json.dumps(tx), json.dumps(amounts), time.time()))

    @synchronized
    def payouts(self):
        return [dict(row) for row in self.db.execute("SELECT * FROM payouts ORDER BY created")]

    @synchronized
    def payout_status(self, txid, status):
        with self.db:
            self.db.execute("UPDATE payouts SET status=? WHERE id=?", (status, txid))

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
        return {"acceptedShares": count, "estimatedHashrate": sum(int(row[0]) for row in rows) / interval,
                "blocks": blocks[-100:], "balancesNanoZYRX": self.balances(), "payouts": payouts[-100:],
                "workers": workers, "halted": self.halted(), "gpuValidation": self.gpu_validation()}
