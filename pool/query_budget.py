"""Bounded public SQLite reads; an overloaded query never holds a mining writer lock."""
import sqlite3
import time
from contextlib import contextmanager


class PublicQueryLimit(sqlite3.OperationalError):
    pass


@contextmanager
def query_budget(db, seconds=0.5, steps=2_000_000):
    deadline = time.monotonic() + seconds
    remaining = steps

    def progress():
        nonlocal remaining
        remaining -= 1000
        return remaining <= 0 or time.monotonic() >= deadline

    db.set_progress_handler(progress, 1000)
    try:
        yield
    except sqlite3.OperationalError as error:
        if str(error) == "interrupted":
            raise PublicQueryLimit("Pool query budget exceeded; retry later") from error
        raise
    finally:
        db.set_progress_handler(None, 0)
