# 🚀 Options Quant Engine v1.3.30

### "The Vanilla Quant" - Java 25+ High-Performance Trading Bot

Un motor de trading algorítmico híbrido diseñado para Interactive Brokers (TWS/Gateway). Diseñado para una ejecución ultrarrápida, gestión de riesgo estricta, análisis automatizado de backtests y filtrado inteligente de tickers.

---

## 🏗️ Project Architecture

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

## 🌟 Características Avanzadas (Core Architecture)

- **Delta Fetching Híbrido & Anti-Deadlock:** El bot prioriza la lectura de datos históricos desde archivos CSV locales. Calcula dinámicamente el tiempo transcurrido desde la última vela y solicita a IBKR solo el Delta faltante. Incluye un sistema **Anti-Deadlock con Timeout de 45 segundos** que evita congelamientos si la API de IBKR pierde peticiones.
- **Hard Caps Anti-Error 162:** Limitación inteligente de peticiones de datos históricos. Previene baneos automáticos de IBKR limitando descargas máximas según el marco temporal.
- **Ticker Sanitization & Smart Routing:** Limpieza extrema de símbolos (`replaceAll("[^a-zA-Z]", "")`) y enrutamiento dinámico de `primaryExch` (NASDAQ vs NYSE) para garantizar un rechazo del 0% por "Error 200: No security definition".
- **Opciones "Exchange-Aware":** Selección dinámica del *Strike Price* validado directamente por la exchange. El bot no adivina el strike, lo machea con las opciones reales disponibles.
- **Sincronización de Equidad en Tiempo Real:** El `AccountManager` detiene la ejecución de simulaciones hasta que el balance real de la cuenta (NetLiquidation) se sincronice, calculando con precisión matemática la cantidad de contratos permitidos según el riesgo por trade (Options Multiplier Aware).
- **Position Size Safety Cap:** Límite máximo de **10 contratos por trade** para prevenir bugs de dimensionamiento (como el P6 Reversal PUT de 96 contratos que perdió -$18,972).
- **Per-Strategy ATR Multiplier Tuning:** Stops y targets personalizados por estrategia basados en análisis de backtest (CALL: 1.2-1.5x SL, PUT: 1.1-1.3x SL).
- **Advanced Bracket Orders (OCA):** Enrutamiento institucional de órdenes. La entrada se lanza directamente a mercado (MKT/LMT) sin bloqueos condicionales, mientras que el riesgo se delega 100% a los servidores de IBKR usando algoritmos "One-Cancels-All" con lógica condicional híbrida (Precio OR Tiempo "Golden Rule").
- **Backtest Analyzer Automatizado:** Servicio que lee `trades.csv` y genera recomendaciones de optimización (win rate, riesgo/recompensa, patrones horarios, concentración).
- **CSV-Based Ticker Management:** 356 tickers con datos fundamentales completos (P/E, ROIC, Beta, EPS Growth) en `data/tickers.csv`.
- **News Filter & Priority Tickers:** Top 10 tickers priorizados por fundamentos para escaneo enfocado.
- **Strategy Screener:** Escaneo automático de los 356 tickers con relajación incremental de criterios si no se encuentran candidatos.

---

## 📟 Consola de Control Remoto & Telegram

El bot incluye dos vías de control remoto sin necesidad de detener el motor:

### 1. Servidor de Comandos Local (Puerto `7070`)
Ideal para testing desde la terminal. Cómo conectar (Git Bash / Linux / macOS):
```bash
curl telnet://localhost:7070
```

| Comando | Descripción |
|---------|-------------|
| `skip` | Hace un bypass del análisis Pre-Market de la IA y pone el bot en estado "LIVE" instantáneamente |
| `macro green` | Fuerza los filtros macroeconómicos (MarketRadar) a estado FAVORABLE. Ideal para testing |
| `trigger <TICKER> <PRICE>` | [Quick Test] Dispara automáticamente la C1SqueezeCallStrategy para el ticker indicado |
| `trigger <TICK> <STRAT> <PRICE>` | Ejecuta manualmente una estrategia específica saltándose las condiciones de tiempo |
| `exit` | Cierra la conexión de la consola remota |

### 2. CLI Interactivo (Modo Línea de Comandos)

#### A. Trading CLI (General Purpose)
Para escaneo de estrategias y trading en vivo:
```bash
java -jar target/options-quant.jar --cli.enabled=true
```

