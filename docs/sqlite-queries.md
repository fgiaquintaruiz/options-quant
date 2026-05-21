# SQLite Queries — candles.db

Cheatsheet of useful queries for the local `data/candles.db` database.

## Tickers with fundamentals

Export all tickers:
```sql
sqlite3 -csv -header data/candles.db "SELECT ticker, company_name, sector, market_cap_billion, pe_ratio, dividend_yield, beta, eps_growth, revenue_growth, debt_to_equity, roic, notes, active, datetime(updated_at,'unixepoch') AS updated_at FROM tickers ORDER BY ticker;" > tickers.csv
```

Active tickers only:
```sql
sqlite3 -csv -header data/candles.db "SELECT ticker, company_name, sector, market_cap_billion, pe_ratio, dividend_yield, beta, eps_growth, revenue_growth, debt_to_equity, roic, notes, datetime(updated_at,'unixepoch') AS updated_at FROM tickers WHERE active = 1 ORDER BY ticker;" > tickers_active.csv
```

By sector:
```sql
sqlite3 -csv -header data/candles.db "SELECT sector, COUNT(*) AS total, AVG(pe_ratio) AS avg_pe, AVG(beta) AS avg_beta, AVG(market_cap_billion) AS avg_mktcap FROM tickers WHERE active = 1 GROUP BY sector ORDER BY total DESC;" > tickers_by_sector.csv
```

## Coverage by ticker and timeframe

```sql
sqlite3 -csv -header data/candles.db "SELECT ticker, timeframe, COUNT(*) AS total_candles, datetime(MIN(ts_epoch),'unixepoch') AS from_date, datetime(MAX(ts_epoch),'unixepoch') AS to_date FROM candles GROUP BY ticker, timeframe ORDER BY ticker, timeframe;" > coverage.csv
```

## Candles — basic queries

All distinct tickers:
```sql
sqlite3 data/candles.db "SELECT DISTINCT ticker FROM candles ORDER BY ticker;"
```

Count by ticker and timeframe:
```sql
sqlite3 data/candles.db "SELECT ticker, timeframe, COUNT(*) as total FROM candles GROUP BY ticker, timeframe ORDER BY ticker, timeframe;"
```

Last 20 candles for a ticker:
```sql
sqlite3 -column -header data/candles.db "
SELECT datetime(ts_epoch, 'unixepoch') as date, open, high, low, close, volume
FROM candles
WHERE ticker = 'AAPL' AND timeframe = 'DAY_1'
ORDER BY ts_epoch DESC LIMIT 20;"
```

## Date and time filters

By date range:
```sql
sqlite3 -column -header data/candles.db "
SELECT datetime(ts_epoch, 'unixepoch') as date, open, high, low, close, volume
FROM candles
WHERE ticker = 'AAPL'
  AND timeframe = 'MIN_5'
  AND ts_epoch BETWEEN strftime('%s','2020-03-01') AND strftime('%s','2020-03-31')
ORDER BY ts_epoch;"
```

Filter by exact date and time:
```sql
sqlite3 -column -header data/candles.db "
SELECT datetime(ts_epoch, 'unixepoch') as date, open, high, low, close, volume
FROM candles
WHERE ticker = 'AAPL'
  AND timeframe = 'HOUR_1'
  AND ts_epoch >= strftime('%s','2020-03-16 09:30:00')
  AND ts_epoch <= strftime('%s','2020-03-16 16:00:00')
ORDER BY ts_epoch;"
```

## download_progress

Checkpoint status by ticker:
```sql
sqlite3 -column -header data/candles.db "
SELECT ticker, timeframe, status,
       datetime(last_chunk_end_ts, 'unixepoch') as last_chunk,
       datetime(updated_at, 'unixepoch') as updated
FROM download_progress
WHERE ticker = 'AAPL'
ORDER BY timeframe;"
```

## Export to CSV

Export candles for a ticker and timeframe:
```sql
sqlite3 -csv -header data/candles.db "
SELECT datetime(ts_epoch,'unixepoch') as date, open, high, low, close, volume
FROM candles WHERE ticker='AAPL' AND timeframe='DAY_1'
ORDER BY ts_epoch;" > aapl_daily.csv
```

Export all timeframes for a ticker:
```sql
sqlite3 -csv -header data/candles.db "
SELECT datetime(ts_epoch,'unixepoch') as date, open, high, low, close, volume
FROM candles WHERE ticker='AAPL'
ORDER BY ts_epoch;" > aapl.csv
```

> Replace `'AAPL'`, `'DAY_1'`, and the dates as needed.
