# Functional Inventory — options-quant

> Last updated: 2026-04-30 | Tests: 619/619 | Status: all green

## How to read this document

| Icon | Meaning |
|---|---|
| ✅ Unit | Covered by unit tests (mocks, no Spring context) |
| ✅ Integration | Covered by integration tests (`@SpringBootTest` or `@WebMvcTest`) |
| ✅ E2E | Covered by Playwright tests (browser or HTTP against real app) |
| ⚠️ Partial | Covered in some branches/paths but not all |
| ❌ No coverage | No automated test validates this functionality |

Multiple icons = multiple active coverage layers.

---

## FRONTEND

### Live Dashboard — Visualization and Status

| Feature | User description | Coverage | Test files |
|---|---|---|---|
| Live page load | User navigates to `/live` and sees the main dashboard with toolbar and signals grid | ✅ E2E | `src/test/java/com/fgiaquinta/optionsquant/e2e/LiveUiDashboardTest.java` |
| Live↔Backtest↔Health SPA navigation | User uses navbar links to switch sections without reloading the page | ✅ E2E | `src/test/java/com/fgiaquinta/optionsquant/e2e/NavigationTest.java` |
| Live signals grid (LiveTradeGrid) | User sees active signals, executed trades, external positions and scan activity chips | ✅ Unit | `frontend/src/components/LiveTradeGrid.test.jsx` (51 tests, 98.51%) |
| PAPER/LIVE indicator (AccountModeChip) | User sees whether they are trading on a paper or live account | ✅ Unit | `frontend/src/components/AccountModeChip.test.jsx` (8 tests) |
| Ticker tooltip with fundamentals | User hovers over a ticker and sees its fundamental data (P/E, beta, etc.) | ✅ Unit | `frontend/src/components/TickerTooltip.test.jsx` (10 tests) |
| Countdown to next scan | User sees how long until the next 15-minute scan | ✅ Unit | `frontend/src/hooks/useScanCountdown.test.js` (100%) |
| Market open/closed status | User sees whether the market is open (30s polling) | ✅ Unit | `frontend/src/hooks/useMarketOpen.test.js` (100%) |
| Scanner status polling | User sees the current scanner state (isScanning, progress, tickers) in real time | ✅ Unit | `frontend/src/hooks/useLiveScanner.test.js` (25 tests, 100%) |
| SCAN STARTED / SCAN FINISHED columns | User sees when analysis started and ended for each ticker | ✅ Unit | `frontend/src/components/LiveTradeGrid.test.jsx` |
| Stale signal indicator (>30min) | User sees a visual warning when a signal has been waiting more than 30 minutes | ✅ Unit | `frontend/src/utils/liveSignalUtils.test.js` (100%) |

### Live Dashboard — Scan Engine Controls

