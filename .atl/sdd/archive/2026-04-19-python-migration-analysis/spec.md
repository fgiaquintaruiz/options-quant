# Delta for Python Migration Analysis — Sidecar Completion

## ADDED Requirements

### Requirement: Streamlit Monitoring Page Connects to Live Data

The Streamlit Monitoring page MUST fetch real-time data from the Java engine instead of using hardcoded placeholder values.

- GIVEN Streamlit Monitoring page is loaded in browser
- WHEN page renders the metrics cards at the top
- THEN each metric value MUST come from an HTTP GET request to a `/live-ui/**` endpoint
- AND the request MUST target the Java engine on port 9090 (or configured port)

#### Scenario: Fetch Active Strategy Count

- GIVEN Streamlit Monitoring page loads
- WHEN it renders the "Active Strategies" metric card
- THEN it MUST call GET `/live-ui/status` and extract the count from the response
- AND fallback to "—" if the endpoint is unreachable

#### Scenario: Fetch Daily P&L

- GIVEN Streamlit Monitoring page loads
- WHEN it renders the "Daily P&L" metric card
- THEN it MUST call GET `/live-ui/tws-status` to retrieve account data
- AND extract the balance delta from the response
- AND display as formatted currency (e.g., "$1,234.56")

#### Scenario: Fetch Open Positions

- GIVEN Streamlit Monitoring page loads
- WHEN it renders the "Open Positions" metric card
- THEN it MUST call GET `/live-ui/tws-status` and retrieve `activeTrades`
- AND display the count

#### Scenario: Display Signal List

- GIVEN Signals tab is selected in Monitoring page
- WHEN signals are displayed in a table
- THEN it MUST call GET `/live-ui/signals` and render each signal as a table row
- AND the table MUST include columns: Ticker, Strategy, Direction, Price, Timestamp

### Requirement: Streamlit Backtesting Page Executes Real Backtests

The Streamlit Backtesting page MUST trigger actual backtest runs via the Java API instead of generating random mock data.

- GIVEN user configures backtest parameters (symbol, dates, initial capital)
- AND clicks "Run Backtest"
- THEN the page MUST POST to `/backtest-ui/run` with the selected parameters
- AND wait for the response
- AND render the returned backtest report

#### Scenario: Run Backtest with Parameters

- GIVEN user selects "AAPL" as symbol, "2025-01-01" to "2025-12-31" as dates
- AND configures $50,000 initial capital
- WHEN "Run Backtest" is clicked
- THEN Streamlit MUST POST to `http://java-host:9090/backtest-ui/run` with params: `from=2025-01-01&to=2025-12-31&tickers=AAPL&initialCapital=50000`
- AND display the returned `BacktestReport` fields: totalTrades, totalReturnPct, winRate, sharpeRatio

#### Scenario: Handle Backtest API Errors

- GIVEN user clicks "Run Backtest" but the Java engine is unreachable
- WHEN the POST request fails with a network error
- THEN the page MUST display an error message: "Unable to connect to backtest engine. Ensure Java service is running."
- AND allow retry without page refresh

#### Scenario: Display Backtest Results Chart

- GIVEN backtest returns successfully
- WHEN results are available
- THEN the page MUST render a chart showing equity curve over time
- AND overlay a table with summary metrics

### Requirement: Grid-Search Optimizer Implemented

The `AnalyticsEngine.grid_search_optimization` method in `python/analytics_service/engine.py` MUST be a functional implementation, not a placeholder.

- GIVEN a strategy function and parameter grid
- WHEN `grid_search_optimization()` is called
- THEN the method MUST iterate over all parameter combinations
- AND execute the strategy function for each combination
- AND return the best parameters ranked by the specified metric

#### Scenario: Exhaustive Grid Search

- GIVEN param_grid = {"period": [10, 20, 30], "threshold": [0.5, 1.0, 1.5]}
- AND data = a DataFrame with OHLCV columns
- AND metric = "sharpe_ratio"
- WHEN `grid_search_optimization()` executes
- THEN it MUST run strategy_func 9 times (3×3 combinations)
- AND compute sharpe_ratio for each run
- AND return `{"best_params": {"period": 20, "threshold": 1.0}, "best_metrics": {"sharpe_ratio": 1.2}, "all_results": [...]}`

