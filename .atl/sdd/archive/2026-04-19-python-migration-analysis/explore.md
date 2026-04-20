# Exploration: Python Migration Analysis

## Summary

Migration from Java to Python is already partially underway: an uncommitted `python/` directory contains a working FastAPI analytics sidecar and a Streamlit UI sidecar (~1,182 LOC), with a `docker-compose.yml` that wires them to the Java engine via REST. The core question this exploration surfaces is NOT "should we migrate" but rather "what should stay in Java forever (IBKR execution layer), what is already migrated in skeleton form (analytics/UI), and what is the realistic effort to make the Python sidecar production-quality vs a cosmetic proof-of-concept." The existing Python code does NOT replace the Java engine — it supplements it, and the supplemental pieces are partially functional but not production-ready.

---

## Findings

### 1. Existing Python Footprint

**Location:** `python/` (uncommitted, untracked per git status)

| File | Lines | Purpose |
|------|-------|---------|
| `python/analytics_service/engine.py` | 378 | `AnalyticsEngine` class: SMA, EMA, RSI, MACD, BB, ATR, STOCH calculations using `numpy`/`pandas`; Sharpe, Sortino, max-drawdown metrics; Monte Carlo simulation; grid-search placeholder (not implemented, line 374: `logger.warning("Grid search optimization not fully implemented - placeholder")`) |
| `python/analytics_service/main.py` | 353 | FastAPI service on port 8001; REST endpoints: `/api/v1/indicators`, `/api/v1/metrics`, `/api/v1/monte-carlo`, `/api/v1/market-data`, `/api/v1/account-status`; pulls candle data from Java engine via `httpx` REST calls to `/api/candles/{ticker}` |
| `python/ui_service/main.py` | 451 | Streamlit dashboard (port 8501): Backtesting page (uses random mock data, does NOT call real strategies), Monitoring page (hardcoded placeholder values: "3 active strategies", "$1,234.56 daily P&L"), Analytics tab (Monte Carlo), Settings page |
| `python/requirements.txt` | 35 | Dependencies: FastAPI, uvicorn, pandas, numpy, scipy, ta-lib-python OR pandas-ta, streamlit, plotly, redis, httpx, scikit-learn, pytest |
| `python/Dockerfile.analytics` | 18 | Python 3.11-slim image; installs requirements; runs uvicorn |
| `python/Dockerfile.ui` | — | Streamlit image |
| `docker-compose.yml` (root) | 104 | Orchestrates: redis, java-engine (port 8080), python-analytics (port 8001, `profiles: analytics`), python-ui (port 8501, `profiles: analytics`) |

**Coverage vs Java codebase:**
- Python covers: basic indicator math, performance metrics, Monte Carlo, a UI shell
- Python does NOT cover: IBKR connectivity, real-time order execution, option chain resolution, strategy logic (C1–C6, P1–P6), backtesting engine, ticker scanning, candle download, earnings filtering, news filtering

**Is migration in progress?** Yes, but only the analytics/reporting layer. The Python services are **satellites** around the Java core, not replacements. The Streamlit monitoring page uses hardcoded placeholder values — it is not connected to live data.

**Proto location:** `protos/` at project root (separate from `python/`) — also untracked.

---

### 2. Java Pain Points (Evidence-Based)

**2a. Blocking spin-wait loop in IBKR data download (`IbkrService.java:398–421`):**
```java
while (pendingRequests.contains(reqId)) {
    Thread.sleep(100);
    if (System.currentTimeMillis() - startTime > timeoutSeconds * 1000L) { break; }
}
```
This pattern appears twice: in `waitForRequestCompletion` (line 398) and in `resolveOptionChain` in `OrderExecutionService.java` (lines 205–208 and 228–231). It is a polling loop with 100ms sleep. For a solo developer's algorithmic trading system it is functional, but it does tie a thread per outstanding request. This is a medium pain point — not "callback hell" but also not reactive.

