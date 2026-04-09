# Options Quant System - Trading Flow

Spring Boot 4.0.4 + Java 25 trading system with 12 technical strategies, IBKR TWS integration, and automated options execution.

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                        REST API Layer                           │
│  /api/candles/*     /api/strategies/*     /api/trading/*        │
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
│  CSV files  │  │ + ta4j engine  │  │ (2% risk sizing)    │
└─────────────┘  └────────────────┘  └─────────────────────┘
                                            │
                                   ┌────────▼────────┐
                                   │   IBKR TWS API  │
                                   │  (Options + STK) │
                                   └─────────────────┘
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
├── config/              IbkrProperties (type-safe config)
├── controller/          REST endpoints (Candle, Strategy, Trading)
├── domain/              Candle, TimeFrame (core domain objects)
├── infrastructure/      IbkrCallbackHandler, MetricsService
├── service/             IbkrService, CandleDownloadService,
│                        CandleCsvService, StrategyScannerService,
│                        OrderExecutionService, TradingService,
│                        AccountManager
├── strategy/            TradingStrategy interface + 12 implementations
│   ├── data/            StrategyData (candle→BarSeries adapter)
│   ├── indicator/       WordenStochasticIndicator
│   ├── model/           TradePlan
│   └── utils/           RiskCalculator, MarketTimeUtils
└── trading/             ContractFactory, OrderFactory, ConditionBuilder
```
