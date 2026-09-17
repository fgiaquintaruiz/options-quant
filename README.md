# Options Quant Engine v1.3.30

### "The Vanilla Quant" - Java 25+ High-Performance Trading Bot

A hybrid algorithmic trading engine designed for Interactive Brokers (TWS/Gateway). Built for ultra-fast execution, strict risk management, automated backtest analysis, and intelligent ticker filtering.

> [!WARNING]
> **DISCLAIMER — EDUCATIONAL PROJECT ONLY**
>
> This is a **personal, educational/portfolio project** built to practice Java 25, Spring Boot, TDD, and trading-systems/backtesting engineering. **Nothing here is financial advice or a recommendation to buy or sell any security or option.**
>
> - It connects to **Interactive Brokers TWS / IB Gateway**, and in **live mode it EXECUTES REAL ORDERS** against whatever account TWS is logged into. Paper vs. live is only a port number (`7497` vs `7496`) — one misconfiguration trades real money.
> - Use it **at your own risk**. The author takes **no responsibility** for financial loss, misuse, or security incidents arising from this project.
> - The included strategies are **examples for exercising the engine**, not validated trading systems, and published backtest results are **not predictive** of live results.
> - Provided **as-is, with no warranty** of any kind.
>
> If you want to study the code — great. If you want to run this against a live account, know exactly what every line does first.

---

## Quick Start: How to Run

### 1. Prerequisites
- **Java 25** installed and in PATH.
- **TWS or IB Gateway** running and logged in (Paper or Live).
- **API Port** configured in TWS (7497 for paper, 7496 for live) and matching `application.yml`.
- **Node.js 20+** (for the frontend).

### 2. Run the Backend (Spring Boot)
Open your terminal in the project root:
```bash
# Using Gradle Wrapper
./gradlew bootRun
```
*The backend will start on port `9090` and the MarketScanner will begin its background task.*

### 3. Run the Frontend (React + Vite)
Open a new terminal in the `frontend` directory:
```bash
cd frontend
npm install
npm run dev
```
*The UI will usually be available at `http://localhost:5173`.*

### 4. IntelliJ IDEA Setup
- **Import Project:** Select `build.gradle.kts`.
- **Run Config:** Create a "Spring Boot" configuration for `OptionsQuantApplication`.
- **Lombok:** Ensure the Lombok plugin is enabled.

---

## Running the app

### Prerequisites

- **Java 25** in `PATH` (matches `build.gradle.kts` toolchain)
- **Node.js 20+** (frontend build and dev server)
- **Python 3.11+** with `pip install -r python/requirements.txt` (analytics sidecar — optional)
- **TWS or IB Gateway** running and logged in; API port enabled in TWS settings

### Backend only (Spring Boot on port 9090)

```bash
./gradlew bootRun
```

### Frontend dev server (Vite on port 5173)

```bash
cd frontend
npm install
npm run dev
```

### Full dev stack — backend + frontend + Python analytics (Unix/macOS)

```bash
bash scripts/dev-up.sh
```

Skip the Python analytics sidecar:

```bash
bash scripts/dev-up.sh --no-python
```

Stop all services started by the script:

```bash
bash scripts/dev-up.sh --stop
```

### Full stack — Windows (PowerShell)

Opens a separate console window per service (Spring Boot :9090, FastAPI :8001, Streamlit :8501):

```powershell
.\scripts\start-full-stack.ps1
```

Or via Gradle (builds frontend first, then opens windows):

```bash
./gradlew startFullStack
```

### Python analytics sidecar (FastAPI on port 8001)

```bash
cd python
python -m uvicorn analytics_service.main:app --host 127.0.0.1 --port 8001
```

With environment variables pointing to the Java engine:

```bash
JAVA_API_HOST=127.0.0.1 JAVA_API_PORT=9090 \
  python -m uvicorn analytics_service.main:app --host 127.0.0.1 --port 8001
```

### Backfill mode (historical candle back-fill from TWS + yfinance)

Pass `--backfill` as a program argument. The backfill rate and start year are controlled by `candles.backfill` in `application.yml` (default: 0.1 req/s from 2007):

```bash
./gradlew bootRun --args='--backfill'
```

#### Historical Backfill

Downloads `DAY_1` candles for all configured tickers into SQLite. Uses TWS for recent data and a Python yfinance sidecar for older chunks. Only date windows configured under `candles.backfill.periods` are downloaded — everything else is skipped. Chunks sent to yfinance are trimmed to the exact period intersection before the request is made.

-> See [docs/backfill.md](docs/backfill.md) for full configuration and troubleshooting.

### Backtest CLI (interactive menu, no web server)

```bash
./gradlew bootRun --args='--backtest-cli.enabled=true --server.port=-1'
```

Run on a different port if the web server is already up:

```bash
./gradlew bootRun --args='--backtest-cli.enabled=true --server.port=9091'
```

### Trading CLI (general purpose)

```bash
./gradlew bootRun --args='--cli.enabled=true'
```

### Run tests

Unit tests only (excludes slow, e2e, tws-paper):

```bash
./gradlew test
```

Full backtest / heavy HTTP tests (`@Tag("slow")`):

```bash
./gradlew slowTest
```

Playwright + Spring end-to-end tests (`@Tag("e2e")`):

```bash
./gradlew buildFrontend e2eTest
```

Real TWS/IB Gateway integration tests (`@Tag("tws-paper")`) — requires a logged-in paper session:

```bash
./gradlew twsTest -DrunTwsTests=true
```

Unit coverage report (JaCoCo HTML at `build/reports/jacoco-unit/html`):

```bash
./gradlew unitCoverageReport
```

Full-stack coverage (Java unit + Vitest + pytest-cov):

```bash
./gradlew fullStackCoverage
```

### Build production JAR (includes frontend)

```bash
./gradlew clean build
```

### Docker Compose

Full stack (Java engine + Python analytics + Streamlit UI + Redis):

```bash
docker compose up
```

Java engine only:

```bash
docker compose up redis java-engine
```

### Verify the stack is up

```bash
curl http://localhost:9090/actuator/health
curl http://localhost:8001/health
```

---

## Project Architecture

### Tech Stack
- **Java 25** (latest LTS)
- **Spring Boot 4.0.4** (latest stable)
- **JUnit 6** (via Spring Boot Starter Test)
- **Gradle 9.3.0** with Version Catalog (`gradle/libs.versions.toml`)
- **IBKR TWS API** for market data
- **TA4J 0.15-0.16** for technical analysis