**2b. Three separate IBKR socket connections (`IbkrService`, `OrderExecutionService`, `AccountManager`):**
Each service opens its own `EClientSocket` with a distinct `clientId` (configured via `IbkrProperties`). TWS limits simultaneous client connections (default: 32). With three connections for data download, order execution, and account monitoring, connection management is non-trivial. The `OrderExecutionService.connect()` (line 143) includes a `client.eDisconnect()` in the `synchronized` connect method to force-free the client ID — evidence of real connection brittleness.

**2c. No reactive/async streaming for candle data:**
`IbkrService.downloadHistoricalData()` (line 180) is fully synchronous from the caller's perspective. The `StrategyScreenerService` screens 300+ tickers by iterating them sequentially, sleeping 2 seconds between requests (`IbkrService.java:271`). This means a full scan is O(N * 2s) = ~10+ minutes for 300 tickers. This is a concrete throughput constraint.

**2d. `closePositionViaConditions` is explicitly incomplete (`OrderExecutionService.java:343–345`):**
```java
log.warn("⚠️ Position close requested but not fully implemented - conditional orders cancelled. " +
         "Manual position closure may be required if position is still open.");
```
This is a real gap: when the user clicks "close trade" in the UI, the bracket orders are cancelled but no market sell order is placed. The state for re-placing the close (option contract, qty) is not persisted.

**2e. No significant pain in strategy logic itself:**
The 12 strategy files (C1–C6, P1–P6) are clean, stateless implementations of `TradingStrategy.isTriggered()`. They use TA4J's `BarSeries`/`Indicator` abstractions idiomatically. The `WordenStochasticIndicator` extends `CachedIndicator` correctly. No complexity hot spots here.

**2f. gRPC services defined in protos but NOT implemented in main source:**
`protos/market_data.proto` and `protos/order_status.proto` exist. Tests reference `MarketDataGrpcService` and `OrderStatusGrpcService` (`src/test/java/com/fgiaquinta/optionsquant/service/MarketDataGrpcServiceTest.java`), but `grep` found **zero gRPC service implementations in `src/main/java`**. The gRPC layer is spec'd and test-driven but not built yet — the tests are presumably failing or skipped.

---

### 3. Performance-Critical vs Migration-Safe Modules

| Module | Verdict | Reason |
|--------|---------|--------|
| `IbkrService` (historical data download) | **Stay JVM** | Uses local `TwsApi.jar` via `EClientSocket`/`EReader`. No Python equivalent of the local JAR path. Direct socket protocol. |
| `OrderExecutionService` (option bracket placement) | **Stay JVM** | Real-money order execution. Uses `TwsApi.jar` natively. Latency matters for option entries. Python ib_insync can do this but adds an async event loop complexity layer. |
| `AccountManager` (balance stream) | **Stay JVM** | Same TwsApi.jar dependency. Simple enough to port, but no reason to split ownership of IBKR connections. |
| `strategy/` (C1–C6, P1–P6) | **Hybrid candidate** | Pure logic, no I/O. Easy to port to Python. But they depend on TA4J's `BarSeries` API today — porting means re-implementing the indicator wrapper API. Python pandas-ta or TA-Lib cover all the indicators used (SMA, EMA, Bollinger Bands, Stochastic). |
| `backtest/` (BacktestReporter, FillEngine) | **Could migrate** | Pure computation on candle data. No IBKR dependency. Python pandas is better suited for vectorized backtest computation than TA4J. This is where Python adds most value. |
| `service/BacktestAnalyzer` | **Could migrate** | Reads a CSV, computes stats. Trivially portable. Python already does this in `analytics_service/engine.py`. |
| `service/OllamaService` | **Stay JVM** | Calls local Ollama REST API. Language-neutral. No migration value. |
| `service/NewsFilterService` | **Neutral** | HTTP calls, simple filtering. Either language works. |
| `service/StrategyScreenerService` | **Hybrid candidate** | The screening loop (O(N*2s)) would benefit from async Python. But the bottleneck is the IBKR data rate limit, not the JVM. |
| `controller/` (REST endpoints) | **Stay JVM** | The React frontend calls `/live-ui/**` and `/backtest-ui/**` — these are tightly coupled to the Java service model. Migrating would require rewriting all endpoints AND the frontend integration. |
| `analytics_service/` (Python, already exists) | **Already Python** | Indicators, metrics, Monte Carlo. Working sidecar. |