Menú interactivo:
- **1:** Ejecutar backtest completo con análisis
- **2:** Backtest rápido (últimos 30 días)
- **3:** Analizar resultados del último backtest
- **4:** Listar tickers de alta calidad
- **5:** Listar tickers por sector
- **6:** Salir

#### B. Backtest Analyzer CLI (Advanced)
Para ejecutar backtests con análisis detallado y guardar resultados en JSON para ajuste de estrategias:
```bash
java -jar target/options-quant.jar --backtest-cli.enabled=true
```

Menú interactivo:
- **1:** Ejecutar backtest + análisis (guardar en JSON)
- **2:** Comparar dos resultados de backtest
- **3:** Analizar resultados existentes
- **4:** Ver historial de rendimiento por estrategia
- **5:** Generar recomendaciones de ajuste de estrategias
- **6:** Salir

**Output Files:**
- `backtest/results/{testName}.json` - Resultado completo con análisis
- `backtest/trades.csv` - Registro detallado de operaciones
- `backtest/equity.csv` - Curva de equidad
- `backtest/summary.txt` - Resumen ejecutivo

**Ejemplo de uso:**
```bash
# Ejecutar y guardar resultado
java -jar target/options-quant.jar --backtest-cli.enabled=true
# Enter: 1
# Test name: test_april_sweep

# Comparar dos tests
# Enter: 2
# First test: test_april_sweep
# Second test: test_march_baseline

# Generar recomendaciones
# Enter: 5
```

### 3. Telegram Integration (Legacy - Pendiente de Migrar)
- Inline keyboard con callback data para ejecución one-tap desde móvil
- Auto-webhook registration vía Cloudflare tunnel
- Bounce-back UX: HTTP server devuelve HTML/JS que ejecuta `window.location.href = "tg://"` para cerrar pestañas Chrome

---

## 🤖 MarketScanner (Automatic Trading)

El **MarketScanner** se ejecuta automáticamente en segundo plano cuando inicias la aplicación Spring Boot.

### Horario de Escaneo (España, CEST = UTC+2)

| Período | Hora España | Actividad |
|---------|-------------|-----------|
| **Pre-market** | 10:00 - 15:30 | Escaneo de 50 tickers prioritarios |
| **Market hours** | 15:30 - 22:00 | Escaneo de los 512 tickers |
| **After hours** | 22:00 - 10:00 | Escáner pausado |

### Sincronización con Velas de 15 Minutos

El escáner se ejecuta **2 segundos después de que cierra cada vela de 15 minutos**:
- 10:00:02, 10:15:02, 10:30:02, 10:45:02, ...
- 15:30:02, 15:45:02, 16:00:02, ...
- 21:30:02, 21:45:02 (último escaneo)

### Cómo Funciona

1. **Carga de datos automática** - Si los CSV están desactualizados, descarga datos frescos de IBKR
2. **Escanea las 12 estrategias** - C1-C6 (CALL) + P1-P6 (PUT) contra cada ticker
3. **Detecta señales** - Loggea cada señal encontrada
4. **Ejecución automática** - Si `auto-execute: true` en application.yml, coloca la orden

### Logs del MarketScanner

Verás logs como estos cada 15 minutos durante el horario de mercado:

```
🔍 === MARKET SCAN === 15:30:02 (Spain) ===
🎯 SIGNAL: AAPL CALL @ $175.50 - C1SqueezeCall at 2026-04-10T15:30:02
  TP: $178.20 | SL: $173.80 | Entry: $175.50
  🚀 AUTO-EXECUTING...

📈 === SCAN SUMMARY ===
  Time: 15:30:02 (Spain)
  Tickers scanned: 512
  Signals found: 3
  Scan duration: 4523ms
======================
```

### Configuración

```yaml
ibkr:
  auto-execute: true   # Cambiar a false para solo escanear sin ejecutar órdenes
```

**Importante:**
- El MarketScanner **reemplaza al Trading CLI** - ya no necesitas interactuar manualmente
- Solo inicia la aplicación Spring Boot y escanea automáticamente
- Para deshabilitar la ejecución automática, pon `auto-execute: false`
- Los escaneos solo ocurren de lunes a viernes

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
🔥 Scanning 15 HOT tickers first: [SPY, QQQ, AAPL, MSFT, NVDA, TSLA, AMZN, META, GOOGL, AMD, JPM, V, BRK.B, XOM, JNJ]
✅ SPY analyzed - 1 signal(s) found (245ms)
   SPY CALL @ $665.50 - C1SqueezeCall (TP: $670.00, SL: $660.00)
