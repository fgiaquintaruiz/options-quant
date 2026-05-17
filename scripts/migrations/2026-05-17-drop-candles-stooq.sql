-- Migration 2026-05-17: drop candles_stooq table
--
-- Context: candles_stooq held historical DAY_1 data for 11,370 inactive
-- tickers (~23.9M rows). After analysis we decided this data does not
-- contribute to current strategy backtesting nor live trading.
--
-- Phase 1 (2026-05-16): DAY_1 rows of inactive tickers deleted from
-- candles (23,877,221 rows removed via dry-run-validated DELETE).
-- Phase 2 (2026-05-17): candles_stooq table dropped entirely.
-- Phase 3 (2026-05-17): VACUUM run to reclaim ~5-6 GB of disk space.

DROP TABLE IF EXISTS candles_stooq;
