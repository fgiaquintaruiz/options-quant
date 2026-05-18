-- Migration 2026-05-18: strategy_config table for dynamic strategy enable/disable
--
-- Allows toggling strategies for backtest and live independently without redeploy.
-- For live: defaults to enabled_live=0 (opt-in) for safety.
-- For backtest: defaults to enabled_backtest=1 (run all unless explicitly disabled).

CREATE TABLE IF NOT EXISTS strategy_config (
  strategy_name        TEXT PRIMARY KEY,
  enabled_backtest     INTEGER NOT NULL DEFAULT 1,
  enabled_live         INTEGER NOT NULL DEFAULT 0,
  notes                TEXT,
  updated_at           INTEGER NOT NULL,
  updated_by           TEXT NOT NULL DEFAULT 'system'
) WITHOUT ROWID;

CREATE INDEX IF NOT EXISTS idx_strategy_config_live ON strategy_config(enabled_live);
CREATE INDEX IF NOT EXISTS idx_strategy_config_backtest ON strategy_config(enabled_backtest);

INSERT OR IGNORE INTO strategy_config (strategy_name, enabled_backtest, enabled_live, notes, updated_at, updated_by)
VALUES
  ('c1 squeeze',       1, 0, 'auto-bootstrap migration 2026-05-18', strftime('%s','now'), 'migration'),
  ('c2 trend',         1, 0, 'auto-bootstrap migration 2026-05-18', strftime('%s','now'), 'migration'),
  ('c3 bounce',        1, 0, 'auto-bootstrap migration 2026-05-18', strftime('%s','now'), 'migration'),
  ('c4 opening',       1, 0, 'auto-bootstrap migration 2026-05-18', strftime('%s','now'), 'migration'),
  ('c5 continuation',  1, 0, 'auto-bootstrap migration 2026-05-18', strftime('%s','now'), 'migration'),
  ('c6 reversal',      1, 0, 'auto-bootstrap migration 2026-05-18', strftime('%s','now'), 'migration'),
  ('p1 squeeze',       1, 0, 'auto-bootstrap migration 2026-05-18', strftime('%s','now'), 'migration'),
  ('p2 trend',         1, 0, 'auto-bootstrap migration 2026-05-18', strftime('%s','now'), 'migration'),
  ('p3 bounce',        1, 0, 'auto-bootstrap migration 2026-05-18', strftime('%s','now'), 'migration'),
  ('p4 opening',       1, 0, 'auto-bootstrap migration 2026-05-18', strftime('%s','now'), 'migration'),
  ('p5 continuation',  1, 0, 'auto-bootstrap migration 2026-05-18', strftime('%s','now'), 'migration'),
  ('p6 reversal',      1, 0, 'auto-bootstrap migration 2026-05-18', strftime('%s','now'), 'migration');
