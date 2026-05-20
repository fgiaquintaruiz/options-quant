# Roadmap — Options Quant Engine

> Plan estratégico vivo. Se actualiza al final de cada sesión.
> Última actualización: 2026-05-20

## Estado actual

**Fase**: Paper trading activo (arrancó 2026-05-18)
**Universo HOT**: NVDA, AMD, AMDL, TSLA, META, AVGO, COIN, MSTR, AMZN, SPY
**Universo táctico**: AAPL, URA, MU, SMH, OXY, GLD
**Estrategias activas**: 12 estrategias activadas para paper trading (2026-05-19)
**Capital paper**: $968 USD — cuenta DUN598216 (2026-05-18)
**Instrumento**: Opciones (CALL/PUT)
**Stack**: SQLite + Spring Boot 4.0.5 + Java 25 + React frontend + IBKR TWS

## Hitos cumplidos

### Infrastructure
- ✅ Bug crítico paginación Polygon arreglado (commit f8342b5)
- ✅ Stooq DAY_1 migration + DROP table + VACUUM (24M filas archivadas)
- ✅ Tabla materializada ticker_stats (1000× speedup en filter queries)
- ✅ Flag --complete-tickers implementado con TDD
- ✅ Wrapper script scripts/run-backtest.ps1 (DevTools off + paper-friendly)
- ✅ Bug persistencia HikariCP/DevTools diagnosticado + workaround

### Analysis
- ✅ Primer análisis de overfitting (62 tickers, 3695 trades, run 2026-05-17_00-10)
- ✅ Top 3 estrategias identificadas: p6 reversal, p1 squeeze, c6 reversal
- ✅ Estrategias a revisar/eliminar identificadas

## Próximas tareas — ordenadas por prioridad

### P0 — Mañana lunes (2026-05-18) ANTES de 15:30 mercado

**Fase 1 — Investigación pre-paper (15-30 min)**
- ✅ Verificar modelo de pricing del backtest (¿direccional o Black-Scholes?)
- ✅ Identificar cómo el live mode manda órdenes a IBKR
- ✅ Confirmar config cuenta paper IBKR (puerto 7497, capital simulado)
- ✅ Decisión: ¿activar paper hoy o postergar?

**Fase 2 — Filtro de estrategias por DB (~2.5h)**
- ✅ Bloque 1: tabla strategy_config + StrategyConfigService + TDD (StrategyConfigController REST API GET /api/strategy-config + PATCH /api/strategy-config/{name}, 12 tests, commit 6aa7a0e)
- ✅ Bloque 2: integración con BacktestBatchRunner + Live engine
- ✅ SQL manual para activar p6 reversal, p1 squeeze, c6 reversal en live (verificado GET /api/strategy-config: c6_reversal, p1_squeeze, p6_reversal enabledLive=true, PID 42108)

**Fase 3 — Smoke test (15 min antes de mercado)**
- ✅ Arrancar live mode
- ✅ Verificar logs: solo las 3 estrategias activas
- ✅ Verificar conexión TWS paper (puerto 7497)
- ✅ Sin trades reales todavía

**15:30 → primer paper trade**

### P1 — Esta semana (post primer paper)

- ✅ UI React para filtro de estrategias — StrategiesPage.jsx + /strategies route + badge en ScanEngineControls. 890 tests, 95.7% coverage. Commit 313eb64.
- ✅ scripts/run-live.ps1 — script PowerShell de arranque: UTF-8 logging, port 9090, log con timestamp, sin DevTools. Creado 2026-05-18.
- ✅ p2 trend activado en live (2026-05-19, SQL directo)
- ✅ 11 estrategias activadas para paper trading (2026-05-19) — c3/c4/p4/p5 activadas para recolección de datos — pendiente análisis post paper trading
- ✅ c1 squeeze, c2 trend, p3 bounce, p4 opening, c3 bounce, p5 continuation, c4 opening, c5 continuation activadas en live (2026-05-19)
- ✅ GLD/SMH agregadas al universo
- ✅ ticker-memory.json rolling backup
- ✅ Play button fix replay UI
- ✅ Market hours gate en replay (virtual clock)
- ✅ Signal timestamp fix (virtual clock during replay)
- ✅ Virtual clock UI + progress bar
- ✅ Playwright E2E para replay controls
- ✅ Banner replay state fix (useReplayStatus direct)
- ✅ Replay auto-disable macro filter during replay
- ✅ Replay TradePlan en JSONL + on-complete summary
- ✅ Replay auto-stop en virtual clock = now
- ✅ ReplayScheduler @Autowired fix

