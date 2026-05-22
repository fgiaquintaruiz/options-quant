# Roadmap — Options Quant Engine

> Living strategic plan. Updated at the end of each session.
> Last updated: 2026-05-22

## Current Status

**Phase**: Active paper trading (started 2026-05-18)
**HOT universe**: NVDA, AMD, AMDL, TSLA, META, AVGO, COIN, MSTR, AMZN, SPY
**Tactical universe**: AAPL, URA, MU, SMH, OXY, GLD
**Active strategies**: 12 strategies activated for paper trading (2026-05-19)
**Paper capital**: $968 USD — account DUN598216 (2026-05-18)
**Instrument**: Options (CALL/PUT)
**Stack**: SQLite + Spring Boot 4.0.5 + Java 25 + React frontend + IBKR TWS

## Completed Milestones

### Infrastructure
- ✅ BacktestEngine Refactor 1: TradingStrategy.isCall() interface method (replaces string matching)
- ✅ BacktestEngine Refactor 2: computeReportStats extracted (no duplication)
- ✅ BacktestEngine Refactor 3: split runCore into single-responsibility methods
- ✅ Critical Polygon pagination bug fixed (commit f8342b5)
- ✅ Stooq DAY_1 migration + DROP table + VACUUM (24M rows archived)
- ✅ Materialized ticker_stats table (1000× speedup on filter queries)
- ✅ --complete-tickers flag implemented with TDD
- ✅ Wrapper script scripts/run-backtest.ps1 (DevTools off + paper-friendly)
- ✅ HikariCP/DevTools persistence bug diagnosed + workaround

### Analysis
- ✅ First overfitting analysis (62 tickers, 3695 trades, run 2026-05-17_00-10)
- ✅ Top 3 strategies identified: p6 reversal, p1 squeeze, c6 reversal
- ✅ Strategies to review/remove identified

## Upcoming Tasks — ordered by priority

### P0 — Monday (2026-05-18) BEFORE 15:30 market open

**Phase 1 — Pre-paper investigation (15-30 min)**
- ✅ Verify backtest pricing model (directional or Black-Scholes?)
- ✅ Identify how live mode sends orders to IBKR
- ✅ Confirm IBKR paper account config (port 7497, simulated capital)
- ✅ Decision: activate paper today or defer?

**Phase 2 — Strategy filter by DB (~2.5h)**
- ✅ Block 1: strategy_config table + StrategyConfigService + TDD (StrategyConfigController REST API GET /api/strategy-config + PATCH /api/strategy-config/{name}, 12 tests, commit 6aa7a0e)
- ✅ Block 2: integration with BacktestBatchRunner + Live engine
- ✅ Manual SQL to enable p6 reversal, p1 squeeze, c6 reversal in live (verified GET /api/strategy-config: c6_reversal, p1_squeeze, p6_reversal enabledLive=true, PID 42108)

**Phase 3 — Smoke test (15 min before market open)**
- ✅ Start live mode
- ✅ Verify logs: only the 3 active strategies
- ✅ Verify TWS paper connection (port 7497)
- ✅ No real trades yet

**15:30 → first paper trade**

### P1 — This week (post first paper)

- ✅ React UI for strategy filter — StrategiesPage.jsx + /strategies route + badge in ScanEngineControls. 890 tests, 95.7% coverage. Commit 313eb64.
- ✅ scripts/run-live.ps1 — PowerShell startup script: UTF-8 logging, port 9090, timestamped log, no DevTools. Created 2026-05-18.
- ✅ p2 trend activated in live (2026-05-19, direct SQL)
- ✅ 11 strategies activated for paper trading (2026-05-19) — c3/c4/p4/p5 activated for data collection — analysis pending post paper trading
- ✅ c1 squeeze, c2 trend, p3 bounce, p4 opening, c3 bounce, p5 continuation, c4 opening, c5 continuation activated in live (2026-05-19)
- ✅ GLD/SMH added to universe
- ✅ ticker-memory.json rolling backup
- ✅ Play button fix replay UI
- ✅ Market hours gate in replay (virtual clock)
- ✅ Signal timestamp fix (virtual clock during replay)
- ✅ Virtual clock UI + progress bar
- ✅ Playwright E2E for replay controls
- ✅ Banner replay state fix (useReplayStatus direct)
- ✅ Replay auto-disable macro filter during replay
- ✅ Replay TradePlan in JSONL + on-complete summary
- ✅ Replay auto-stop at virtual clock = now
- ✅ ReplayScheduler @Autowired fix

