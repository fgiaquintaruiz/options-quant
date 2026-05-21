[1.3.31] - 2026-05-16 (Polygon Pagination, Backtest Refactor, C1/P1 Squeeze)
Added
massive_import.py: pagination support added (commit ec430d8). NOTE: Polygon free tier appears to enforce delayed-data limits that pagination cannot bypass — full cohort resolution pending P0 diagnostic.
massive_explore.py: diagnostic script for import state analysis (commit c6edc82)
BacktestBatchRunner refactor: --backtest-fresh / --backtest-fresh-force flags (DB wipe with VACUUM via BacktestPersistenceService.deleteAllData + interactive confirmation), runtime metrics (RunMetrics + throughput), and seam fix (protected exit() wrapper replaces System.exit() to enable test interception).
C1SqueezeCallStrategy / P1SqueezePutStrategy: breakout buffer (0.3% default), directional coherence body filter on MIN_15, BB width expansion check vs 20-bar avg.

Fixed
P1 squeeze strategy fixture: bodyPct/threshold alignment in StrategyUnitTest

Changed
BackfillCheckpoint: Option 3 chunk_origin filter implementation
HistoricalBackfillService: chunk_origin filtering logic
application.yml: backfill period adjusted 2023-01 → 2024-05 for Polygon handoff

### Infrastructure
- New table ticker_stats: materialized aggregations of candles
  (per-timeframe counts + min/max timestamps). Range: 2024-05+.
- scripts/refresh_ticker_stats.py: idempotent refresh script.
- Filter query reduced from 17.9s → 14ms (~1000× speedup).

---

[1.3.30] - 2026-04-10 (Spring Boot 4, Advanced CLIs & Strategy Tuning)
Added
Spring Boot 4.0.4 Migration: Upgraded from Spring Boot 3.5.0 to 4.0.4 (latest stable release). Includes JUnit 6 via spring-boot-starter-test and Gradle 9.3.0 with Version Catalog (gradle/libs.versions.toml).

Jackson JSON Dependencies: Added jackson-databind, jackson-yaml, and jackson-datatype-jsr310 dependencies for JSON serialization in CLIs and reports.

Backtest Analyzer CLI (Advanced): New specialized command-line interface for backtesting with automated analysis and JSON result persistence.
  - Command 1: Run backtest + full analysis (saves to backtest/results/{testName}.json)
  - Command 2: Compare two backtest results side by side
  - Command 3: Analyze saved historical results
  - Command 4: View strategy performance history (aggregated across all tests)
  - Command 5: Generate automatic strategy tuning recommendations
  - Activation: java -jar app.jar --backtest-cli.enabled=true

Trading CLI (General Purpose): Command-line interface for daily operations.
  - Command 1: Run backtest with analysis
  - Command 2: Quick backtest (last 30 days)
  - Command 3: Analyze latest backtest
  - Command 4: List high-quality tickers
  - Command 5: List tickers by sector
  - Activation: java -jar app.jar --cli.enabled=true

JSON Results Export: Backtests now export complete results to JSON including:
  - Configuration used (dates, capital, risk, tickers)
  - Performance metrics (return, win rate, profit factor, Sharpe, drawdown, avg duration)
  - Per-strategy breakdown (trades, win rate, PnL, profit factor, max DD)
  - Per-ticker breakdown
  - Full analysis report (critical issues, optimization tips)
  - Structure: backtest/results/{testName}.json

Strategy History Tracking: System that aggregates strategy performance across multiple backtests to identify long-term patterns.

Strategy Tuning Recommendations: Automated recommendation engine that analyzes JSON results and suggests:
  - Widen SL ATR multiplier for strategies with win rate < 40%
  - Increase risk allocation for strategies with win rate > 60% and PF > 1.5
  - Review TP/SL ratio for strategies with profit factor < 1.0
  - Reduce position size for strategies with significant losses
  - Specific instructions for editing RiskCalculator.java

IntelliJ Run Configurations: Full documentation for configuring IDE run configs:
  - Spring Boot App (web server on port 9090)
  - Trading CLI (general purpose)
  - Backtest Analyzer CLI (advanced with JSON output)
  - Custom backtest with specific parameters

Updated README: Merged REFACTORED_README.md and README.md into a single comprehensive document with:
  - Updated tech stack (Java 25, Spring Boot 4.0.4, JUnit 6)
  - Full project structure (directory tree)
  - Documentation for both CLIs with examples
  - Troubleshooting section (TWS competing session, position sizing, CSV duplicates)
  - Architecture Evolution (v1.0.0 vs v2.0.0+)
  - Legacy Code Status with migration progress