### P1 — Esta semana (nuevas, 2026-05-19 evening)

- [ ] **Investigar y decidir SignalQualityFilter** — backtest no lo aplica, replay/live sí; puede explicar 0 señales en replay. Decidir: agregar al backtest o quitar del replay.
- [ ] **Investigar c4/p4 opening** — BacktestEngine fuerza entry window 9:45 ET, c4/p4 requieren 9:30 ET; posiblemente nunca se evalúan en backtest. Verificar y corregir.
- [ ] **Implementar Condition logging en 12 estrategias** — POC completado en P1Squeeze; extender a todas. Permite debug de qué condición falla por ticker.
- [ ] **Visualización profunda por señal** — 2 charts (daily+1h, 1h+15m) con SMA/BB/TP/SL. Diseño pendiente.

### P1 — Pendientes operativos (2026-05-20)

- ⏸️ **Backtest c4/p4 opening con entry window 9:30-9:36 ET** — script listo en `scripts/run-backtest-c4-p4.ps1`, Fabio ejecuta manualmente
- [ ] **Decidir sobre SignalQualityFilter** — no proviene del libro de the course author, sin tests, estrategias ya tienen filtros propios; opciones: (a) remover de live/replay, (b) agregar al backtest también, (c) mover inside cada estrategia
- [ ] **Extender Condition logging a las 11 estrategias restantes** — POC listo en P1/C1 Squeeze
- [ ] **Implementar MultiTimeframeChart 1h+15m side-by-side**

### P1 CRÍTICO — Pricing de opciones (2026-05-20)

- [ ] **Validar gap de pricing** — backtest usa `(exitPrice - entryPrice) * qty * 100` (delta=1 implícito) vs opciones reales; sobreestima ganancias/pérdidas, ignora theta decay y spread bid/ask [`BacktestEngine.java:1114-1117`, `RiskCalculator.java:15-16`]
- [ ] **Validar divergencia estructural** — backtest mueve precio del subyacente, live ejecuta contratos reales con strike/expiry via IBKR [`OrderExecutionService.java:296-340`]; los P&L del backtest no son directamente comparables con live
- [ ] **Investigar fuentes de datos reales de opciones** — Polygon options API, IBKR históricas; evaluar costo, granularidad y cobertura para el universo de 16 tickers

### P2 — Próximas 2 semanas

- [ ] Filtro --start-date/--end-date en BacktestBatchRunner
- [ ] Backfill completo Polygon para SMH/GLD (2 años)
- [ ] Análisis primeros días paper trades (con parámetros actuales)
- [ ] Investigar c4 opening / p4 opening: ¿por qué no generan trades? (0 trades en backtest — revisar detección de señales o umbrales de parámetros)
- [ ] Investigar bug MIN_5 0 bars — algunos tickers devuelven 0 bars en timeframe MIN_5; no es crítico pero afecta completeness
- [ ] Evaluar habilitar MIN_5 para tickers tácticos (c4/p4) — considerar MIN_5 específicamente para estrategias de apertura
- [ ] Revisar CLAUDE_ANALYSIS.md overfitting analysis — reorientación filosófica pendiente según risk philosophy de the course author; no es un bug, es decisión estratégica
- [ ] Decidir inclusión de strategy.config en JaCoCo o formalizar exclusión — exclusión actual documentada en testing-debt.md; definir si entra al coverage gate
- [ ] Revisar gestión de riesgo: worst trades de -$1500 son excesivos
- [ ] Implementar threshold dinámico en ticker_stats (para tickers nuevos con poca data)
- [ ] Re-correr backtest con universo focal (16 tickers) y rango 2020+

