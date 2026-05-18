import argparse
import json
import sqlite3
import time
import urllib.parse
import urllib.request
from datetime import date, timedelta
from pathlib import Path

PROJECT_ROOT = Path(__file__).parent.parent
DB_PATH = PROJECT_ROOT / "data" / "candles.db"
SIDECAR_URL = "http://localhost:8001"
HISTORY_DAYS = 730


def fetch_day1(ticker, from_date, to_date):
    params = urllib.parse.urlencode({"from": from_date, "to": to_date, "interval": "1d"})
    url = f"{SIDECAR_URL}/api/v1/historical/{ticker}?{params}"
    with urllib.request.urlopen(url, timeout=60) as resp:
        return json.loads(resp.read())


def upsert_candles(conn, ticker, candles):
    rows = [(ticker, "DAY_1", c["ts_epoch"], c["open"], c["high"], c["low"], c["close"], c["volume"])
            for c in candles]
    conn.executemany(
        "INSERT OR REPLACE INTO candles (ticker, timeframe, ts_epoch, open, high, low, close, volume) VALUES (?,?,?,?,?,?,?,?)",
        rows
    )
    conn.commit()
    return len(rows)


def main():
    parser = argparse.ArgumentParser(description="Baja DAY_1 vía sidecar yfinance")
    parser.add_argument("--tickers", required=True, help="Comma-separated tickers")
    args = parser.parse_args()

    tickers = [t.strip().upper() for t in args.tickers.split(",") if t.strip()]
    from_date = (date.today() - timedelta(days=HISTORY_DAYS)).isoformat()
    to_date = date.today().isoformat()

    print(f"[INFO] DAY_1 para {len(tickers)} tickers: {tickers}")
    print(f"[INFO] Rango: {from_date} -> {to_date}")
    print(f"[INFO] Sidecar: {SIDECAR_URL}")

    conn = sqlite3.connect(str(DB_PATH))
    try:
        counts = {"DONE": 0, "EMPTY": 0, "ERROR": 0}
        for ticker in tickers:
            try:
                candles = fetch_day1(ticker, from_date, to_date)
                if not candles:
                    print(f"[EMPTY] {ticker}")
                    counts["EMPTY"] += 1
                    continue
                bars = upsert_candles(conn, ticker, candles)
                print(f"[DONE]  {ticker} -- {bars} bars")
                counts["DONE"] += 1
            except Exception as e:
                print(f"[ERROR] {ticker} -- {e}")
                counts["ERROR"] += 1
            time.sleep(0.5)
        print(f"\n[RESUMEN] DONE={counts['DONE']} EMPTY={counts['EMPTY']} ERROR={counts['ERROR']}")
    finally:
        conn.close()


if __name__ == "__main__":
    main()