# SQLite Queries — candles.db

Cheatsheet de queries útiles para la base local `data/candles.db`.

## Tickers con fundamentals

Export todos los tickers:
```sql
sqlite3 -csv -header data/candles.db "SELECT ticker, company_name, sector, market_cap_billion, pe_ratio, dividend_yield, beta, eps_growth, revenue_growth, debt_to_equity, roic, notes, active, datetime(updated_at,'unixepoch') AS updated_at FROM tickers ORDER BY ticker;" > tickers.csv
```

Solo activos:
```sql
sqlite3 -csv -header data/candles.db "SELECT ticker, company_name, sector, market_cap_billion, pe_ratio, dividend_yield, beta, eps_growth, revenue_growth, debt_to_equity, roic, notes, datetime(updated_at,'unixepoch') AS updated_at FROM tickers WHERE active = 1 ORDER BY ticker;" > tickers_activos.csv
```

Por sector:
```sql
sqlite3 -csv -header data/candles.db "SELECT sector, COUNT(*) AS total, AVG(pe_ratio) AS pe_promedio, AVG(beta) AS beta_promedio, AVG(market_cap_billion) AS mktcap_promedio FROM tickers WHERE active = 1 GROUP BY sector ORDER BY total DESC;" > tickers_por_sector.csv
```

## Cobertura por ticker y timeframe

```sql
sqlite3 -csv -header data/candles.db "SELECT ticker, timeframe, COUNT(*) AS total_candles, datetime(MIN(ts_epoch),'unixepoch') AS desde, datetime(MAX(ts_epoch),'unixepoch') AS hasta FROM candles GROUP BY ticker, timeframe ORDER BY ticker, timeframe;" > cobertura.csv
```

## Candles — consultas básicas

Todos los tickers distintos:
```sql
sqlite3 data/candles.db "SELECT DISTINCT ticker FROM candles ORDER BY ticker;"
```

Contar por ticker y timeframe:
```sql
sqlite3 data/candles.db "SELECT ticker, timeframe, COUNT(*) as total FROM candles GROUP BY ticker, timeframe ORDER BY ticker, timeframe;"
```

Últimas 20 candles de un ticker:
```sql
sqlite3 -column -header data/candles.db "
SELECT datetime(ts_epoch, 'unixepoch') as fecha, open, high, low, close, volume
FROM candles
WHERE ticker = 'AAPL' AND timeframe = 'DAY_1'
ORDER BY ts_epoch DESC LIMIT 20;"
```

## Filtros por fecha y hora

Por rango de fechas:
```sql
sqlite3 -column -header data/candles.db "
SELECT datetime(ts_epoch, 'unixepoch') as fecha, open, high, low, close, volume
FROM candles
WHERE ticker = 'AAPL'
  AND timeframe = 'MIN_5'
  AND ts_epoch BETWEEN strftime('%s','2020-03-01') AND strftime('%s','2020-03-31')
ORDER BY ts_epoch;"
```

Filtro con fecha y hora exacta:
```sql
sqlite3 -column -header data/candles.db "
SELECT datetime(ts_epoch, 'unixepoch') as fecha, open, high, low, close, volume
FROM candles
WHERE ticker = 'AAPL'
  AND timeframe = 'HOUR_1'
  AND ts_epoch >= strftime('%s','2020-03-16 09:30:00')
  AND ts_epoch <= strftime('%s','2020-03-16 16:00:00')
ORDER BY ts_epoch;"
```

## download_progress

Estado del checkpoint por ticker:
```sql
sqlite3 -column -header data/candles.db "
SELECT ticker, timeframe, status,
       datetime(last_chunk_end_ts, 'unixepoch') as ultimo_chunk,
       datetime(updated_at, 'unixepoch') as actualizado
FROM download_progress
WHERE ticker = 'AAPL'
ORDER BY timeframe;"
```

## Export a CSV

Export candles de un ticker y timeframe:
```sql
sqlite3 -csv -header data/candles.db "
SELECT datetime(ts_epoch,'unixepoch') as fecha, open, high, low, close, volume
FROM candles WHERE ticker='AAPL' AND timeframe='DAY_1'
ORDER BY ts_epoch;" > aapl_daily.csv
```

Export todos los timeframes de un ticker:
```sql
sqlite3 -csv -header data/candles.db "
SELECT datetime(ts_epoch,'unixepoch') as fecha, open, high, low, close, volume
FROM candles WHERE ticker='AAPL'
ORDER BY ts_epoch;" > aapl.csv
```

> Reemplazá `'AAPL'`, `'DAY_1'`, y las fechas según necesites.