---

### 4. IBKR Python Ecosystem

**Primary Python clients:**

| Library | Maturity | Notes |
|---------|----------|-------|
| `ib_insync` | Semi-abandoned (last release 2022, Python 3.11 issues) | Originally the best Python IBKR wrapper. Built on asyncio. Wraps the official C API protocol. Issues: maintenance dropped, not updated for newer TWS versions. |
| `ibapi` (official IBKR Python client) | Active but low-level | Official client, mirrors the Java API structure. Callback-based like Java `EWrapper`. Same awkward threading model. No ergonomic improvements over Java. |
| `ib-async` (fork of ib_insync) | Active fork (2023+) | Maintained fork of ib_insync fixing Python 3.11/3.12 compatibility. Uses asyncio. |

**Feature parity analysis against actual IBKR callbacks used in this project:**

The project uses these specific `EWrapper` callbacks (from `IbkrCallbackHandler.java` and `AccountManager.java`):
- `nextValidId` — available in all Python clients
- `historicalData` / `historicalDataEnd` — available in all Python clients
- `connectAck` — available in all Python clients
- `contractDetails` / `contractDetailsEnd` — available in all Python clients
- `securityDefinitionOptionalParameter` — available in `ib-async` and `ibapi`; this is the callback used for option chain resolution and is the most critical one
- `updateAccountValue` — available in all Python clients
- `error` (with `advancedOrderRejectJson` parameter) — only in newer versions; `ib-async` supports it

**Assessment:** Feature parity is achievable with `ib-async`. The risk is that `ib-async` uses asyncio, which means the current synchronous polling model (spin-wait loops) would need to be rewritten as coroutines. This is a non-trivial refactor, not a 1:1 port. The official `ibapi` mirrors the Java API exactly but provides zero improvement in developer experience.

---

### 5. TA4J and Indicator Libraries