### P1 — This week (new, 2026-05-19 evening)

- ✅ **Investigate and decide on SignalQualityFilter** — backtest does not apply it, replay/live does; may explain 0 signals in replay. Decision: add to backtest or remove from replay.
- ✅ **Investigate c4/p4 opening** — BacktestEngine forces entry window 9:45 ET, c4/p4 require 9:30 ET; possibly never evaluated in backtest. Verify and fix.
- ✅ **Implement Condition logging in 12 strategies** — POC completed in P1Squeeze; extended to all (12/12 done). Allows debugging which condition fails per ticker.
- [ ] **Deep signal visualization** — 2 charts (daily+1h, 1h+15m) with SMA/BB/TP/SL. Design pending.

### P1 — Operational items (2026-05-20)

- ⏸️ **Backtest c4/p4 opening with entry window 9:30-9:36 ET** — script ready at `scripts/run-backtest-c4-p4.ps1`, Fabio runs manually
- ✅ **Decide on SignalQualityFilter** — not from the course author's book, no tests, strategies already have their own filters; options: (a) remove from live/replay, (b) add to backtest as well, (c) move inside each strategy — removed from live/replay path (f63b0b0, 8d9fd78)
- [ ] **Implement MultiTimeframeChart 1h+15m side-by-side**

### P1 IMMEDIATE — Live trading bugs + Option Chain Recorder

### OptionChainRecorder fix — 3 stacked blockers
**Status**: Ready to execute (today)

| # | Layer | Issue | File |
|---|-------|-------|------|
| 1 | Wiring | `snapshotAsync()` never calls `snapshotSync()` | `OptionChainRecorderService.java:53-62` |
| 2 | Gateway | `NoOpOptionChainIbkrGateway` returns `null` for all calls | `options/NoOpOptionChainIbkrGateway.java` |
| 3 | Connection | TWS error 502 (not running) during 2026-05-21 session | operator checklist |

**Scheduler is CLEAN** — zero changes needed. It correctly calls
`recorderService.snapshotAsync(ticker, batchId, null, "BOTH", "SCHEDULED")` at
`OptionChainScheduler.java:106`. Recorder must resolve strikes/expiry/price internally.

**Fix tasks (TDD, separate commits):**

1. Wire `snapshotAsync()` → resolve params internally → `snapshotSync()` — size: **S**
2. Implement real `IbkrOptionChainGateway.fetchOptionSnapshot()` (TWS `reqSecDefOptParams` + `reqMktData` with greeks ticks) — size: **L**
3. `@Profile("live")` on real impl, `@Profile("!live")` on no-op — size: **XS**
4. Diagnostic logging: `[optchain] snapshotAsync ENTRY/DONE ticker=X rows=N` / `snapshotSync ticker=X validStrikes=N expiry=Y` / `null snap ticker=X strike=Y right=Z` (WARN) — size: **XS**

**Validation steps:**
1. Start TWS first, verify port 7497 connected
2. Run live session before 15:30 ES
3. Wait for 15:30 ES cron fire (or next 15-min tick)
4. `sqlite3 data/candles.db "SELECT COUNT(*) FROM option_chain_snapshot;"` → expect > 0 with greeks

- ✅ **Bug Fix: manual execute silently fails on stale signals** — isMarketHours() guard added (75082d0)
- ✅ **Bug Fix: no Telegram notification on manual order execution** — Telegram injection in execute path (80ce1bf)
- ✅ **Bug Fix: EXITED status without Entry timestamp** — guard close-trade against unknown tickers (80ce1bf)
- ✅ **Option Chain Recorder** — OptionChainSnapshotService async, 16 tickers, ATM±5 strikes, expiry ~48h, every 15min 9:30-16:00 ET + every 5min 9:30-9:40 ET, option_chain_snapshot table (0293460)