| Feature | User description | Coverage | Test files |
|---|---|---|---|
| Manual Start Scan | User clicks "Scan Now" to trigger an immediate scan of all tickers | ✅ Unit / ✅ E2E | `frontend/src/components/ScanEngineControls.test.jsx`, `LiveModeInteractionTest.java` |
| Stop Scan | User stops an in-progress scan before it finishes | ✅ Unit / ✅ E2E | `frontend/src/components/ScanEngineControls.test.jsx`, `LiveModeInteractionTest.java` |
| Force Stop / Reset | User forces a full state reset when the app gets stuck | ✅ Unit | `frontend/src/components/ScanEngineControls.test.jsx` |
| Toggle Scheduler (auto-scan every 15min) | User enables/disables the scheduled automatic scanner | ✅ Unit / ✅ E2E | `frontend/src/components/ScanEngineControls.test.jsx`, `LiveUiDashboardTest.java` |
| Toggle Mock Market Open | User simulates an open market to allow scanning outside trading hours | ✅ Unit / ✅ E2E | `frontend/src/components/ScanEngineControls.test.jsx`, `LiveUiDashboardTest.java` |
| Toggle Auto Execute | User enables automatic signal execution via TWS | ✅ Unit / ✅ E2E | `frontend/src/components/ScanEngineControls.test.jsx`, `LiveUiDashboardTest.java` |
| Toggle Extended Hours | User enables/disables extended trading hours | ✅ Unit / ✅ E2E | `frontend/src/components/ScanEngineControls.test.jsx`, `LiveModeInteractionTest.java` |
| Toggle Macro Filter | User enables/disables the macro filter (manual override) | ✅ Unit | `frontend/src/components/ScanEngineControls.test.jsx` |
| Adjust Risk % | User adjusts the per-trade risk percentage at runtime | ✅ Unit | `frontend/src/components/ScanEngineControls.test.jsx` |
| Set Max Concurrent Scans | User configures how many tickers are analyzed in parallel (1–16) | ✅ Unit | `frontend/src/hooks/useLiveScanner.test.js` |
| Inject Mock Signal | User injects a simulated signal for testing without TWS connected | ✅ Unit | `frontend/src/components/ScanEngineControls.test.jsx` |
| IBKR Lock (TWS status) | User sees the TWS connection status (data/account/exec) | ✅ Unit | `frontend/src/components/ScanEngineControls.test.jsx` |
| Scope HOT/ALL | User filters the scan to hot tickers or the full universe | ✅ Unit | `frontend/src/hooks/useLiveScanner.test.js` |

### Live Dashboard — Trade Actions

| Feature | User description | Coverage | Test files |
|---|---|---|---|
| Execute signal manually | User clicks "Open" on a signal row to send the order to TWS | ✅ Unit | `frontend/src/hooks/useTradeActions.test.js` (16 tests, ~95%) |
| Close trade | User clicks "Close" to cancel TP/SL orders or mark the trade as closed | ✅ Unit | `frontend/src/hooks/useTradeActions.test.js` |
| Cancel order | User cancels a pending order in TWS by orderId | ✅ Unit | `frontend/src/hooks/useTradeActions.test.js` |
| Delete signal row | User deletes a signal row from the grid when there is no open position | ✅ Unit | `frontend/src/hooks/useTradeActions.test.js` |
| Close external position | User places a market SELL order for an external position (not opened by the app) | ✅ Unit | `frontend/src/hooks/useTradeActions.test.js` |
| Schedule Close 14:50 ET | User schedules automatic close of an external position at 14:50 ET | ✅ Unit | `frontend/src/hooks/useTradeActions.test.js` |
| External positions (10s polling) | User sees positions open in TWS that were not opened by the app | ✅ Unit | `frontend/src/hooks/useExternalPositions.test.js` (100%) |
| Stale signal execution block (>30min) | System rejects execution of a signal older than 30 minutes | ✅ Unit | `frontend/src/hooks/useTradeActions.test.js` |

### Live Dashboard — Replay Mode

| Feature | User description | Coverage | Test files |
|---|---|---|---|
| ReplayControls UI (date picker + speed picker) | User selects a date and speed to replay the historical market | ✅ Unit | `frontend/src/components/ReplayControls.test.jsx` (12 tests, 72%) |
| Start Replay | User starts historical replay for that date | ✅ Unit / ✅ E2E | `frontend/src/components/ReplayControls.test.jsx`, `ReplayControlsE2eTest.java` (tag: tws-paper) |
| Stop Replay | User stops the in-progress replay | ✅ Unit / ✅ E2E | `frontend/src/components/ReplayControls.test.jsx`, `ReplayControlsE2eTest.java` |
| Change Replay Speed | User adjusts replay speed in real time | ✅ Unit | `frontend/src/components/ReplayControls.test.jsx` |
| Poll Replay Status | UI reflects whether replay is active/inactive via polling | ✅ Unit | `frontend/src/components/ReplayControls.test.jsx` |

### Configuration — Settings Page

