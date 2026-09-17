# Roadmap — Options Quant Engine
_Last updated: 2026-09-17_

## Where the project is (September 2026)

The repository was renamed and republished on 2026-09-17 with rewritten history to remove
stale credentials and unrelated local artifacts; the previous name and prior contributors
are not referenced anywhere in this document. Before the republish, the project had reached
a working paper-trading pipeline: historical backtesting, a replay mode for re-running past
sessions against a virtual clock, live market scanning against IBKR TWS, Telegram alerts on
signals, and an `OptionChainRecorder` that started capturing option-chain snapshots with
greeks on 2026-05-21. That pipeline runs, but a 2026-09-17 review found structural issues in
both the backtest math and the codebase's architecture (see "Known limitations" below). The
project is now being rebuilt in the open as a portfolio piece: the immediate goal is not new
features but an honest architecture — hexagonal boundaries, trading strategies defined as
external configuration instead of hardcoded classes, and reproducible backtests — with each
phase documented as it happens.

## Guiding principles

- Strategies are configuration, not code — the engine should not know what a strategy does, only how to run one.
- The broker is an adapter — IBKR/TWS vocabulary must not leak past a port boundary into domain or application code.
- Backtests must be honest before they are fast — a backtest that cannot see the future is worth more than one with more features.
- Hexagonal / ports-and-adapters over layered MVC — domain logic testable without TWS, without SQLite, without wall-clock time.
- Public and reproducible — every claim in this roadmap about a bug or a metric must point at a file, a line, or a commit.

## Known limitations (public, honest)

- **Look-ahead bias in backtests**: IBKR intraday bars are timestamped at bar START, but that same timestamp is passed to ta4j as the bar END time in `StrategyData.buildSeries`. Index lookups by "last bar ending at or before time T" then return the bar that is still forming, so strategies read its close/high/low/volume before that bar has actually closed. This affects strategies C1, C2, C3, C4, C6, and P6. Historical backtest metrics for those strategies are inflated and must be re-run once the fix lands. Status: identified in review 2026-09-17, fix pending (mentored).
- **Same-bar TP/SL tie-break is wrong**: when a bar's range touches both the take-profit and stop-loss levels in the same bar, `BacktestEngine` resolves it take-profit-first; the conservative and standard choice is stop-loss-first (or skipping the ambiguous bar entirely).
- **No option pricing model**: P&L is computed as underlying price move × a fixed delta of 0.6 × 100, with no theta decay, no implied volatility, and no bid/ask spread. A Black-Scholes design exists (see "Carried forward") but is not implemented.
- **Position sizing can exceed the account**: each ticker's position sizing risks 2% of the *full* account, while each ticker is allocated only 1/N of capital, and the concurrent-trade cap is applied per ticker rather than account-wide. Worst-case simultaneous exposure across tickers is a multiple of total account equity.
- **Walk-forward optimiser is a no-op**: the grid/walk-forward optimiser only varies two parameters that `RiskCalculator` never reads, so every fold produces identical results regardless of the grid.
- **In-sample data leaks into live filtering**: `TickerMemory` feeds backtest trade history into the live ticker-blocking logic, effectively applying an in-sample filter to forward-looking decisions; per-strategy `lastTriggerMap` state is also never reset between runs.
- **Sharpe/drawdown are not meaningful as reported**: Sharpe is computed from per-trade returns scaled by √252 (not a valid annualisation for non-daily, non-uniform trade series); per-strategy Sharpe is hardcoded to 0; drawdown is computed from the realised-only equity curve, ignoring open positions.
- **Architecture debt**: several god classes exist (`LiveModeController` ~1.6k lines, `BacktestDashboardController` ~1.2k, `BacktestEngine` ~1.2k, `StrategyScannerService` ~1.1k); the backtest engine performs file I/O and thread management directly; there are 40+ direct calls to `now()` scattered through the codebase instead of an injected clock; `com.ib` (IBKR SDK) types leak into controllers, DTOs, and `AccountManager`; and `strategy/**` is excluded from the JaCoCo coverage gate.

## Phase 0 — Sanitise & republish (MECHANICAL)

| Step | Status | Notes |
|---|---|---|
| Inventory + history rewrite + republish | DONE 2026-09-17 | New repository, rewritten git history |
| Author identity normalised | DONE | |
| README educational disclaimer | DONE 2026-09-17 | commit `160defa` |
| Credential rotation (Telegram bot token, webhook secret + master key to env vars, Gemini key) | OPEN, manual | Owner action, not code |
| Move local-only data from old working folder (`data/`, `analysis/`, `scripts`) | IN PROGRESS | Blocked intermittently by open file handles (IDE watcher / Gradle daemons) on Windows |
| Hygiene commit: untrack `.idea/`, `.qwen/`, `.continue/`, `META-INF/`, root `com/ib/**` class files, `.atl/` | OPEN | |
| Tone down README tagline marketing language | OPEN, optional | |

