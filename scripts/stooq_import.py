"""
Stooq CSV → SQLite importer.

Usage:
    python scripts/stooq_import.py [--daily-only | --hourly-only | --dry-run]

Reads all .txt files from:
    - data/stooq/hourly/         (hourly data, PER=60)
    - data/stooq/d_us_txt/data/daily/us/   (daily data, PER=D)

Inserts into data/candles.db using INSERT OR REPLACE (upsert).
"""
import argparse
import os
import sqlite3
import sys
import time
from calendar import timegm
from datetime import datetime, timezone

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------
PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DB_PATH = os.path.join(PROJECT_ROOT, "data", "candles.db")
HOURLY_BASE = os.path.join(PROJECT_ROOT, "data", "stooq", "hourly")
DAILY_BASE = os.path.join(PROJECT_ROOT, "data", "stooq", "daily")

TIMEFRAME_MAP = {
    "D": "DAY_1",
    "60": "HOUR_1",
    "5": "MIN_5",
    "15": "MIN_15",
}

CREATE_TABLE_SQL = """
CREATE TABLE IF NOT EXISTS candles (
    ticker    TEXT    NOT NULL,
    timeframe TEXT    NOT NULL,
    ts_epoch  INTEGER NOT NULL,
    open      REAL    NOT NULL,
    high      REAL    NOT NULL,
    low       REAL    NOT NULL,
    close     REAL    NOT NULL,
    volume    INTEGER NOT NULL,
    PRIMARY KEY (ticker, timeframe, ts_epoch)
) WITHOUT ROWID;
"""

UPSERT_SQL = """
INSERT OR REPLACE INTO candles (ticker, timeframe, ts_epoch, open, high, low, close, volume)
VALUES (?, ?, ?, ?, ?, ?, ?, ?)
"""


# ---------------------------------------------------------------------------
# Core functions
# ---------------------------------------------------------------------------

def parse_row(line: str):
    """
    Parse a single Stooq CSV data line.

    Returns:
        tuple (ticker, timeframe, ts_epoch, open, high, low, close, volume)
        or None if the line is malformed / a header / unknown PER.
    """
    line = line.strip()
    if not line:
        return None

    parts = line.split(",")
    if len(parts) < 9:
        return None

    raw_ticker, per, date_str, time_str = parts[0], parts[1], parts[2], parts[3]

    # Skip header
    if raw_ticker.startswith("<"):
        return None

    # Timeframe mapping
    timeframe = TIMEFRAME_MAP.get(per)
    if timeframe is None:
        return None

    # Ticker: strip .US suffix (and any other exchange suffix), uppercase
    ticker = raw_ticker.split(".")[0].upper()

    # Parse timestamp: YYYYMMDD + HHMMSS → UTC epoch
    try:
        dt = datetime(
            int(date_str[0:4]),
            int(date_str[4:6]),
            int(date_str[6:8]),
            int(time_str[0:2]),
            int(time_str[2:4]),
            int(time_str[4:6]),
            tzinfo=timezone.utc,
        )
        ts_epoch = int(dt.timestamp())
    except (ValueError, IndexError):
        return None

    # OHLCV
    try:
        open_ = float(parts[4])
        high = float(parts[5])
        low = float(parts[6])
        close = float(parts[7])
        volume = round(float(parts[8]))
    except ValueError:
        return None

    return (ticker, timeframe, ts_epoch, open_, high, low, close, volume)


def import_file(cursor, filepath: str) -> int:
    """
    Import all valid rows from a single Stooq .txt file.

    Args:
        cursor: sqlite3 cursor (table must already exist).
        filepath: absolute or relative path to the .txt file.

    Returns:
        Number of rows inserted/replaced.
    """
    inserted = 0
    try:
        with open(filepath, "r", encoding="utf-8", errors="replace") as fh:
            for line in fh:
                row = parse_row(line)
                if row is None:
                    continue
                cursor.execute(UPSERT_SQL, row)
                inserted += 1
    except OSError:
        pass  # Skip unreadable files silently
    return inserted


def walk_stooq_dir(base_dir: str):
    """
    Generator that yields paths to all .txt files under base_dir recursively.
    """
    for dirpath, _dirnames, filenames in os.walk(base_dir):
        for fname in filenames:
            if fname.lower().endswith(".txt"):
                yield os.path.join(dirpath, fname)


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description="Import Stooq CSV data into SQLite candles.db")
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--daily-only", action="store_true", help="Only import daily data")
    group.add_argument("--hourly-only", action="store_true", help="Only import hourly data")
    parser.add_argument("--dry-run", action="store_true", help="Parse files without writing to DB")
    args = parser.parse_args()

    t0 = time.time()

    conn = sqlite3.connect(DB_PATH)
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA synchronous=NORMAL")
    cursor = conn.cursor()
    cursor.executescript(CREATE_TABLE_SQL)
    conn.commit()

    daily_rows = 0
    hourly_rows = 0
    file_count = 0

    sources = []
    if not args.hourly_only:
        sources.append(("daily", DAILY_BASE))
    if not args.daily_only:
        sources.append(("hourly", HOURLY_BASE))

    for kind, base_dir in sources:
        if not os.path.isdir(base_dir):
            print(f"[WARN] Directory not found, skipping: {base_dir}", file=sys.stderr)
            continue

        for filepath in walk_stooq_dir(base_dir):
            file_count += 1

            if not args.dry_run:
                rows = import_file(cursor, filepath)
                if kind == "daily":
                    daily_rows += rows
                else:
                    hourly_rows += rows
            else:
                # Dry-run: count parse hits without DB writes
                try:
                    with open(filepath, "r", encoding="utf-8", errors="replace") as fh:
                        for line in fh:
                            row = parse_row(line)
                            if row is not None:
                                if kind == "daily":
                                    daily_rows += 1
                                else:
                                    hourly_rows += 1
                except OSError:
                    pass

            if file_count % 500 == 0:
                elapsed = time.time() - t0
                print(
                    f"  [{elapsed:6.1f}s] {file_count} files processed | "
                    f"Daily: {daily_rows:,}  Hourly: {hourly_rows:,}",
                    flush=True,
                )

            if not args.dry_run and file_count % 1000 == 0:
                conn.commit()

    if not args.dry_run:
        conn.commit()

    conn.close()

    elapsed = time.time() - t0
    total = daily_rows + hourly_rows
    print(
        f"\nDone — Daily: {daily_rows:,} rows | Hourly: {hourly_rows:,} rows | "
        f"Total: {total:,} rows | Time: {elapsed:.1f}s"
    )
    if args.dry_run:
        print("(dry-run mode — nothing written to DB)")


if __name__ == "__main__":
    main()
