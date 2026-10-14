"""Consistent SQLite snapshots and non-overwriting recovery for pool/explorer databases."""
import argparse
from contextlib import closing
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import sqlite3
import tempfile
import time

FORMAT = "zyrex-sqlite-backup-v1"


def private_directory(path):
    path = Path(path)
    if path.is_symlink() or not path.is_dir():
        raise ValueError("Backup parent must be an existing private directory")
    stat = path.stat()
    if stat.st_uid != os.geteuid() or stat.st_mode & 0o077:
        raise ValueError("Backup parent must be owner-only and owned by this user")
    return path.resolve()


def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1048576), b""):
            value.update(chunk)
    return value.hexdigest()


def database_info(path, kind):
    with closing(sqlite3.connect(path.resolve().as_uri() + "?mode=ro", uri=True, timeout=5)) as database:
        database.execute("PRAGMA query_only=ON")
        database.execute("BEGIN")
        if database.execute("PRAGMA integrity_check").fetchall() != [("ok",)]:
            raise ValueError("Database failed its integrity check")
        if database.execute("PRAGMA foreign_key_check").fetchone() is not None:
            raise ValueError("Database has invalid foreign keys")
        if kind == "pool":
            rows = database.execute("SELECT value FROM meta WHERE key='identity'").fetchall()
            tables = ("shares", "blocks", "credits", "balances", "payouts", "fee_adjustments")
        elif kind == "explorer":
            rows = database.execute("SELECT genesis FROM identity").fetchall()
            tables = ("blocks", "transactions", "boxes")
        else:
            raise ValueError("Backup kind must be pool or explorer")
        if len(rows) != 1 or not isinstance(rows[0][0], str) or not rows[0][0]:
            raise ValueError("Database has no unique pinned network identity")
        return {"identity": rows[0][0], "rows": {table: database.execute("SELECT COUNT(*) FROM " + table).fetchone()[0]
                                                for table in tables}}


def sync_file(path):
    with path.open("rb") as stream:
        os.fsync(stream.fileno())


def sync_directory(path):
    descriptor = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def publish_directory(temporary, destination):
    # mkdir is an exclusive reservation: a concurrent operator cannot overwrite an existing backup.
    destination.mkdir(mode=0o700)
    try:
        for path in temporary.iterdir():
            os.replace(path, destination / path.name)
        sync_directory(destination)
        sync_directory(destination.parent)
    except BaseException:
        shutil.rmtree(destination)
        raise


def create_backup(database, destination, kind, timeout=30):
    database, destination = Path(database), Path(destination)
    parent = private_directory(destination.parent)
    if destination.exists() or destination.is_symlink() or database.is_symlink() or not database.is_file():
        raise ValueError("Source must be a regular database and destination must not exist")
    deadline = time.monotonic() + timeout
    with tempfile.TemporaryDirectory(prefix=".zyrex-backup-", dir=parent) as folder:
        temporary = Path(folder)
        snapshot = temporary / "database.sqlite"
        source = sqlite3.connect(database.resolve().as_uri() + "?mode=ro", uri=True, timeout=5)
        target = sqlite3.connect(snapshot)
        snapshot.chmod(0o600)
        try:
            def progress(status, remaining, total):
                if time.monotonic() >= deadline:
                    raise TimeoutError("SQLite backup exceeded its time budget")
            source.backup(target, pages=256, progress=progress, sleep=0.05)
            target.execute("PRAGMA journal_mode=DELETE")
        finally:
            source.close()
            target.close()
        info = database_info(snapshot, kind)
        manifest = dict(info, format=FORMAT, kind=kind, createdAt=datetime.now(timezone.utc).isoformat(),
                        databaseSha256=digest(snapshot), databaseBytes=snapshot.stat().st_size,
                        scope="Database only; wallet recovery secrets and native node data are excluded")
        metadata = temporary / "manifest.json"
        with metadata.open("x") as output:
            os.fchmod(output.fileno(), 0o600)
            json.dump(manifest, output, indent=2)
            output.write("\n")
            output.flush()
            os.fsync(output.fileno())
        sync_file(snapshot)
        publish_directory(temporary, destination)
    return manifest


def verify_backup(bundle, expected_identity=None):
    bundle = Path(bundle)
    private_directory(bundle)
    database, metadata = bundle / "database.sqlite", bundle / "manifest.json"
    for path in (database, metadata):
        if path.is_symlink() or not path.is_file() or path.stat().st_mode & 0o077:
            raise ValueError("Backup files must be owner-only regular files")
    if metadata.stat().st_size > 16384:
        raise ValueError("Backup manifest is oversized")
    manifest = json.loads(metadata.read_text())
    if manifest.get("format") != FORMAT or manifest.get("databaseBytes") != database.stat().st_size \
            or manifest.get("databaseSha256") != digest(database):
        raise ValueError("Backup format, size or checksum does not match")
    info = database_info(database, manifest.get("kind"))
    if info["identity"] != manifest.get("identity") or info["rows"] != manifest.get("rows"):
        raise ValueError("Backup identity or table counts do not match")
    if expected_identity is not None and info["identity"] != expected_identity:
        raise ValueError("Backup belongs to a different network or pool mining key")
    return manifest


def restore_backup(bundle, destination, expected_identity):
    if not expected_identity:
        raise ValueError("Recovery requires an explicit expected network identity")
    manifest = verify_backup(bundle, expected_identity)
    destination = Path(destination)
    parent = private_directory(destination.parent)
    if destination.exists() or destination.is_symlink():
        raise ValueError("Recovery destination must not exist; live databases are never overwritten")
    with tempfile.TemporaryDirectory(prefix=".zyrex-recovery-", dir=parent) as folder:
        temporary = Path(folder)
        # Recheck the copied bytes, not just an earlier path hash, before publishing.
        for name in ("database.sqlite", "manifest.json"):
            source = Path(bundle) / name
            target = temporary / name
            with source.open("rb") as input_file, target.open("xb") as output:
                os.fchmod(output.fileno(), 0o600)
                shutil.copyfileobj(input_file, output, 1048576)
                output.flush()
                os.fsync(output.fileno())
        copied_manifest = verify_backup(temporary, expected_identity)
        if copied_manifest != manifest:
            raise ValueError("Backup changed while recovery was being prepared")
        publish_directory(temporary, destination)
    return copied_manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    create = commands.add_parser("create")
    create.add_argument("--database", required=True, type=Path)
    create.add_argument("--output", required=True, type=Path)
    create.add_argument("--kind", required=True, choices=("pool", "explorer"))
    verify = commands.add_parser("verify")
    verify.add_argument("--backup", required=True, type=Path)
    verify.add_argument("--identity")
    restore = commands.add_parser("restore")
    restore.add_argument("--backup", required=True, type=Path)
    restore.add_argument("--output", required=True, type=Path)
    restore.add_argument("--identity", required=True)
    args = parser.parse_args()
    if args.command == "create":
        result = create_backup(args.database, args.output, args.kind)
    elif args.command == "verify":
        result = verify_backup(args.backup, args.identity)
    else:
        result = restore_backup(args.backup, args.output, args.identity)
    print(json.dumps(result))


if __name__ == "__main__":
    main()