## Phase 1 — Assessment (MECHANICAL analysis)

Deliverables:
- A dependency map of everything that touches the IBKR TWS API, and every place that dependency leaks into business logic.
- An honest design assessment of the current codebase.
- The three riskiest refactor points, ranked.
- A test-coverage report, including what is currently untestable and why.

Constraint: this phase proposes no target architecture — that is Phase 2's job.

Status: partially done 2026-09-17 (the quant-methodology review that produced "Known limitations" above); the dependency map and the coverage report are still open.

## Phase 2 — DDD / hexagonal migration (MENTORED)

Questions to answer before writing migration code:
- What are the real aggregates — `Strategy`? `Position`? `BacktestRun`? `OptionChain`? `Order`?
- Where is the boundary between domain and application logic?
- Can a broker port be defined with zero IBKR vocabulary in its signature (no `Contract`, `TickType`, `reqId`)?
- Are backtest, forward-test (replay), and live execution the same domain operation running behind three different adapters?
- How does the domain express "now" without knowing which mode it is running in (a `Clock` port)?
- Which parts of the system do NOT need DDD — where would it be over-engineering?

## Phase 2b — Strategies as configuration (MENTORED)

This is the core design problem of the rebuild. Goals, from the project owner:

- The engine must contain **no specific strategy**. The current hardcoded strategies (C1–C6, P1–P6) are removed from the codebase entirely; the indicators, comparators, and condition primitives they were built from stay and become the building blocks for configured strategies.
- Strategy definitions live as external files in a gitignored directory; only synthetic example definitions are committed to the public repo. Every backtest result references the exact strategy definition version it was run against.
- A backend and frontend exist to create, edit, and validate strategy definitions manually (CRUD, with load-time validation that fails loudly rather than silently accepting a broken definition).
- A connector lets an AI assistant author strategy definitions through the same validated API an operator would use (an MCP server or a REST endpoint, design still open) — never bypassing validation.

Open design questions:
- What is the vocabulary of a strategy definition — entry, exit, legs, strike selection, DTE, deltas, sizing, roll rules, stop rules?
- Which of those are a configurable parameter versus a rule that genuinely needs code (a plugin system vs. an expression language vs. a smell that the model is wrong)?
- How is a definition validated at load time, and what happens on failure?
- How are definitions versioned so a backtest result stays reproducible after the definition changes?
- Is a strategy definition a domain concept, or an application-layer concern?
- The standing question to keep asking during design: "am I building a rules engine when three interfaces would do?"

## Phase 3 — Option-chain persistence (SQLite) (MENTORED)

Design questions: schema, indexing strategy, and expected data volume per chain per day; why SQLite rather than PostgreSQL at current scale; the shape of a repository port from the domain side; and the migration strategy for the schema as it evolves.

Prerequisite from the project owner: the recorded option-chain data must be usable to run both backtests and forward tests against real historical option prices, replacing the fixed-delta proxy described under "Known limitations."

## Carried forward from the May 2026 roadmap (still live)

- **OptionChainRecorder wiring** — the recorder had three stacked blockers (async/sync wiring, a no-op gateway, a TWS connectivity issue) and a locked 8-piece design for a real IBKR gateway using streaming `reqMktData` + `tickOptionComputation`. Full design preserved in "History" below.
- **Black-Scholes delta pricing** — a partial design lock (2026-05-23) exists to replace the hardcoded delta=0.60 constant with a proper N(d1) Black-Scholes delta, including a `TradePlan` field-propagation gap discovered during investigation. Full design preserved in "History" below; several decisions (IV source, risk-free rate, day-count convention, backfill policy) were left open and still need to be made under Phase 2b/3.
- **Condition logging** — extending per-condition debug logging from C1/P1 to the remaining strategy pairs was completed and verified 2026-05-23 (all 12 strategies have condition logging and dedicated tests); superseded once Phase 2b removes the hardcoded strategy classes.
- **c4/p4 opening backtest script** — `scripts/run-backtest-c4-p4.ps1` remains the way to backtest the c4/p4 opening-window fix; still useful as a regression check until those strategies are migrated to configuration.
- **BacktestEngine SRP tech debt** — `BacktestEngine` still does its own file I/O and threading; extracting `CheckpointManager`, `TradeWriter`, and `ReportBuilder` remains open and is a natural side effect of the Phase 2 hexagonal migration rather than a standalone task.