### Core Structure
```
src/main/java/com/fgiaquinta/optionsquant/
├── OptionsQuantApplication.java      # Spring Boot entry point
├── config/
│   ├── IbkrProperties.java          # Type-safe IBKR configuration
│   └── StartupInitializer.java      # Auto-load services on startup
├── domain/
│   ├── Candle.java                  # OHLCV candle record (immutable)
│   ├── TimeFrame.java               # Enum: MIN_5, MIN_15, HOUR_1, DAY_1
│   ├── TickerInfo.java              # Ticker fundamental data record
│   └── TradePlan.java               # Trade plan with TP/SL
├── service/
│   ├── IbkrService.java             # Core IBKR TWS socket service
│   ├── CandleCsvService.java        # CSV persistence for candles
│   ├── AccountManager.java          # Balance tracking & position sizing
│   ├── TickerService.java           # CSV-based ticker management
│   ├── NewsFilterService.java       # Priority ticker filtering
│   ├── BacktestAnalyzer.java        # Automated backtest analysis
│   └── StrategyScreenerService.java # Strategy candidate screening
├── controller/
│   ├── CandleController.java        # REST API for candle operations
│   ├── BacktestController.java      # REST API for backtesting & analysis
│   └── StrategyController.java      # REST API for strategy scanning
├── strategy/
│   ├── TradingStrategy.java         # Strategy interface
│   ├── C1SqueezeCallStrategy.java   # 6 CALL strategies
│   ├── P1SqueezePutStrategy.java    # 6 PUT strategies
│   ├── utils/
│   │   └── RiskCalculator.java      # ATR-based TP/SL calculation
│   └── indicator/
│       └── WordenStochasticIndicator.java
├── backtest/
│   └── engine/
│       ├── BacktestEngine.java      # Core backtest runner
│       ├── SimulatedFillEngine.java # Slippage & commission simulation
│       └── CsvBacktestReporter.java # CSV output generation
├── cli/
│   └── TradingCli.java              # Interactive CLI tool
└── trading/
    ├── OrderFactory.java            # Bracket order creation
    ├── ContractFactory.java         # Smart exchange routing
    └── ConditionBuilder.java        # IBKR price/time conditions
```

---

## Advanced Features (Core Architecture)

- **Hybrid Delta Fetching & Anti-Deadlock:** The bot prioritizes reading historical data from local CSV files. It dynamically calculates the time elapsed since the last candle and requests only the missing delta from IBKR. Includes a **45-second Anti-Deadlock Timeout** that prevents freezes if the IBKR API drops requests.
- **Hard Caps Against Error 162:** Intelligent rate-limiting for historical data requests. Prevents automatic IBKR bans by capping max downloads per timeframe.
- **Ticker Sanitization & Smart Routing:** Aggressive symbol cleanup (`replaceAll("[^a-zA-Z]", "")`) and dynamic `primaryExch` routing (NASDAQ vs NYSE) to guarantee 0% rejection from "Error 200: No security definition".
- **Exchange-Aware Options:** Dynamic Strike Price selection validated directly against the exchange. The bot does not guess the strike — it matches against real available options.
- **Real-Time Equity Sync:** `AccountManager` pauses simulation execution until the real account balance (NetLiquidation) is synchronized, then mathematically calculates the number of allowed contracts based on per-trade risk (Options Multiplier Aware).
- **Position Size Safety Cap:** Hard cap of **10 contracts per trade** to prevent sizing bugs (e.g., P6 Reversal PUT with 96 contracts that lost -$18,972).
- **Per-Strategy ATR Multiplier Tuning:** Custom stops and targets per strategy based on backtest analysis (CALL: 1.2-1.5x SL, PUT: 1.1-1.3x SL).
- **Advanced Bracket Orders (OCA):** Institutional order routing. Entry is fired directly to market (MKT/LMT) without conditional blocks, while risk is fully delegated to IBKR servers using One-Cancels-All algorithms with hybrid conditional logic (Price OR Time "Golden Rule").
- **Automated Backtest Analyzer:** Service that reads `trades.csv` and generates optimization recommendations (win rate, risk/reward, time patterns, concentration).
- **CSV-Based Ticker Management:** 356 tickers with full fundamental data (P/E, ROIC, Beta, EPS Growth) in `data/tickers.csv`.
- **News Filter & Priority Tickers:** Top 10 tickers prioritized by fundamentals for focused scanning.
- **Strategy Screener:** Automatic scan of all 356 tickers with incremental criteria relaxation when no candidates are found.

---

## Remote Control Console & Telegram

The bot includes two remote control channels without needing to stop the engine:

### 1. Local Command Server (Port `7070`)
Ideal for terminal-based testing. How to connect (Git Bash / Linux / macOS):
```bash
curl telnet://localhost:7070
```

| Command | Description |
|---------|-------------|
| `skip` | Bypasses the AI Pre-Market analysis and immediately puts the bot into "LIVE" state |
| `macro green` | Forces macroeconomic filters (MarketRadar) to FAVORABLE state. Ideal for testing |
| `trigger <TICKER> <PRICE>` | [Quick Test] Automatically fires C1SqueezeCallStrategy for the given ticker |
| `trigger <TICK> <STRAT> <PRICE>` | Manually executes a specific strategy, bypassing time conditions |
| `exit` | Closes the remote console connection |

### 2. Interactive CLI (Command-Line Mode)

#### A. Trading CLI (General Purpose)
For strategy scanning and live trading:
```bash
./gradlew bootRun --args='--cli.enabled=true'
```

Interactive menu:
- **1:** Run full backtest with analysis
- **2:** Quick backtest (last 30 days)
- **3:** Analyze last backtest results
- **4:** List high-quality tickers
- **5:** List tickers by sector
- **6:** Exit

#### B. Backtest Analyzer CLI (Advanced)
For running backtests with detailed analysis and saving results to JSON for strategy tuning:
```bash
./gradlew bootRun --args='--backtest-cli.enabled=true'
```

Interactive menu:
- **1:** Run backtest + analysis (save to JSON)
- **2:** Compare two backtest results
- **3:** Analyze existing results
- **4:** View performance history by strategy
- **5:** Generate strategy tuning recommendations
- **6:** Exit