✅ NVDA analyzed - 2 signal(s) found (312ms)
   NVDA PUT @ $890.00 - P2TrendPut (TP: $880.00, SL: $900.00)
   NVDA PUT @ $890.00 - P6ReversalPut (TP: $875.00, SL: $905.00)
✅ Hot tickers scan complete - 3 signals found
📊 Scanning 497 remaining tickers...
... (continues with remaining tickers)
```

**To Customize:**
Edit the `hot-tickers` list in `application.yml` with your preferred tickers. The system will scan those first on every cycle.

---

## 📡 REST API Completa (Puerto `9090`)

### Datos Históricos
| Método | Endpoint | Descripción |
|--------|----------|-------------|
| `POST` | `/api/candles/download` | Descarga velas de un ticker/timeframe específico |
| `POST` | `/api/candles/download-all` | Descarga todos los timeframes de un ticker |
| `POST` | `/api/candles/download-all-tickers` | Descarga masiva de los 356 tickers configurados |
| `GET` | `/api/candles/local/{ticker}/{timeframe}` | Lee datos CSV locales |

### Escaneo de Estrategias
| Método | Endpoint | Descripción |
|--------|----------|-------------|
| `POST` | `/api/strategies/scan` | Escanea los 356 tickers contra las 12 estrategias |
| `POST` | `/api/strategies/scan/{ticker}` | Escanea un ticker específico (con `includeTradePlans` param) |

### Ejecución de Trades
| Método | Endpoint | Descripción |
|--------|----------|-------------|
| `POST` | `/api/trading/scan-and-execute` | Escaneo + ejecución automática con gestión de riesgo |
| `POST` | `/api/trading/execute` | Ejecuta un trade manual con bracket orders |
| `GET` | `/api/trading/check-options` | Verifica cadena de opciones disponible |
| `GET` | `/api/trading/account-status` | Estado de la cuenta IBKR en tiempo real |

### Backtesting & Analysis
| Método | Endpoint | Descripción |
|--------|----------|-------------|
| `POST` | `/api/backtest/run?from=YYYY-MM-DD&to=YYYY-MM-DD&tickers=SPY,AAPL` | Ejecuta backtest con parámetros custom |
| `POST` | `/api/backtest/run-all?from=YYYY-MM-DD&to=YYYY-MM-DD` | Backtest completo de todos los tickers |
| `POST` | `/api/backtest/analyze` | **Analiza trades.csv y genera sugerencias de mejora** |
| `GET` | `/api/backtest/tickers?sector=Technology&highQuality=true` | Query ticker database con filtros |
| `GET` | `/api/backtest/priority-tickers?refresh=true` | Top 10 tickers por fundamentos |
| `GET` | `/api/backtest/ticker-score/{ticker}` | Score detallado de un ticker |
| `POST` | `/api/backtest/screen?count=10` | Strategy screener con criteria relaxation |

### Monitoreo (Actuator)
| Método | Endpoint | Descripción |
|--------|----------|-------------|
| `GET` | `/actuator/health` | Estado de salud del bot |
| `GET` | `/actuator/metrics` | Métricas de rendimiento |
| `GET` | `/actuator/prometheus` | Exportación de métricas en formato Prometheus |

#### Ejemplos de Uso

**1. Ejecutar backtest y analizar resultados:**
```bash
# Ejecutar backtest
curl -X POST "http://localhost:9090/api/backtest/run?from=2026-01-01&to=2026-04-10&tickers=SPY,AAPL" \
  -H "Content-Type: application/json"

