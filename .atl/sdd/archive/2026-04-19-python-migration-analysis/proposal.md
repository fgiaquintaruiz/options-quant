# Change Proposal: Python Migration Analysis

## Intent

Decide — with evidence, not preference — whether and how to migrate functionality from the Java trading engine to Python. An uncommitted `python/` sidecar already exists (FastAPI + Streamlit, ~1,182 LOC) and `docker-compose.yml` already wires it alongside the Java engine, so the status quo is unstable: the system is mid-migration without a documented endpoint. This proposal selects a single architectural path, justifies it against the three concrete pain points surfaced in exploration, and defines the boundaries of what the next phases (spec, design, tasks) will actually deliver.

## Current State (from Exploration)

- **IBKR core must stay Java.** `IbkrService`, `OrderExecutionService`, and `AccountManager` depend on the local `TwsApi.jar`, which has no drop-in Python equivalent. Real-money order execution runs through these services (explore.md §3).
- **Python sidecar already exists, uncommitted.** `python/analytics_service/engine.py` (378 LOC) implements SMA/EMA/RSI/MACD/BB/ATR/STOCH, Sharpe, Sortino, max-drawdown, and Monte Carlo; `python/ui_service/main.py` (451 LOC) is a Streamlit shell with **hardcoded placeholder values** ("3 active strategies", "$1,234.56 daily P&L") and a Backtesting page that runs on **random mock data**, not real strategies (explore.md §1).
- **Grid-search optimization is a placeholder in the sidecar.** `engine.py:374` logs `"Grid search optimization not fully implemented - placeholder"` (explore.md §1).
- **Three evidence-based Java pain points (explore.md §2):**
  1. Blocking spin-wait polling in IBKR data download — `IbkrService.java:398–421` and `OrderExecutionService.java:205–208, 228–231`. 100ms sleep loops that tie a thread per outstanding request.
  2. Sequential ticker scanning with 2-second delays — `IbkrService.java:271`. Full scan of 300+ tickers is O(N*2s) = ~10+ minutes, bounded by the IBKR rate limit, not the JVM.
  3. `closePositionViaConditions` is explicitly incomplete — `OrderExecutionService.java:343–345`. When the UI requests a position close, bracket orders are cancelled but no market sell is placed. Open correctness bug, independent of language.
- **Strategy logic (C1–C6, P1–P6) has no pain.** Twelve clean, stateless TA4J-based strategies (explore.md §2e). Porting cost is driven by TA4J `BarSeries`/`CachedIndicator` → pandas DataFrame reimplementation, not by logic complexity (explore.md §5).
- **Frontend contract is REST-only.** React (`frontend/src/api.js`) has zero gRPC usage. Live mode: 5 GET + 14 POST under `/live-ui/`. Backtest: 3 GET + 6 POST under `/backtest-ui/`. Any Python backend that keeps the same REST shape requires zero frontend change (explore.md §6).
- **gRPC contracts exist but are not implemented.** `protos/market_data.proto` and `protos/order_status.proto` are untracked; test files reference `MarketDataGrpcService` / `OrderStatusGrpcService`, but there is zero gRPC implementation in `src/main/java`. The "gRPC boundary" cited by the prior analysis does not exist in production (explore.md §7).
- **IBKR Python options: `ib-async` is the only viable client.** `ib_insync` is semi-abandoned; `ibapi` (official) replicates the Java ergonomics exactly; `ib-async` is the maintained fork. All use asyncio, which is a non-trivial rewrite from the current synchronous polling model (explore.md §4).

## Architectural Options Considered

### Option A — Complete the Python Analytics Sidecar; keep Java core