**Output Files:**
- `backtest/results/{testName}.json` - Complete result with analysis
- `backtest/trades.csv` - Detailed trade log
- `backtest/equity.csv` - Equity curve
- `backtest/summary.txt` - Executive summary

**Usage Example:**
```bash
# Run and save result
java -jar target/options-quant.jar --backtest-cli.enabled=true
# Enter: 1
# Test name: test_april_sweep

# Compare two tests
# Enter: 2
# First test: test_april_sweep
# Second test: test_march_baseline

# Generate recommendations
# Enter: 5
```

### 3. Telegram Integration (Legacy - Pending Migration)
- Inline keyboard with callback data for one-tap mobile execution
- Auto-webhook registration via Cloudflare tunnel
- Bounce-back UX: HTTP server returns HTML/JS that executes `window.location.href = "tg://"` to close Chrome tabs

---

## MarketScanner (Automatic Trading)

The **MarketScanner** runs automatically in the background when you start the Spring Boot application.

### Scan Schedule (Spain time, CEST = UTC+2)

| Period | Spain Time | Activity |
|--------|------------|----------|
| **Pre-market** | 10:00 - 15:30 | Scans 50 priority tickers |
| **Market hours** | 15:30 - 22:00 | Scans all 512 tickers |
| **After hours** | 22:00 - 10:00 | Scanner paused |

### Sync with 15-Minute Candles

The scanner runs **2 seconds after each 15-minute candle closes**:
- 10:00:02, 10:15:02, 10:30:02, 10:45:02, ...
- 15:30:02, 15:45:02, 16:00:02, ...
- 21:30:02, 21:45:02 (last scan)

### How It Works

1. **Automatic data loading** - If CSVs are stale, downloads fresh data from IBKR
2. **Scans all 12 strategies** - C1-C6 (CALL) + P1-P6 (PUT) against each ticker
3. **Detects signals** - Logs every signal found
4. **Auto-execution** - If `auto-execute: true` in application.yml, places the order

### MarketScanner Logs

You will see logs like these every 15 minutes during market hours:

```
=== MARKET SCAN === 15:30:02 (Spain) ===
SIGNAL: AAPL CALL @ $175.50 - C1SqueezeCall at 2026-04-10T15:30:02
  TP: $178.20 | SL: $173.80 | Entry: $175.50
  AUTO-EXECUTING...

=== SCAN SUMMARY ===
  Time: 15:30:02 (Spain)
  Tickers scanned: 512
  Signals found: 3
  Scan duration: 4523ms
======================
```

### Configuration

```yaml
ibkr:
  auto-execute: true   # Set to false to scan only without placing orders
```

**Important:**
- The MarketScanner **replaces the Trading CLI** — you no longer need to interact manually
- Just start the Spring Boot application and it scans automatically
- To disable auto-execution, set `auto-execute: false`
- Scans only run Monday through Friday

### Hot Tickers (Priority Scanning)

The MarketScanner scans **hot tickers FIRST** before the remaining 500+ tickers. This ensures high-priority, high-volume stocks are analyzed immediately for faster signal detection.

**Configuration (`application.yml`):**
```yaml
ibkr:
  hot-tickers:
    - SPY     # S&P 500 ETF (market benchmark)
    - QQQ     # Nasdaq 100 ETF (tech-heavy)
    - AAPL    # Apple
    - MSFT    # Microsoft
    - NVDA    # NVIDIA (AI boom)
    - TSLA    # Tesla
    - AMZN    # Amazon
    - META    # Meta/Facebook
    - GOOGL   # Google/Alphabet
    - AMD     # AMD (semiconductors)
    - JPM     # JPMorgan (financials)
    - V       # Visa (payments)
    - BRK.B   # Berkshire Hathaway
    - XOM     # Exxon (energy)
    - JNJ     # Johnson & Johnson (healthcare)
```

**How It Works:**
1. **Hot tickers scanned first** (1-2 seconds)
2. **Signals from hot tickers logged immediately**
3. **Remaining 500+ tickers scanned** (2-5 minutes)
4. **All signals aggregated** in scan summary

**Example Output:**
```
>>> Scanning 512 tickers (15 hot first, 497 remaining) against 12 strategies (autoRefresh=true)
Scanning 15 HOT tickers first: [SPY, QQQ, AAPL, MSFT, NVDA, TSLA, AMZN, META, GOOGL, AMD, JPM, V, BRK.B, XOM, JNJ]
SPY analyzed - 1 signal(s) found (245ms)
   SPY CALL @ $665.50 - C1SqueezeCall (TP: $670.00, SL: $660.00)
NVDA analyzed - 2 signal(s) found (312ms)
   NVDA PUT @ $890.00 - P2TrendPut (TP: $880.00, SL: $900.00)
   NVDA PUT @ $890.00 - P6ReversalPut (TP: $875.00, SL: $905.00)
Hot tickers scan complete - 3 signals found
Scanning 497 remaining tickers...
... (continues with remaining tickers)
```

**To Customize:**
Edit the `hot-tickers` list in `application.yml` with your preferred tickers. The system will scan those first on every cycle.

---

## REST API (Port `9090`)

### Historical Data
| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/candles/download` | Downloads candles for a specific ticker/timeframe |
| `POST` | `/api/candles/download-all` | Downloads all timeframes for a ticker |
| `POST` | `/api/candles/download-all-tickers` | Bulk download for all 356 configured tickers |
| `GET` | `/api/candles/local/{ticker}/{timeframe}` | Reads local CSV data |

### Strategy Scanning
| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/strategies/scan` | Scans all 356 tickers against all 12 strategies |
| `POST` | `/api/strategies/scan/{ticker}` | Scans a specific ticker (with `includeTradePlans` param) |

