# Historical Backfill

## Overview

The backfill mode downloads `DAY_1` historical candles for all configured tickers and stores them in SQLite (`data/candles.db`).

Data comes from two sources:

- **TWS (IBKR)**: used for recent chunks (within `cutoff-years`).
- **yfinance sidecar** (Python FastAPI): used for older chunks beyond the TWS limit. This is necessary because IBKR restricts how far back `DAY_1` data can be requested in a single session.

Only chunks that overlap a configured **period window** are downloaded. Everything else is skipped with an `OUT_OF_RANGE` log entry. This keeps the database focused on the market regimes that matter for backtesting (crises, volatility spikes, baseline periods) instead of storing unstructured years of data.

Before each yfinance request the chunk is **trimmed to the exact intersection with the matching period** — the sidecar never receives dates outside the window.

---

## Prerequisites

1. **Python sidecar** running on port 8001 (required for pre-cutoff chunks):
   ```bash
   cd python
   python -m uvicorn analytics_service.main:app --host 127.0.0.1 --port 8001
   ```

2. **TWS or IB Gateway** running and logged in (required for post-cutoff chunks). Set port in `application.yml` under `ibkr.port`.

3. Python dependencies installed:
   ```bash
   pip install -r python/requirements.txt
   ```

---

## Configuration

All backfill settings live under `candles.backfill` in `application.yml`:

```yaml
candles:
  store: sqlite
  backfill:
    rate-per-second: 0.1        # 1 TWS request per 10s (within 60 req/10min IBKR limit)
    start-year: 2007             # Floor year for chunk generation
    yfinance:
      enabled: true
      cutoff-years: 0            # Chunks older than this (in years) go to yfinance; 0 = all via yfinance
    periods:
      - from: "2008-09"
        to: "2009-03"
      - from: "2018-01"
        to: "2018-03"
      - from: "2019-01"
        to: "2019-12"
      - from: "2020-02"
        to: "2020-05"
      - from: "2022-01"
        to: "2022-11"
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `rate-per-second` | `0.1` | TWS request rate — 0.1 = 1 req/10s, safe under IBKR 60 req/10min limit |
| `start-year` | `2007` | Earliest year chunk generation starts from |
| `yfinance.enabled` | `true` | Enable/disable the yfinance fallback path |
| `yfinance.cutoff-years` | `0` | Age threshold in years. Chunks older than this go to yfinance; 0 = all chunks go to yfinance |
| `periods` | see above | List of `{from, to}` windows (YYYY-MM format). Only chunks overlapping these windows are downloaded |

---

## Running the backfill

1. Start the Python sidecar (new terminal):
   ```bash
   cd python
   python -m uvicorn analytics_service.main:app --host 127.0.0.1 --port 8001
   ```

2. Run the backfill:
   ```bash
   ./gradlew bootRun --args='--backfill'
   ```

The service iterates all configured tickers, generates year-sized chunks from `start-year` to the present, and processes each chunk that overlaps a configured period. Progress is checkpointed atomically in `download_progress` (SQLite) so a restart continues from where it left off.

---

## Verifying results

### Java logs

Look for these patterns:

| Pattern | Meaning |
|---------|---------|
| `OUT_OF_RANGE` | Chunk had no overlap with any configured period — skipped intentionally |
| `[yfinance]` | Request dispatched to the Python sidecar |
| `COMPLETE_YFINANCE` | Chunk downloaded successfully via yfinance |
| `COMPLETE` | Chunk downloaded successfully via TWS |

A healthy run shows many `OUT_OF_RANGE` entries (all non-period chunks) and a smaller set of `[yfinance]` + `COMPLETE_YFINANCE` entries for the configured windows.

### Sidecar logs

Each yfinance request should show a trimmed date range:
```
GET /api/v1/historical/{ticker}?from=YYYY-MM-DD&to=YYYY-MM-DD&interval=1d
```

The `to` date must match the **period end**, not the chunk end. If you see `to` dates that extend beyond the configured period boundary, trimming is broken.

> **⚠️ Sidecar URL is INTERNAL**: `GET /api/v1/historical/{ticker}` shown in sidecar logs is called by Java internally. Do not call it directly from Postman.
> To query historical candles from SQLite, use the Java endpoint: `GET /api/v1/historical/{ticker}?from=YYYY-MM-DD&to=YYYY-MM-DD&interval=1d`

### SQLite

Query the download progress table:
```bash
sqlite3 data/candles.db \
  "SELECT ticker, timeframe, datetime(last_chunk_end_ts, 'unixepoch') as last_end, status
   FROM download_progress
   ORDER BY ticker, timeframe"
```

Expected `status` values after a successful backfill: `COMPLETE` (TWS) or `COMPLETE_YFINANCE` (yfinance path).

---

## Adding new periods

Add an entry to the `periods` list in `application.yml`:

```yaml
candles:
  backfill:
    periods:
      - from: "2025-07"
        to: "2025-09"