- **Description**: Keep the Java engine as the system of record for IBKR, strategies, backtests, and REST APIs. Finish wiring the existing `python/` sidecar: replace Streamlit placeholder values with real calls into `/live-ui/**` and `/backtest-ui/**`, implement the grid-search optimizer that is a placeholder in `engine.py:374`, and promote the sidecar from `profiles: analytics` opt-in to a first-class deployment component (or explicitly abandon it if redundant with the React dashboard).
- **Keeps in Java**: `IbkrService`, `OrderExecutionService`, `AccountManager`, all strategies (C1–C6, P1–P6), `backtest/`, all `controller/` REST endpoints, `StrategyScreenerService`, `OllamaService`, `NewsFilterService`.
- **Moves to Python**: Nothing net-new moves. The already-present analytics responsibilities (indicator math, Monte Carlo, performance metrics, grid-search optimizer) are completed in Python.
- **Effort order-of-magnitude**: **S–M** (~2–3 weeks solo). Scaffolding exists. Work is mostly HTTP wiring from Streamlit → Java `/live-ui/**` and implementing one optimizer.
- **Reversibility**: **Easy**. Python sidecar is purely additive. Removing it leaves the Java engine untouched.
- **Key risks** (from explore.md):
  - Two UIs for overlapping purposes (React at 3000, Streamlit at 8501) creates duplicate maintenance.
  - `ib-async`/ibapi asyncio mismatch doesn't apply here — the sidecar doesn't touch IBKR.
  - Grid-search completion is the only real functional gap.
- **Addresses pain points**:
  - 2a (spin-wait) — **No.** IBKR data path stays in Java.
  - 2c (sequential scan) — **No.** Scan loop stays in Java; bottleneck is the IBKR rate limit anyway (explore.md §2c).
  - 2d (`closePositionViaConditions`) — **No.** Java bug remains.

### Option B — Migrate Backtest Layer to Python; keep Java for live trading

- **Description**: Extract `backtest/`, `BacktestAnalyzer`, and backtest-related REST endpoints (`/backtest-ui/**`) out of the Java Spring Boot app into the Python FastAPI sidecar. Backtests read candle CSVs (no IBKR dependency) and compute strategy results using pandas. The React frontend either gets rewired to hit the Python service directly for backtest endpoints, or the Java app keeps a thin proxy.
- **Keeps in Java**: All of `IbkrService`, `OrderExecutionService`, `AccountManager`, `strategy/` (for live mode), `/live-ui/**` endpoints, scanner.
- **Moves to Python**: `backtest/BacktestReporter`, `backtest/FillEngine`, `backtest/SimulatedFillEngine`, `service/BacktestAnalyzer`, the 12 strategies reimplemented on pandas (required — because backtest must run them), `/backtest-ui/**` REST endpoints on the FastAPI side.
- **Effort order-of-magnitude**: **L** (~4–6 weeks solo). The non-trivial work is reimplementing the 12 strategies on pandas DataFrames (TA4J `BarSeries` has no Python equivalent — explore.md §5) AND reimplementing `FillEngine` vectorized. Strategy logic must be duplicated because live mode stays in Java and needs the same triggers — this creates a two-implementation maintenance tax.
- **Reversibility**: **Moderate**. Backtest endpoints live in one service (`LiveModeController`) today; splitting them across runtimes is a one-way architectural cut. Rolling back means re-importing the strategies into Java.
- **Key risks**:
  - **Two implementations of every strategy** (Java for live, Python for backtest). Any strategy change must land in both, with two test suites. Silent drift is the default failure mode.
  - `WordenStochasticIndicator` has no pandas_ta equivalent and must be manually ported (explore.md §5).
  - Frontend routing: either the React client learns two backend URLs, or a Java proxy forwards `/backtest-ui/**` → Python — extra hop, extra failure mode.
- **Addresses pain points**:
  - 2a (spin-wait) — **No.** IBKR path unchanged.
  - 2c (sequential scan) — **No.** Scanner stays in Java.
  - 2d (`closePositionViaConditions`) — **No.**
  - **Net:** introduces strategy-duplication pain without fixing the three documented pain points.

### Option C — Full Migration: Python replaces the Java engine

- **Description**: Port everything to Python: IBKR connectivity via `ib-async`, all 12 strategies on pandas, backtest engine on pandas, all 28 REST endpoints on FastAPI. Java is deleted.
- **Keeps in Java**: Nothing.
- **Moves to Python**: Everything.
- **Effort order-of-magnitude**: **XL** (12–20+ weeks solo). Every TA4J `BarSeries`/`CachedIndicator` reference must be reimplemented. The `EWrapper` synchronous-polling + `pendingRequests` model maps awkwardly to asyncio. `securityDefinitionOptionalParameter` callback (option chain resolution — the critical path for this system) must be re-verified on `ib-async`.
- **Reversibility**: **Hard / effectively irreversible** once Java is removed and production accounts are trading on the Python client.
- **Key risks** (from explore.md §8):
  - Real-money option execution on a library (`ib-async`) that has been maintained by a different party since 2023, with no rollback path.
  - Three IBKR socket connections today (`IbkrService`, `OrderExecutionService`, `AccountManager`) — connection brittleness is already evident in Java (`OrderExecutionService.connect():143` force-disconnects to reclaim client IDs). Replicating this in asyncio is a new class of concurrency bugs.
  - `closePositionViaConditions` bug (pain point 2d) is inherited, not fixed, by porting.
  - Solo developer, no rollback partner.