### Trade Execution
| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/trading/scan-and-execute` | Scan + auto-execution with risk management |
| `POST` | `/api/trading/execute` | Executes a manual trade with bracket orders |
| `GET` | `/api/trading/check-options` | Checks available option chain |
| `GET` | `/api/trading/account-status` | Real-time IBKR account status |

### Backtesting & Analysis
| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/backtest/run?from=YYYY-MM-DD&to=YYYY-MM-DD&tickers=SPY,AAPL` | Runs backtest with custom parameters |
| `POST` | `/api/backtest/run-all?from=YYYY-MM-DD&to=YYYY-MM-DD` | Full backtest across all tickers |
| `POST` | `/api/backtest/analyze` | **Analyzes trades.csv and generates improvement suggestions** |
| `GET` | `/api/backtest/tickers?sector=Technology&highQuality=true` | Query ticker database with filters |
| `GET` | `/api/backtest/priority-tickers?refresh=true` | Top 10 tickers by fundamentals |
| `GET` | `/api/backtest/ticker-score/{ticker}` | Detailed score for a ticker |
| `POST` | `/api/backtest/screen?count=10` | Strategy screener with criteria relaxation |

### Monitoring (Actuator)
| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/actuator/health` | Bot health status |
| `GET` | `/actuator/metrics` | Performance metrics |
| `GET` | `/actuator/prometheus` | Metrics export in Prometheus format |

#### Usage Examples

**1. Run backtest and analyze results:**
```bash
# Run backtest
curl -X POST "http://localhost:9090/api/backtest/run?from=2026-01-01&to=2026-04-10&tickers=SPY,AAPL" \
  -H "Content-Type: application/json"

# Analyze results
curl -X POST http://localhost:9090/api/backtest/analyze
```

**2. Get high-quality tickers:**
```bash
curl "http://localhost:9090/api/backtest/tickers?highQuality=true"
```

**3. Get top 10 tickers by fundamentals:**
```bash
curl "http://localhost:9090/api/backtest/priority-tickers?refresh=true"
```

---

## Backtesting & Analytics

### Backtest Output
Each run generates 3 files in the `backtest/` directory:

| File | Contents |
|------|----------|
| `trades.csv` | Detailed log: entry/exit prices, PnL, commissions, slippage, max drawdown, max runup |
| `equity.csv` | Timestamped equity curve |
| `summary.txt` | Executive summary with Sharpe ratio, profit factor, win rate |

### Backtest Analyzer Service
Automatically reads `trades.csv` and detects:
- **Position sizing bugs** (e.g., 96 contracts in one trade)
- **Low win rate strategies** (<40% on 20+ trades)
- **Poor risk/reward ratios** (avg win < avg loss)
- **High SL rates** (>60% of trades hitting stops)
- **Poor time patterns** (hours with low performance)
- **Consecutive losses** (streaks of 5+ losses)
- **Ticker underperformance** (tickers with <35% win rate on 5+ trades)
- **Direction bias** (CALL vs PUT win rate disparity >20%)

### Calculated Metrics
- **Profit Factor:** Total gains / Total losses (target > 1.5)
- **Sharpe Ratio:** Risk-adjusted return (annualized, 252-day basis)
- **Max Drawdown:** Maximum loss from historical peak (absolute and %)
- **Win Rate:** Percentage of winning trades
- **Avg Duration:** Average trade duration in hours

---

## Implemented Strategies (12 Total)

### CALL Strategies (Bullish)

| Strategy | Concept | Time Window | Key Condition |
|----------|---------|-------------|---------------|
| **C1 Squeeze Call** | Volatility compression breakout | Anytime | 4 SMAs (20/40/100/200) within 4% + 10-day high break |
| **C2 Trend Call** | Trend continuation on pullback | After 10 AM | Pullback to SMA20 + bullish confirmation candle |
| **C3 Bounce Call** | Bounce off SMA20 support (1H) | After 10 AM | Daily bullish trend + low touches SMA20 (1H) + 15m confirmation |
| **C4 Opening Call** | Gap-down reversal at open | 9:30-9:35 AM | Lateral prior day + gap -1.5% to -6% + first 5m green candle |
| **C5 Magnet Effect** | Extreme gap + Bollinger breakout | 9:45-9:55 AM | Worden Stochastic crosses above 20 |
| **C6 Reversal Call** | Bear-to-bull trend reversal | After 10 AM | Price below SMA20 for 3+ hours, crosses above with volume |

### PUT Strategies (Bearish)

| Strategy | Concept | Time Window | Key Condition |
|----------|---------|-------------|---------------|
| **P1 Squeeze Put** | Lateral breakdown | Anytime | Volatility compression + downside break |
| **P2 Trend Put** | Pullback rejection (bearish trend) | After 10 AM | Pullback to SMA20 + bearish confirmation candle |
| **P3 Bounce Put** | Rejection at SMA20 resistance (1H) | After 10 AM | Daily bearish trend + high touches SMA20 (1H) |
| **P4 Opening Put** | Gap-up trap at open | 9:30-9:35 AM | Excessive gap-up + immediate reversal |
| **P5 Magnet Effect** | Extreme gap up + Bollinger breakout | 9:45-9:55 AM | Worden Stochastic crosses below 80 |
| **P6 Reversal Put** | Bull-to-bear trend reversal | After 10 AM | Price above SMA20 for 3+ hours, crosses below with volume |

### Custom Indicator: Worden Stochastic
- **Implementation:** `WordenStochasticIndicator.java`
- **Calculation:** Percentile rank of close price within the lookback period
- **Usage:** Confirmation in C5/P5 strategies (Magnet Effect)
- **Fallback:** If unavailable, uses volume surge (1.5x average)

---

## News Filter & Priority Tickers

The system filters and prioritizes all 356 tickers to focus on the top 10 based on:

### Scoring Criteria (0-100 points)
- **ROIC (0-15 pts):** >15% = 15pts, >10% = 10pts, >5% = 5pts
- **EPS Growth (0-15 pts):** >15% = 15pts, >10% = 10pts, >5% = 5pts
- **Debt/Equity (0-20 pts):** <0.3 = 20pts, <0.5 = 15pts, <1.0 = 10pts
- **P/E Ratio (0-20 pts):** 0-15 = 20pts, <25 = 15pts, <35 = 10pts
- **Market Cap (0-20 pts):** >$50B = 20pts, >$20B = 15pts, >$10B = 10pts
- **Beta (0-10 pts):** 0.8-1.3 = 10pts, 0.6-1.5 = 7pts, other = 3pts

### Usage
```bash
# Get top 10
GET /api/backtest/priority-tickers

# Refresh list
GET /api/backtest/priority-tickers?refresh=true