## History — May 2026 roadmap (archived)

The project's first operational phase ran from 2026-05-16 through 2026-05-23: an initial
backtest engine, materialized ticker statistics, a strategy-config table driving which of 12
hardcoded strategies (C1–C6, P1–P6) were live, a paper-trading account activation on
2026-05-18, a replay mode with a virtual clock, and the start of live paper trading with
Telegram alerts. Sessions were logged day by day; the operational detail below is preserved
only where it documents a still-referenced design decision.

**Session log (condensed, one line each):**
- 2026-05-16 — Marathon session: Polygon pagination bug fixed, Stooq DAY_1 migration, materialized `ticker_stats`, `--complete-tickers` flag (TDD), 24M rows archived.
- 2026-05-17 — First functional backtest with persistence (62 tickers, 3695 trades), first overfitting analysis, HikariCP/DevTools bug resolved.
- 2026-05-18 (Monday P0) — Pre-paper investigation, strategy-config table + REST API, paper trading started (account DUN598216, $968 USD, 3 active strategies), first day: 0 signals.
- 2026-05-19 — 12 strategies activated for paper data collection, React strategy-filter UI, replay UI fixes (play button, market-hours gate, virtual clock, Playwright E2E), `SignalQualityFilter` divergence between backtest and replay/live identified.
- 2026-05-20 — c4/p4 entry-window conflict investigated and fixed via `BacktestConfig` override, condition-logging proof-of-concept in C1/P1, `SignalQualityFilter` removed from the live/replay path.
- 2026-05-21 — Strategy-filter and script logging hardening; `OptionChainRecorder` scheduled to start capturing snapshots from market open.
- 2026-05-22 — `--strategies` CLI/PowerShell filter validated end-to-end against c4/p4 (0 trades expected for a non-earnings period).
- 2026-05-23 — Condition logging verified across all 12 strategies; Black-Scholes design lock and `TradePlan` field-propagation gap investigated; `TwsPaperConnectivityTest` fixed.

### Design lock — OptionChainRecorder Task 2 (verbatim, referenced)

**Goal**: Replace `NoOpOptionChainIbkrGateway` with a `RealOptionChainIbkrGateway` that fetches per-contract greeks via `reqMktData` streaming + `tickOptionComputation`.

**Three stacked blockers identified**:

| # | Layer | Issue | File |
|---|-------|-------|------|
| 1 | Wiring | `snapshotAsync()` never calls `snapshotSync()` | `OptionChainRecorderService.java:53-62` |
| 2 | Gateway | `NoOpOptionChainIbkrGateway` returns `null` for all calls | `options/NoOpOptionChainIbkrGateway.java` |
| 3 | Connection | TWS error 502 (not running) during the 2026-05-21 session | operator checklist |

The scheduler required zero changes — it correctly calls
`recorderService.snapshotAsync(ticker, batchId, null, "BOTH", "SCHEDULED")` at
`OptionChainScheduler.java:106`; the recorder resolves strikes/expiry/price internally.

**Implementation order (8 pieces, ~430-450 lines)**:
1. `TickOptionComputationEvent` record + consumer registration on OES (XS, ~30 lines)
2. `tickOptionComputation` override on the OES anonymous wrapper (XS, ~10-15 lines)
3. `Semaphore(5)` throttling per gateway (XS, ~10 lines)
4. Lifecycle: subscribe → wait → `cancelMktData` (S, ~70 lines)
5. `RealOptionChainIbkrGateway` class (M, ~120-140 lines)
6. `@Profile("live")` + `@Profile("!live")` routing (XS, ~15 lines)
7. Integration with the recorder (0 lines — already decoupled via the gateway port)
8. Unit + TWS-gated integration tests (M, ~180 lines)

**Design decisions locked**:
- Streaming `reqMktData` (`snapshot=false`), with an explicit `cancelMktData` on completion or timeout.
- 5-second default timeout via a `Duration` constructor parameter.
- reqId range 20000-29999 reserved for option snapshots.
- Strikes are resolved serially within `snapshotSync`; concurrency is bounded by a gateway-level `Semaphore(5)`.
- Return partial greeks if at least delta or option price is non-null; skip the strike if all fields are null.
- No caching — greeks change tick by tick.
- `@Profile("live")` activates the real gateway; the no-op gateway remains the default.

**Reuse identified**: `ContractFactory.createOptionContract` (existing, handles dot-sanitising + trading class), `OrderExecutionService.requestPriceSnapshot` (pattern for the `reqMktData` call), the `CopyOnWriteArrayList<Consumer<>>` registration pattern already used for `tickPrice`, and the `Semaphore(5)` idiom already used in `UnderlyingPriceGateway`.