### P1 NEXT — Pricing + Condition logging

- [ ] Black-Scholes pricing model in BacktestEngine — replace hardcoded OPTIONS_DELTA=0.50 with N(d1) using configurable IV per ticker, calculated DTE, risk-free rate 4.5%
- [ ] Condition logging extend to c3-p3 / c4-p4 / c5-p5 / c6-p6 (POC ready in P1/C1)

### P1 PENDING — Validation (Fabio runs)

- ✅ **Run `.\scripts\run-backtest.ps1 -Strategies c4,p4`** — 2026-05-22: -Strategies filter validated end-to-end (117 tickers × c4,p4 → 0 trades expected for non-earnings period, log: run-backtest_20260522_125614.log)
- [ ] **Verify OptionChainRecorder on first market open** — confirm `option_chain_snapshot` is populated on first open (15:30 ET / 9:30 ET) on 2026-05-21

### P1 CRITICAL — Options pricing (2026-05-20)

- ✅ **Validate pricing gap** — backtest uses `(exitPrice - entryPrice) * qty * 100` (implicit delta=1) vs real options; overestimates gains/losses, ignores theta decay and bid/ask spread [`BacktestEngine.java:1114-1117`, `RiskCalculator.java:15-16`] — delta=0.60 applied (d5e3a41). Pending: theta decay, IV crush, real contract data.
- [ ] **Validate structural divergence** — backtest moves the underlying price, live executes real contracts with strike/expiry via IBKR [`OrderExecutionService.java:296-340`]; backtest P&L is not directly comparable to live
- [ ] **Investigate real options data sources** — Polygon options API, IBKR historical; evaluate cost, granularity, and coverage for the 16-ticker universe

### P2 — Next 2 weeks

- [ ] --start-date/--end-date filter in BacktestBatchRunner — needed to backtest specific historical periods where c4/p4 actually fire (nudge 2026-05-22: blocker for c4/p4 validation on real signal windows)
- [ ] Full Polygon backfill for SMH/GLD (2 years)
- [ ] Analysis of first days of paper trades (with current parameters)
- [ ] Investigate c4 opening / p4 opening: why do they not generate trades? (0 trades in backtest — check signal detection or parameter thresholds)
- [ ] Investigate MIN_5 0 bars bug — some tickers return 0 bars on MIN_5 timeframe; not critical but affects completeness
- [ ] Evaluate enabling MIN_5 for tactical tickers (c4/p4) — consider MIN_5 specifically for opening strategies
- [ ] Review CLAUDE_ANALYSIS.md overfitting analysis — philosophical reorientation pending per the course author's risk philosophy; not a bug, it's a strategic decision
- [ ] Decide inclusion of strategy.config in JaCoCo or formalize exclusion — current exclusion documented in testing-debt.md; define whether it enters the coverage gate
- [ ] Historical IV for free per ticker — Yahoo Finance / CBOE VIX data; calibrate IV config per ticker
- [ ] Black-Scholes calibration against option_chain_snapshot — minimum 3 months of data for validation
- [ ] Review risk management: worst trades of -$1500 are excessive
- [ ] Implement dynamic threshold in ticker_stats (for new tickers with little data)
- [ ] Re-run backtest with focal universe (16 tickers) and 2020+ range

### Tech-debt

- [ ] Fix BacktestBatchRunnerResumeTest.whenBacktestFreshFlagPresent_startsFreshWithoutPrompt
- [ ] Fix RollbackConfigTest$CsvActiveTest.candleStore_csv_injectsCsvCandleRepository
- [ ] Investigate HISTORICAL hardcoded checkpoint bug (HistoricalBackfillService L311, L448)
- [ ] `BacktestBatchRunner.java ~L93`: Scanner resource leak — `new Scanner(System.in)` opened without closing on each invocation
- [ ] `BacktestBatchRunner.java ~L395`: ResultAccumulator mutable fields are package-private — should be `private`
- [ ] `BacktestBatchRunner.java buildConfig()`: DRY violation — BacktestConfig construction (17 args) duplicated across two branches
- [ ] `BacktestBatchRunner.java run()`: ~150-line method with multiple responsibilities — extract `resolveRunId`, `handleFreshFlag`, `printSummary`
- [ ] `C4P4OpeningBacktestIT.java`: weak assertion — only `assertThat(report).isNotNull()`, no assert on trade count — test passes with 0 trades

