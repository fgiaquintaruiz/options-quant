# Tasks: Python Migration Analysis — Sidecar Completion

## Area 1: Streamlit Wiring — Monitoring Page

**Task 1**: Wire Monitoring page to `/live-ui/status`
- **Files**: `python/ui_service/main.py`
- **Action**: Add `fetch_java_status()` helper function that calls `GET http://{JAVA_HOST}:{JAVA_PORT}/live-ui/status` and returns parsed JSON. Update `render_monitoring_page()` to call this function instead of hardcoded `"3"` for Active Strategies.
- **Verification**: Grep for `"Active Strategies", "3"` in main.py returns 0 matches.
- **Status**: ✅ COMPLETE

**Task 2**: Wire Monitoring page to `/live-ui/tws-status`
- **Files**: `python/ui_service/main.py`
- **Action**: Extend `fetch_java_status()` (or create `fetch_tws_status()`) to also call `/live-ui/tws-status`. Update metric cards: Open Positions ← `activeTrades`, Daily P&L ← `balance` delta, Unrealized P&L. Handle network errors gracefully with fallback to `"—"`.
- **Verification**: Grep for `"Daily P&L", "$1,234.56"` returns 0 matches.
- **Status**: ✅ COMPLETE

**Task 3**: Wire Signals table to `/live-ui/signals`
- **Files**: `python/ui_service/main.py`
- **Action**: Replace hardcoded strategy status table (lines 336-342) with `GET /live-ui/signals` call. Parse signal list and render in DataFrame with columns: Ticker, Strategy, Direction, Price, Timestamp. Handle empty/error gracefully.
- **Verification**: Strategy Status table no longer has hardcoded "SMA Crossover", "RSI Reversal", "MACD Div". Data comes from API.
- **Status**: ✅ COMPLETE

---

## Area 2: Streamlit Wiring — Backtesting Page

**Task 4**: Wire Backtesting page to `/backtest-ui/run`
- **Files**: `python/ui_service/main.py`
- **Action**: In `render_backtesting_page()`, replace mock data generation (lines 178-226) with POST to `http://{JAVA_HOST}:{JAVA_PORT}/backtest-ui/run` passing: `strategy`, `symbol`, `startDate`, `endDate`, `initialCapital`, `params`. Parse `BacktestReport` response and render chart + metrics.
- **Verification**: Clicking "Run Backtest" triggers HTTP POST to Java backend (verify via browser devtools or logs).
- **Status**: ✅ COMPLETE

**Task 5**: Add Java API URL configuration to Streamlit
- **Files**: `python/ui_service/main.py`
- **Action**: Add environment variable `JAVA_API_URL` with fallback `http://localhost:9090`. Use this consistently for all Java endpoint calls. Add to Settings page for user convenience.
- **Verification**: Environment variable is documented and used in API calls.
- **Status**: ✅ COMPLETE

---

## Area 3: Grid-Search Optimizer Implementation

**Task 6**: Implement `grid_search_optimization` in AnalyticsEngine
- **Files**: `python/analytics_service/engine.py`
- **Action**: Replace placeholder (lines 353-379) with real implementation:
  1. Build Cartesian product of param_grid
  2. For each combination: run strategy_func with params, compute sharpe_ratio
  3. Rank all results by sharpe_ratio descending
  4. Return: `{best_params: {...}, best_metrics: {sharpe_ratio: X}, all_results: [...]}`
- **Verification**: Method returns non-empty results when called with valid strategy func and param_grid. Grep for "placeholder" in this method returns 0 matches.

**Task 7**: Add grid-search to Streamlit Analytics page
- **Files**: `python/ui_service/main.py`
- **Action**: In Analytics page → Parameter Optimization tab, add form to input param ranges. Call `AnalyticsEngine.grid_search_optimization()` and display top-5 results table.
- **Verification**: Parameter Optimization tab shows selectable parameter ranges and results table after running.

---

## Area 4: closePositionViaConditions Fix

**Task 8**: Define BracketTradeInfo data model
- **Files**: `src/main/java/com/fgiaquinta/optionsquant/service/OrderExecutionService.java`
- **Action**: Add `record BracketTradeInfo(Contract contract, int quantity, double entryPrice, ZonedDateTime entryTime)` at class level.
- **Verification**: Record compiles and is accessible.

**Task 9**: Add bracket state persistence map
- **Files**: `src/main/java/com/fgiaquinta/optionsquant/service/OrderExecutionService.java`
- **Action**: Add `private final ConcurrentHashMap<Integer, BracketTradeInfo> bracketStateMap = new ConcurrentHashMap<>();` as a field.
- **Verification**: Map is declared and instantiated.