**Open risks flagged at design time**: IBKR's market-data-line concurrency limit (100 lines) — the recorder uses few lines but this should be monitored; `tickOptionComputation` field semantics (field 13 = model; fields 10/11/12 may also need checking); a possible race between `cancelMktData` and the final tick delivery.

**Validation criteria**: an integration test against TWS with SPY options must assert that at least one strike returns a non-null delta within 5 seconds; after full integration, `option_chain_snapshot` should show rows with greeks populated after the first scheduled cron tick.

### Design lock — Black-Scholes delta pricing (verbatim, referenced)

**Goal**: Replace the hardcoded `BacktestConfig.OPTIONS_DELTA=0.60` constant with an N(d1) Black-Scholes delta for option-exit pricing at `BacktestEngine.java:917-920` (unrealised) and `:1022-1024` (realised).

**Locked decisions (2026-05-23)**:
- Strike and expiry are decided at strategy signal-time and propagated via `TradePlan`.
- `RiskCalculator` populates `OpenPosition.strike` and `OpenPosition.expiry` from `TradePlan`.
- Scope for this phase is delta only — gamma, vega, and theta are deferred.
- A new `OptionPricer` port with a `BlackScholesOptionPricer` implementation, mirroring the existing `UnderlyingPriceGateway` hexagonal pattern.
- No external math dependency: a 5-line Abramowitz-Stegun `Phi(d)` approximation (±0.0015 error), sufficient for backtest purposes.

**Decisions left open at lock time** (still open — candidates for Phase 2/3): implied-volatility source (single global default of 0.30 vs. per-ticker from `option_chain_snapshot`); risk-free rate (hardcoded 0.045 vs. configurable vs. an external feed); day-count convention (calendar days/365 vs. business days/252); backfill policy (apply only forward from a cutover date vs. recompute historical trades); backtest strike source (synthesized from entry price rounded to a strike increment vs. real strikes from `option_chain_snapshot`, which is only available forward from the recorder's deployment date).

**Side effect discovered**: resolving strike and expiry at signal time (instead of at order-placement time, which is what live mode currently does) introduces stale-strike risk if signal-to-order latency is high. Mitigation policy: `TradePlan.strike`/`expiryDate` are treated as preferred hints, and the order-execution service may re-resolve them at placement time if elapsed time exceeds a threshold (default candidate ~30 seconds, value not finalised).

**Scope estimate**: revised to ~685 lines after a 2026-05-23 investigation confirmed that live mode actually resolves strike and expiry at order placement (`OrderExecutionService.java:280-347`), not at signal time as originally assumed, and that `TradePlan` lacks strike/expiry/option-type fields entirely — both paths need the field propagation added.

| # | Piece | Size | Lines |
|---|---|---|---|
| 1 | `TradeRecord` field add (strike, expiryDate) | XS | 30 |
| 2 | `OpenPosition` field add (strike, expiryDate) | XS | 5 |
| 3 | `BlackScholesPricer.java` (pure function) | XS | 40 |
| 4 | Unit tests vs. Hull textbook values | XS | 60 |
| 5 | `OptionPricer` interface + impl | XS | 30 |
| 6 | `RiskCalculator` populates strike + expiry | S | 35 |
| 7 | `BacktestEngine` wiring (replace constant) | S | 50 |
| 8 | Config plumbing (IV + risk-free rate) | XS | 40 |
| 9 | CSV format update + migration | S | 80 |
| 10 | Integration test (end-to-end PnL) | S | 100 |
| 11 | `TradePlan` field add + propagation | S | 80 |
| 12 | Shared strike/expiry picker utility | S | 60 |
| **Total** | | S-M | **~685** |

**Validation criteria**: unit tests must pass against textbook Black-Scholes values (ATM/OTM/ITM scenarios from Hull); a backtest run with the Black-Scholes delta must differ from the constant-0.60 baseline by a documented, explained amount.

### Other archived items

- **p2 trend predicate subsumption**: `priceBelowMiddleBB` in `P2TrendPutStrategy` was found redundant with `isDowntrend15m` (2026-05-19); left unmodified pending backtest validation, and now moot once Phase 2b removes hardcoded strategy classes.
- **Architecture decisions carried at the time**: stay on SQLite (no PostgreSQL migration below a 100+ ticker universe), keep a small focal universe (10-20 tickers) aligned to real capital, simulate 100% of capital, options only (CALL/PUT), paper before real capital. These are superseded or re-opened by the Phase 2/3 design work above where they conflict.