# View detailed score
GET /api/backtest/ticker-score/AAPL
```

---

## Strategy Screener

Scans all 356 tickers and finds ideal candidates for the 12 strategies.

### Automatic Criteria Relaxation
If no candidates are found with strict criteria, the system incrementally relaxes:

| Level | Min Volume Change | Min Price Change | Min Market Cap | Min Volatility |
|-------|------------------|------------------|----------------|----------------|
| 1 (Strict) | 2% | 1.5% | $50M | 2% |
| 2 (Moderate) | 1.5% | 1.2% | $20M | 1.5% |
| 3 (Relaxed) | 1% | 1.0% | $10M | 1% |
| 4 (Very Relaxed) | 0.5% | 0.8% | $5M | 0.5% |

### Usage
```bash
POST /api/backtest/screen?count=10
```

---

## Configuration

### Main Parameters (`application.yml`)

```yaml
ibkr:
  host: 127.0.0.1              # TWS/IB Gateway host
  port: 7497                   # 7497 (paper), 7496 (live), 4002 (Gateway paper)
  sync-timeout: 30             # Connection timeout (seconds)
  auto-execute: true           # Must be true for real orders
  account-id: DUN598126        # IBKR account ID
  default-qty: 10              # Default contracts
  risk-per-trade-pct: 0.02     # Risk per trade (2% of balance)
  use-csv-tickers: true        # Use tickers.csv instead of YAML list
  tickers: []                  # Legacy - see data/tickers.csv
```

### Live Scanning (`scanner`)

Controls how many tickers are analyzed in parallel during live strategy scans and the traversal order for non-hot symbols. Values live under the `scanner` prefix in `application.yml`.

```yaml
scanner:
  concurrent-mode: AUTO              # AUTO | FIXED
  fixed-max-concurrent: 4            # Used only when concurrent-mode: FIXED
  auto-reserve-logical-cpus: 4       # Logical CPUs to leave free (IDE, OS, etc.)
  auto-min-concurrent: 1             # Parallelism floor in AUTO mode
  auto-max-concurrent-cap: 6         # Parallelism ceiling in AUTO mode
  prioritization-mode: HYBRID        # HYBRID | NATURAL
  hybrid-fundamental-weight: 0.65    # Relative weight (normalized with memory)
  hybrid-memory-weight: 0.35
```

**Parallelism**

- **FIXED:** always uses `fixed-max-concurrent` concurrent workers.
- **AUTO:** calculates a maximum based on `Runtime.getRuntime().availableProcessors()`:
  `effective = min(auto-max-concurrent-cap, max(auto-min-concurrent, availableProcessors - auto-reserve-logical-cpus))`.
  This limits load on laptops and reserves capacity for the environment.

**Ticker order (after hot list and `ibkr.hot-tickers`)**

- **NATURAL:** respects the order tickers appear in from CSV / configuration.
- **HYBRID:** priority tier first (top fundamentals from `NewsFilterService`, typically ~10 symbols), then the rest. Within each group, ordered by a hybrid score: normalized weights of `hybrid-fundamental-weight` × fundamentals score (CSV) plus `hybrid-memory-weight` × learned score in `TickerMemory`. If both weights are <= 0 in config, the code applies defaults (0.65 / 0.35).

**Observability:** the `GET /live-ui/status` response includes `scannerConcurrentMode`, `scannerPrioritizationMode`, `scannerHybridFundamentalWeight`, `scannerHybridMemoryWeight`, along with `maxConcurrentScans` and scan progress counters.

### Automatic Risk Management

**Position Formula:**
```
qty = (balance * riskPct) / (|entry - SL| * 100)
qty = Math.max(1, Math.min(qty, 10))  # HARD CAP AT 10 CONTRACTS MAX
```

- **Real-time balance:** NetLiquidation streaming from IBKR
- **ATR-based Stops:** TP = 1.5x ATR, SL = 1.0x ATR (14-period on 1H)
- **0.9% cap:** Maximum TP/SL distance capped at 0.9% of price for options
- **$0.25 minimum:** Minimum absolute distance to avoid overly tight stops
- **Exit time:** Default 15:55 ET (before close)

### Per-Strategy ATR Multipliers

| Strategy | SL Multiplier | TP Multiplier | Reason |
|----------|--------------|--------------|--------|
| c1squeezecall | 1.3x | 1.6x | Squeeze needs more room |
| c2trendcall | 1.2x | 1.5x | Trend pullbacks can be deep |
| c3bouncecall | 1.4x | 1.4x | Bounce needs wider stops |
| c4openingcall | 1.5x | 1.3x | Opening volatility requires wide stops |
| c5continuationcall | 1.4x | 1.4x | Gap reversals are volatile |
| c6reversalcall | 1.3x | 1.5x | Reversals need confirmation room |
| p1squeezeput | 1.2x | 1.5x | Squeeze puts slightly more reliable |
| p2trendput | 1.1x | 1.5x | Trend puts work well with standard stops |
| p3bounceput | 1.2x | 1.4x | Bounce puts similar to calls |
| p4openingput | 1.3x | 1.4x | Opening puts volatile |
| p5continuationput | 1.2x | 1.5x | Gap continuation puts |
| p6reversalput | 1.3x | 1.4x | Reversal puts need room |

**To adjust:** Edit `src/main/java/com/fgiaquinta/optionsquant/strategy/utils/RiskCalculator.java` HashMaps.

---

## Monitoring & Real-Time Metrics

### Prometheus Metrics Export
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
```

| Metric | Type | Description |
|--------|------|-------------|
| `ibkr_operation_duration_seconds` | Timer | Duration of each IBKR operation |
| `ibkr_errors_total` | Counter | IBKR error counter |
| `csv_operations_total` | Counter | CSV operations (read/write) |
| `http_requests_total` | Counter | HTTP requests per endpoint |

**Access:** `GET http://localhost:9090/actuator/prometheus`

### Cloudflare Tunnel Integration
- **Auto-exposure:** The bot launches `cloudflared.exe tunnel --url localhost:9090`
- **Automatic webhook:** Detects `.trycloudflare.com` URL and registers webhook in Telegram
- **Bounce-Back UX:** After confirming an order from mobile, closes the Chrome tab

---

## Testing

The project includes 5 test suites:

| Test | Coverage |
|------|----------|
| `RiskCalculatorTest` | TP/SL generation, 0.9% cap, minimums |
| `AccountManagerTest` | Balance tracking, 2% risk calculation, concurrent limits, position cap |
| `StrategyUnitTest` | Strategy trigger conditions + negative cases |
| `CandleCsvServiceTest` | CSV read/write, file detection |
| `CandleTest` | Domain model validations |