| Feature | User description | Coverage | Test files |
|---|---|---|---|
| Hot ticker count editor (HotTickerCountEditor) | User adjusts how many tickers are considered "hot" (N between 1 and 100) | ✅ Unit | `frontend/src/components/HotTickerCountEditor.test.jsx` (11 tests, 97%) |
| Chip-based list editor (ChipListEditor) | User edits ticker lists as chips (add/remove) | ✅ Unit | `frontend/src/components/ChipListEditor.test.jsx` (16 tests) |
| Ticker fundamentals form | User edits fundamental data (P/E, beta, sector, etc.) for a ticker | ✅ Unit | `frontend/src/components/FundamentalTickerForm.test.jsx` (17 tests) |
| Watchlist CRUD | User creates, edits, and deletes watchlist groups saved in localStorage | ✅ Unit | `frontend/src/hooks/useWatchlists.test.js` (11 tests, ~90%) |
| Save filter as watchlist (SaveListPopover) | User saves the current ticker filter as a named watchlist | ✅ Unit | `frontend/src/components/SaveListPopover.test.jsx` (7 tests, 95%) |
| Per-ticker TP/SL ATR overrides (MemoryPanel) | User configures custom TP/SL multipliers per ticker and strategy | ✅ Unit | `frontend/src/components/MemoryPanel.test.jsx` (13 tests) |
| Settings backup to backend (debounced) | App automatically syncs localStorage settings to the backend (2s debounce) | ✅ Unit | `frontend/src/hooks/useStorageBackup.test.js` (5 tests, ~90%) |
| Settings sync to scanner (useScanSettings) | App syncs configuration toggles with the backend on change | ⚠️ Partial | No dedicated test; error branches and mock-market uncovered |
| SettingsPage tabs (Server/Watchlists/Preferences) | User navigates between the 3 configuration tabs | ❌ No coverage | — |

### Configuration — TickerSelector

| Feature | User description | Coverage | Test files |
|---|---|---|---|
| Ticker autocomplete | User types letters and sees filtered ticker suggestions | ✅ Unit | `frontend/src/components/TickerSelector.test.jsx` (41 tests) |
| @watchlist expansion | User types `@name` and all tickers from that watchlist are expanded | ✅ Unit | `frontend/src/components/TickerSelector.test.jsx` |
| Hot chips (quick selection) | User clicks on hot ticker chips to select them | ✅ Unit | `frontend/src/components/TickerSelector.test.jsx` |
| News ticker display | User sees a real-time news ticker | ⚠️ Partial | Stub covered; real integration has no coverage |

### Backtest Dashboard