# Analizar resultados
curl -X POST http://localhost:9090/api/backtest/analyze
```

**2. Obtener tickers de alta calidad:**
```bash
curl "http://localhost:9090/api/backtest/tickers?highQuality=true"
```

**3. Obtener top 10 tickers por fundamentos:**
```bash
curl "http://localhost:9090/api/backtest/priority-tickers?refresh=true"
```

---

## 📊 Backtesting & Analytics

### Output de Backtest
Cada ejecución genera 3 archivos en el directorio `backtest/`:

| Archivo | Contenido |
|---------|-----------|
| `trades.csv` | Registro detallado: entry/exit prices, PnL, comisiones, slippage, max drawdown, max runup |
| `equity.csv` | Curva de equidad timestampada |
| `summary.txt` | Resumen ejecutivo con Sharpe ratio, profit factor, win rate |

### Backtest Analyzer Service
Lee automáticamente `trades.csv` y detecta:
- 🚨 **Position sizing bugs** (ej: 96 contratos en una operación)
- 📉 **Low win rate strategies** (<40% en 20+ trades)
- ⚖️ **Poor risk/reward ratios** (avg win < avg loss)
- 🛑 **High SL rates** (>60% de trades hitting stops)
- 🕐 **Poor time patterns** (horas con bajo rendimiento)
- ⚠️ **Consecutive losses** (rachas de 5+ pérdidas)
- 📊 **Ticker underperformance** (tickers con <35% win rate en 5+ trades)
- 🔄 **Direction bias** (CALL vs PUT win rate disparity >20%)

### Métricas Calculadas
- **Profit Factor:** Ganancias totales / Pérdidas totales (objetivo > 1.5)
- **Sharpe Ratio:** Rendimiento ajustado al riesgo (anualizado, base 252 días)
- **Max Drawdown:** Pérdida máxima desde pico histórico (absoluto y %)
- **Win Rate:** Porcentaje de trades ganadores
- **Avg Duration:** Duración promedio de trades en horas

---

## 🎯 Estrategias Implementadas (12 Total)

### Estrategias CALL (Alcistas)

| Estrategia | Concepto | Ventana Horaria | Condición Clave |
|------------|----------|-----------------|-----------------|
| **C1 Squeeze Call** | Breakout de compresión de volatilidad | Cualquier hora | 4 SMAs (20/40/100/200) dentro de 4% + quiebre de máximo 10 días |
| **C2 Trend Call** | Continuación de tendencia en pullback | Después 10 AM | Pullback a SMA20 + vela alcista de confirmación |
| **C3 Bounce Call** | Rebote en soporte SMA20 (1H) | Después 10 AM | Tendencia diaria alcista + mínimo toca SMA20 (1H) + confirmación 15m |
| **C4 Opening Call** | Reversal de gap-down en apertura | 9:30-9:35 AM | Día lateral previo + gap -1.5% a -6% + primera vela 5min verde |
| **C5 Efecto Imán** | Gap extremo + breakout de Bollinger | 9:45-9:55 AM | Worden Stochastic cruza arriba de 20 |
| **C6 Reversal Call** | Reversal de tendencia bear-to-bull | Después 10 AM | Precio bajo SMA20 por 3+ horas, cruza arriba con volumen |

### Estrategias PUT (Bajistas)

| Estrategia | Concepto | Ventana Horaria | Condición Clave |
|------------|----------|-----------------|-----------------|
| **P1 Squeeze Put** | Breakout lateral a la baja | Cualquier hora | Compresión de volatilidad + ruptura descendente |
| **P2 Trend Put** | Rechazo en pullback (tendencia bajista) | Después 10 AM | Pullback a SMA20 + vela bajista de confirmación |
| **P3 Bounce Put** | Rechazo en resistencia SMA20 (1H) | Después 10 AM | Tendencia diaria bajista + máximo toca SMA20 (1H) |
| **P4 Opening Put** | Trampa de gap-up en apertura | 9:30-9:35 AM | Gap-up excesivo + reversión inmediata |
| **P5 Efecto Imán** | Gap extremo arriba + breakout de Bollinger | 9:45-9:55 AM | Worden Stochastic cruza abajo de 80 |
| **P6 Reversal Put** | Reversal de tendencia bull-to-bear | Después 10 AM | Precio sobre SMA20 por 3+ horas, cruza abajo con volumen |

### Indicador Personalizado: Worden Stochastic
- **Implementación:** `WordenStochasticIndicator.java`
- **Cálculo:** Percentil del precio de cierre dentro del período de lookback
- **Uso:** Confirmación en estrategias C5/P5 (Efecto Imán)
- **Fallback:** Si no está disponible, usa surgimiento de volumen (1.5x promedio)

---

## 📈 News Filter & Priority Tickers

El sistema filtra y prioriza los 356 tickers para enfocarse en los 10 mejores basados en:

### Criterios de Scoring (0-100 puntos)
- **ROIC (0-15 pts):** >15% = 15pts, >10% = 10pts, >5% = 5pts
- **EPS Growth (0-15 pts):** >15% = 15pts, >10% = 10pts, >5% = 5pts
- **Debt/Equity (0-20 pts):** <0.3 = 20pts, <0.5 = 15pts, <1.0 = 10pts
- **P/E Ratio (0-20 pts):** 0-15 = 20pts, <25 = 15pts, <35 = 10pts
- **Market Cap (0-20 pts):** >$50B = 20pts, >$20B = 15pts, >$10B = 10pts
- **Beta (0-10 pts):** 0.8-1.3 = 10pts, 0.6-1.5 = 7pts, otro = 3pts

### Uso
```bash
# Obtener top 10
GET /api/backtest/priority-tickers