## Pending Strategy Decisions

### p2 trend — Predicate Subsumption (investigated 2026-05-19)
- `priceBelowMiddleBB` is redundant with `isDowntrend15m` in `P2TrendPutStrategy.java:~125`
  — `priceBelowMiddleBB` = price < SMA20 = already guaranteed by `isDowntrend15m`
- **Pending the course author decision**: remove redundant predicate → increases trade count (currently 255)
- Tickers with negative results to consider removing from p2 universe: LULU (-$2,719), LLY (-$1,784), ANET (-$1,010), GOOG (-$627), BITX (-$581)
- Status: ⏳ DO NOT modify yet — requires backtest validation before activating in live

## Architecture Decisions

- **SQLite stays** (no migration to PostgreSQL until universe grows to 100+ tickers)
- **Small focal universe** (10-20 tickers vs 500+) to align with real capital
- **100% capital simulation** (config from live trade UI)
- **Options only** (CALL/PUT)
- **Paper trading before real capital**

## Historical — recent sessions

### 2026-05-21 (Wednesday, strategy filter + scripts session)
- Robust logging pattern applied to run-backtest.ps1 and run-live.ps1 (568e794)
- `getCode()` added to TradingStrategy interface — implemented in 12 strategies (7b292a7)
- BacktestEngine uses `getCode()` instead of class name in strategyFilter (2abfbe9)
- C4P4OpeningBacktestIT migrated to strategyFilter — fix silent no-op bug (0369d3f)
- `--strategies` CLI flag + `-Strategies` PS script param — filter by strategy code (39d9f2c)
- Fix unused imports in BacktestBatchRunnerStrategiesArgTest (97607ed)

### 2026-05-16 (Saturday, marathon session)
- 14 commits to main
- Polygon pagination bug (f8342b5)
- Complete Stooq DAY_1 migration
- Materialized ticker_stats
- --complete-tickers TDD
- 23.9M DAY_1 rows deleted from candles

### 2026-05-18 (Monday, pre-paper + paper launch session)
- StrategyConfigController REST API: GET /api/strategy-config + PATCH /api/strategy-config/{name}, TDD 12 tests (commit 6aa7a0e)
- StrategiesPage.jsx + /strategies route + badge ScanEngineControls, TDD 15+6+5 tests, 890 total, 95.7% coverage (commit 313eb64)
- scripts/run-live.ps1: startup script UTF-8, port 9090, timestamped log, no DevTools
- Backend verified at :9090 — 12 strategies, c6_reversal/p1_squeeze/p6_reversal enabledLive=true
- **Paper trading started**: account DUN598216, $968 USD, 3 active strategies
- **HOT tickers corrected**: NVDA, AMD, AMDL, TSLA, META, AVGO, COIN, MSTR, AMZN, SPY
- **Full universe**: HOT + AAPL, URA, MU, SMH, OXY, GLD (tactical)
- **First day**: 0 signals — conditions not met, system working correctly

### 2026-05-17 (Sunday, closing session)
- HikariCP/DevTools persistence bug resolved (commit 7224145)
- First functional backtest with persistence (62 tickers, 3695 trades)
- Complete overfitting analysis
- DROP candles_stooq + VACUUM (~5-6 GB freed)
- Roadmap reorganized
- Phase A pre-paper investigation: pure directional pricing (implicit delta=1), expected gap vs real options
- Tactical ticker coverage verification: MU/OXY/URA already complete in 4 TFs ✅, SMH/GLD bootstrapped
- massive_import.py modification: --only-tickers flag (TDD)
- New scripts/yfinance_day1_import.py for DAY_1 via sidecar :8001 (TDD)
- Overnight download: SMH/GLD intraday (HOUR_1+MIN_15+MIN_5) via massive in background
- Final 16-ticker universe defined: NVDA, AMD, AMZN, TSLA, META, AVGO, COIN, MSTR, AAPL, AMZN + SPY + URA, MU, SMH, OXY, GLD