| Feature | User description | Coverage | Test files |
|---|---|---|---|
| Backtest page load | User navigates to `/backtest` and sees the dashboard with its sections | ✅ E2E | `src/test/java/com/fgiaquinta/optionsquant/e2e/BacktestUiDashboardTest.java` |
| KPIs report + equity curve (BacktestReportPanel) | User sees win rate, total PnL, max drawdown, and the equity curve | ✅ Unit | `frontend/src/components/BacktestReportPanel.test.jsx` (25 tests) |
| Data grid with sorting and chart modal (UnifiedDataGrid) | User sorts the trade log and opens the chart modal for a trade | ✅ Unit | `frontend/src/components/UnifiedDataGrid.test.jsx` (38 tests) |
| Run Backtest | User launches a full backtest for selected tickers and dates | ✅ E2E | `BacktestUiDashboardTest.java`, `BacktestInteractionTest.java` |
| Stop in-progress backtest | User interrupts a running backtest | ✅ E2E | `BacktestInteractionTest.java` |
| Auto Run (periodic automatic backtest) | User enables automatic backtest mode | ✅ E2E | `BacktestAutoRunStabilityTest.java` (soak test) |
| Improve strategy (analysis and tuning) | User clicks "Improve" on a strategy to see parameter recommendations | ✅ E2E | `BacktestInteractionTest.java` |
| Retest strategy with new params | User re-runs the backtest for a strategy with improved parameters | ✅ E2E | `BacktestInteractionTest.java` |
| Improve Modal — grid search analysis (ImproveModal) | User sees the modal with detailed analysis and can launch a grid search from there | ✅ Unit | `frontend/src/components/ImproveModal.test.jsx` (25 tests) |
| Grid Search + Walk-Forward (GridSearchPanel) | User configures and launches an exhaustive grid search with walk-forward validation | ✅ Unit / ✅ E2E | `frontend/src/components/GridSearchPanel.test.jsx` (49 tests), `BacktestGridWalkForwardUiE2eTest.java`, `BacktestGridSearchHttpE2eTest.java` |
| Walk-forward fold results | User sees results per fold of the walk-forward within the panel | ✅ Unit | `frontend/src/components/GridSearchPanel.test.jsx` |
| Apply grid-search to memory | User applies the optimal grid search parameters to ticker-memory | ✅ Unit | `frontend/src/hooks/useGridSearch.test.js` (13 tests, ~90%) |
| Promote risk params to ticker-memory | User promotes backtest risk parameters as permanent overrides | ✅ E2E | `BacktestInteractionTest.java`, `BacktestGridSearchWebMvcTest.java` |
| Value formatting (USD, PnL coloring, lookback→date) | Monetary values and PnL are correctly formatted and colored in the UI | ✅ Unit | `frontend/src/utils/backtestFormatters.test.js` (97%) |
| Scan row sort (scan activity feed) | Tickers in the scan feed are sorted by HOT priority | ✅ Unit | `frontend/src/utils/scanRowSort.test.js` (100%) |

### Health Page

| Feature | User description | Coverage | Test files |
|---|---|---|---|
| View system health status | User sees whether the app is UP/DOWN | ✅ E2E | `src/test/java/com/fgiaquinta/optionsquant/e2e/HealthEndpointTest.java` (5 tests) |
| HealthPage React component | User sees the `/health` page with rendered content | ❌ No coverage | — |
| UI styling / responsive layout | UI renders correctly with the color theme | ✅ E2E | `src/test/java/com/fgiaquinta/optionsquant/e2e/UiStylingTest.java` (4 tests) |

### API Client (frontend)

| Feature | User description | Coverage | Test files |
|---|---|---|---|
| All HTTP calls (liveApi, replayApi, accountApi, tickerConfigApi, backtestApi) | Frontend HTTP clients call the correct endpoints | ✅ Unit | `frontend/src/api.test.js` (full coverage) |
| localStorage get/set/remove (storage.js) | Frontend persists and retrieves data from local storage | ✅ Unit | `frontend/src/utils/storage.test.js` (100%) |

---

## BACKEND

### Scan Engine — Core

| Feature | User description | Coverage | Test files |
|---|---|---|---|
| GET /live-ui/status — full scanner state | UI obtains current state (isScanning, tickers, progress, toggles) | ✅ Unit | `LiveModeControllerStatusTest.java` |
| POST /live-ui/scan-now — launch manual scan | User launches a scan; respects market hours and blocks double-scan | ✅ E2E | `LiveModeInteractionTest.java` |
| POST /live-ui/stop-scan | User stops the in-progress scan | ✅ E2E | `LiveModeInteractionTest.java` |
| POST /live-ui/force-stop — full state reset | User forces a reset when the app gets into an inconsistent state | ⚠️ Partial | Endpoint validated via E2E; internal logic has no direct unit test |
| POST /live-ui/toggle-extended-hours | Extended hours toggle | ✅ E2E | `LiveModeInteractionTest.java` |
| POST /live-ui/toggle-scheduler | Automatic scheduler toggle | ❌ No coverage | — |
| POST /live-ui/toggle-mock-market | Mock market open toggle | ❌ No coverage | — |
| POST /live-ui/toggle-auto-execute | Auto execute toggle | ❌ No coverage | — |
| POST /live-ui/toggle-macro-filter | Macro filter toggle | ❌ No coverage | — |
| POST /live-ui/set-max-concurrent | Configure scanner concurrency (1–16) | ❌ No coverage | — |
| POST /live-ui/set-risk | Configure per-trade risk at runtime | ❌ No coverage | — |
| GET /live-ui/scan-scores — HYBRID breakdown | Returns ticker prioritization scores (fundamentals + memory) | ✅ Unit | `LiveModeControllerScanScoresTest.java` |
| ScanPrioritizationService — HYBRID 0.65/0.35 | Scorer calculates the hybrid fundamentals+memory score to prioritize scan order | ✅ Unit | `ScanPrioritizationServiceTest.java` |
| ScannerConcurrencyTest — exclusive lock | Scanner respects exclusive lock under concurrency | ✅ Unit | `ScannerConcurrencyTest.java` |
| MarketScanner.isUSMarketOpen (9:30–16:00 ET) | Scanner correctly detects whether the market is open | ✅ Unit | `MarketScannerScanScoresTest.java` |