Position Size Cap Enforcement: Fixed critical bug in BacktestEngine where the limit was 100 contracts instead of 10.
  - Before: qty = Math.max(1, Math.min(qty, 100))  ❌
  - After:  qty = Math.max(1, Math.min(qty, 10))   ✅
  - Impact: Prevents disasters like P6 Reversal PUT with 96 contracts (-$18,972)

Per-Strategy ATR Multipliers in Backtest: BacktestEngine now passes strategy name to RiskCalculator.generatePlan() to use per-strategy custom multipliers.

News Filter Service: Service that prioritizes top 10 tickers by fundamentals (ROIC, EPS Growth, D/E, P/E, Market Cap, Beta).

Strategy Screener Service: Automated scan of 356 tickers with incremental criteria relaxation (4 levels) to find ideal candidates.

BacktestAnalyzer Service: REST service that analyzes trades.csv and generates automated suggestions (position sizing bugs, low win rate, poor risk/reward, high SL rate, poor time patterns, consecutive losses, ticker underperformance, direction bias).

Fixed
Backtest Position Sizing Bug: BacktestEngine.java was using a 100-contract limit instead of 10. Fixed to Math.min(qty, 10).

Map.of() Limit Issues: Replaced Map.of() with LinkedHashMap.put() in multiple files to avoid the 10-entry key-value limit.

AvgDuration Method Name: Fixed from report.avgDuration() to report.avgTradeDurationHours() to match the BacktestReport record.

Redundant README Files: Removed REFACTORED_README.md and merged all content into README.md as single source of truth.

Added
Anti-Deadlock Backfill Barrier: Implemented a 45-second timeout on historical data loading (waitForBackfillCompletion) and active purging of ghost request IDs on API errors, ensuring the bot always starts even if TWS drops packets.

Telegram Bounce-Back UX: Modified HttpServer (port 9090) to return an HTML/JS payload that executes window.location.href = "tg://". This prevents the Chrome tab from staying open unnecessarily after confirming an order from mobile.

Full REST API: Implemented RESTful endpoints for integration with external dashboards:
  - Historical data: download, download-all, download-all-tickers, local CSV read
  - Strategy scanning: bulk scan (356 tickers) and per-ticker
  - Trade execution: scan-and-execute, manual execute, account-status, check-options
  - Backtesting: run with custom parameters, run-all for all tickers
  - Monitoring: Spring Boot Actuator (/actuator/health, /metrics, /prometheus)

Backtesting Engine with CSV Reporting: Full backtesting engine with realistic simulation:
  - trades.csv: Detailed log with entry/exit prices, PnL, commissions, slippage, max drawdown, max runup
  - equity.csv: Timestamped equity curve for visualization
  - summary.txt: Summary with win rate, profit factor, Sharpe ratio, per-strategy and per-ticker stats
  - SimulatedFillEngine: Configurable slippage and commissions for realism
  - Advanced metrics: Sharpe Ratio (annualized), Profit Factor, Max Drawdown %, Avg Duration

Backtest Analyzer Service: Automated backtest analysis service that reads trades.csv and generates recommendations:
  - Position sizing bug detection (e.g. 96 contracts in a single trade)
  - Per-strategy performance analysis with optimization suggestions
  - Exit pattern detection (SL vs TP rate)
  - Time pattern analysis (hours with poor performance)
  - Risk metrics: consecutive loss streaks, average drawdown
  - Ticker concentration analysis
  - POST /api/backtest/analyze endpoint for automated report

Position Size Safety Cap: Implemented hard cap of 10 contracts per trade in AccountManager.calculateQuantity() to prevent sizing bugs like P6 Reversal PUT (96 contracts, -$18,972).

Per-Strategy ATR Multiplier Tuning: Fine-tuning system for stops and targets based on backtest analysis:
  - CALL strategies: SL multipliers 1.2–1.5x (wider due to higher volatility)
  - PUT strategies: SL multipliers 1.1–1.3x (slightly tighter)
  - Custom TP multipliers per strategy (1.3–1.6x ATR)
  - Configuration in RiskCalculator with editable HashMaps

CSV-Based Ticker Management: Migrated ticker list from application.yml to data/tickers.csv:
  - 356 tickers with full fundamental data (name, sector, market cap, P/E, beta, EPS growth, ROIC, debt/equity)
  - Advanced filters: by sector, market cap, quality, growth
  - GET /api/backtest/tickers endpoint with filter parameters
  - Backwards compatibility: use-csv-tickers flag to toggle between YAML and CSV
  - Configuration: ibkr.use-csv-tickers: true/false in application.yml

Strategy Performance Tracking: AccountManager now tracks real-time per-strategy performance:
  - Win rate, total PnL, max position size per strategy
  - isStrategyUnderperforming() method to disable strategies with < 40% win rate
  - Integration with BacktestAnalyzer for automated suggestions

