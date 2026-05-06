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

### SQLite

Query the download progress table:
```bash
sqlite3 data/candles.db \
  "SELECT ticker, timeframe, datetime(last_chunk_end_ts/1000, 'unixepoch') as last_end, status
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