### Scan Engine — Signals

| Feature | User description | Coverage | Test files |
|---|---|---|---|
| GET /live-ui/signals — signal list | UI retrieves active signals (live or replay) with stale filter | ✅ Unit | `LiveModeControllerStaleSignalTest.java` |
| Stale signal guard (>30min blocks execute) | System rejects executing signals older than 30 minutes | ✅ Unit | `LiveModeControllerStaleSignalTest.java` |
| DELETE /live-ui/signal — delete row | System allows deleting a row only if there is no open executed position | ⚠️ Partial | Logic covered indirectly; no dedicated test |
| POST /live-ui/signals/clear-stale | Batch delete all stale signals with no open position | ❌ No coverage | — |
| POST /live-ui/signals/batch-delete | Delete multiple signals by ticker in a single request | ❌ No coverage | — |
| GET /live-ui/scan-activity — activity feed | UI sees the tickers-in-scanning feed with start/end times | ❌ No coverage | — |
| Inject Mock Signal | Inject a simulated signal (for testing/demo without TWS) | ❌ No coverage | — |

### Trading — Order Execution

| Feature | User description | Coverage | Test files |
|---|---|---|---|
| POST /live-ui/execute-signal — execute signal | User sends a bracket order (entry + TP + SL) to TWS | ❌ No coverage | — (TradingService covered only at unit level) |
| POST /live-ui/close-trade — close trade | Cancels TP/SL orders in TWS (or local close if no IDs) | ✅ Unit | `LiveModeControllerCloseTradeTest.java` |
| POST /live-ui/cancel-trade — cancel order | Cancels a pending order in TWS by orderId | ❌ No coverage | — |
| OrderExecutionService — bracket order + contractDetails | Builds and sends the bracket order and retrieves the best conId | ✅ Unit | `OrderExecutionServiceTest.java` |
| TradingService — scanAndExecute + executeSignal | Orchestrates scan→signal→order flow and handles execution failure | ✅ Unit | `TradingServiceTest.java` |
| AccountManager — balance, 2% risk rule, positions snapshot | Manages balance, contract quantity, and positions snapshot | ✅ Unit | `AccountManagerTest.java` |
| PositionPollingScheduler — periodic position polling | Syncs the positions snapshot with TWS every N seconds | ✅ Unit | `PositionPollingSchedulerTest.java` |
| POST /api/trading/scan-and-execute | Legacy endpoint: scans and executes in a single call | ❌ No coverage | — |
| POST /api/trading/execute | Legacy endpoint: executes a single signal | ❌ No coverage | — |
| GET /api/trading/account-status | Returns balance, risk limit, and number of active trades | ❌ No coverage | — |

### External Positions

