"""
Tests for stooq_import.py — TDD: tests written FIRST, implementation after.
Run: python scripts/test_stooq_import.py
"""
import os
import sqlite3
import sys
import tempfile
import unittest

# Allow importing from scripts/ regardless of cwd
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from stooq_import import import_file, parse_row

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


def _make_cursor():
    """Create an in-memory SQLite connection + candles table, return (conn, cursor)."""
    conn = sqlite3.connect(":memory:")
    cursor = conn.cursor()
    cursor.executescript(CREATE_TABLE_SQL)
    return conn, cursor


class TestParseRowHourly(unittest.TestCase):
    """Test 1 — parse_row() parses a valid hourly line correctly."""

    def test_hourly_line(self):
        line = "AAPL.US,60,20240426,160000,168.349,169.171,168.01,168.566,3925663.5,0"
        result = parse_row(line)

        self.assertIsNotNone(result, "parse_row returned None for a valid hourly line")
        ticker, timeframe, ts_epoch, open_, high, low, close, volume = result

        # Ticker: strip .US suffix, uppercase
        self.assertEqual(ticker, "AAPL")

        # Timeframe mapping: 60 → HOUR_1
        self.assertEqual(timeframe, "HOUR_1")

        # Epoch: 2024-04-26 16:00:00 UTC
        # python: datetime(2024,4,26,16,0,0, tzinfo=timezone.utc).timestamp() == 1714147200
        self.assertEqual(ts_epoch, 1714147200)

        # Open / High / Low / Close
        self.assertAlmostEqual(open_, 168.349, places=3)
        self.assertAlmostEqual(high, 169.171, places=3)
        self.assertAlmostEqual(low, 168.01, places=3)
        self.assertAlmostEqual(close, 168.566, places=3)

        # Volume: float input → int
        self.assertIsInstance(volume, int)
        self.assertEqual(volume, 3925664)  # round(3925663.5)


class TestParseRowDaily(unittest.TestCase):
    """Test 2 — parse_row() parses a valid daily line correctly (PER=D → DAY_1)."""

    def test_daily_line(self):
        line = "AADR.US,D,20100721,000000,23.1646,23.1646,22.7969,22.7969,45503.68,0"
        result = parse_row(line)

        self.assertIsNotNone(result, "parse_row returned None for a valid daily line")
        ticker, timeframe, ts_epoch, open_, high, low, close, volume = result

        self.assertEqual(ticker, "AADR")
        self.assertEqual(timeframe, "DAY_1")

        # 2010-07-21 00:00:00 UTC → 1279670400
        self.assertEqual(ts_epoch, 1279670400)

        self.assertAlmostEqual(open_, 23.1646, places=4)
        self.assertAlmostEqual(close, 22.7969, places=4)

        self.assertIsInstance(volume, int)
        self.assertEqual(volume, 45504)  # round(45503.68)


class TestParseRowMalformed(unittest.TestCase):
    """Test 3 — parse_row() returns None for malformed lines (no crash)."""

    def test_header_line_returns_none(self):
        self.assertIsNone(parse_row("<TICKER>,<PER>,<DATE>,<TIME>,<OPEN>,<HIGH>,<LOW>,<CLOSE>,<VOL>,<OPENINT>"))

    def test_empty_line_returns_none(self):
        self.assertIsNone(parse_row(""))

    def test_too_few_columns_returns_none(self):
        self.assertIsNone(parse_row("AAPL.US,60,20240426"))

    def test_non_numeric_open_returns_none(self):
        self.assertIsNone(parse_row("AAPL.US,60,20240426,160000,BAD,169.171,168.01,168.566,3925663.5,0"))

    def test_unknown_per_returns_none(self):
        # PER value not in the mapping → should return None
        self.assertIsNone(parse_row("AAPL.US,999,20240426,160000,168.349,169.171,168.01,168.566,3925663.5,0"))