- **Addresses pain points**:
  - 2a (spin-wait) — **Partially.** Asyncio eliminates the polling loop conceptually, but `ib-async` still dispatches via callbacks; await points replace sleeps. Win is real but modest.
  - 2c (sequential scan) — **No.** IBKR rate limit is external; asyncio doesn't speed up a rate-limited API.
  - 2d (`closePositionViaConditions`) — **No.** Bug is logic, not language.

### Option D — Fix the three Java pain points in place; freeze Python sidecar

- **Description**: Stay in Java for everything. Fix the actual documented pain points directly: replace the spin-wait loops with `CompletableFuture`-based async request tracking, implement `closePositionViaConditions` correctly (persist option contract + qty on bracket placement, place market close on demand), and decide the scanning throughput question (either accept the IBKR rate limit or parallelize across the 3 existing connections). Delete `python/` or mark it as "experimental, not part of production".
- **Keeps in Java**: Everything.
- **Moves to Python**: Nothing. Sidecar is frozen or deleted.
- **Effort order-of-magnitude**: **S–M** (~1–2 weeks solo). Pain point 2d is the largest piece (persist position state + implement close). Pain points 2a and 2c are refactors.
- **Reversibility**: **Easy**. In-place Java changes, standard git revert.
- **Key risks**:
  - No Python analytics capability going forward — if Monte Carlo / grid-search is actually wanted, it won't exist.
  - Deletes work already partially done in `python/`.
- **Addresses pain points**:
  - 2a — **Yes, directly.**
  - 2c — **Yes, directly** (to the extent the IBKR rate limit permits).
  - 2d — **Yes, directly.** This is the correctness bug and this option is the only one that fixes it as a first-class goal.

## Recommended Option

**Option A — Complete the Python Analytics Sidecar; keep Java core.**

Combined with an **explicit, bounded side-track** during the same change cycle to fix pain point **2d (`closePositionViaConditions`)** in Java, because that bug is independent of the Java/Python question and ignoring it lets a known correctness gap stay in production.

### Rationale

1. **The existing Python code was designed for exactly this shape.** `analytics_service/engine.py` implements indicators, Monte Carlo, and performance metrics; `ui_service/main.py` is a Streamlit shell wired via `httpx` to `/api/candles/{ticker}` on the Java engine. There is no mismatch between "what Python does today" and "what Python is good at" — analytics on historical data, vectorized math, scientific-stack ergonomics. This is what pandas/numpy/scipy are for (explore.md §1, §5).
2. **The three documented Java pain points do NOT justify migration.** Pain points 2a (spin-wait) and 2c (sequential scan) are bounded by the IBKR rate limit and the synchronous `EWrapper` callback model, neither of which Python escapes — `ib-async` uses asyncio but the IBKR server still rate-limits externally (explore.md §2c, §4). Pain point 2d (`closePositionViaConditions`) is a logic bug that ports to any language unchanged. None of the three pain points is "Java is the wrong runtime" — they are local defects.
3. **Strategy logic has NO pain.** The 12 strategies (C1–C6, P1–P6) are clean, stateless, idiomatic TA4J (explore.md §2e). Porting them is gratuitous; the migration cost is entirely the `BarSeries`/`CachedIndicator` → pandas reimplementation, which is work with no user-visible payoff (explore.md §5).
4. **Option B introduces strategy duplication.** If live trading stays in Java but backtest moves to Python, the 12 strategies must exist in both runtimes and stay synchronized forever. Silent drift is the default failure mode. The exploration does not document any backtest-specific pain that would pay for this tax.
5. **Option C is unjustifiable given the evidence.** 12–20 weeks of solo-developer work, real-money execution on a library the author has not operated in production, effectively irreversible. Exploration surfaced zero findings that describe a benefit proportionate to that cost.
6. **The frontend is REST-only and Java-controlled.** React calls `/live-ui/**` and `/backtest-ui/**` directly from the Java app (explore.md §6). Keeping the core Java + adding a Python sidecar preserves that contract; any other option perturbs it.
7. **Bundling the 2d fix** costs ~2–3 days of incremental work on top of Option A's ~2–3 weeks, eliminates the most embarrassing open defect, and does not require any Python decision.