#### Scenario: Empty Parameter Grid

- GIVEN param_grid is empty or has no values
- WHEN `grid_search_optimization()` is called
- THEN it MUST return `{"best_params": {}, "best_metrics": {}, "all_results": []}`
- AND log a warning

#### Scenario: Grid Search Returns Ranked Results

- GIVEN multiple parameter combinations
- WHEN optimization completes
- THEN `all_results` in the return MUST be sorted descending by the metric
- AND the first entry MUST match `best_params`/`best_metrics`

### Requirement: closePositionViaConditions Persists State and Executes Close

The `OrderExecutionService.closePositionViaConditions` method MUST persist contract details at bracket placement time and execute a market sell order when called.

- GIVEN a bracket order is placed (parent order + TP + SL)
- THEN the option contract details and quantity MUST be persisted to an in-memory map or JSON file
- AND the persisted state MUST be retrievable by the bracket order ID

#### Scenario: Persist Contract on Bracket Placement

- GIVEN `placeBracketOrder()` is called with contract C, quantity Q, parentId P, tpOrderId T, slOrderId S
- WHEN the method executes successfully
- THEN it MUST store: `{bracketId: {contract: C, quantity: Q, parentId: P, tpOrderId: T, slOrderId: S}}`
- AND the persistence location is an in-memory `ConcurrentHashMap` in `OrderExecutionService`
- AND if JVM restarts and the position is still open in IBKR, the state can be recovered via open-orders query

#### Scenario: Execute Market Sell on closePositionViaConditions

- GIVEN `closePositionViaConditions(tpOrderId, slOrderId, currentPrice)` is called
- AND contract details were persisted at placement time
- WHEN the method executes
- THEN it MUST:
  1. Cancel the bracket TP order
  2. Cancel the bracket SL order
  3. Place a MARKET SELL order for the persisted option contract and quantity
- AND return success/failure status

#### Scenario: Close Without Persisted State

- GIVEN `closePositionViaConditions()` is called but no contract details are found
- THEN it MUST:
  1. Still cancel both TP and SL orders (as before)
  2. Log a warning: "Contract details not found—manual close may be required"
  3. NOT throw an exception

### Requirement: Deployment Topology Documented

The deployment topology for the Python sidecar vs Java engine MUST be documented in the design.

#### Scenario: Docker Compose Default-On

- GIVEN user runs `docker compose up` (no profile flag)
- THEN it MUST bring up: Java engine, Redis, Python analytics (FastAPI), Python UI (Streamlit)
- AND Streamlit MUST be accessible at `http://localhost:8501`
- AND FastAPI at `http://localhost:8001`

#### Scenario: Docker Compose Profile Mode

- GIVEN user runs `docker compose --profile analytics up`
- THEN it MUST bring up the analytics services
- AND if the profile is not selected, only Java + Redis start

#### Scenario: Direct Windows Execution

- GIVEN user runs Python services directly on Windows (not Docker)
- THEN startup instructions MUST be documented:
  1. Start Java engine: `./mvnw spring-boot:run`
  2. Start FastAPI: `cd python && uvicorn analytics_service.main:app --port 8001`
  3. Start Streamlit: `cd python && streamlit run ui_service/main.py --server.port 8501`

### Requirement: Streamlit vs React Resolution

The design document MUST state the resolved role of Streamlit.

#### Scenario: Streamlit Kept as Analytics Surface

- GIVEN the design resolves "Streamlit stays"
- THEN React remains the trading/live dashboard
- AND Streamlit serves only: Backtesting, Monitoring, Analytics (Monte Carlo), Settings
- AND there is no feature overlap

#### Scenario: Streamlit Removed

- GIVEN the design resolves "Streamlit is removed"
- THEN Streamlit is not deployed
- AND React gains an "Analytics" tab that consumes `/api/v1/*` endpoints from the Python FastAPI service
- AND `/analytics/` proxy endpoint is added to Java (optional)

## MODIFIED Requirements

### Requirement: Hardcoded Placeholders Removed from Streamlit

All hardcoded mock values in `python/ui_service/main.py` that represent live data MUST be replaced with API calls.

- GIVEN grep searches for `"3 active strategies"` or `"$1,234.56"` in `python/ui_service/main.py`
- WHEN search is executed
- THEN result MUST return 0 matches

