import sys
import sqlite3
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent / "scripts"))
import yfinance_day1_import as mod


def test_parse_tickers_comma_separated():
    raw = "SMH,GLD"
    tickers = [t.strip().upper() for t in raw.split(",") if t.strip()]
    assert tickers == ["SMH", "GLD"]


def test_parse_tickers_strips_whitespace_and_uppercases():
    raw = "  smh , GLD , URA  "
    tickers = [t.strip().upper() for t in raw.split(",") if t.strip()]
    assert tickers == ["SMH", "GLD", "URA"]


def test_parse_tickers_handles_empty_entries():
    raw = "SMH,,GLD,"
    tickers = [t.strip().upper() for t in raw.split(",") if t.strip()]
    assert tickers == ["SMH", "GLD"]


def test_upsert_candles_inserts_with_day_1_timeframe(tmp_path):
    db = tmp_path / "test.db"
    conn = sqlite3.connect(str(db))
    conn.execute("""
        CREATE TABLE candles (
            ticker TEXT, timeframe TEXT, ts_epoch INTEGER,
            open REAL, high REAL, low REAL, close REAL, volume INTEGER,
            PRIMARY KEY (ticker, timeframe, ts_epoch)
        )
    """)
    candles = [
        {"ts_epoch": 1700000000, "open": 100.0, "high": 101.0, "low": 99.0, "close": 100.5, "volume": 1000},
        {"ts_epoch": 1700086400, "open": 100.5, "high": 102.0, "low": 100.0, "close": 101.5, "volume": 1200},
    ]
    n = mod.upsert_candles(conn, "SMH", candles)
    assert n == 2
    rows = conn.execute("SELECT ticker, timeframe, ts_epoch, close FROM candles ORDER BY ts_epoch").fetchall()
    assert rows[0] == ("SMH", "DAY_1", 1700000000, 100.5)
    assert rows[1] == ("SMH", "DAY_1", 1700086400, 101.5)
    conn.close()


def test_upsert_candles_replaces_on_conflict(tmp_path):
    db = tmp_path / "test.db"
    conn = sqlite3.connect(str(db))
    conn.execute("""
        CREATE TABLE candles (
            ticker TEXT, timeframe TEXT, ts_epoch INTEGER,
            open REAL, high REAL, low REAL, close REAL, volume INTEGER,
            PRIMARY KEY (ticker, timeframe, ts_epoch)
        )
    """)
    candles_v1 = [{"ts_epoch": 1700000000, "open": 100.0, "high": 101.0, "low": 99.0, "close": 100.5, "volume": 1000}]
    candles_v2 = [{"ts_epoch": 1700000000, "open": 100.0, "high": 101.0, "low": 99.0, "close": 999.0, "volume": 9999}]
    mod.upsert_candles(conn, "SMH", candles_v1)
    mod.upsert_candles(conn, "SMH", candles_v2)
    row = conn.execute("SELECT close, volume FROM candles WHERE ts_epoch=1700000000").fetchone()
    assert row == (999.0, 9999)
    conn.close()