### Why not the alternatives

- **Not B** because it duplicates strategy code without fixing any documented pain point and introduces deployment-topology complexity (frontend must route to two backends, or a proxy must be built).
- **Not C** because the risk-vs-evidence ratio is indefensible: real-money order execution regression on a solo-dev timeline, no rollback, no exploration finding that even proposes a benefit proportional to the cost. Explore.md §8 is explicit: "high-risk and not justified by any measured pain point."
- **Not D (pure Java, freeze Python)** because the `python/` sidecar already exists with ~750 LOC of working analytics code, and the grid-search / Monte Carlo / Sortino / max-drawdown work is genuinely easier in Python than in Java. Deleting it throws away free leverage. However, D's instinct — "fix 2d in Java" — is correct and is folded into Option A.

## Scope of This Change

**In scope** (what the next phases will actually produce):

- A specification and design for the Python analytics sidecar reaching production-ready state:
  - Streamlit Monitoring page reads live data from `/live-ui/status`, `/live-ui/signals`, `/live-ui/tickers` instead of hardcoded values.
  - Streamlit Backtesting page drives a real backtest by POSTing to `/backtest-ui/run` and rendering the response, replacing the random-mock-data implementation.
  - `AnalyticsEngine.optimize_parameters` (`engine.py:374`) is implemented (not a placeholder) with a documented grid-search strategy.
  - Clear deployment story: decide whether the sidecar is `profiles: analytics` opt-in or default-on in `docker-compose.yml`.
- An explicit resolution of the "Streamlit vs React" question (see Open Questions): either Streamlit is a complementary analytics surface with a scoped role, or Streamlit is removed entirely and only `analytics_service` (FastAPI) stays.
- A bundled Java fix for `OrderExecutionService.closePositionViaConditions`:
  - Persist option contract + quantity at bracket-placement time.
  - Implement market-sell close path using the persisted state.
  - Cover with unit test (mock IBKR client) so the fix is verifiable without a live TWS.
- The `python/` and `protos/` directories and the new `Dockerfile.java` / `docker-compose.yml` are brought under version control (they are currently untracked).

**Out of scope** (this change will explicitly NOT do):

- No port of any Java strategy to Python.
- No port of IBKR connectivity, order execution, or account management to Python.
- No port of the backtest engine to Python.
- No migration of any REST endpoint from Java to Python.
- No implementation of the gRPC services in `protos/`. Those proto files remain designs; `MarketDataGrpcService` and `OrderStatusGrpcService` stay test-only until a separate change proposes implementing them.
- No decision on "should we migrate more to Python later" — that is deferred and will require its own exploration if revisited.
- No new React frontend features beyond what is needed for the `closePositionViaConditions` fix (the UI already calls `/close-trade`; only the server-side behavior changes).

## Success Criteria

