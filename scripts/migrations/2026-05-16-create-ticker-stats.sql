-- Migration 2026-05-16: ticker_stats materialized table
-- Stores aggregated stats per ticker for fast filtering.
-- Range: from 2024-05-01 onwards (Polygon coverage start).
-- Refresh: scripts/refresh_ticker_stats.py

CREATE TABLE IF NOT EXISTS ticker_stats (
  ticker            TEXT PRIMARY KEY,
  bars_day_1        INTEGER NOT NULL DEFAULT 0,
  bars_hour_1       INTEGER NOT NULL DEFAULT 0,
  bars_min_15       INTEGER NOT NULL DEFAULT 0,
  bars_min_5        INTEGER NOT NULL DEFAULT 0,
  bars_total        INTEGER NOT NULL DEFAULT 0,
  last_day_1        INTEGER,
  last_hour_1       INTEGER,
  last_min_15       INTEGER,
  last_min_5        INTEGER,
  first_day_1       INTEGER,
  first_hour_1      INTEGER,
  first_min_15      INTEGER,
  first_min_5       INTEGER,
  has_all_4_tfs     INTEGER NOT NULL DEFAULT 0,
  updated_at        INTEGER NOT NULL,
  active            INTEGER NOT NULL DEFAULT 0
) WITHOUT ROWID;

CREATE INDEX IF NOT EXISTS idx_ticker_stats_active_complete
  ON ticker_stats(active, has_all_4_tfs);

CREATE INDEX IF NOT EXISTS idx_ticker_stats_bars
  ON ticker_stats(bars_total DESC);