# Refrescar lista
GET /api/backtest/priority-tickers?refresh=true

# Ver score detallado
GET /api/backtest/ticker-score/AAPL
```

---

## 🔍 Strategy Screener

Escanea los 356 tickers y encuentra candidatos ideales para las 12 estrategias.

### Criteria Relaxment Automático
Si no se encuentran candidatos con criterios estrictos, el sistema relaja incrementalmente:

| Nivel | Min Volume Change | Min Price Change | Min Market Cap | Min Volatility |
|-------|------------------|------------------|----------------|----------------|
| 1 (Strict) | 2% | 1.5% | $50M | 2% |
| 2 (Moderate) | 1.5% | 1.2% | $20M | 1.5% |
| 3 (Relaxed) | 1% | 1.0% | $10M | 1% |
| 4 (Very Relaxed) | 0.5% | 0.8% | $5M | 0.5% |

### Uso
```bash
POST /api/backtest/screen?count=10
```

---

## ⚙️ Configuración

### Parámetros Principales (`application.yml`)

```yaml
ibkr:
  host: 127.0.0.1              # TWS/IB Gateway host
  port: 7497                   # 7497 (paper), 7496 (live), 4002 (Gateway paper)
  sync-timeout: 30             # Timeout de conexión (segundos)
  auto-execute: true           # DEBE ser true para órdenes reales
  account-id: DUN598126        # ID de cuenta IBKR
  default-qty: 10              # Contratos por defecto
  risk-per-trade-pct: 0.02     # Riesgo por trade (2% del balance)
  use-csv-tickers: true        # Usar tickers.csv en vez de lista YAML
  tickers: []                  # Legacy - ver data/tickers.csv
```

### Gestión de Riesgo Automática

**Fórmula de Posición:**
```
qty = (balance * riskPct) / (|entry - SL| * 100)
qty = Math.max(1, Math.min(qty, 10))  # CAPADO A 10 CONTRATOS MAX
```

- **Balance en tiempo real:** Streaming de NetLiquidation desde IBKR
- **ATR-based Stops:** TP = 1.5x ATR, SL = 1.0x ATR (14-período en 1H)
- **Cap del 0.9%:** Distancia máxima TP/SL limitada a 0.9% del precio para opciones
- **Mínimo $0.25:** Distancia mínima absoluta para evitar stops demasiado ajustados
- **Hora de salida:** Por defecto 15:55 ET (antes del cierre)

### Per-Strategy ATR Multipliers

| Estrategia | SL Multiplier | TP Multiplier | Razón |
|------------|--------------|--------------|-------|
| c1squeezecall | 1.3x | 1.6x | Squeeze necesita más room |
| c2trendcall | 1.2x | 1.5x | Trend pullbacks pueden ser profundos |
| c3bouncecall | 1.4x | 1.4x | Bounce necesita stops amplios |
| c4openingcall | 1.5x | 1.3x | Volatilidad de apertura requiere stops anchos |
| c5continuationcall | 1.4x | 1.4x | Gap reversals son volátiles |
| c6reversalcall | 1.3x | 1.5x | Reversals necesitan room de confirmación |
| p1squeezeput | 1.2x | 1.5x | Squeeze puts ligeramente más confiables |
| p2trendput | 1.1x | 1.5x | Trend puts funcionan bien con stops estándar |
| p3bounceput | 1.2x | 1.4x | Bounce puts similar a calls |
| p4openingput | 1.3x | 1.4x | Opening puts volátiles |
| p5continuationput | 1.2x | 1.5x | Gap continuation puts |
| p6reversalput | 1.3x | 1.4x | Reversal puts necesitan room |

**Para ajustar:** Editar `src/main/java/com/fgiaquinta/optionsquant/strategy/utils/RiskCalculator.java` HashMaps.

---

## 📡 Monitoreo & Métricas en Tiempo Real

### Prometheus Metrics Export
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
```

