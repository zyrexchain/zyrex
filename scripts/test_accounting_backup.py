"""WAL-consistent recovery preserves reserved balances and identical signed payouts."""
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch

from accounting_backup import create_backup, restore_backup, verify_backup
from ledger import Ledger
from test_pool import ADDRESS1, FEE_SCRIPT, MESSAGE, PK, payment
from node import payout_plan


class AccountingBackupTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.root = Path(self.folder.name)
        self.root.chmod(0o700)
        self.path = self.root / "pool.sqlite"
        self.ledger = Ledger(str(self.path))
        self.identity = "f" * 64 + ":" + PK
        self.ledger.bind_network("f" * 64, PK)
        for index in range(25):
            end = self.ledger.share(MESSAGE, str(index), ADDRESS1, "worker", index + 1)
        self.ledger.block({"msg": MESSAGE, "h": 1, "pk": PK}, "24", end)
        self.ledger.settle(self.ledger.blocks()[0], "canonical", 90_000_000_000)
        self.ledger.migrate_payout_fees(FEE_SCRIPT)
        gross, net, fees = payout_plan(self.ledger.balances(), 1_000_000_000, 1_000_000)
        self.signed = payment("a" * 64, net, 1_000_000)
        self.ledger.prepare_payout(self.signed, net, gross, fees)

    def tearDown(self):
        self.ledger.db.close()
        self.folder.cleanup()

    def test_uncheckpointed_wal_and_pending_signed_payout_survive_recovery(self):
        self.assertTrue(Path(str(self.path) + "-wal").exists())
        bundle, output = self.root / "snapshot", self.root / "recovered"
        report = create_backup(self.path, bundle, "pool")
        self.assertEqual(report["rows"]["shares"], 25)
        self.assertEqual(report["rows"]["payouts"], 1)
        restore_backup(bundle, output, self.identity)
        recovered = Ledger(str(output / "database.sqlite"))
        try:
            recovered.bind_network("f" * 64, PK)
            self.assertEqual(recovered.balances(), self.ledger.balances())
            original, restored = self.ledger.payouts()[0], recovered.payouts()[0]
            for field in ("id", "raw", "amounts", "gross", "fees", "status"):
                self.assertEqual(original[field], restored[field])
            self.assertEqual(json.loads(restored["raw"]), self.signed)
            self.assertTrue(recovered.has_pending_payouts())
        finally:
            recovered.db.close()
        for file in bundle.iterdir():
            self.assertEqual(file.stat().st_mode & 0o777, 0o600)

    def test_corrupt_snapshot_manifest_and_wrong_chain_fail_without_output(self):
        bundle = self.root / "snapshot"
        create_backup(self.path, bundle, "pool")
        with self.assertRaises(ValueError):
            restore_backup(bundle, self.root / "wrong", "another-chain:" + PK)
        self.assertFalse((self.root / "wrong").exists())
        manifest = bundle / "manifest.json"
        contents = manifest.read_text()
        report = json.loads(contents)
        report["rows"]["balances"] += 1
        manifest.write_text(json.dumps(report))
        with self.assertRaises(ValueError):
            verify_backup(bundle)
        manifest.write_text(contents)
        database = bundle / "database.sqlite"
        with database.open("r+b") as stream:
            stream.seek(-1, 2)
            stream.write(b"X")
        with self.assertRaises(ValueError):
            restore_backup(bundle, self.root / "corrupt", self.identity)
        self.assertFalse((self.root / "corrupt").exists())

    def test_existing_destinations_and_symlinks_are_never_replaced(self):
        bundle = self.root / "snapshot"
        create_backup(self.path, bundle, "pool")
        marker = bundle / "operator-file"
        marker.write_text("retain")
        for action in (lambda: create_backup(self.path, bundle, "pool"),
                       lambda: restore_backup(bundle, bundle, self.identity)):
            with self.assertRaises(ValueError):
                action()
        self.assertEqual(marker.read_text(), "retain")
        link = self.root / "link"
        link.symlink_to(bundle, target_is_directory=True)
        with self.assertRaises(ValueError):
            verify_backup(link)
        with self.assertRaises(ValueError):
            create_backup(self.path, link, "pool")

    def test_changed_valid_snapshot_is_rejected_before_recovery_publication(self):
        bundle, output = self.root / "snapshot", self.root / "recovered"
        create_backup(self.path, bundle, "pool")
        original_verify = verify_backup

        def replace_after_first_verification(path, identity=None):
            manifest = original_verify(path, identity)
            if Path(path) == bundle:
                report = dict(manifest, createdAt="changed-during-copy")
                (bundle / "manifest.json").write_text(json.dumps(report))
            return manifest

        with patch("accounting_backup.verify_backup", side_effect=replace_after_first_verification):
            with self.assertRaisesRegex(ValueError, "changed"):
                restore_backup(bundle, output, self.identity)
        self.assertFalse(output.exists())

    def test_public_parent_and_unpinned_database_are_rejected(self):
        public = self.root / "public"
        public.mkdir(mode=0o755)
        with self.assertRaises(ValueError):
            create_backup(self.path, public / "snapshot", "pool")
        other = self.root / "unbound.sqlite"
        database = Ledger(str(other))
        database.db.close()
        with self.assertRaises(ValueError):
            create_backup(other, self.root / "unbound", "pool")
        self.assertFalse((self.root / "unbound").exists())

    def test_explorer_snapshot_retains_its_genesis_and_foreign_keys(self):
        path = self.root / "explorer.sqlite"
        with sqlite3.connect(path) as database:
            database.executescript("""
                PRAGMA journal_mode=WAL;
                CREATE TABLE identity(genesis TEXT PRIMARY KEY);
                CREATE TABLE blocks(height INTEGER PRIMARY KEY,id TEXT,raw TEXT);
                CREATE TABLE transactions(id TEXT PRIMARY KEY,height INTEGER REFERENCES blocks(height),raw TEXT);
                CREATE TABLE boxes(id TEXT PRIMARY KEY,tx TEXT REFERENCES transactions(id));
                INSERT INTO identity VALUES('test-genesis');
                INSERT INTO blocks VALUES(1,'block','{}');
                INSERT INTO transactions VALUES('tx',1,'{}');
                INSERT INTO boxes VALUES('box','tx');
            """)
        bundle = self.root / "explorer-backup"
        report = create_backup(path, bundle, "explorer")
        self.assertEqual(report["rows"], {"blocks": 1, "transactions": 1, "boxes": 1})
        self.assertEqual(verify_backup(bundle, "test-genesis")["identity"], "test-genesis")


if __name__ == "__main__":
    unittest.main()