class TestImportFile(unittest.TestCase):
    """Test 4 — import_file() inserts N rows into an in-memory SQLite DB."""

    def _make_temp_file(self, lines):
        tf = tempfile.NamedTemporaryFile(mode="w", suffix=".txt", delete=False, encoding="utf-8")
        tf.write("\n".join(lines) + "\n")
        tf.close()
        return tf.name

    def test_import_inserts_rows(self):
        csv_lines = [
            "<TICKER>,<PER>,<DATE>,<TIME>,<OPEN>,<HIGH>,<LOW>,<CLOSE>,<VOL>,<OPENINT>",
            "AAPL.US,60,20240426,160000,168.349,169.171,168.01,168.566,3925663.5,0",
            "AAPL.US,60,20240426,170000,168.576,169.635,168.497,169.545,4910034.3,0",
            "MSFT.US,60,20240426,160000,415.2,416.1,414.9,415.8,1234567.0,0",
        ]
        path = self._make_temp_file(csv_lines)
        try:
            conn, cursor = _make_cursor()
            inserted = import_file(cursor, path)
            conn.commit()

            self.assertEqual(inserted, 3, f"Expected 3 rows inserted, got {inserted}")

            cursor.execute("SELECT COUNT(*) FROM candles")
            count = cursor.fetchone()[0]
            self.assertEqual(count, 3)
        finally:
            os.unlink(path)

    def test_import_skips_malformed_rows(self):
        csv_lines = [
            "<TICKER>,<PER>,<DATE>,<TIME>,<OPEN>,<HIGH>,<LOW>,<CLOSE>,<VOL>,<OPENINT>",
            "AAPL.US,60,20240426,160000,168.349,169.171,168.01,168.566,3925663.5,0",
            "THIS_IS_GARBAGE",
            "",
        ]
        path = self._make_temp_file(csv_lines)
        try:
            conn, cursor = _make_cursor()
            inserted = import_file(cursor, path)
            conn.commit()

            self.assertEqual(inserted, 1)
        finally:
            os.unlink(path)


class TestImportFileIdempotent(unittest.TestCase):
    """Test 5 — import_file() is idempotent (upsert: running twice = same row count)."""

    def _make_temp_file(self, lines):
        tf = tempfile.NamedTemporaryFile(mode="w", suffix=".txt", delete=False, encoding="utf-8")
        tf.write("\n".join(lines) + "\n")
        tf.close()
        return tf.name

    def test_double_import_no_duplicates(self):
        csv_lines = [
            "<TICKER>,<PER>,<DATE>,<TIME>,<OPEN>,<HIGH>,<LOW>,<CLOSE>,<VOL>,<OPENINT>",
            "AAPL.US,60,20240426,160000,168.349,169.171,168.01,168.566,3925663.5,0",
            "AAPL.US,60,20240426,170000,168.576,169.635,168.497,169.545,4910034.3,0",
        ]
        path = self._make_temp_file(csv_lines)
        try:
            conn, cursor = _make_cursor()

            # First import
            import_file(cursor, path)
            conn.commit()

            # Second import — same file
            import_file(cursor, path)
            conn.commit()

            cursor.execute("SELECT COUNT(*) FROM candles")
            count = cursor.fetchone()[0]
            self.assertEqual(count, 2, f"Expected 2 rows after double import (upsert), got {count}")
        finally:
            os.unlink(path)

    def test_updated_values_on_reimport(self):
        """Re-importing with different OHLCV values replaces (not duplicates) the row."""
        original = [
            "<TICKER>,<PER>,<DATE>,<TIME>,<OPEN>,<HIGH>,<LOW>,<CLOSE>,<VOL>,<OPENINT>",
            "AAPL.US,60,20240426,160000,168.349,169.171,168.01,168.566,3925663.5,0",
        ]
        updated = [
            "<TICKER>,<PER>,<DATE>,<TIME>,<OPEN>,<HIGH>,<LOW>,<CLOSE>,<VOL>,<OPENINT>",
            "AAPL.US,60,20240426,160000,999.0,999.1,998.9,999.05,1111111.0,0",
        ]
        path1 = self._make_temp_file(original)
        path2 = self._make_temp_file(updated)
        try:
            conn, cursor = _make_cursor()

            import_file(cursor, path1)
            import_file(cursor, path2)
            conn.commit()

            cursor.execute("SELECT close FROM candles WHERE ticker='AAPL' AND timeframe='HOUR_1'")
            row = cursor.fetchone()
            self.assertIsNotNone(row)
            self.assertAlmostEqual(row[0], 999.05, places=2)
        finally:
            os.unlink(path1)
            os.unlink(path2)


class TestDailyBasePath(unittest.TestCase):
    """Test 6 — DAILY_BASE path exists and contains .txt files."""

    def test_daily_base_dir_exists(self):
        from stooq_import import DAILY_BASE
        self.assertTrue(
            os.path.isdir(DAILY_BASE),
            f"DAILY_BASE does not exist: {DAILY_BASE}"
        )

    def test_daily_base_has_txt_files(self):
        from stooq_import import DAILY_BASE
        txt_files = [
            f for dirpath, _, files in os.walk(DAILY_BASE)
            for f in files if f.endswith(".txt")
        ]
        self.assertGreater(len(txt_files), 0, f"No .txt files found under {DAILY_BASE}")


if __name__ == "__main__":
    unittest.main(verbosity=2)
