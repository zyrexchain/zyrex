"""Additive, durable chain verification checkpoints and bounded maintenance reads."""
import json
import time


class ChainLedger:
    def _migrate_canonical_claims(self):
        """Claim unique legacy hashes without changing historical credits or signed payments."""
        with self.lock, self.db:
            ambiguity = None
            for row in self.db.execute("""SELECT hash,MIN(id) block,COUNT(*) count FROM blocks
                WHERE status='confirmed' GROUP BY hash COLLATE NOCASE"""):
                if not row["hash"] or row["count"] != 1:
                    ambiguity = row if ambiguity is None else ambiguity
                    continue
                existing = self.db.execute("SELECT block FROM canonical_claims WHERE hash=?", (row["hash"],)).fetchone()
                if existing and existing[0] != row["block"]:
                    ambiguity = row if ambiguity is None else ambiguity
                    continue
                self.db.execute("INSERT OR IGNORE INTO canonical_claims VALUES(?,?)", (row["hash"], row["block"]))
            if ambiguity:
                reason = "Historical canonical reward attribution is ambiguous. Review credits before resuming payouts."
                self.db.execute("INSERT OR REPLACE INTO meta VALUES('halt',?)", (reason,))
                if not self.db.execute("SELECT 1 FROM meta WHERE key='canonical_claim_ambiguity'").fetchone():
                    self.db.execute("""INSERT INTO chain_events(block,expected_hash,actual_hash,reason,created)
                        VALUES(?,?,?,?,?)""", (ambiguity["block"], ambiguity["hash"], ambiguity["hash"], reason, time.time()))
                    self.db.execute("INSERT INTO meta VALUES('canonical_claim_ambiguity',?)", (reason,))
            self.db.execute("INSERT OR REPLACE INTO meta VALUES('canonical_claim_version','1')")

    def chain_state(self):
        with self.lock:
            row = self.db.execute("SELECT value FROM meta WHERE key='chain_verification'").fetchone()
            return json.loads(row[0]) if row else None

    def save_chain_state(self, state):
        with self.lock, self.db:
            self.db.execute("INSERT OR REPLACE INTO meta VALUES('chain_verification',?)", (json.dumps(state),))

    def chain_halt(self, block, actual_hash, reason):
        """Record evidence and stop atomically; paid credits cannot safely be auto-reversed."""
        with self.lock, self.db:
            self.db.execute("""INSERT INTO chain_events(block,expected_hash,actual_hash,reason,created)
                VALUES(?,?,?,?,?)""", (block["id"], block["hash"], actual_hash, reason, time.time()))
            self.db.execute("INSERT OR REPLACE INTO meta VALUES('halt',?)", (reason,))

    def maintenance_boundary(self):
        with self.lock:
            return self.db.execute("SELECT COALESCE(MAX(id),0) FROM blocks").fetchone()[0]

    def maintenance_blocks(self, cursor=0, limit=16, minimum_height=None, boundary=None):
        with self.lock:
            where, params = "id>?", [cursor]
            if minimum_height is not None:
                where += " AND height>=?"
                params.append(minimum_height)
            if boundary is not None:
                where += " AND id<=?"
                params.append(boundary)
            params.append(limit)
            return [dict(row) for row in self.db.execute(
                "SELECT * FROM blocks WHERE " + where + " ORDER BY id LIMIT ?", params)]

    def maintenance_recent_blocks(self, height, block_id, minimum, limit=16):
        with self.lock:
            if height < minimum:
                height, block_id = minimum, 0
            return [dict(row) for row in self.db.execute("""SELECT * FROM blocks
                WHERE (height,id)>(?,?) ORDER BY height,id LIMIT ?""", (height, block_id, limit))]

    def maintenance_payout_boundary(self):
        with self.lock:
            return self.db.execute("SELECT COALESCE(MAX(rowid),0) FROM payouts").fetchone()[0]

    def maintenance_payouts(self, cursor=0, limit=4, boundary=None, pending=False):
        with self.lock:
            where, params = "rowid>?", [cursor]
            if boundary is not None:
                where += " AND rowid<=?"
                params.append(boundary)
            if pending:
                where += " AND status IN ('prepared','broadcast')"
            params.append(limit)
            return [dict(row) for row in self.db.execute(
                "SELECT rowid checkpoint,* FROM payouts WHERE " + where + " ORDER BY rowid LIMIT ?", params)]

    def has_pending_payouts(self):
        with self.lock:
            return self.db.execute("SELECT 1 FROM payouts WHERE status IN ('prepared','broadcast') LIMIT 1").fetchone() is not None

    def _freeze_weights(self, block, fallback=False):
        rows = self.db.execute("SELECT address,weight FROM block_weights WHERE block=?", (block["id"],)).fetchall()
        if rows:
            return {row[0]: int(row[1]) for row in rows}
        if fallback:
            # Older orphan records have no snapshot. Recover their original interval,
            # including work already carried into a later canonical round.
            start = self.db.execute("""SELECT COALESCE(MAX(end_share),0) FROM blocks
                WHERE status='confirmed' AND end_share<?""", (block["end_share"],)).fetchone()[0]
            shares = self.db.execute("SELECT address,weight FROM shares WHERE id>? AND id<=?",
                                     (start, block["end_share"]))
        else:
            shares = self.db.execute("SELECT address,weight FROM shares WHERE round IS NULL AND id<=?",
                                     (block["end_share"],))
        weights = {}
        for row in shares:
            weights[row[0]] = weights.get(row[0], 0) + int(row[1])
        self.db.executemany("INSERT INTO block_weights VALUES(?,?,?)",
                            ((block["id"], address, str(weight)) for address, weight in weights.items()))
        return weights
