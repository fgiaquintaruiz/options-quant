import sqlite3
import time
from pathlib import Path

DB_PATH = Path(__file__).parent.parent / "data" / "candles.db"
RANGE_FROM = "2024-05-01"

def refresh_ticker_stats(db_path: Path = DB_PATH) -> dict:
    conn = sqlite3.connect(str(db_path), timeout=60.0)
    try:
        conn.execute("PRAGMA journal_mode = WAL")
        conn.execute("PRAGMA busy_timeout = 60000")
        
        start = time.time()
        now_epoch = int(time.time())
        
        sql = """
        INSERT OR REPLACE INTO ticker_stats (
          ticker,
          bars_day_1, bars_hour_1, bars_min_15, bars_min_5, bars_total,
          last_day_1, last_hour_1, last_min_15, last_min_5,
          first_day_1, first_hour_1, first_min_15, first_min_5,
          has_all_4_tfs,
          updated_at,
          active
        )
        SELECT
          t.ticker,
          COALESCE(SUM(CASE WHEN c.timeframe='DAY_1'  THEN 1 ELSE 0 END), 0),
          COALESCE(SUM(CASE WHEN c.timeframe='HOUR_1' THEN 1 ELSE 0 END), 0),
          COALESCE(SUM(CASE WHEN c.timeframe='MIN_15' THEN 1 ELSE 0 END), 0),
          COALESCE(SUM(CASE WHEN c.timeframe='MIN_5'  THEN 1 ELSE 0 END), 0),
          COUNT(c.ts_epoch),
          MAX(CASE WHEN c.timeframe='DAY_1'  THEN c.ts_epoch END),
          MAX(CASE WHEN c.timeframe='HOUR_1' THEN c.ts_epoch END),
          MAX(CASE WHEN c.timeframe='MIN_15' THEN c.ts_epoch END),
          MAX(CASE WHEN c.timeframe='MIN_5'  THEN c.ts_epoch END),
          MIN(CASE WHEN c.timeframe='DAY_1'  THEN c.ts_epoch END),
          MIN(CASE WHEN c.timeframe='HOUR_1' THEN c.ts_epoch END),
          MIN(CASE WHEN c.timeframe='MIN_15' THEN c.ts_epoch END),
          MIN(CASE WHEN c.timeframe='MIN_5'  THEN c.ts_epoch END),
          CASE
            WHEN SUM(CASE WHEN c.timeframe='DAY_1'  THEN 1 ELSE 0 END) > 0
             AND SUM(CASE WHEN c.timeframe='HOUR_1' THEN 1 ELSE 0 END) > 0
             AND SUM(CASE WHEN c.timeframe='MIN_15' THEN 1 ELSE 0 END) > 0
             AND SUM(CASE WHEN c.timeframe='MIN_5'  THEN 1 ELSE 0 END) > 0
            THEN 1 ELSE 0
          END,
          ?,
          t.active
        FROM tickers t
        LEFT JOIN candles c
          ON c.ticker = t.ticker
         AND c.ts_epoch >= strftime('%s', ?)
        GROUP BY t.ticker, t.active;
        """
        
        cursor = conn.execute(sql, (now_epoch, RANGE_FROM))
        conn.commit()
        
        # Count results
        cnt_row = conn.execute(
            "SELECT COUNT(*) FROM ticker_stats WHERE active=1 AND has_all_4_tfs=1"
        ).fetchone()
        complete_count = cnt_row[0] if cnt_row else 0
        
        total_row = conn.execute("SELECT COUNT(*) FROM ticker_stats").fetchone()
        total_count = total_row[0] if total_row else 0
        
        elapsed = time.time() - start
        return {
            "total_tickers": total_count,
            "complete_tickers": complete_count,
            "elapsed_seconds": round(elapsed, 2),
        }
    finally:
        conn.close()

if __name__ == "__main__":
    result = refresh_ticker_stats()
    print(f"[ticker_stats] refresh OK:")
    print(f"  - Total tickers: {result['total_tickers']}")
    print(f"  - Complete (4 TFs): {result['complete_tickers']}")
    print(f"  - Elapsed: {result['elapsed_seconds']}s")