| Feature | User description | Coverage | Test files |
|---|---|---|---|
| GET /live-ui/external-positions — list external positions | UI sees positions open in TWS that the app did not record | ✅ Unit / ✅ Integration | `LiveModeControllerExternalPositionsTest.java`, `ExternalPositionsIntegrationTest.java` |
| POST /live-ui/external-positions/{ticker}/close — immediate SELL | User closes an external position with a market order | ✅ Unit / ✅ Integration | `LiveModeControllerExternalPositionsTest.java`, `ExternalPositionsIntegrationTest.java` |
| POST /live-ui/external-positions/{ticker}/schedule-close-1450 — conditional close | User schedules automatic close at 14:50 ET | ✅ Unit | `LiveModeControllerExternalPositionsTest.java` |
| ExternalPositionDto JSON serialization | DTO serializes with snake_case and correct fields | ✅ Unit / ✅ Integration | `ExternalPositionDtoTest.java`, `ExternalPositionsIntegrationTest.java` |
| PositionSnapshot correctness | Snapshot captures symbol, secType, quantity, and avgCost | ✅ Unit | `PositionSnapshotTest.java` |
| External positions startup check after restart | App logs a WARNING if there are positions in TWS but no registered trades | ✅ Unit | `LiveModeControllerStartupListenerTest.java` |

### Replay Mode (Backend)

| Feature | User description | Coverage | Test files |
|---|---|---|---|
| POST /live-ui/replay/start — start replay | User starts historical replay for a date at a given speed | ✅ Unit | `LiveModeControllerReplayTest.java` |
| POST /live-ui/replay/stop — stop replay | User stops replay and clears replay signals | ✅ Unit | `LiveModeControllerReplayTest.java` |
| PUT /live-ui/replay/speed — change speed | User changes replay speed in real time | ✅ Unit | `LiveModeControllerReplayTest.java` |
| GET /live-ui/replay/status — replay status | UI reflects whether replay is active, virtual time, and current speed | ✅ Unit | `LiveModeControllerReplayTest.java` |
| GET /live-ui/account-mode — PAPER/LIVE chip | UI shows whether the connected account is paper or live | ✅ Unit | `LiveModeControllerReplayTest.java` |
| ReplayService — start/stop/setSpeed/time validation | Service rejects replay during market hours and manages state | ✅ Unit | `ReplayServiceTest.java` |
| ReplayClock — snapshot/activate/deactivate | Virtual clock advances correctly during replay | ✅ Unit | `ReplayClockTest.java` |
| ReplayScheduler — ticks and progress | Scheduler emits ticks at the correct cadence per speed | ✅ Unit | `ReplaySchedulerTest.java` |
| ReplayCandleSource — load from CSV for a date | Source returns historical candles from CSV for the selected date | ✅ Unit | `ReplayCandleSourceTest.java` |
| ReplayOrderGate — block real orders during replay | Real orders are blocked while replay is active | ✅ Unit | `ReplayOrderGateTest.java` |
| SignalReplayField — replay flag on signals | Signals generated during replay are flagged with `replay=true` | ✅ Unit | `SignalReplayFieldTest.java` |
| E2E replay happy path (toggle mock + start + stop) | Full UI flow: enable mock market, start and stop replay | ✅ E2E | `ReplayControlsE2eTest.java` (tag: tws-paper) |

### Ticker Configuration

| Feature | User description | Coverage | Test files |
|---|---|---|---|
| GET /api/ticker-config — full configuration | UI loads the universe, HOT list, and fundamentals for all tickers | ❌ No coverage | — |
| PUT /api/ticker-config — save configuration | User saves changes to the universe, HOT list, and fundamentals | ❌ No coverage | — |
| DELETE /api/ticker-config/symbol/{symbol} — delete ticker | User deletes a ticker from the universe and from the HOT list | ❌ No coverage | — |
| GET /api/ticker-config/hot-ticker-count | Returns the effective hot-ticker-count (runtime override vs yml default) | ✅ Unit | `TickerConfigControllerHotTickerCountTest.java` |
| PUT /api/ticker-config/hot-ticker-count | Persists a hot-ticker-count override (validation 1–100) | ✅ Unit | `TickerConfigControllerHotTickerCountTest.java` |
| GET /api/ticker-config/validate?symbol= | Validates a ticker against TWS before adding it to the universe | ⚠️ Partial | BUG fix (L63) closed but no automated regression test |
| TickerService — RUNTIME-WINS GUARD for HOT | Service respects the priority: runtime > yml when the HOT list is present | ✅ Unit | `TickerServiceTest.java` |
| IbkrProperties — configuration validation | IBKR configuration (host, port, risk, etc.) is validated at startup | ✅ Unit | `IbkrPropertiesTest.java` |

