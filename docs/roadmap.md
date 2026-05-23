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

#### Task 2 (locked design, ready to execute Monday after IT passes)

**Goal**: Replace NoOpOptionChainIbkrGateway with RealOptionChainIbkrGateway that fetches per-contract greeks via reqMktData streaming + tickOptionComputation.

**Implementation order (8 pieces, ~430-450 lines)**:
1. TickOptionComputationEvent record + Consumer registration on OES (XS, 30 lines)
2. tickOptionComputation override on OES anonymous wrapper (XS, 10-15 lines)
3. Semaphore(5) throttling per-gateway (XS, 10 lines)
4. Lifecycle: subscribe → wait → cancelMktData (S, 70 lines)
5. RealOptionChainIbkrGateway class (M, 120-140 lines)
6. @Profile("live") + @Profile("!live") routing (XS, 15 lines)
7. Integration with Recorder (0 lines — already decoupled via gateway port)
8. Tests unit + IT TWS-gated (M, 180 lines)

**Design decisions locked**:
- Streaming reqMktData (snapshot=false), explicit cancelMktData on completion or timeout
- 5s default timeout via Duration ctor param
- reqId range 20000-29999 for option snapshots (document in OES constants block)
- Serial strikes within snapshotSync (gateway-level Semaphore(5) bounds concurrency)
- Return partial greeks if at least delta OR optPrice non-null; skip if all null
- No caching (greeks change tick-by-tick)
- @Profile("live") activates RealGateway; default uses NoOp

**Reuse identified**:
- ContractFactory.createOptionContract — existing, handles dot-sanitize + tradingClass
- OrderExecutionService.requestPriceSnapshot — pattern for reqMktData call
- CopyOnWriteArrayList<Consumer<>> + register pattern — same as tickPrice consumer
- Semaphore(5) — same idiom as UnderlyingPriceGateway

**Open risks to validate Monday**:
- IBKR market data line limit (100 concurrent) — recorder uses few but worth monitoring
- tickOptionComputation field semantics (field=13 = model, may need to also check fields 10/11/12)
- Cancel timing — race between cancelMktData and final tick delivery

**Validation criteria**:
- IT against TWS with SPY options
- Asserts: at least one strike returns non-null delta within 5s
- After full integration, check option_chain_snapshot table populated with greeks on Monday's first scheduled cron tick
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
- [ ] **Re-run IbkrSnapshotUnderlyingPriceGatewayIT during market hours** — Saturday 2026-05-23 attempt failed due to usfarm/ushmds disconnected on weekend (IBKR paper accounts lose Market Data + HMDS connections on weekends — expected behavior, environmental not code). Required: Monday 2026-05-26 (or any weekday) between 09:30 and 16:00 ET / 15:30-22:00 ES with TWS running.

  Command:
  ```
  .\gradlew twsTest "-DrunTwsTests=true" --tests "*IbkrSnapshotUnderlyingPriceGatewayIT*"
  ```

### P1 NEXT — Pricing + Condition logging

- [ ] Black-Scholes pricing model in BacktestEngine — replace hardcoded OPTIONS_DELTA=0.50 with N(d1) using configurable IV per ticker, calculated DTE, risk-free rate 4.5%
- ~~Condition logging extend to c3-p3 / c4-p4 / c5-p5 / c6-p6~~ (✅ verified 2026-05-23: all 12 strategies have condition logging + dedicated test files, no coverage gap)

#### Phase 2 (Black-Scholes Delta) — partial design lock 2026-05-23

**Goal**: Replace hardcoded `BacktestConfig.OPTIONS_DELTA=0.60` with N(d1) Black-Scholes delta for accurate PnL on option exits at `BacktestEngine.java:917-920` (unrealized) + `:1022-1024` (realized).

**Locked decisions**:
- Strike + expiry decided at **strategy signal-time**, propagated via `TradePlan`
- `RiskCalculator` populates `OpenPosition.strike` + `OpenPosition.expiry` from `TradePlan`
- **Delta-only scope** for Phase 2 (gamma / vega / theta deferred to Phase 3)
- New port `OptionPricer` + `BlackScholesOptionPricer` implementation (hexagonal pattern, mirrors `UnderlyingPriceGateway`)
- No external math dependency — 5-line Abramowitz-Stegun `Phi(d)` approximation (±0.0015 error, sufficient for backtest)

**Pending decisions (deferred to Monday 2026-05-26 after Task 2 IT validates)**:
- **IV source** — default 0.30 (single global) vs per-ticker from `option_chain_snapshot` table
- **Risk-free rate** — hardcoded 0.045 vs `application.yml` configurable vs external API (ECB/Fed)
- **T convention** — calendar days / 365 vs business days / 252
- **Backfill policy** — apply only forward (cutover date) vs recompute historical trades

**Scope estimate (10 pieces, ~510 lines, S-M total)**:

| # | Piece | Size | Lines |
|---|---|---|---|
| 1 | `TradeRecord` field add (strike, expiryDate) | XS | 15 |
| 2 | `OpenPosition` field add (strike, expiryDate) | XS | 5 |
| 3 | `BlackScholesPricer.java` (pure function) | XS | 40 |
| 4 | Unit tests vs Hull textbook values | XS | 60 |
| 5 | `OptionPricer` interface + impl | XS | 30 |
| 6 | `RiskCalculator` populates strike + expiry | S | 50 |
| 7 | `BacktestEngine` wiring (replace constant) | S | 50 |
| 8 | Config plumbing (IV + r) | XS | 40 |
| 9 | CSV format update + migration | S | 80 |
| 10 | Integration test (end-to-end PnL) | S | 100 |

**Pre-Monday sanity check required before implementation**:
- Trace `ContractFactory.createOptionContract` callers in live mode
- Confirm whether `TradePlan` in live mode already carries strike + expiry
- If yes → mirror in backtest path
- If no → add to both paths consistently (avoids parallel-shape drift)

**Reuse identified**:
- `OptionPricer` port mirrors `UnderlyingPriceGateway` hexagonal pattern
- No external dependency (custom `Phi(d)` 5 lines, no commons-math3 needed)

**Validation criteria**:
- Unit tests pass against textbook BS values (ATM/OTM/ITM scenarios from Hull)
- Backtest PnL on identical inputs differs from constant-0.60 baseline by expected amount
- Integration test: backtest run with BS delta vs constant delta — diff documented

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
- [ ] **TwsPaperConnectivityTest broken** — `ObjectMapper` bean not available in Spring test context. Pre-existing, surfaced during 2026-05-23 IT run. `NoSuchBeanDefinitionException`, not related to pricing spike work. Investigate and fix or document as test-context configuration issue.
- [ ] **ReplayControlsE2eTest TimeoutError** — surfaced during 2026-05-23 `twsTest` task run. Likely cascade from clientId conflicts during test cleanup (`clientId already in use` errors). Investigate ordering / cleanup between tests in `twsTest` task.

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