(Previously: Streamlit used hardcoded mock values for all metrics)

## SCENARIOS NOT COVERED (deferred to future changes)

| Scenario | Reason for Deferral |
|---------|-------------------|
| gRPC implementation for Python↔Java | Requires separate change proposal; protos exist but no server in Java |
| Strategy port to Python | Out of scope per proposal; no measured pain point |
| Backtest engine port to Python | Out of scope per proposal; creates strategy duplication |
| IBKR connectivity port to Python | Out of scope per proposal; no drop-in replacement |

## RESOLUTION OF OPEN QUESTIONS

### 1. Streamlit Role

**Resolution: Option (a) — Streamlit is the analytics surface, React is the trading surface.**

- React serves `/live-ui/**` endpoints: live trading dashboard, signals, execution
- Streamlit serves `/backtest-ui/**` and analytics: backtesting, monitoring, Monte Carlo, parameter tuning
- No feature overlap: React shows live data, Streamlit shows analytics/research

### 2. Python→Java Transport

**Resolution: REST-only for this change.**

- Current implementation uses REST via `httpx`
- gRPC server is NOT implemented in Java and remains test-only
- `grpcio` dependency remains in `requirements.txt` for future use but is unused
- If gRPC is needed later, a separate change proposes the implementation

### 3. Deployment Topology

**Resolution: Docker Compose default-on.**

- Running `docker compose up` brings up all services (Java, Redis, FastAPI, Streamlit)
- No profile flag required for default deployment
- Streamlit on port 8501, FastAPI on port 8001, Java on port 9090
- If resources are constrained, user may disable Python services manually

### 4. closePositionViaConditions Persistence

**Resolution: In-memory ConcurrentHashMap within OrderExecutionService.**

- Chosen because:
  - Single-process JVM assumption
  - Crash recovery via IBKR open-orders query
  - Simpler than JSON disk-backed (no file I/O on hot path)
- If JVM restarts and position is still open, state is recovered on-demand via IBKR
- Trade-off: acknowledged that positions may need manual close if JVM dies mid-trade (rare)

---

## Acceptance Criteria Checklist

| ID | Criterion | Verification Method |
|----|-----------|---------------------|
| SC-1 | Grep for `"3 active strategies"` returns 0 matches | `grep "3 active strategies" python/ui_service/main.py` |
| SC-2 | Grep for `"$1,234.56"` returns 0 matches | `grep "\$1,234.56" python/ui_service/main.py` |
| SC-3 | Backtest page POSTs to `/backtest-ui/run` | HTTP capture in browser dev tools |
| SC-4 | Grid-search executes without placeholder log | Inspect `engine.py:374` — no "placeholder" log |
| SC-5 | closePositionViaConditions calls market sell order | Code inspection + unit test with mock |
| SC-6 | Docker compose starts all services | `docker compose up` launches 4 containers |
| SC-7 | Design document states Streamlit role | Document contains resolved decision |
| SC-8 | No regression in live trading | Manual smoke test: scan → signal → execute |

---

## Implementation Notes (for design phase)

1. **Java endpoints for Streamlit to consume:**
   - GET `/live-ui/status` → {isScanning, signalsToday, twsConnected, ...}
   - GET `/live-ui/signals` → {signals: [...], count: N}
   - GET `/live-ui/tickers` → {allTickers: [...], hotTickers: [...], total: N}
   - GET `/live-ui/tws-status` → {balance, activeTrades, connected: bool}
   - POST `/backtest-ui/run` → BacktestReport JSON

2. **Grid-search search space definition:**
   - Strategy: Exhaustive grid search on bounded discrete parameters
   - Default search space defined in a YAML config or in-code Dict
   - Parameters: SMA period [10-50], threshold [0.5-2.0], stopMultiplier [1.5-3.0]
   - Metric ranking: sharpe_ratio descending

3. **closePositionViaConditions data structure:**
   ```java
   record BracketState(Contract contract, int quantity, int parentId, int tpOrderId, int slOrderId) {}
   // Stored in: ConcurrentHashMap<Integer, BracketState> bracketStateMap
   ```

4. **Streamlit ↔ Java port mapping:**
   - Assume Java on `localhost:9090`
   - Streamlit calls: `http://localhost:9090/live-ui/...`
   - Override via environment variable `JAVA_API_URL`