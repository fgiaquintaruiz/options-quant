# Options Quant - Spring Boot Refactored Core

## Project Structure (v2.0.0)

### Tech Stack
- **Java 25** (latest LTS)
- **Spring Boot 3.5.0** (latest stable)
- **Gradle 9.3.0** with Kotlin DSL (`build.gradle.kts`)
- **JUnit 5** for testing
- **IBKR TWS API** for market data

### Core Architecture

```
src/main/java/com/fgiaquinta/optionsquant/
├── OptionsQuantApplication.java      # Spring Boot entry point
├── CandleDownloader.java            # CLI runner for downloading candles
├── config/
│   └── IbkrProperties.java          # Type-safe IBKR configuration
├── domain/
│   ├── Candle.java                  # OHLCV candle record (immutable)
│   └── TimeFrame.java               # Enum: MIN_5, MIN_15, HOUR_1, DAY_1
├── service/
│   ├── IbkrService.java             # Core IBKR TWS socket service
│   └── CandleCsvService.java        # CSV persistence for candles
└── controller/
    └── CandleController.java        # REST API for candle operations
```

### Key Features

#### 1. **IbkrService** - Core TWS Socket Integration
- Direct connection to TWS/IB Gateway via `EClientSocket`
- Downloads historical data with proper error handling
- Supports all timeframes (5min, 15min, 1hour, 1day)
- Sequential multi-ticker download with rate limiting
- No caching/polling complexity - just pure API calls

#### 2. **CandleCsvService** - Data Persistence
- Saves candles to `data/{TICKER}_{TIMEFRAME}.csv`
- Loads from CSV for offline backtesting
- Detects existing local data to avoid redundant downloads

#### 3. **REST API** (`/api/candles`)
```bash
# Download historical data
POST /api/candles/download?ticker=SPY&timeframe=DAY_1&saveToCsv=true

# Download all timeframes
POST /api/candles/download-all?ticker=SPY&saveToCsv=true

# Load from local CSV
GET /api/candles/local?ticker=SPY&timeframe=DAY_1

# Check if local data exists
GET /api/candles/local/exists?ticker=SPY&timeframe=DAY_1

# Connection status
GET /api/candles/status
```

#### 4. **CLI Runner** (`CandleDownloader`)
Run on startup with:
```bash
./gradlew bootRun --args='--download-on-start=true'
```

### Configuration

`application.yml`:
```yaml
ibkr:
  host: 127.0.0.1
  port: 7497          # TWS paper: 7497, IB Gateway paper: 4002
  accountId: DUN598126
  sync-timeout: 30
  tickers:
    - SPY
```

### How to Use

#### Build
```bash
./gradlew clean build
```

#### Run Tests
```bash
./gradlew test
```

#### Run Application
```bash
# Start Spring Boot (web server on port 8080)
./gradlew bootRun

# Download candles on startup
./gradlew bootRun --args='--download-on-start=true'
```

#### Test IBKR Connection
With TWS running on port 7497:
```bash
curl -X POST "http://localhost:8080/api/candles/download?ticker=SPY&timeframe=DAY_1"
```

### What Changed from v1.0.0

**Before (v1.0.0):**
- Complex architecture with multiple layers (strategies, analyzers, indicators, etc.)
- Mixed concerns: backtesting, live trading, and data downloading intertwined
- No clear separation of data download vs. strategy execution
- Difficult to test IBKR integration in isolation

**After (v2.0.0):**
- **Single responsibility**: Each service does one thing well
- **Testable**: Unit tests for CSV service, domain models
- **Simple**: No polling, no caching complexity, just direct API calls
- **REST API**: Easy to trigger downloads from anywhere
- **CLI runner**: Download on startup for batch operations

### Next Steps

The old code (strategies, backtester, etc.) is still in the project but excluded from compilation. You can gradually migrate it into this new architecture:

1. ✅ Core IBKR service (DONE)
2. ⏳ Strategy engine migration
3. ⏳ Backtest runner migration  
4. ⏳ Live trading mode
5. ⏳ Telegram integration
6. ⏳ AI optimizer integration

### Known Issue: TWS Competing Session

**Error 10197**: "No market data during competing live session"

This happens when TWS already has an active market data session. Solutions:
1. Use **IB Gateway** instead of TWS (port 4002)
2. Keep TWS open with charts for the tickers you're downloading
3. Close TWS completely and use IB Gateway only

See `IB_GATEWAY_SETUP.md` for detailed instructions.