```

Format is `YYYY-MM`. The backfill will include any chunk whose date range overlaps `[from-01, to-last-day]`. Restart the backfill after adding — the new period will be processed for any ticker/chunk not yet marked `COMPLETE` or `COMPLETE_YFINANCE`.

---

## Troubleshooting

**All chunks show `OUT_OF_RANGE`, nothing downloads**

The configured periods do not overlap the chunks being generated. Check that `start-year` is at or before the earliest period `from` year. Current start-year is `2007`, which covers the `2008-09` period.

**No sidecar calls appear in logs**

Either `yfinance.enabled: false` or `cutoff-years` is set higher than the age of all configured period chunks. Set `cutoff-years: 0` to route all DAY_1 chunks through yfinance regardless of age.

**2008-2009 period missing from SQLite**

The `start-year` was set too high (e.g., `2018`). Set `start-year: 2007` to generate chunks that reach back to `2008-09`. Restart the backfill.

**Sidecar returns 422 or date errors**

The `to` date sent to yfinance is outside the valid range or in the future. Check that the configured period `to` values are in the past and in `YYYY-MM` format.

---

## SQLite Clients

To inspect `data/candles.db` locally:

1. **DB Browser for SQLite (DB4S)** — GUI, free, zero config. Recommended.
   - Download: https://sqlitebrowser.org
   - Connect: File → Open Database → `data/candles.db`

2. **DBeaver Community** — Full SQL editor + multi-DB support. Heavier, useful if you already have it installed.
   - Connect: New Connection → SQLite → select `data/candles.db`

3. **SQLiteOnline.com** — browser-based, no install, drag & drop the `.db` file. For quick queries without installing anything.

> **Note**: the `data/candles.db` file is in the project root directory. If you run the app from IntelliJ, the working directory points there by default.

---

## Useful Queries (SQLite)

Run in DB Browser for SQLite or any SQLite client connected to `data/candles.db`.

> **Timestamp note**: `last_chunk_end_ts`, `updated_at`, and `ts_epoch` are stored in **epoch seconds** (not milliseconds). Use `datetime(col, 'unixepoch')` directly, without dividing by 1000.

### Reference Schema

```sql
-- candles: datos OHLCV
-- PRIMARY KEY (ticker, timeframe, ts_epoch)
CREATE TABLE candles (
    ticker      TEXT    NOT NULL,
    timeframe   TEXT    NOT NULL,
    ts_epoch    INTEGER NOT NULL,  -- epoch seconds
    open        REAL    NOT NULL,
    high        REAL    NOT NULL,
    low         REAL    NOT NULL,
    close       REAL    NOT NULL,
    volume      INTEGER NOT NULL
);

-- download_progress: checkpoint por (ticker, timeframe)
-- PRIMARY KEY (ticker, timeframe)
CREATE TABLE download_progress (
    ticker            TEXT    NOT NULL,
    timeframe         TEXT    NOT NULL,
    last_chunk_end_ts INTEGER NOT NULL,  -- epoch seconds
    status            TEXT    NOT NULL,  -- COMPLETE_TWS | COMPLETE_YFINANCE | COMPLETE_EMPTY
    updated_at        INTEGER NOT NULL   -- epoch seconds
);
```

### View: human-readable timestamps

Run once to create the view:

```sql
CREATE VIEW IF NOT EXISTS download_progress_readable AS
SELECT
    ticker,
    timeframe,
    status,
    datetime(last_chunk_end_ts, 'unixepoch', 'localtime') AS last_chunk_end,
    datetime(updated_at,        'unixepoch', 'localtime') AS updated_at_readable
FROM download_progress;
```

Then query:

```sql
SELECT * FROM download_progress_readable
ORDER BY ticker, timeframe;
```

### View: candles with human-readable dates

```sql
CREATE VIEW IF NOT EXISTS candles_readable AS
SELECT
    ticker,
    timeframe,
    datetime(ts_epoch, 'unixepoch', 'localtime') AS date_local,
    date(ts_epoch, 'unixepoch')                  AS date_only,
    open, high, low, close, volume
FROM candles;
```

Example usage:
```sql
-- AAPL DAY_1 candles during the 2008 crisis period
SELECT date_only, open, high, low, close, volume
FROM candles_readable
WHERE ticker = 'AAPL'
  AND timeframe = 'DAY_1'
  AND date_only BETWEEN '2008-09-01' AND '2009-03-31'
ORDER BY date_only;
```

### Verify data for a ticker in a period

```sql
-- Check data in candles
SELECT COUNT(*)              AS total_candles,
       datetime(MIN(ts_epoch), 'unixepoch', 'localtime') AS from_date,
       datetime(MAX(ts_epoch), 'unixepoch', 'localtime') AS to_date