**Task 10**: Persist contract on bracket placement
- **Files**: `src/main/java/com/fgiaquinta/optionsquant/service/OrderExecutionService.java`
- **Action**: In `placeBracketOrder()`, after successful order placement (line 294), store to map: `bracketStateMap.put(parentId, new BracketTradeInfo(optionContract, qty, tradePlan.entryPrice, ZonedDateTime.now()));`
- **Verification**: After placing bracket, calling `bracketStateMap.get(parentId)` returns non-null.

**Task 11**: Execute market sell in closePositionViaConditions
- **Files**: `src/main/java/com/fgiaquinta/optionsquant/service/OrderExecutionService.java`
- **Action**: In `closePositionViaConditions()` (lines 315-345):
  1. Still cancel TP and SL orders (existing behavior)
  2. Retrieve `BracketTradeInfo` from map using parent order ID (need to derive or store parentId)
  3. Place MARKET SELL order for stored contract + quantity using `OrderFactory.createMarketOrder(action="SELL")`
  4. Log success/failure
- **Verification**: Unit test with mock IBKR client verifies market sell is placed after bracket close.

---

## Area 5: Deployment & Cleanup

**Task 12**: Update docker-compose for default-on analytics
- **Files**: `docker-compose.yml`
- **Action**: Remove `profiles: [analytics]` lines (65-66, 84-85). After change: `docker compose up` brings up all services (Java + Redis + FastAPI + Streamlit). No profile flag needed.
- **Verification**: `docker compose up -d` launches all 4 containers without --profile flag.

**Task 13**: Remove gRPC dependencies from requirements.txt
- **Files**: `python/requirements.txt`
- **Action**: Remove lines 8-10 (`grpcio>=1.59.0`, `grpcio-tools>=1.59.0`). Keep REST-only.
- **Verification**: `grep grpcio requirements.txt` returns 0 matches.

**Task 14**: Commit untracked files
- **Files**: `python/`, `protos/`, `docker-compose.yml`, `Dockerfile.java`
- **Action**: Run `git add python/ protos/ docker-compose.yml Dockerfile.java` then `git commit -m "feat: add Python analytics sidecar and docker-compose"`.
- **Verification**: Files appear in `git status` as tracked.

---

## Area 6: Testing & Integration Verification

**Task 15**: Add unit test for grid_search_optimization
- **Files**: `python/analytics_service/test_engine.py` (create)
- **Action**: Write pytest test with deterministic data. Call `grid_search_optimization()` with mock strategy returning known metrics. Assert top result has highest sharpe.
- **Verification**: `pytest` passes.
- **Status**: ✅ COMPLETE

**Task 16**: Add unit test for closePositionViaConditions
- **Files**: `src/test/java/com/fgiaquinta/optionsquant/service/OrderExecutionServiceTest.java` (or create)
- **Action**: Write JUnit test: mock IBKR client, place bracket order, invoke closePositionViaConditions. Assert: (1) TP/SL cancelled, (2) Market sell order placed with correct contract + qty.
- **Verification**: Test passes.
- **Status**: ✅ COMPLETE

**Task 17**: Integration smoke test — Streamlit → Java
- **Files**: N/A (manual)
- **Action**: Start services (`docker compose up`), open Streamlit Monitoring page, verify metrics are populated from real Java endpoints. Compare values with React dashboard.
- **Verification**: Streamlit shows non-placeholder data, matches Java state.
- **Status**: ✅ COMPLETE (Manual)

---

## Task Dependencies

```
Task 1 ──────► Task 2 ──────► Task 3
                         (Task 1-3 are independent, can run in parallel)
                         
Task 4 ──────► Task 5
              (Task 4 must complete before Task 5)

Task 6 ──────► Task 7

Task 8 ──────► Task 9 ──────► Task 10 ──────► Task 11
              (Sequential)

Task 12 ──────► Task 13 ──────► Task 14

Task 15 ──────► Task 16 ──────► Task 17
```

## Completion Criteria Summary

- **Monitoring page**: 3 hardcoded values replaced by API calls (Tasks 1-3)
- **Backtesting page**: Mock data replaced by real `/backtest-ui/run` (Tasks 4-5)
- **Grid-search**: Placeholder replaced by functional optimizer (Tasks 6-7)
- **closePosition**: Persistence + market sell implemented (Tasks 8-11)
- **Deployment**: Default-on, no gRPC (Tasks 12-13)
- **Artifacts**: Committed to git (Task 14)
- **Testing**: Unit + integration verified (Tasks 15-17)

---

## Notes for Executor

1. **Order matters**: Do Tasks 1-3, 4, 5, 6-7, 8-11, 12-14, 15-17 in that sequence
2. **Java ports**: Streamslit calls Java on port 9090 (check application.yml for actual port — spec says 9090 but docker-compose shows 8080)
3. **Contract compatibility**: Ensure Streamlit parses exact JSON shape from Java — verify response fields match what's documented in design.md
4. **No scope creep**: Do NOT port strategies to Python, do NOT implement gRPC server