**Run tests:**
```bash
./gradlew test
```

---

## Legacy Features (Not Yet Migrated)

The `com.fgiaquinta.optionsquant.legacy` package contains functional features excluded from compilation:

- **AI Strategy Optimizer:** Gemini analyzes backtests and recommends optimal TP/SL per strategy
- **AI News Interpreter:** Macroeconomic sentiment analysis (BULLISH/BEARISH/NEUTRAL)
- **Staircase Filter:** Blocks re-entries on losing trades if price has not improved
- **90-Minute Time Stop:** Forces exit after 90 minutes in position
- **Dynamic Trailing Stop:** Uses SMA20 (5m/15m) when profit > 0.35%
- **Analyzers:** Channel, Gap, Trend, Volatility, Worden

---

## Trading Flow

See [`TRADING_FLOW.md`](TRADING_FLOW.md) for a detailed diagram of the complete lifecycle:
1. Historical data download → 2. Strategy scanning → 3. Macroeconomic filter → 4. Risk calculation → 5. Bracket order execution → 6. Position monitoring → 7. Results reporting → 8. **Automated analysis** (new)

---

## Changelog

See [`CHANGELOG.md`](CHANGELOG.md) for the full version history and changes.

### Latest Changes (v1.3.30)
- **Position Size Cap:** Maximum 10 contracts per trade (prevents bugs like 96 contracts)
- **Backtest Analyzer:** Automated analysis of trades.csv with suggestions
- **Per-Strategy ATR Tuning:** Custom stops/targets per strategy
- **CSV-Based Tickers:** 356 tickers with fundamentals in data/tickers.csv
- **CLI Tool:** Command-line interface for backtests and analysis
- **News Filter:** Top 10 tickers by fundamentals
- **Strategy Screener:** Scanning with automatic criteria relaxation

---

## IntelliJ Run Configuration Guide

### Option 1: Spring Boot Web Server (REST API)

**Purpose:** Run the full web application with REST endpoints on port 9090

**Steps:**
1. Open IntelliJ → `Run` → `Edit Configurations...`
2. Click `+` → Select `Spring Boot`
3. Configure:
   - **Name:** `OptionsQuant - Web Server`
   - **Main class:** `com.fgiaquinta.optionsquant.OptionsQuantApplication`
   - **VM options:** (leave empty)
   - **Program arguments:** (leave empty)
   - **Working directory:** `$MODULE_WORKING_DIR$`
   - **Use classpath of module:** `options-quant.main`
4. Click `Apply` → `OK`
5. Run with `Shift+F10` or click the run button

**Test it:**
```bash
curl http://localhost:9090/actuator/health
```

### Option 2: Backtest Analyzer CLI (Advanced with JSON Output)

**Purpose:** Advanced backtesting with comprehensive analysis, JSON export, test comparison, and strategy tuning recommendations

**IMPORTANT:** If the web server (MarketScanner) is already running on port 9090, you must use a different port or disable the web server:

**IntelliJ Configuration:**
1. `Run` → `Edit Configurations...`
2. Click `+` → Select `Application`
3. Configure:
   - **Name:** `OptionsQuant - Backtest Analyzer CLI`
   - **Main class:** `com.fgiaquinta.optionsquant.OptionsQuantApplication`
   - **Program arguments:** `--backtest-cli.enabled=true --server.port=-1`
   - **Working directory:** `$MODULE_WORKING_DIR$`
   - **Use classpath of module:** `options-quant.main`
   - **Modify options:** Check "Include dependencies with 'Provided' scope"
4. Click `Apply` → `OK`
5. Run with `Shift+F10`

**Alternative (Gradle):**
```bash
# Disable web server (recommended when MarketScanner is already running)
./gradlew bootRun --args='--backtest-cli.enabled=true --server.port=-1'

# Or use a different port
./gradlew bootRun --args='--backtest-cli.enabled=true --server.port=9091'
```

### Option 3: Custom Backtest with Specific Parameters

**Purpose:** Run backtest with custom date range, tickers, and risk parameters

**Steps:**
1. Use Option 3 (Backtest Analyzer CLI)
2. When prompted, enter:
   - From date: `2025-01-01` (1 year ago default)
   - To date: `2026-04-10` (today default)
   - Initial capital: `50000`
   - Risk %: `0.02`
   - Tickers: `SPY,AAPL,MSFT` (or leave empty for all 356)
   - Test name: `test_tech_giants` (auto-generated if empty)

**Results saved to:**
- `backtest/results/test_tech_giants.json` - Complete analysis
- `backtest/trades.csv` - Trade log
- `backtest/equity.csv` - Equity curve
- `backtest/summary.txt` - Summary

---

## Complete Trading Flow

### Phase 1: Data Download
```
POST /api/candles/download-all?ticker=SPY&saveToCsv=true
```
1. Connects to TWS via `IbkrService`
2. Downloads 4 timeframes: 5min, 15min, 1hour, 1day
3. Saves to `data/SPY_5min.csv`, `data/SPY_15min.csv`, etc.

```
POST /api/candles/download-all-tickers?saveToCsv=true
```
Downloads all 356 configured tickers x 4 timeframes = 1,424 CSV files.

### Phase 2: Data Freshness Check (Automatic)
When scanning strategies, the system checks CSV freshness:

| Timeframe | Stale Threshold |
|-----------|----------------|
| 5min      | 15 minutes     |
| 15min     | 30 minutes     |
| 1hour     | 2 hours        |
| 1day      | 26 hours       |

If data is stale or missing → **auto-downloads** fresh data before scanning.

### Phase 3: Strategy Scanning
```
POST /api/strategies/scan?includeTradePlans=true
POST /api/strategies/scan/AAPL?includeTradePlans=true
```

1. Loads CSV data for each ticker → converts to ta4j `BarSeries`
2. Runs all 12 strategies against the data
3. Returns triggered signals with entry/TP/SL levels

### Phase 4: Order Execution
```
# Scan + auto-execute all signals
POST /api/trading/scan-and-execute?autoExecute=true&qty=0&maxConcurrent=3

# Manual single execution
POST /api/trading/execute?ticker=AAPL&direction=CALL&qty=2&entryPrice=3.00&tp=4.50&sl=2.50

# Check option chain availability
GET /api/trading/check-options?ticker=AAPL

# Account status
GET /api/trading/account-status
```