**Indicators actually used in `strategy/`:**
- `SMAIndicator` (C1, C2, C4, C5, C6, P-series)
- `ClosePriceIndicator` (all strategies)
- `BollingerBandsUtil` (custom wrapper in `strategy/utils/BollingerBandsUtil.java` — wraps TA4J's `BollingerBandsMiddleIndicator`, `BollingerBandsUpperIndicator`, `BollingerBandsLowerIndicator`)
- `WordenStochasticIndicator` (custom indicator extending `CachedIndicator`, used by C5/P5)
- Implicit: ATR, channel analysis, trend analysis via `strategy/utils/`

**Python equivalents:**

| TA4J indicator | Python equivalent | Gap |
|----------------|-------------------|-----|
| `SMAIndicator` | `pandas.rolling().mean()` or `pandas_ta.sma()` | None — trivial |
| `ClosePriceIndicator` | `df['close']` directly | None |
| `BollingerBandsMiddle/Upper/Lower` | `pandas_ta.bbands()` or manual via `rolling().mean()` + `rolling().std()` | None; Python's analytics_service already implements this at line 130–141 of engine.py |
| `WordenStochasticIndicator` | No standard library equivalent — custom percentile rank formula | **Must be ported manually.** Formula is simple (line 47: `(100.0 / (period - 1)) * rank`) but needs to be re-implemented |
| TA4J `BarSeries` / `CachedIndicator` | No equivalent concept in pandas-ta | **This is the real migration cost.** TA4J provides index-based, lazy, cached evaluation. Python strategies would need to operate on `pd.DataFrame` slices instead. All strategy logic references `series.getBar(idx)` and `indicator.getValue(idx)` — this API must be replaced. |

**What migration of `strategy/` would actually look like:**
Each `isTriggered(ticker, StrategyData data, ZonedDateTime currentTime)` method would become a function accepting a `pd.DataFrame` and a row index. The `StrategyData.getIndexForTime()` lookup becomes `df.index.get_loc(timestamp)`. The `BarSeries` slicing becomes `df.iloc[idx-lookback:idx]`. The `BollingerBandsUtil.isRidingUpperBand()` check becomes a direct pandas comparison. This is doable for each strategy but requires rethinking the `StrategyData` abstraction — it's not a drop-in port, it's a reimplementation.

---

### 6. Frontend Contract Surface

The React frontend (`frontend/src/api.js`) calls **REST only** — no gRPC from the browser:

**Live mode endpoints (all under `/live-ui/`):**
- GET: `status`, `signals`, `tickers`, `tws-status`, `market-status`, `scan-activity`
- POST: `scan-now`, `stop-scan`, `force-stop`, `toggle-extended-hours`, `toggle-auto-execute`, `toggle-scheduler`, `set-risk`, `set-scan-filter`, `set-max-concurrent`, `toggle-mock-market`, `inject-mock-signal`, `close-trade`, `execute-signal`, `execute-trade`, `cancel-trade`

**Backtest endpoints (all under `/backtest-ui/`):**
- POST: `run`, `stop`, `checkpoint/clear`, `set-max-concurrent`, `improve/{name}`, `retest/{name}`
- GET: `running`, `checkpoint`, `max-concurrent`

**Health endpoints:** `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`

**Impact of backend migration on frontend:** The frontend has no gRPC dependency — it is pure REST over HTTP. If the Java backend is replaced/migrated with a Python FastAPI service that exposes the same REST contract, the frontend would require **zero changes**. However, the Python analytics sidecar today does NOT implement any of these endpoints — it only has `/api/v1/indicators`, `/api/v1/metrics`, `/api/v1/monte-carlo`. The live dashboard and backtest endpoints would need to be rebuilt from scratch in Python.

---

### 7. Protobuf Contracts

**Defined in `protos/` (project root, untracked):**

**`market_data.proto`:**
- Service: `MarketDataService`
- RPCs: `SubscribeMarketData(MarketDataRequest) → stream MarketDataUpdate`, `GetHistoricalData(HistoricalDataRequest) → stream HistoricalDataBar`
- Messages: `MarketDataRequest`, `MarketDataUpdate`, `HistoricalDataRequest`, `HistoricalDataBar`

**`order_status.proto`:**
- Service: `OrderStatusService`
- RPCs: `GetOpenOrders`, `GetOrderStatus`, `SubscribeOrderUpdates`
- Messages: `Order`, `OrderStatus`, `OrderStatusUpdate`

**Boundary viability:** Python has `grpcio` and `grpcio-tools` (already in `requirements.txt`) which can generate Python stubs from these `.proto` files. The generated stubs are fully compatible. The Python analytics sidecar already declares `grpcio>=1.59.0` in requirements — it was originally designed to consume gRPC from the Java server, though the gRPC client code was later replaced with REST (`JavaApiClient` uses `httpx`, not gRPC stubs).

**Key discovery:** The gRPC services (`MarketDataGrpcService`, `OrderStatusGrpcService`) have test files but **no production implementation in `src/main/java`**. The protos are designs, not deployed contracts. The Python sidecar's `MarketDataRequest` Pydantic model in `main.py` (line 64) mirrors the proto structure but is not actually wired to gRPC. This means the "gRPC boundary" the original hybrid recommendation was based on does not exist in production yet.

---

### 8. Architectural Options Surfaced

**Option A: Status Quo + Complete the Python Analytics Sidecar**
- What it means: Keep Java as the core trading engine (IBKR, strategies, controllers). Complete the Python sidecar with real data connections (replace placeholder Streamlit values with actual API calls). Add Monte Carlo and advanced analytics as a separate service consumed by the React frontend or alongside it.
- Effort: ~2–3 weeks. The sidecar scaffolding exists; the main work is wiring the Streamlit pages to real `/live-ui/**` endpoints.
- Reversibility: Fully reversible. Python sidecar is additive, Java is unchanged.
- Risk: Duplicate tech stacks to maintain. React frontend + Streamlit dashboard = two UIs for similar things.

**Option B: Migrate Backtest Layer to Python, Keep Java for Live Trading**
- What it means: Replace `backtest/` and `service/BacktestAnalyzer` with Python (pandas + vectorized ops). Keep all IBKR connectivity, order execution, and live scanning in Java. Expose backtest results via the Python analytics FastAPI.
- Effort: ~4–6 weeks. Backtest engine reimplementation is non-trivial (FillEngine, SimulatedFillEngine, trade chart generation), but there are no IBKR dependencies here.
- Reversibility: Medium. The backtest and live paths are currently co-deployed in one Spring Boot app — splitting them requires extracting the backtest REST endpoints from `LiveModeController` and routing them to Python.
- Risk: Two runtimes to deploy and coordinate. The Java app still serves `/backtest-ui/**` — either migrate those endpoints to Python (frontend change needed) or keep a Java proxy layer.

**Option C: Full Migration — Python Replaces Java Engine**
- What it means: Port IBKR connectivity, all 12 strategies, backtesting, and REST controllers to Python. Use `ib-async` for IBKR. Use pandas/TA-Lib for indicators. Use FastAPI for REST.
- Effort: 12–20 weeks minimum (solo developer). Every TA4J `BarSeries` reference must be reimplemented. The `EWrapper` callback model maps awkwardly to asyncio. The `securityDefinitionOptionalParameter` callback (for option chain resolution) must be verified in `ib-async`.
- Reversibility: Effectively irreversible once Java is removed.
- Risk: HIGH. Option execution (real money) on a new IBKR client library introduces regression risk with no easy rollback. The `closePositionViaConditions` bug is already open in Java — migrating doesn't fix it, it inherits it.

---

## Open Questions for Proposal Phase

1. **Is the Streamlit UI meant to replace the React dashboard, coexist with it, or be abandoned?** The docker-compose puts both on different ports (3000/React vs 8501/Streamlit). Maintaining two UIs for the same data is a maintenance burden. The proposal must take a position.

2. **Should the Python analytics sidecar consume gRPC or REST from Java?** The `requirements.txt` includes `grpcio` but the code uses REST (`httpx`). The protos exist but the gRPC server is not implemented. If the proposal includes building the gRPC server, the Python sidecar gains a real-time streaming path. If not, REST polling is the final architecture.

3. **What is the acceptance criterion for "production-ready" Python analytics?** The current Monte Carlo and indicator implementations in `engine.py` are functional but the grid-search optimization is a placeholder (line 374). Is that acceptable for the first production cut, or must it be complete?

4. **Is the `closePositionViaConditions` gap (OrderExecutionService.java:343) in scope for any migration work?** This is an open correctness bug independent of the Java/Python question. A migration that doesn't fix this is not an improvement.

5. **What is the target deployment topology?** The `docker-compose.yml` has the Python analytics under `profiles: analytics` — meaning it's opt-in, not part of the default deployment. Is containerized deployment the target, or does this run directly on the developer's Windows machine alongside TWS?

---

## Recommended Next Phase

**`sdd-propose`** — the exploration has surfaced enough concrete evidence to make a binding architectural decision. The key inputs for the proposal are:

1. Option A (complete the sidecar) is the lowest-risk, lowest-effort path and is clearly what the existing `python/` code was designed for.
2. Option B (migrate backtest) is valuable but requires splitting a monorepo deployment.
3. Option C (full migration) is high-risk and not justified by any measured pain point in the current Java code.

The proposal should define: which option, what the definition of done looks like, and whether the Streamlit UI should be promoted to primary or abandoned in favor of the React dashboard.