| Métrica | Tipo | Descripción |
|---------|------|-------------|
| `ibkr_operation_duration_seconds` | Timer | Tiempo de cada operación IBKR |
| `ibkr_errors_total` | Counter | Contador de errores IBKR |
| `csv_operations_total` | Counter | Operaciones CSV (lectura/escritura) |
| `http_requests_total` | Counter | Requests HTTP por endpoint |

**Acceso:** `GET http://localhost:9090/actuator/prometheus`

### Cloudflare Tunnel Integration
- **Auto-exposición:** El bot lanza `cloudflared.exe tunnel --url localhost:9090`
- **Webhook automático:** Detecta URL `.trycloudflare.com` y registra webhook en Telegram
- **Bounce-Back UX:** Tras confirmar orden desde móvil, cierra pestaña Chrome

---

## 🧪 Testing

El proyecto incluye 5 suites de tests:

| Test | Cobertura |
|------|-----------|
| `RiskCalculatorTest` | Generación de TP/SL, cap del 0.9%, mínimos |
| `AccountManagerTest` | Balance tracking, cálculo de riesgo 2%, límites concurrentes, position cap |
| `StrategyUnitTest` | Condiciones de trigger de estrategias + casos negativos |
| `CandleCsvServiceTest` | Lectura/escritura CSV, detección de archivos |
| `CandleTest` | Validaciones del modelo de dominio |

**Ejecutar tests:**
```bash
./gradlew test
```

---

## 🚨 Funcionalidades en Legacy (No Migradas aún)

El paquete `com.fgiaquinta.optionsquant.legacy` contiene características funcionales pero excluidas de compilación:

- **AI Strategy Optimizer:** Gemini analiza backtests y recomienda TP/SL óptimos por estrategia
- **AI News Interpreter:** Análisis de sentimiento macroeconómico (BULLISH/BEARISH/NEUTRAL)
- **Staircase Filter:** Bloquea re-entradas perdedoras si el precio no ha mejorado
- **90-Minute Time Stop:** Fuerza salida tras 90 minutos en posición
- **Trailing Stop Dinámico:** Usa SMA20 (5m/15m) cuando profit > 0.35%
- **Analyzers:** Channel, Gap, Trend, Volatility, Worden

---

## 📖 Flujo de Trading

Consulta [`TRADING_FLOW.md`](TRADING_FLOW.md) para un diagrama detallado del ciclo de vida completo:
1. Descarga de datos históricos → 2. Escaneo de estrategias → 3. Filtro macroeconómico → 4. Cálculo de riesgo → 5. Ejecución de bracket orders → 6. Monitoreo de posiciones → 7. Reporte de resultados → 8. **Análisis automatizado** (nuevo)

---

## 📝 Changelog

Consulta [`CHANGELOG.md`](CHANGELOG.md) para el historial completo de versiones y cambios.

### Últimos Cambios (v1.3.30)
- ✅ **Position Size Cap:** Máximo 10 contratos por trade (previene bugs como 96 contratos)
- ✅ **Backtest Analyzer:** Análisis automatizado de trades.csv con sugerencias
- ✅ **Per-Strategy ATR Tuning:** Stops/targets personalizados por estrategia
- ✅ **CSV-Based Tickers:** 356 tickers con fundamentos en data/tickers.csv
- ✅ **CLI Tool:** Interfaz de línea de comandos para backtests y análisis
- ✅ **News Filter:** Top 10 tickers por fundamentos
- ✅ **Strategy Screener:** Escaneo con criteria relaxation automático

---

## 💻 IntelliJ Run Configuration Guide

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

**⚠️ IMPORTANT:** If the web server (MarketScanner) is already running on port 9090, you must use a different port or disable the web server:

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

## 🔄 Complete Trading Flow

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

---

## 📊 Backtest Analysis & Strategy Tuning

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

---

## 🎯 The 12 Strategies

### Call Strategies (Bullish)