**Execution Flow:**
1. Resolve option chain (find nearest expiration >= 48h away)
2. Find best strike (closest to entry price)
3. Calculate position size using 2% risk rule (or override with `qty`)
4. Place 3-leg bracket order: Entry (MKT) + Take Profit + Stop Loss
5. TP/SL use **price conditions on the underlying stock** + Golden Rule (exit before 21:55 ET)

---

## Backtest Analysis & Strategy Tuning

### Step 1: Run Backtest with Analysis

```bash
# Using Backtest Analyzer CLI
java -jar target/options-quant.jar --backtest-cli.enabled=true
Enter command (1-6): 1
```

**Output Files:**
```
backtest/
├── results/
│   └── april_baseline.json      <- Complete analysis
├── trades.csv                   <- Trade-by-trade log
├── equity.csv                   <- Equity curve
└── summary.txt                  <- Executive summary
```

### Step 2: Review Analysis Report

The CLI displays:
```
Performance Summary:
  Return: $2,340.50 (4.68%)
  Trades: 24 (Win Rate: 45.5%)
  Profit Factor: 1.25
  Max Drawdown: 8.2%
  Sharpe Ratio: 0.85

Suggestions:
  Critical Issues: 2
  Optimization Tips: 9
```

### Step 3: Tune Strategies Based on Results

**Example Analysis Output:**
```
c3bouncecall has 0% win rate (0/8 trades)
  -> Recommendation: Widen SL ATR multiplier from 1.4x to 1.7x

p6reversalput avg win $0 vs avg loss $-18,972
  -> CRITICAL: Position size bug detected (96 contracts)
  -> FIXED: Now capped at 10 contracts max

AAPL has 0% win rate over 10 trades ($-21,339 PnL)
  -> Recommendation: Remove from watchlist or reduce position size
```

**Apply Tuning:**
Edit `src/main/java/com/fgiaquinta/optionsquant/strategy/utils/RiskCalculator.java`:

```java
// Before (poor performance)
STRATEGY_SL_MULTIPLIERS.put("c3bouncecall", 1.4);

// After (wider stops for better win rate)
STRATEGY_SL_MULTIPLIERS.put("c3bouncecall", 1.7);
STRATEGY_TP_MULTIPLIERS.put("c3bouncecall", 1.5);  // Also increase TP
```

### Step 4: Re-Run Backtest with Tuned Parameters

```bash
# Run again with same CLI
Enter command: 1
Test name: april_tuned_v1
```

### Step 5: Compare Results

```bash
Enter command: 2
First test: april_baseline
Second test: april_tuned_v1
```

**Comparison Output:**
```
Metric                  | april_baseline     | april_tuned_v1
--------------------------------------------------------------------------------
Final Capital           | $52,340.50         | $53,890.25
Return %                | 4.68%              | 7.78%
Total Trades            | 24                 | 22
Win Rate                | 45.5%              | 54.5%
Profit Factor           | 1.25               | 1.52
Max Drawdown %          | 8.20%              | 6.80%
Sharpe Ratio            | 0.85               | 1.12

Better Return: april_tuned_v1 (7.78% vs 4.68%)
```

### Step 6: Iterate Until Satisfied

Repeat steps 3-5 until:
- Win rate > 50%
- Profit factor > 1.5
- Max drawdown < 10%
- Sharpe ratio > 1.0

---

## The 12 Strategies

### Call Strategies (Bullish)

| Strategy | Concept | Time Window | Key Indicators |
|----------|---------|-------------|----------------|
| **C1 Squeeze Call** | Volatility compression → upside breakout | Anytime | 4 SMAs (20/40/100/200) within 4%, 10-day high break, 15m Bollinger ride |
| **C2 Trend Call** | Pullback continuation in uptrend | After 10 AM | Daily uptrend, pullback to SMA20, bullish candle, volume, **BB context** |
| **C3 Bounce Call** | Bounce off 1H SMA20 support | After 10 AM | Daily uptrend, 1H low touches SMA20, 15m confirmation, **BB trend** |
| **C4 Opening Call** | Gap-down reversal at open | 9:30-9:35 AM | Lateral prev day, gap -1.5% to -6%, first 5m green, **BB bands** |
| **C5 Magnet Effect** | Magnet effect after extreme gap down | 9:45-9:55 AM | Bearish trend, 3% below SMA20, 15m below BB, **Worden Stochastic cross** |
| **C6 Reversal Call** | Bear-to-bull trend reversal | After 10 AM | Was below SMA20 for 3h+, crosses above with volume |

### Put Strategies (Bearish)

| Strategy | Concept | Time Window | Key Indicators |
|----------|---------|-------------|----------------|
| **P1 Squeeze Put** | Volatility compression → downside breakdown | Anytime | 4 SMAs within 4%, 10-day low break, 15m Bollinger ride down |
| **P2 Trend Put** | Pullback rejection in downtrend | After 10 AM | Daily downtrend, rally to SMA20 rejected, bearish candle, **BB context** |
| **P3 Bounce Put** | Rejection at 1H SMA20 resistance | After 10 AM | Daily downtrend, 1H high touches SMA20, 15m confirmation, **BB trend** |
| **P4 Opening Put** | Gap-up trap at open | 9:30-9:35 AM | Lateral prev day, gap +1.5% to +6%, first 5m red, **BB bands** |
| **P5 Magnet Effect** | Magnet effect after extreme gap up | 9:45-9:55 AM | Bullish trend, 3% above SMA20, 15m above BB, **Worden Stochastic cross** |
| **P6 Reversal Put** | Bull-to-bear trend reversal | After 10 AM | Was above SMA20 for 3h+, crosses below with volume |

---

## Position Sizing (2% Risk Rule with 10-Contract Cap)

```
maxRiskDollars = accountBalance x riskPerTradePct    (default 2%)
riskPerContract = |entryPrice - stopLoss| x 100      (options multiplier)
quantity = floor(maxRiskDollars / riskPerContract)
quantity = Math.max(1, Math.min(quantity, 10))        <- HARD CAP AT 10
```

