[1.3.30] - 2026-04-10 (Spring Boot 4, Advanced CLIs & Strategy Tuning)
Added
Spring Boot 4.0.4 Migration: Actualizado de Spring Boot 3.5.0 a 4.0.4 (última versión estable). Incluye JUnit 6 vía spring-boot-starter-test y Gradle 9.3.0 con Version Catalog (gradle/libs.versions.toml).

Jackson JSON Dependencies: Añadidas dependencias jackson-databind, jackson-yaml, y jackson-datatype-jsr310 para serialización JSON en CLIs y reportes.

Backtest Analyzer CLI (Advanced): Nueva interfaz de línea de comandos especializada para backtesting con análisis automatizado y guardado de resultados en JSON.
  - Comando 1: Ejecutar backtest + análisis completo (guarda en backtest/results/{testName}.json)
  - Comando 2: Comparar dos resultados de backtest lado a lado
  - Comando 3: Analizar resultados históricos guardados
  - Comando 4: Ver historial de rendimiento por estrategia (agregado de todos los tests)
  - Comando 5: Generar recomendaciones automáticas de ajuste de estrategias
  - Activación: java -jar app.jar --backtest-cli.enabled=true

Trading CLI (General Purpose): Interfaz de línea de comandos para operaciones diarias.
  - Comando 1: Ejecutar backtest con análisis
  - Comando 2: Backtest rápido (últimos 30 días)
  - Comando 3: Analizar último backtest
  - Comando 4: Listar tickers de alta calidad
  - Comando 5: Listar tickers por sector
  - Activación: java -jar app.jar --cli.enabled=true

JSON Results Export: Los backtests ahora exportan resultados completos a JSON con:
  - Configuración utilizada (fechas, capital, riesgo, tickers)
  - Métricas de rendimiento (return, win rate, profit factor, Sharpe, drawdown, avg duration)
  - Desglose por estrategia (trades, win rate, PnL, profit factor, max DD)
  - Desglose por ticker
  - Reporte de análisis completo (critical issues, optimization tips)
  - Estructura: backtest/results/{testName}.json

Strategy History Tracking: Sistema que agrega rendimiento de estrategias a través de múltiples backtests para identificar patrones de largo plazo.

Strategy Tuning Recommendations: Motor automatizado de recomendaciones que analiza resultados JSON y sugiere:
  - Widen SL ATR multiplier para estrategias con win rate <40%
  - Increase risk allocation para estrategias con win rate >60% y PF >1.5
  - Review TP/SL ratio para estrategias con profit factor <1.0
  - Reduce position size para estrategias con pérdidas significativas
  - Instrucciones específicas para editar RiskCalculator.java

IntelliJ Run Configurations: Documentación completa para configurar ejecuciones en IDE:
  - Spring Boot App (web server en puerto 9090)
  - Trading CLI (general purpose)
  - Backtest Analyzer CLI (advanced con JSON output)
  - Custom backtest con parámetros específicos

Updated README: Fusionado de REFACTORED_README.md y README.md en un único documento comprehensivo con:
  - Tech Stack actualizado (Java 25, Spring Boot 4.0.4, JUnit 6)
  - Estructura completa del proyecto (árbol de directorios)
  - Documentación de ambos CLIs con ejemplos
  - Troubleshooting section (TWS competing session, position sizing, CSV duplicates)
  - Architecture Evolution (v1.0.0 vs v2.0.0+)
  - Legacy Code Status con progreso de migración

Position Size Cap Enforcement: Corregido bug crítico en BacktestEngine donde el límite era 100 contratos en lugar de 10.
  - Antes: qty = Math.max(1, Math.min(qty, 100))  ❌
  - Ahora: qty = Math.max(1, Math.min(qty, 10))   ✅
  - Impacto: Previene desastres como P6 Reversal PUT de 96 contratos (-$18,972)

Per-Strategy ATR Multipliers in Backtest: BacktestEngine ahora pasa el nombre de estrategia a RiskCalculator.generatePlan() para usar multiplicadores personalizados.

News Filter Service: Servicio que prioriza top 10 tickers por fundamentos (ROIC, EPS Growth, D/E, P/E, Market Cap, Beta).

Strategy Screener Service: Escaneo automático de 356 tickers con relajación incremental de criterios (4 niveles) para encontrar candidatos ideales.

BacktestAnalyzer Service: Servicio REST que analiza trades.csv y genera sugerencias automatizadas (position sizing bugs, low win rate, poor risk/reward, high SL rate, poor time patterns, consecutive losses, ticker underperformance, direction bias).

Fixed
Backtest Position Sizing Bug: BacktestEngine.java usaba límite de 100 contratos en lugar de 10. Corregido a Math.min(qty, 10).

Map.of() Limit Issues: Reemplazados Map.of() con LinkedHashMap.put() en múltiples archivos para evitar límite de 10 pares clave-valor.

