# Options Quant System - Trading Flow

Spring Boot 4.0.4 + Java 25 trading system with 12 technical strategies, IBKR TWS integration, automated options execution, and advanced backtest analysis.

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

### Option 2: Trading CLI (General Purpose)

**Purpose:** Interactive CLI for quick backtests, ticker screening, and analysis

**Steps:**
1. `Run` → `Edit Configurations...`
2. Click `+` → Select `Application`
3. Configure:
   - **Name:** `OptionsQuant - Trading CLI`
   - **Main class:** `com.fgiaquinta.optionsquant.OptionsQuantApplication`
   - **Program arguments:** `--cli.enabled=true`
   - **Working directory:** `$MODULE_WORKING_DIR$`
   - **Use classpath of module:** `options-quant.main`
   - **Modify options:** Check "Include dependencies with 'Provided' scope"
4. Click `Apply` → `OK`
5. Run with `Shift+F10`

**Alternative (Gradle):**
```bash
./gradlew bootRun --args='--cli.enabled=true'
```

### Option 3: Backtest Analyzer CLI (Advanced with JSON Output)

**Purpose:** Advanced backtesting with comprehensive analysis, JSON export, test comparison, and strategy tuning recommendations

**Steps:**
1. `Run` → `Edit Configurations...`
2. Click `+` → Select `Application`
3. Configure:
   - **Name:** `OptionsQuant - Backtest Analyzer CLI`
   - **Main class:** `com.fgiaquinta.optionsquant.OptionsQuantApplication`
   - **Program arguments:** `--backtest-cli.enabled=true`
   - **Working directory:** `$MODULE_WORKING_DIR$`
   - **Use classpath of module:** `options-quant.main`
   - **Modify options:** Check "Include dependencies with 'Provided' scope"
4. Click `Apply` → `OK`
5. Run with `Shift+F10`

**Alternative (Gradle):**
```bash
./gradlew bootRun --args='--backtest-cli.enabled=true'
```

### Option 4: Custom Backtest with Specific Parameters

**Purpose:** Run backtest with custom date range, tickers, and risk parameters

**Steps:**
1. Use Option 3 (Backtest Analyzer CLI)
2. When prompted, enter:
   - From date: `2026-01-01`
   - To date: `2026-04-10`
   - Initial capital: `50000`
   - Risk %: `0.02`
   - Tickers: `SPY,AAPL,MSFT` (or leave empty for all 356)
   - Test name: `test_tech_giants`