### Backtest Engine

| Feature | User description | Coverage | Test files |
|---|---|---|---|
| Grid Search — EXHAUSTIVE + axis validation | Service runs all parameter combinations and validates known axes | ✅ Unit | `GridSearchServiceTest.java` |
| Grid Search — HTTP contract (POST /backtest-ui/grid-search) | Endpoint rejects invalid bodies (unknown axes, empty tickers) with 400 | ✅ Integration / ✅ E2E | `BacktestGridSearchWebMvcTest.java`, `BacktestGridSearchHttpE2eTest.java` |
| Walk-Forward validation | Grid search supports walk-forward with configurable train/test/step folds | ✅ Unit / ✅ E2E | `GridSearchServiceTest.java`, `BacktestGridWalkForwardUiE2eTest.java` |
| Promote risk params (dryRun vs persist) | User can dry-run or persist optimal risk parameters to memory | ✅ Unit | `PromoteRiskServiceTest.java` |
| TickerMemoryProfileMap — profile-based learning | Profile map persists and retrieves overrides per ticker+strategy correctly | ✅ Unit | `TickerMemoryProfileMapTest.java` |
| POST /backtest-ui/run + stop + running check | Execute, stop, and check status of the in-progress backtest | ✅ E2E | `BacktestInteractionTest.java` (slow tag) |
| POST /backtest-ui/improve/{strategy} + retest | Analyze and improve a strategy, then re-test it | ✅ E2E | `BacktestInteractionTest.java` |
| Backtest scheduler auto-run stability | Backtest auto-run produces no console errors and does not break the UI on soak | ✅ E2E | `BacktestAutoRunStabilityTest.java` |

### Trading Strategies

| Feature | User description | Coverage | Test files |
|---|---|---|---|
| C5ContinuationCall / P5ContinuationPut strategies | Strategies correctly detect the continuation pattern | ✅ Unit | `StrategyUnitTest.java` |
| C1SqueezeCallStrategy — Worden Stochastic | C1 strategy detects squeeze breakouts using the Worden indicator | ✅ Unit | `StrategyUnitTest.java` |
| RiskCalculator — TP/SL from ATR | Risk calculator correctly computes TP/SL levels using ATR | ✅ Unit | `RiskCalculatorTest.java` |
| CandlestickPatternDetector — pattern detection | Detector identifies hammer, engulfing, and other candlestick patterns | ✅ Unit | `CandlestickPatternDetectorTest.java` |
| ConditionBuilder — TimeCondition 14:50 ET | Builder correctly creates the time condition for scheduled close | ✅ Unit | `ConditionBuilderTest.java` |
| ContractFactory — STK/OPT contract construction | Factory builds IBKR contracts correctly for stocks and options | ✅ Unit | `ContractFactoryTest.java` |
| 12 full strategies (C1–C6, P1–P6) | All call and put strategies are correctly implemented | ⚠️ Partial | Only C1, C5, P5 have direct tests; rest covered indirectly via backtest engine |

### Market Data (Candles)

| Feature | User description | Coverage | Test files |
|---|---|---|---|
| POST /api/candles/download — download candles from IBKR | System downloads historical data for a ticker/timeframe from TWS | ❌ No coverage | — |
| POST /api/candles/download-all — all timeframes | Downloads all timeframes for a ticker | ❌ No coverage | — |
| POST /api/candles/download-all-tickers — full universe | Downloads data for all configured tickers | ❌ No coverage | — |
| GET /api/candles/local — load from CSV | Reads historical candles from local CSV | ✅ Unit | `CandleCsvServiceTest.java` |
| GET /api/candles/local/exists | Verifies whether local data exists for a ticker/timeframe | ✅ Unit | `CandleCsvServiceTest.java` |
| Candle domain object — validation and construction | Candle domain object is built and validated correctly | ✅ Unit | `CandleTest.java` |