Worden Stochastic Indicator: Custom implementation of the Worden Stochastic indicator (closing price percentile within a lookback period). Integrated into C5/P5 Magnet Effect strategies as confirmation. Falls back to volume surge (1.5× average) if unavailable.

12 Trading Strategies Fully Documented:
  - CALL: C1 Squeeze, C2 Trend, C3 Bounce, C4 Opening, C5 Magnet Effect, C6 Reversal
  - PUT: P1 Squeeze, P2 Trend, P3 Bounce, P4 Opening, P5 Magnet Effect, P6 Reversal
  - Each strategy with specific time windows and multi-timeframe entry conditions (5min, 15min, 1H, 1D)

Prometheus Metrics Export: Micrometer integration for real-time metrics export:
  - ibkr_operation_duration_seconds: Timers per IBKR operation
  - ibkr_errors_total: Error counter by code and message
  - csv_operations_total: CSV operations (read/write)
  - http_requests_total: HTTP requests by endpoint and method
  - Access via GET /actuator/prometheus

Cloudflare Tunnel Integration: Auto-exposes the bot to the internet via cloudflared.exe:
  - Automatic tunnel launch with --url localhost:9090
  - Automatic .trycloudflare.com URL detection
  - Automatic Telegram webhook registration

Type-Safe Configuration: Implementation of IbkrProperties record for typed configuration with validation. Full support for 356 tickers in the trading universe.

Full Test Suite: 5 test suites implemented:
  - RiskCalculatorTest: TP/SL validation, 0.9% cap, minimums
  - AccountManagerTest: Balance tracking, 2% risk calculation, concurrent limits
  - StrategyUnitTest: Trigger conditions + negative cases
  - CandleCsvServiceTest: CSV read/write, file detection
  - CandleTest: Domain model validations

ATR-Based Risk Management: Risk management system using ATR (14-period on 1H):
  - TP = 1.5x ATR, SL = 1.0x ATR
  - 0.9% maximum distance cap for options
  - Absolute minimum of $0.25 to avoid excessively tight stops
  - Default exit time 15:55 ET

Options Exchange-Aware Strike Validation: Dynamic strike price validation directly from the exchange. The bot queries the IBKR option chain to find valid strikes, eliminating rejections from invalid strikes (0% Error 200).

Smart Exchange Routing: Intelligent contract routing:
  - NYSE for specific tickers (SQ, NVO, TSM)
  - ISLAND for all other stocks
  - Dramatic reduction in Error 200 "No security definition" rejections

Strategy Auto-Refresh with Staleness Detection: Automatic strategy scanning with staleness thresholds:
  - 5min timeframe: stale if > 15 minutes
  - 15min timeframe: stale if > 30 minutes
  - 1hour timeframe: stale if > 2 hours
  - 1day timeframe: stale if > 26 hours

Fixed
IBKR Error 135 (Bracket Rejection): Removed the price condition from the parent order (OrderType: MKT) in OrderFactory. This fixes the instant rejection that caused child orders (Take Profit / Stop Loss) to fail as orphans.

Bracket Logic (AND to OR): Fixed ConditionBuilder by setting conjunctionConnection(false). IBKR now correctly understands that the bot should exit the trade if it hits the Stop Loss/Take Profit OR if the Golden Rule time limit (21:55) is reached, rather than requiring both conditions simultaneously.

[1.3.28] - 2026-04-02
Added
Command Server V2: Added support for macro green and "Quick Trigger" mode.

Account Latch: Startup synchronization barrier to guarantee real net liquidation value (NetLiquidation).

Changed
Contract Factory: Improved routing by assigning NYSE to known tickers and ISLAND to all others.

Account Manager: Fixed Qty calculation by integrating the options multiplier (×100) vs USD/risk.

Data Infrastructure: Complete candle persistence system in CSV (data/{TICKER}_{TIMEFRAME}.csv):
  - CandleCsvService: Candle save/load with IBKR format parsing (daily/intraday)
  - CandleDownloadService: Individual, per-timeframe, or bulk download of 356 tickers with error tracking
  - IbkrCallbackHandler: Composition-based callback handler (no inheritance) with spurious error-366 filters

Strategy Scanner Service: Automatic scan of 356 tickers against 12 strategies with auto-refresh and staleness detection.

Order Execution Service: Full options execution with chain resolution:
  - Search for nearest expiration >= 48 hours away
  - Best strike selection from exchange-validated strikes
  - Bracket order placement with status tracking via EWrapper callbacks

Anti-Deadlock Backfill Barrier: Implemented a 45-second timeout on historical data loading (waitForBackfillCompletion) and active purging of ghost request IDs on API errors, ensuring the bot always starts even if TWS drops packets.

Telegram Bounce-Back UX: Modified HttpServer (port 9090) to return an HTML/JS payload that executes window.location.href = "tg://". This prevents the Chrome tab from staying open unnecessarily after confirming an order from mobile.