AvgDuration Method Name: Corregido de report.avgDuration() a report.avgTradeDurationHours() para coincidir con BacktestReport record.

Redundant README Files: Eliminado REFACTORED_README.md y fusionado todo el contenido en README.md como única fuente de verdad.

Added
Anti-Deadlock Backfill Barrier: Implementado un Timeout de 45 segundos en la carga de datos históricos (waitForBackfillCompletion) y una purga activa de IDs de peticiones fantasma en caso de errores de API, asegurando que el bot siempre arranque incluso si TWS pierde paquetes.

Telegram Bounce-Back UX: Modificado el HttpServer (Puerto 9090) para devolver un payload HTML/JS que ejecuta window.location.href = "tg://";. Esto evita que la pestaña de Chrome se quede abierta inútilmente tras confirmar una orden desde el móvil.

REST API Completa: Implementados endpoints RESTful para integración con dashboards externos:
  - Datos históricos: download, download-all, download-all-tickers, lectura local CSV
  - Escaneo de estrategias: scan masivo (356 tickers) y por ticker individual
  - Ejecución de trades: scan-and-execute, execute manual, account-status, check-options
  - Backtesting: run con parámetros custom, run-all para todos los tickers
  - Monitoreo: Spring Boot Actuator (/actuator/health, /metrics, /prometheus)

Backtesting Engine con CSV Reporting: Motor de backtesting completo con simulación realista:
  - trades.csv: Registro detallado con entry/exit prices, PnL, comisiones, slippage, max drawdown, max runup
  - equity.csv: Curva de equidad timestampada para visualización
  - summary.txt: Resumen con win rate, profit factor, Sharpe ratio, estadísticas por estrategia y ticker
  - SimulatedFillEngine: Slippage y comisiones configurables para realismo
  - Métricas avanzadas: Sharpe Ratio (anualizado), Profit Factor, Max Drawdown %, Avg Duration

Backtest Analyzer Service: Servicio automatizado de análisis de backtests que lee trades.csv y genera recomendaciones:
  - Detección de bugs de position sizing (ej: 96 contratos en una operación)
  - Análisis de rendimiento por estrategia con sugerencias de optimización
  - Detección de patrones de salida (SL vs TP rate)
  - Análisis de patrones temporales (horas con bajo rendimiento)
  - Métricas de riesgo: rachas de pérdidas consecutivas, drawdown promedio
  - Análisis de concentración por ticker
  - Endpoint POST /api/backtest/analyze para obtener reporte automatizado

Position Size Safety Cap: Implementado límite máximo de 10 contratos por operación en AccountManager.calculateQuantity() para prevenir bugs de dimensionamiento como el P6 Reversal PUT (96 contratos, -$18,972).

Per-Strategy ATR Multiplier Tuning: Sistema de ajuste fino de stops y targets basado en análisis de backtest:
  - CALL strategies: SL multipliers 1.2-1.5x (más amplios por mayor volatilidad)
  - PUT strategies: SL multipliers 1.1-1.3x (ligeramente más estrechos)
  - TP multipliers personalizados por estrategia (1.3-1.6x ATR)
  - Configuración en RiskCalculator con HashMaps editables

CSV-Based Ticker Management: Migración de lista de tickers desde application.yml a data/tickers.csv:
  - 356 tickers con datos fundamentales completos (nombre, sector, market cap, P/E, beta, EPS growth, ROIC, debt/equity)
  - Filtros avanzados: por sector, market cap, calidad, crecimiento
  - Endpoint GET /api/backtest/tickers con parámetros de filtro
  - Backwards compatibility: use-csv-tickers flag para alternar entre YAML y CSV
  - Configuración: ibkr.use-csv-tickers: true/false en application.yml

Strategy Performance Tracking: AccountManager ahora trackea rendimiento por estrategia en tiempo real:
  - Win rate, total PnL, max position size por estrategia
  - Método isStrategyUnderperforming() para deshabilitar estrategias con <40% win rate
  - Integración con BacktestAnalyzer para sugerencias automatizadas

Worden Stochastic Indicator: Implementación personalizada de indicador Worden Stochastic (percentil de precio de cierre dentro de lookback period). Integrado en estrategias C5/P5 Efecto Imán como confirmación. Fallback a volumen surge (1.5x promedio) si no disponible.

12 Estrategias de Trading Completamente Documentadas:
  - CALL: C1 Squeeze, C2 Trend, C3 Bounce, C4 Opening, C5 Efecto Imán, C6 Reversal
  - PUT: P1 Squeeze, P2 Trend, P3 Bounce, P4 Opening, P5 Efecto Imán, P6 Reversal
  - Cada estrategia con ventanas horarias específicas y condiciones de entrada multi-timeframe (5min, 15min, 1H, 1D)