FROM candles
WHERE ticker    = 'AAPL'
  AND timeframe = 'DAY_1'
  AND ts_epoch BETWEEN strftime('%s', '2008-09-01')
                   AND strftime('%s', '2009-03-31');
```

Result:
- `total_candles > 0` → data present
- `total_candles = 0` + status `COMPLETE_EMPTY` in download_progress → yfinance processed the chunk but returned no data
- `total_candles = 0` + no row in download_progress → chunk was never processed

### View download status by ticker

```sql
-- Check checkpoint status
SELECT ticker,
       timeframe,
       status,
       datetime(last_chunk_end_ts, 'unixepoch', 'localtime') AS last_chunk_end
FROM download_progress
WHERE ticker    = 'AAPL'
  AND timeframe = 'DAY_1'
ORDER BY last_chunk_end_ts;
```

### Find empty chunks (COMPLETE_EMPTY)

```sql
-- Chunks that yfinance processed but returned empty
SELECT ticker,
       timeframe,
       datetime(last_chunk_end_ts, 'unixepoch', 'localtime') AS chunk_end
FROM download_progress
WHERE status = 'COMPLETE_EMPTY'
ORDER BY ticker, timeframe, last_chunk_end_ts;
```

---

## Querying Data (SQLite CLI)

> **Prerequisite**: close DB Browser for SQLite before running any query — see [Concurrent Access](#concurrent-access).

### Available tickers and timeframes

```bash
# Unique tickers in the database
sqlite3 data/candles.db "SELECT DISTINCT ticker FROM candles ORDER BY ticker;"

# Count by ticker + timeframe
sqlite3 data/candles.db "SELECT ticker, timeframe, COUNT(*) as total FROM candles GROUP BY ticker, timeframe ORDER BY ticker, timeframe;"
```

### Last N candles for a ticker

```bash
sqlite3 -column -header data/candles.db "
SELECT datetime(ts_epoch, 'unixepoch') as date, open, high, low, close, volume
FROM candles
WHERE ticker = 'AAPL' AND timeframe = 'DAY_1'
ORDER BY ts_epoch DESC LIMIT 20;"
```

Valid values for `timeframe`: `MIN_5` | `MIN_15` | `HOUR_1` | `DAY_1`

### Filter by ticker + timeframe + date range

```bash
sqlite3 -column -header data/candles.db "
SELECT datetime(ts_epoch, 'unixepoch') as date, open, high, low, close, volume
FROM candles
WHERE ticker = 'AAPL'
  AND timeframe = 'MIN_5'
  AND ts_epoch BETWEEN strftime('%s','2020-03-01') AND strftime('%s','2020-03-31')
ORDER BY ts_epoch;"
```

### Filter by exact date and time

```bash
sqlite3 -column -header data/candles.db "
SELECT datetime(ts_epoch, 'unixepoch') as date, open, high, low, close, volume
FROM candles
WHERE ticker = 'AAPL'
  AND timeframe = 'HOUR_1'
  AND ts_epoch >= strftime('%s','2020-03-16 09:30:00')
  AND ts_epoch <= strftime('%s','2020-03-16 16:00:00')
ORDER BY ts_epoch;"
```

### Checkpoint status by ticker

```bash
sqlite3 -column -header data/candles.db "
SELECT ticker, timeframe, status,
       datetime(last_chunk_end_ts, 'unixepoch') as last_chunk,
       datetime(updated_at, 'unixepoch') as updated
FROM download_progress
WHERE ticker = 'AAPL'
ORDER BY timeframe;"
```

### Export to CSV

```bash
sqlite3 -csv -header data/candles.db "
SELECT datetime(ts_epoch,'unixepoch') as date, open, high, low, close, volume
FROM candles WHERE ticker='AAPL' AND timeframe='DAY_1'
ORDER BY ts_epoch;" > aapl_daily.csv
```

---

## Concurrent Access

SQLite is a **file-based database with a single writer at a time**. The `data/candles.db` file cannot be opened by two processes simultaneously if either needs to write.

### Rules

| Situation | Result |
|-----------|--------|
| App running + DB Browser open | `SQLITE_BUSY` — app fails to start |
| App running + `sqlite3` CLI (read-only) | ✅ OK in WAL mode |
| App running alone | ✅ OK |
| DB Browser alone | ✅ OK |

**Always close DB Browser before running `bootRun` or any backfill task.**

### If the app fails to start with SQLITE_BUSY

1. Close DB Browser (or any tool that has the file open)
2. Clean up the orphaned WAL:
   ```bash
   sqlite3 data/candles.db "PRAGMA wal_checkpoint(TRUNCATE);"
   ```
3. Retry `./gradlew bootRun --args='--backfill'`

### Why this happens

When DB Browser (or another tool) opens `candles.db`, it keeps an active connection with an OS-level lock. Spring Boot + HikariCP tries to connect to the same file and SQLite immediately returns `SQLITE_BUSY` (error code 5), before the configured `busy_timeout` can help.