**Example:** Account $50,000, 2% risk
- Max risk per trade: $1,000
- Entry $3.00, SL $2.50 → risk/contract = $50
- Calculated qty: 20 contracts
- **Capped qty: 10 contracts** (safety limit)

**Why the cap?**
Prevents catastrophic losses from position sizing bugs (e.g., P6 Reversal PUT with 96 contracts that lost $18,972).

---

## Bracket Order Structure

Each options trade places 3 orders as an OCA (One-Cancels-All) group:

```
+-------------------------------------------------+
| Parent Order (BUY, MKT, transmit=false)         |
|   * Adaptive algo, Normal priority              |
|                                                 |
| Take Profit (SELL, MKT, transmit=false)         |
|   * Condition: underlying price >= TP (calls)   |
|   * OR time >= 21:55 ET (Golden Rule)           |
|                                                 |
| Stop Loss (SELL, MKT, transmit=true) <- fires   |
|   * Condition: underlying price <= SL (calls)   |
|   * OR time >= 21:55 ET (Golden Rule)           |
+-------------------------------------------------+
```

When TP or SL triggers, the other is automatically cancelled. The Golden Rule ensures no options are held overnight.

---

## Safety Checklist

- [ ] `auto-execute: false` until ready for live trading
- [ ] Test with TWS paper account first (port 7497)
- [ ] Verify account balance syncs correctly
- [ ] Check position sizing makes sense for your account size
- [ ] **Verify 10-contract cap is active** (check logs for "Position size cap triggered")
- [ ] Set appropriate `maxConcurrent` limit
- [ ] Verify option chain resolution works for your tickers
- [ ] Run backtest analysis before live trading
- [ ] Tune strategies based on analysis recommendations
- [ ] Compare tuned vs baseline backtest results

---

## Tests

45 tests cover:
- **StrategyData**: Candle→BarSeries conversion, timeframe checks
- **WordenStochasticIndicator**: Percentile rank calculation
- **C5/P5 Strategies**: All trigger conditions and negative cases
- **C1 Squeeze**: Compressed SMA breakout logic
- **AccountManager**: Balance tracking, 2% risk sizing, concurrent limits, **position cap**
- **RiskCalculator**: TP/SL generation, 0.9% cap, **per-strategy multipliers**
- **CandleCsvService**: CSV read/write, file detection
- **BollingerBandsUtil**: Band calculations, trend detection, candle position checks

```bash
./gradlew test
```

---

## Package Structure

### Build & Run
```bash
# Build
./gradlew clean build

# Run tests
./gradlew test

# Start Spring Boot (web server + automatic MarketScanner on port 9090)
./gradlew bootRun
```

### Test IBKR Connection
With TWS running on port 7497:
```bash
curl -X POST "http://localhost:9090/api/candles/download?ticker=SPY&timeframe=DAY_1"
```

### 1. Run Backtest with Analysis
```bash
# Option A: Interactive CLI
java -jar target/options-quant.jar --cli.enabled=true
# Select option 1

# Option B: REST API
curl -X POST "http://localhost:9090/api/backtest/run?from=2026-01-01&to=2026-04-10"
curl -X POST http://localhost:9090/api/backtest/analyze
```

### 2. Get Priority Tickers
```bash
curl "http://localhost:9090/api/backtest/priority-tickers?refresh=true"
```

### 3. Run Strategy Screener
```bash
curl -X POST "http://localhost:9090/api/backtest/screen?count=10"
```

### 4. Analyze Last Backtest
```bash
curl -X POST http://localhost:9090/api/backtest/analyze
```

---

## Known Issues & Troubleshooting

### TWS Competing Session Error

**Error 10197**: "No market data during competing live session"

This happens when TWS already has an active market data session. Solutions:
1. Use **IB Gateway** instead of TWS (port 4002)
2. Keep TWS open with charts for the tickers you're downloading
3. Close TWS completely and use IB Gateway only

### Position Sizing Safety

If you see unusually large position sizes (>10 contracts), the position cap should prevent execution. Check logs for:
```
Position size cap triggered: 96 -> 10 contracts (strategy: p6reversalput)
```

### Backtest CSV Duplicates

If `backtest/trades.csv` shows duplicate trades, delete the file before running a new backtest:
```bash
rm backtest/trades.csv
```

---

## Architecture Evolution

### What Changed from v1.0.0

**Before (v1.0.0):**
- Complex architecture with multiple layers (strategies, analyzers, indicators, etc.)
- Mixed concerns: backtesting, live trading, and data downloading intertwined
- No clear separation of data download vs. strategy execution
- Difficult to test IBKR integration in isolation

**After (v2.0.0+):**
- **Single responsibility**: Each service does one thing well
- **Testable**: Unit tests for CSV service, domain models
- **Simple**: No polling, no caching complexity, just direct API calls
- **REST API**: Easy to trigger downloads from anywhere
- **CLI runner**: Download on startup for batch operations
- **Automated analysis**: Backtest analyzer provides actionable insights

### Legacy Code Status

The old code (strategies, backtester, etc.) is still in the project but excluded from compilation. Migration progress:

1. Core IBKR service (DONE)
2. Strategy engine (DONE - 12 strategies migrated)
3. Backtest runner (DONE with CSV reporting)
4. Live trading mode (DONE with bracket orders)
5. Telegram integration (Legacy - pending migration)
6. AI optimizer integration (Legacy - pending migration)

---

## Data Sources

| Source | Timeframes | Tickers | Period | Status |
|--------|-----------|---------|--------|--------|
| Polygon.io (massive_import.py) | MIN_5, MIN_15, HOUR_1 | 510 active | 2024-05 → present | Active — note: free tier limits cause ~7-24 day lag in MIN_5 coverage |
| TWS/IBKR (Java backfill) | DAY_1, HOUR_1, MIN_15, MIN_5 | 511 | 2023-01 → present | Active |
| yfinance (sidecar) | DAY_1 | 511 | historical | Complete |
| Stooq DAY_1 | DAY_1 | ~8000-10000 | historical | Pending migration to candles_stooq |
| Stooq MIN_5 | MIN_5 | 4570 files | 2025-12 → 2026-05 | Pending import |

> Note: Polygon and Java overlap May 15-31, 2024 (~16 days). Idempotent upsert handles duplicates.

---

## Changelog

See [`CHANGELOG.md`](CHANGELOG.md) for the full version history and changes.