**Results saved to:**
- `backtest/results/test_tech_giants.json` - Complete analysis
- `backtest/trades.csv` - Trade log
- `backtest/equity.csv` - Equity curve
- `backtest/summary.txt` - Summary

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                        REST API Layer                           │
│  /api/candles/*   /api/strategies/*   /api/trading/*            │
│  /api/backtest/*  /actuator/*                                   │
└──────┬──────────────────┬──────────────────┬────────────────────┘
       │                  │                  │
┌──────▼──────┐  ┌───────▼────────┐  ┌──────▼──────────────┐
│ Candle      │  │ Strategy       │  │ Trading             │
│ Download    │  │ Scanner        │  │ Service             │
│ Service     │  │ Service        │  │                     │
└──────┬──────┘  └───────┬────────┘  └──────┬──────────────┘
       │                  │                  │
┌──────▼──────┐  ┌───────▼────────┐  ┌──────▼──────────────┐
│ Candle CSV  │  │ 12 Strategies  │  │ Order Execution     │
│ Service     │  │ (C1-C6,P1-P6)  │  │ Service             │
└──────┬──────┘  └───────┬────────┘  └──────┬──────────────┘
       │                  │                  │
┌──────▼──────┐  ┌───────▼────────┐  ┌──────▼──────────────┐
│   data/     │  │ StrategyData   │  │ AccountManager      │
│  CSV files  │  │ + ta4j engine  │  │ (2% risk, max 10)   │
└─────────────┘  └────────────────┘  └─────────────────────┘
       │                                    │
┌──────▼────────────┐              ┌───────▼────────────────┐
│ TickerService     │              │ Backtest Engine        │
│ (356 tickers CSV) │              │ + Analyzer Service     │
│ + NewsFilter      │              │ + JSON Export          │
│ + Screener        │              └────────────────────────┘
└───────────────────┘                       │
                                    ┌────────▼────────┐
                                    │  IBKR TWS API   │
                                    │ (Options + STK) │
                                    └─────────────────┘

┌─────────────────────────────────────────────────────────────┐
│                    CLI Interfaces                           │
│  Trading CLI (--cli.enabled=true)                           │
│  Backtest Analyzer CLI (--backtest-cli.enabled=true)        │
└─────────────────────────────────────────────────────────────┘
```

## Complete Flow

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
Downloads all 356 configured tickers × 4 timeframes = 1,424 CSV files.

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
1. Resolve option chain (find nearest expiration ≥ 48h away)
2. Find best strike (closest to entry price)
3. Calculate position size using 2% risk rule (or override with `qty`)
4. Place 3-leg bracket order: Entry (MKT) + Take Profit + Stop Loss
5. TP/SL use **price conditions on the underlying stock** + Golden Rule (exit before 21:55 ET)

## The 12 Strategies

### Call Strategies (Bullish)

| Strategy | Concept | Time Window | Key Indicators |
|----------|---------|-------------|----------------|
| **C1 Squeeze Call** | Volatility compression → upside breakout | Anytime | 4 SMAs (20/40/100/200) within 4%, 10-day high break, 15m Bollinger ride |
| **C2 Trend Call** | Pullback continuation in uptrend | After 10 AM | Daily uptrend, pullback to SMA20, bullish candle, volume |
| **C3 Bounce Call** | Bounce off 1H SMA20 support | After 10 AM | Daily uptrend, 1H low touches SMA20, 15m confirmation |
| **C4 Opening Call** | Gap-down reversal at open | 9:30-9:35 AM | Lateral prev day, gap -1.5% to -6%, first 5m green |
| **C5 Efecto Imán** | Magnet effect after extreme gap down | 9:45-9:55 AM | Bearish trend, 3% below SMA20, 15m below BB, **Worden Stochastic cross** |
| **C6 Reversal Call** | Bear-to-bull trend reversal | After 10 AM | Was below SMA20 for 3h+, crosses above with volume |

### Put Strategies (Bearish)

| Strategy | Concept | Time Window | Key Indicators |
|----------|---------|-------------|----------------|
| **P1 Squeeze Put** | Volatility compression → downside breakdown | Anytime | 4 SMAs within 4%, 10-day low break, 15m Bollinger ride down |
| **P2 Trend Put** | Pullback rejection in downtrend | After 10 AM | Daily downtrend, rally to SMA20 rejected, bearish candle |
| **P3 Bounce Put** | Rejection at 1H SMA20 resistance | After 10 AM | Daily downtrend, 1H high touches SMA20, 15m confirmation |
| **P4 Opening Put** | Gap-up trap at open | 9:30-9:35 AM | Lateral prev day, gap +1.5% to +6%, first 5m red |
| **P5 Efecto Imán** | Magnet effect after extreme gap up | 9:45-9:55 AM | Bullish trend, 3% above SMA20, 15m above BB, **Worden Stochastic cross** |
| **P6 Reversal Put** | Bull-to-bear trend reversal | After 10 AM | Was above SMA20 for 3h+, crosses below with volume |

### C5/P5 "Efecto Imán" Detail

The **Magnet Effect** strategies (C5/P5) use the Worden Stochastic indicator as the final confirmation signal:

**C5 (CALL):**
1. Bearish trend (2+ red daily candles)
2. Strong gap down opening, price ≥ 3% below 1H SMA20
3. First 15m candle completely below Bollinger lower band
4. **Confirmation**: Worden Stochastic crosses above 20 (oversold recovery) → buy signal

**P5 (PUT):**
1. Bullish trend (2+ green daily candles)
2. Strong gap up opening, price ≥ 3% above 1H SMA20
3. First 15m candle completely above Bollinger upper band
4. **Confirmation**: Worden Stochastic crosses below 80 (overbought recovery) → sell signal

If WordenStochasticIndicator is not provided, the system falls back to **volume surge** (1.5× average) as the mathematical substitute.

## Position Sizing (2% Risk Rule)

```
maxRiskDollars = accountBalance × riskPerTradePct    (default 2%)
riskPerContract = |entryPrice - stopLoss| × 100      (options multiplier)
quantity = floor(maxRiskDollars / riskPerContract)
```

**Example:** Account $50,000, 2% risk
- Max risk per trade: $1,000
- Entry $3.00, SL $2.50 → risk/contract = $50
- Quantity = 20 contracts

**Configurable** via `ibkr.risk-per-trade-pct` in `application.yml`:
```yaml
ibkr:
  risk-per-trade-pct: 0.02   # 2% (change to 0.05 for 5%, etc.)
```

## Bracket Order Structure

Each options trade places 3 orders as an OCA (One-Cancels-All) group:

```
┌─────────────────────────────────────────────────┐
│ Parent Order (BUY, MKT, transmit=false)         │
│   • Adaptive algo, Normal priority              │
│                                                 │
│ Take Profit (SELL, MKT, transmit=false)         │
│   • Condition: underlying price >= TP (calls)   │
│   • OR time >= 21:55 ET (Golden Rule)           │
│                                                 │
│ Stop Loss (SELL, MKT, transmit=true) ← fires    │
│   • Condition: underlying price <= SL (calls)   │
│   • OR time >= 21:55 ET (Golden Rule)           │
└─────────────────────────────────────────────────┘
```

When TP or SL triggers, the other is automatically cancelled. The Golden Rule ensures no options are held overnight.

## Configuration

```yaml
ibkr:
  host: 127.0.0.1
  port: 7497              # 7497=paper, 7496=live
  sync-timeout: 30
  auto-execute: false     # ⚠️ MUST be true to place real orders
  account-id: "DUN598126"
  default-qty: 10
  risk-per-trade-pct: 0.02
  tickers:
    - SPY
    - QQQ
    # ... 356 tickers total
```

## Safety Checklist

- [ ] `auto-execute: false` until ready for live trading
- [ ] Test with TWS paper account first (port 7497)
- [ ] Verify account balance syncs correctly
- [ ] Check position sizing makes sense for your account size
- [ ] Set appropriate `maxConcurrent` limit
- [ ] Verify option chain resolution works for your tickers

## Tests

45 tests cover:
- **StrategyData**: Candle→BarSeries conversion, timeframe checks
- **WordenStochasticIndicator**: Percentile rank calculation
- **C5/P5 Strategies**: All trigger conditions and negative cases
- **C1 Squeeze**: Compressed SMA breakout logic
- **AccountManager**: Balance tracking, 2% risk sizing, concurrent limits
- **RiskCalculator**: TP/SL generation, 0.9% cap enforcement
- **CandleCsvService**: CSV read/write, file detection

```bash
gradlew test
```

## Package Structure

```
com.fgiaquinta.optionsquant/
├── config/              IbkrProperties, StartupInitializer
├── cli/                 TradingCli, BacktestCli
├── controller/          CandleController, StrategyController,
│                        TradingController, BacktestController
├── domain/              Candle, TimeFrame, TickerInfo, TradePlan
├── infrastructure/      IbkrCallbackHandler, MetricsService
├── service/             IbkrService, CandleDownloadService,
│                        CandleCsvService, StrategyScannerService,
│                        OrderExecutionService, TradingService,
│                        AccountManager, TickerService,
│                        NewsFilterService, BacktestAnalyzer,
│                        StrategyScreenerService
├── strategy/            TradingStrategy interface + 12 implementations
│   ├── data/            StrategyData (candle→BarSeries adapter)
│   ├── indicator/       WordenStochasticIndicator
│   ├── model/           TradePlan
│   └── utils/           RiskCalculator (per-strategy ATR multipliers)
├── trading/             ContractFactory, OrderFactory, ConditionBuilder
└── backtest/
    ├── engine/          BacktestEngine, SimulatedFillEngine,
    │                    CsvBacktestReporter
    └── domain/          BacktestConfig, BacktestReport,
                         TradeRecord, FillResult
```

## Workflow 5: Backtest Analysis & Strategy Tuning

### Step 1: Run Backtest with Analysis

```bash
# Using Backtest Analyzer CLI
java -jar target/options-quant.jar --backtest-cli.enabled=true
Enter command (1-6): 1
```

**Input:**
- Date range: 2026-01-01 to 2026-04-10
- Initial capital: $50,000
- Risk per trade: 2%
- Tickers: All 356 (or custom list)
- Test name: `april_baseline`

**Output Files:**
```
backtest/
├── results/
│   └── april_baseline.json      ← Complete analysis
├── trades.csv                   ← Trade-by-trade log
├── equity.csv                   ← Equity curve
└── summary.txt                  ← Executive summary
```

### Step 2: Review Analysis Report

The CLI displays:
```
📊 Performance Summary:
  Return: $2,340.50 (4.68%)
  Trades: 24 (Win Rate: 45.5%)
  Profit Factor: 1.25
  Max Drawdown: 8.2%
  Sharpe Ratio: 0.85

💡 Suggestions:
  Critical Issues: 2
  Optimization Tips: 9
```

### Step 3: Tune Strategies Based on Results

**Example Analysis Output:**
```
⚠️ c3bouncecall has 0% win rate (0/8 trades)
  → Recommendation: Widen SL ATR multiplier from 1.4x to 1.7x

⚖️ p6reversalput avg win $0 vs avg loss $-18,972
  → CRITICAL: Position size bug detected (96 contracts)
  → FIXED: Now capped at 10 contracts max

📉 AAPL has 0% win rate over 10 trades ($-21,339 PnL)
  → Recommendation: Remove from watchlist or reduce position size
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

🏆 Better Return: april_tuned_v1 (7.78% vs 4.68%)
```

### Step 6: Iterate Until Satisfied

Repeat steps 3-5 until:
- Win rate > 50%
- Profit factor > 1.5
- Max drawdown < 10%
- Sharpe ratio > 1.0

### Step 7: Review Strategy History

```bash
Enter command: 4
```

**Output:**
```
Strategy                | Tests    | Avg PnL    | Total PnL
-----------------------------------------------------------------
c1squeezecall           | 3        | $450.25    | $1,350.75
c2trendcall             | 3        | $320.50    | $961.50
c3bouncecall            | 3        | $180.30    | $540.90
p1squeezeput            | 3        | $520.75    | $1,562.25
p6reversalput           | 3        | $-5,200.00 | $-15,600.00  ❌
```

**Action:** Consider disabling consistently underperforming strategies.

---

## Position Sizing (2% Risk Rule with 10-Contract Cap)

```
maxRiskDollars = accountBalance × riskPerTradePct    (default 2%)
riskPerContract = |entryPrice - stopLoss| × 100      (options multiplier)
quantity = floor(maxRiskDollars / riskPerContract)
quantity = Math.max(1, Math.min(quantity, 10))        ← HARD CAP AT 10
```

**Example:** Account $50,000, 2% risk
- Max risk per trade: $1,000
- Entry $3.00, SL $2.50 → risk/contract = $50
- Calculated qty: 20 contracts
- **Capped qty: 10 contracts** (safety limit)

**Why the cap?**
Prevents catastrophic losses from position sizing bugs (e.g., P6 Reversal PUT with 96 contracts that lost $18,972).

**Configurable** via `ibkr.risk-per-trade-pct` in `application.yml`:
```yaml
ibkr:
  risk-per-trade-pct: 0.02   # 2% (change to 0.05 for 5%, etc.)
```

---

## Quick Reference: CLI Commands

### Trading CLI (--cli.enabled=true)
```
1. Run backtest with analysis
2. Quick backtest (last 30 days)
3. Analyze latest backtest results
4. List high-quality tickers
5. List tickers by sector
6. Exit
```

### Backtest Analyzer CLI (--backtest-cli.enabled=true)
```
1. Run backtest + analysis (save to JSON)
2. Compare two backtest results
3. Analyze existing backtest results
4. View strategy performance history
5. Generate strategy tuning recommendations
6. Exit
```

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

```bash
./gradlew test
```