### Backup and Configuration

| Feature | User description | Coverage | Test files |
|---|---|---|---|
| POST /api/backup — save settings snapshot | App persists localStorage settings to the server as a backup | ❌ No coverage | — |
| GET /live-ui/market-status — market status | App returns the current session (REGULAR/OPEN EXT/CLOSED) and seconds to next open | ❌ No coverage | — |
| GET /live-ui/tws-status — TWS connection status | UI sees the detailed status of the 3 IBKR connections with auto-reconnect | ❌ No coverage | Only TwsPaperConnectivityTest requires real TWS |

### Infrastructure and Health

| Feature | User description | Coverage | Test files |
|---|---|---|---|
| GET /actuator/health — liveness/readiness probes | App reports UP with liveness and readiness groups | ✅ E2E | `HealthEndpointTest.java` |
| Real TWS connectivity (paper account) | App connects correctly to TWS paper and reports accountConnected=true | ✅ E2E | `TwsPaperConnectivityTest.java` (tag: tws-paper, requires TWS) |
| MarketScanner scan scores (scheduled scan) | Scheduled scanner calculates and stores scores correctly at scan start | ✅ Unit | `MarketScannerScanScoresTest.java` |

---

## Summary

| Section | Features | ✅ Covered | ⚠️ Partial | ❌ No coverage |
|---|---|---|---|---|
| Frontend — Live Dashboard | 31 | 28 (90%) | 2 | 1 |
| Frontend — Configuration/Settings | 12 | 9 (75%) | 2 | 1 |
| Frontend — Backtest | 15 | 14 (93%) | 0 | 1 |
| **Frontend total** | **58** | **51 (88%)** | **4** | **3** |
| Backend — Scan Engine | 15 | 8 (53%) | 1 | 6 |
| Backend — Trading/Orders | 10 | 4 (40%) | 0 | 6 |
| Backend — External Positions | 6 | 6 (100%) | 0 | 0 |
| Backend — Replay Mode | 12 | 12 (100%) | 0 | 0 |
| Backend — Ticker Config | 8 | 4 (50%) | 1 | 3 |
| Backend — Backtest Engine | 8 | 8 (100%) | 0 | 0 |
| Backend — Strategies | 6 | 3 (50%) | 2 | 1 |
| Backend — Candles | 6 | 2 (33%) | 0 | 4 |
| Backend — Backup/Infra | 3 | 1 (33%) | 0 | 2 |
| **Backend total** | **74** | **48 (65%)** | **4** | **22** |
| **TOTAL** | **132** | **99 (75%)** | **8** | **25** |

---

## Critical Gaps — Operational Risk

### Frontend
1. **`SettingsPage` tabs** — 0% direct page coverage
2. **`useScanSettings`** — error branches and mock-market uncovered

### Backend — CRITICAL
1. **`POST /live-ui/execute-signal`** — end-to-end order execution flow to TWS has no integration test
2. **`GET /live-ui/tws-status`** — connection status endpoint with auto-reconnect has no dedicated test
3. **`POST /api/candles/download*`** (3 endpoints) — silent failure possible on IBKR download
4. **`POST /api/backup`** — frontend settings backup has no test
5. **Runtime toggles** (scheduler/mock-market/auto-execute/macro-filter/set-risk/set-max-concurrent) — 6 endpoints with no unit test
6. **9 of the 12 strategies** — C2–C4, C6, P1–P4, P6 covered only indirectly via backtest engine
7. **`GET /api/ticker-config/validate`** — BUG fix at L63 has no automated regression test