### Tech-debt

- [ ] Fix BacktestBatchRunnerResumeTest.whenBacktestFreshFlagPresent_startsFreshWithoutPrompt
- [ ] Fix RollbackConfigTest$CsvActiveTest.candleStore_csv_injectsCsvCandleRepository
- [ ] Investigar bug checkpoint HISTORICAL hardcoded (HistoricalBackfillService L311, L448)

## Decisiones pendientes de estrategias

### p2 trend — Predicate Subsumption (investigado 2026-05-19)
- `priceBelowMiddleBB` es redundante con `isDowntrend15m` en `P2TrendPutStrategy.java:~125`
  — `priceBelowMiddleBB` = precio < SMA20 = ya garantizado por `isDowntrend15m`
- **Decisión pendiente the course author**: eliminar predicado redundante → aumenta trade count (actualmente 255)
- Tickers negativos a considerar excluir del universo p2: LULU (-$2.719), LLY (-$1.784), ANET (-$1.010), GOOG (-$627), BITX (-$581)
- Estado: ⏳ NO modificar aún — requiere validación backtest antes de activar en live

## Decisiones de arquitectura

- **SQLite se queda** (no migrar a PostgreSQL hasta que universo crezca a 100+ tickers)
- **Universo focal pequeño** (10-20 tickers vs 500+) para alinear con capital real
- **Capital simulación 100% del capital** (config desde UI live trade)
- **Operativa solo en opciones** (CALL/PUT)
- **Paper trading antes de capital real**

## Histórico — sesiones recientes

### 2026-05-16 (sábado, sesión maratónica)
- 14 commits a main
- Bug paginación Polygon (f8342b5)
- Stooq DAY_1 migration completa
- ticker_stats materializada
- Flag --complete-tickers TDD
- 23.9M filas DAY_1 borradas de candles

### 2026-05-18 (lunes, sesión pre-paper + arranque paper)
- StrategyConfigController REST API: GET /api/strategy-config + PATCH /api/strategy-config/{name}, TDD 12 tests (commit 6aa7a0e)
- StrategiesPage.jsx + /strategies route + badge ScanEngineControls, TDD 15+6+5 tests, 890 total, 95.7% coverage (commit 313eb64)
- scripts/run-live.ps1: startup script UTF-8, port 9090, timestamped log, sin DevTools
- Backend verificado en :9090 — 12 estrategias, c6_reversal/p1_squeeze/p6_reversal enabledLive=true
- **Paper trading arrancado**: cuenta DUN598216, $968 USD, 3 estrategias activas
- **HOT tickers corregidos**: NVDA, AMD, AMDL, TSLA, META, AVGO, COIN, MSTR, AMZN, SPY
- **Universo completo**: HOT + AAPL, URA, MU, SMH, OXY, GLD (tácticos)
- **Primer día**: 0 señales — condiciones no cumplidas, sistema funcionando correctamente

### 2026-05-17 (domingo, sesión cierre)
- Bug persistencia HikariCP/DevTools resuelto (commit 7224145)
- Primer backtest funcional con persistencia (62 tickers, 3695 trades)
- Análisis de overfitting completo
- DROP candles_stooq + VACUUM (~5-6 GB liberados)
- Roadmap reorganizado
- Investigación Fase A pre-paper: pricing direccional puro (delta=1 implícito), gap esperado vs realidad opciones reales
- Verificación cobertura tickers tácticos: MU/OXY/URA ya completos en 4 TFs ✅, SMH/GLD bootstrapeados
- Modificación massive_import.py: --only-tickers flag (TDD)
- Nuevo scripts/yfinance_day1_import.py para DAY_1 vía sidecar :8001 (TDD)
- Descarga overnight: SMH/GLD intradía (HOUR_1+MIN_15+MIN_5) via massive en background
- Universo final 16 tickers definido: NVDA, AMD, AMZN, TSLA, META, AVGO, COIN, MSTR, AAPL, AMZN + SPY + URA, MU, SMH, OXY, GLD