| Strategy | Concept | Time Window | Key Indicators |
|----------|---------|-------------|----------------|
| **C1 Squeeze Call** | Volatility compression → upside breakout | Anytime | 4 SMAs (20/40/100/200) within 4%, 10-day high break, 15m Bollinger ride |
| **C2 Trend Call** | Pullback continuation in uptrend | After 10 AM | Daily uptrend, pullback to SMA20, bullish candle, volume, **BB context** |
| **C3 Bounce Call** | Bounce off 1H SMA20 support | After 10 AM | Daily uptrend, 1H low touches SMA20, 15m confirmation, **BB trend** |
| **C4 Opening Call** | Gap-down reversal at open | 9:30-9:35 AM | Lateral prev day, gap -1.5% to -6%, first 5m green, **BB bands** |
| **C5 Efecto Imán** | Magnet effect after extreme gap down | 9:45-9:55 AM | Bearish trend, 3% below SMA20, 15m below BB, **Worden Stochastic cross** |
| **C6 Reversal Call** | Bear-to-bull trend reversal | After 10 AM | Was below SMA20 for 3h+, crosses above with volume |

### Put Strategies (Bearish)

| Strategy | Concept | Time Window | Key Indicators |
|----------|---------|-------------|----------------|
| **P1 Squeeze Put** | Volatility compression → downside breakdown | Anytime | 4 SMAs within 4%, 10-day low break, 15m Bollinger ride down |
| **P2 Trend Put** | Pullback rejection in downtrend | After 10 AM | Daily downtrend, rally to SMA20 rejected, bearish candle, **BB context** |
| **P3 Bounce Put** | Rejection at 1H SMA20 resistance | After 10 AM | Daily downtrend, 1H high touches SMA20, 15m confirmation, **BB trend** |
| **P4 Opening Put** | Gap-up trap at open | 9:30-9:35 AM | Lateral prev day, gap +1.5% to +6%, first 5m red, **BB bands** |
| **P5 Efecto Imán** | Magnet effect after extreme gap up | 9:45-9:55 AM | Bullish trend, 3% above SMA20, 15m above BB, **Worden Stochastic cross** |
| **P6 Reversal Put** | Bull-to-bear trend reversal | After 10 AM | Was above SMA20 for 3h+, crosses below with volume |

---

## 💰 Position Sizing (2% Risk Rule with 10-Contract Cap)

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

---

## 📦 Bracket Order Structure

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

---

## ✅ Safety Checklist

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

## 🧪 Tests

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

## 📁 Package Structure

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

### 1. Ejecutar Backtest con Análisis
```bash
# Opción A: CLI interactivo
java -jar target/options-quant.jar --cli.enabled=true
# Seleccionar opción 1

# Opción B: REST API
curl -X POST "http://localhost:9090/api/backtest/run?from=2026-01-01&to=2026-04-10"
curl -X POST http://localhost:9090/api/backtest/analyze
```

### 2. Obtener Tickers Prioritarios
```bash
curl "http://localhost:9090/api/backtest/priority-tickers?refresh=true"
```

### 3. Ejecutar Strategy Screener
```bash
curl -X POST "http://localhost:9090/api/backtest/screen?count=10"
```

### 4. Analizar Último Backtest
```bash
curl -X POST http://localhost:9090/api/backtest/analyze
```

---

## ⚠️ Known Issues & Troubleshooting

### TWS Competing Session Error

**Error 10197**: "No market data during competing live session"

This happens when TWS already has an active market data session. Solutions:
1. Use **IB Gateway** instead of TWS (port 4002)
2. Keep TWS open with charts for the tickers you're downloading
3. Close TWS completely and use IB Gateway only

### Position Sizing Safety

If you see unusually large position sizes (>10 contracts), the position cap should prevent execution. Check logs for:
```
🚨 Position size cap triggered: 96 -> 10 contracts (strategy: p6reversalput)
```

### Backtest CSV Duplicates

If `backtest/trades.csv` shows duplicate trades, delete the file before running a new backtest:
```bash
rm backtest/trades.csv
```

---

## 📝 Architecture Evolution

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

1. ✅ Core IBKR service (DONE)
2. ✅ Strategy engine (DONE - 12 strategies migrated)
3. ✅ Backtest runner (DONE with CSV reporting)
4. ✅ Live trading mode (DONE with bracket orders)
5. ⏳ Telegram integration (Legacy - pending migration)
6. ⏳ AI optimizer integration (Legacy - pending migration)

---

## 📝 Changelog

Consulta [`CHANGELOG.md`](CHANGELOG.md) para el historial completo de versiones y cambios.