Prometheus Metrics Export: Integración con Micrometer para exportación de métricas en tiempo real:
  - ibkr_operation_duration_seconds: Timers por operación IBKR
  - ibkr_errors_total: Contador de errores por código y mensaje
  - csv_operations_total: Operaciones CSV (lectura/escritura)
  - http_requests_total: Requests HTTP por endpoint y método
  - Acceso vía GET /actuator/prometheus

Cloudflare Tunnel Integration: Auto-exposición del bot a internet mediante cloudflared.exe:
  - Lanzamiento automático de tunnel --url localhost:9090
  - Detección automática de URL .trycloudflare.com
  - Registro automático de webhook en Telegram

Configuración Type-Safe: Implementación de IbkrProperties record para configuración tipada con validación. Soporte completo para 356 tickers en universo de trading.

Test Suite Completa: 5 suites de tests implementadas:
  - RiskCalculatorTest: Validación de TP/SL, cap 0.9%, mínimos
  - AccountManagerTest: Balance tracking, cálculo riesgo 2%, límites concurrentes
  - StrategyUnitTest: Condiciones de trigger + casos negativos
  - CandleCsvServiceTest: Lectura/escritura CSV, detección de archivos
  - CandleTest: Validaciones del modelo de dominio

ATR-Based Risk Management: Sistema de gestión de riesgo con ATR (14-período en 1H):
  - TP = 1.5x ATR, SL = 1.0x ATR
  - Cap del 0.9% de distancia máxima para opciones
  - Mínimo absoluto de $0.25 para evitar stops demasiado ajustados
  - Hora de salida por defecto 15:55 ET

Options Exchange-Aware Strike Validation: Validación dinámica de strike prices directamente desde la exchange. El bot consulta la cadena de opciones de IBKR para encontrar strikes válidos, eliminando rechazos por strikes inválidos (0% Error 200).

Smart Exchange Routing: Enrutamiento inteligente de contratos:
  - NYSE para tickers específicos (SQ, NVO, TSM)
  - ISLAND para el resto de acciones
  - Reducción drástica de rechazos Error 200 "No security definition"

Strategy Auto-Refresh con Staleness Detection: Escaneo automático de estrategias con thresholds de antigüedad:
  - 5min timeframe: stale si > 15 minutos
  - 15min timeframe: stale si > 30 minutos
  - 1hour timeframe: stale si > 2 horas
  - 1day timeframe: stale si > 26 horas

Fixed
IBKR Error 135 (Bracket Rejection): Eliminada la condición de precio en la orden Padre (OrderType: MKT) dentro de OrderFactory. Esto soluciona el rechazo instantáneo que provocaba que las órdenes hijas (Take Profit / Stop Loss) fallaran por orfandad.

Bracket Logic (AND to OR): Corregido el ConditionBuilder seteando conjunctionConnection(false). Ahora IBKR entiende correctamente que el bot debe salir del trade si toca el Stop Loss/Take Profit O si se alcanza la hora límite de la Golden Rule (21:55), en lugar de exigir que ambas condiciones ocurran simultáneamente.

[1.3.28] - 2026-04-02
Added
Command Server V2: Añadido soporte para macro green y modo "Quick Trigger".

Account Latch: Barrera de sincronización en el arranque para garantizar capital neto real (NetLiquidation).

Changed
Contract Factory: Mejorado enrutamiento asignando NYSE a tickers conocidos e ISLAND a los demás.

Account Manager: Corregido cálculo Qty integrando multiplicador de opciones (x100) vs USD/Riesgo.

Data Infrastructure: Sistema completo de persistencia de velas en CSV (data/{TICKER}_{TIMEFRAME}.csv):
  - CandleCsvService: Guardado/carga de velas con parsing de formatos IBKR (diario/intraday)
  - CandleDownloadService: Descarga individual, por timeframe, o masiva de 356 tickers con tracking de errores
  - IbkrCallbackHandler: Composición-based callback handler (no herencia) con filtros de error 366 espurios

Strategy Scanner Service: Escaneo automático de 356 tickers contra 12 estrategias con auto-refresh y staleness detection.

Order Execution Service: Ejecución completa de opciones con resolución de cadena:
  - Búsqueda de expiración más cercana >= 48 horas
  - Selección de mejor strike desde strikes validados por exchange
  - Colocación de bracket orders con tracking de estado vía EWrapper callbacks

Anti-Deadlock Backfill Barrier: Implementado un Timeout de 45 segundos en la carga de datos históricos (waitForBackfillCompletion) y una purga activa de IDs de peticiones fantasma en caso de errores de API, asegurando que el bot siempre arranque incluso si TWS pierde paquetes.

Telegram Bounce-Back UX: Modificado el HttpServer (Puerto 9090) para devolver un payload HTML/JS que ejecuta window.location.href = "tg://";. Esto evita que la pestaña de Chrome se quede abierta inútilmente tras confirmar una orden desde el móvil.