1. **Streamlit placeholders removed.** Grepping `ui_service/main.py` for the literal strings `"3 active strategies"` and `"$1,234.56"` (and any other hardcoded mock) returns zero matches. Monitoring page values come from live HTTP responses.
2. **Backtest page runs a real backtest.** A click on "Run Backtest" in Streamlit results in a POST to the Java `/backtest-ui/run` endpoint, and the rendered results match the Java backtest output for the same inputs (verified by side-by-side run vs the existing React `/backtest` dashboard).
3. **Grid-search optimizer is implemented.** `engine.py:374` no longer logs `"placeholder"`. Function returns a ranked parameter set for a documented search space. Covered by a pytest test with a deterministic seed.
4. **`closePositionViaConditions` closes positions.** In a unit test with a mocked IBKR client, invoking close after a bracket placement results in (a) bracket cancellation AND (b) a market sell order for the correct option contract and quantity.
5. **Artifacts are committed.** `python/`, `protos/`, `docker-compose.yml`, and `Dockerfile.java` are tracked in git at the end of the change. `.gitignore` is updated if needed.
6. **Docker deployment is documented.** Running `docker compose --profile analytics up` (or whatever is decided) brings Java + Python sidecar + Redis up and the Streamlit dashboard shows live data from the running Java engine.
7. **Zero regressions in live trading flow.** Existing manual smoke test — IBKR connect, scan, signal generation, bracket placement — succeeds unchanged (pre-change vs post-change parity).
8. **Streamlit-vs-React question is answered in writing** in the design document: either "Streamlit is the analytics surface, React is the trading surface, here are their non-overlapping roles" or "Streamlit is removed; only FastAPI sidecar stays, consumed by React via a new `/analytics/**` proxy or directly."

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Streamlit and React drift into redundant UIs with overlapping features | High | Medium | Success Criterion 8 forces an explicit decision during design; if redundant, Streamlit is cut during this change, not left as tech debt. |
| Grid-search implementation expands in scope (optimization infrastructure, CI sweeps, etc.) | Medium | Medium | Spec phase caps this at a single documented search strategy (e.g., exhaustive on a bounded grid) with one test; further optimization work is a separate change. |
| `closePositionViaConditions` fix requires persisting state across JVM restarts | Medium | High | Design phase decides: in-memory map is acceptable if the engine is considered single-process and crash-recoverable via IBKR open-orders query, otherwise persist to JSON like `data/ticker-memory.json`. Explicit call-out, not discovered mid-apply. |
| Python sidecar wiring exposes undocumented Java REST response shapes | Medium | Medium | Spec phase enumerates exactly which `/live-ui/**` and `/backtest-ui/**` endpoints the sidecar will consume and locks down the response contract before Streamlit work starts. |
| Untracked `python/` directory is lost or diverged when finally committed | Low | High | First apply batch: commit `python/`, `protos/`, `docker-compose.yml`, `Dockerfile.java` as-is under the existing `.gitignore`, before any logic changes. Preserves the starting state. |
| Scope creep into Option B/C territory ("while we're here, let's port one strategy") | Medium | High | This proposal explicitly declares strategy-porting out of scope (see Scope §Out of scope). Verify phase checks this hasn't happened. |
| `ib-async` asyncio mismatch bites a future change | N/A for this proposal | — | Not relevant — no IBKR code moves to Python in this change. Flagged only so a later proposal revisits it. |

## Open Questions

These MUST be resolved during the spec + design phases (not during apply):

1. **Streamlit's role: complementary or replaced?** The design document must state one of:
   (a) Streamlit stays as the analytics / research surface; React stays as the live-trading surface; their scopes do not overlap.
   (b) Streamlit is removed; the FastAPI sidecar alone serves analytics, and React gains a new analytics tab that consumes it.
   This is a single binary decision, not a discussion.
2. **Python ↔ Java transport: REST only or add gRPC?** Today it is REST via `httpx`. The protos exist but no gRPC server is implemented. The design must either commit to REST-only (delete `grpcio` from `requirements.txt` and move protos to a "future work" location) or scope a `MarketDataGrpcService` implementation separately — but that is NOT in this change.
3. **Deployment topology**: Docker Compose (default-on or profile-gated) vs direct Windows install alongside TWS. The design must pick one target and document startup.
4. **`closePositionViaConditions` state persistence**: in-memory vs disk-backed. Design decides based on JVM restart policy.

The following are NOT open questions — they are resolved by this proposal:

- Will IBKR code move to Python? **No.**
- Will strategies be ported to Python? **No.**
- Will the backtest engine be ported to Python? **No.**
- Will the gRPC services be implemented in this change? **No.**

## Constraints

- Solo developer (Fabio). No team coordination overhead. No "team decision" language applies.
- Must not break the existing live trading flow during analysis or implementation. The Java engine's `/live-ui/**` path is production-critical.
- IBKR integration uses local `TwsApi.jar` — no drop-in Python replacement available. This constraint is structural and bounds every option above.
- The `python/` directory is currently uncommitted; any file-level decisions assume those files are the canonical starting state.
- `docker-compose.yml` is also untracked; it is the reference topology.

## Related Items

- **Exploration**: `.atl/sdd/python-migration-analysis/explore.md` (on-disk) / engram topic `sdd/python-migration-analysis/explore` (id 112).
- **Supersedes**: `tws-ids-toolbar-refactor` — marked non-binding on 2026-04-19. Its prior "hybrid Java+Python recommended" conclusion was zero-evidence and is replaced by this document.
- **Next phase**: `sdd-spec` and `sdd-design` (can run in parallel; design informs spec on the Streamlit-vs-React decision).

## Stakeholders

- **Fabio** — solo developer, decision owner, operator. Single point of accountability for apply + verify.
