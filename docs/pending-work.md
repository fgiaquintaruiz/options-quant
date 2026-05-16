# Pending Work — options-quant

_Last updated: 2026-05-16_

---

## SDD: Historical Candles + SQLite (EN PROGRESO)

### Estado del SDD
- ✅ Explore — completado 2026-05-04
- ✅ Propose — completado 2026-05-04
- ✅ Spec — completado 2026-05-04
- ✅ Design — completado 2026-05-04
- ✅ Tasks — completado 2026-05-04
- ✅ Apply — completado 2026-05-05 (27/27 tasks, 441/441 tests GREEN)
- ✅ Verify — completado 2026-05-05 (PASS, 0 CRITICAL)
- ✅ Archive — completado 2026-05-05

### Scope confirmado
- **Fuente**: TWS (IBKR)
- **Tickers**: ~513 (todos los configurados)
- **Timeframes**: `5 mins`, `15 mins`, `1 hour`, `1 day` ✅ CONFIRMADOS
- **Desde**: enero 2018 (MIN_5 arrancar con 1 año primero — 17 días continuo para 8 años)
- **Storage**: SQLite `data/candles.db` (reemplaza 2,041 CSVs en `data/`)
- **Uso**: backtest + live replay exclusivamente
- **Objetivo**: memoria RAM <30 MB vs 173 MB actual

### Clases nuevas (13)
- `CandleRepository` (interface), `RepositoryException`
- `SqliteCandleRepository` (@Primary), `SchemaInitializer`, `CandleStoreMigrator`
- `CandleRowMapper`, `CandleResultSetSpliterator`
- `CsvCandleRepository` (rollback adapter)
- `HistoricalBackfillService` (CLI --backfill), `BackfillCheckpoint`, `TickerTimeframeChunk`
- `TickerCursor` (lazy PriorityQueue), `CandlesDataSourceConfig`

### Modificadas (4)
- `BacktestEngine`, `IbkrService`, `ReplayCandleSource`, `StrategyScannerService`

### 27 Tasks (7 fases)
- T1–T5: Infrastructure
- T6–T11: Repository (SqliteCandleRepository + CsvCandleRepository)
- T12–T14: Migration (CSV→SQLite on boot)
- T15–T18: Consumer wiring (TODOS PARALELOS)
- T19–T20: Lazy backtest (TickerCursor + PriorityQueue)
- T21–T22: Downloader (--backfill CLI, rate limiter Guava)
- T23–T27: Validation + rollback + E2E

### Decisiones clave
- Downloader: foreground CLI only (NOT background daemon — comparte TWS con live trading)
- Rollback: `candles.store=csv` en application.yml
- Checkpoint: atómico por chunk (download_progress table)
- Test infra: `jdbc:sqlite::memory:` (NO H2)
- Nombres de interfaz: `load()`, `upsert()`, `loadRange()`, `stream()`, `lastTimestamp()`, `hasLocalData()`

### Engram topic keys
- `sdd/historical-candles-sqlite/scope`
- `sdd/historical-candles-sqlite/explore`
- `sdd/historical-candles-sqlite/proposal`
- `sdd/historical-candles-sqlite/spec`
- `sdd/historical-candles-sqlite/design`
- `sdd/historical-candles-sqlite/tasks`

### Próximos pasos
1. Arrancar `/sdd-apply` — Phase 1 Infrastructure (T1–T5)
2. Continuar fases en orden (T6–T27)

---

## QA Audit — Estado por pantalla

### Settings ✅ DONE
- 26 features mapeadas, cobertura excelente

### Live ⚠️ EN PROGRESO
- 44 features mapeadas — 23% covered, 64% partial, 14% none
- P1 gaps críticos:
  - `POST /cancel-trade` — sin cobertura
  - `POST /execute-signal` — 6 unit tests agregados (`LiveModeControllerExecuteSignalTest`)

### Backtest ✅ DONE
- 38 features mapeadas — 89% covered
- P2 gaps (no bloqueantes):
  - `GET /backtest-ui/max-concurrent` — sin test explícito
  - `GET /backtest-ui/resume` — SSE resume sin test dedicado
  - Chart rendering (Recharts) no validado con curva real
  - Falta load test para runs concurrentes

### Telegram ⚠️ EN PROGRESO
- 3 test suites implementadas: `TelegramWebhookControllerTest`, `TelegramServiceSecurityTest`, `MarketScannerTelegramRoutingTest`
- Features 1-22 cubiertas (webhook + security + routing)
- Features 23-35 → **DEFERRED** (ver sección DEFERRED abajo)
- **Dead code — DECISIÓN TOMADA**:
  - DELETE: `sendTradeExit`, `sendDailySummary`, `sendMacroStatus`, `testConnection` — features huérfanas, sin plan de uso
  - WIRE UP: `validateWebhookRequest` → llamar desde `TelegramWebhookController` como segunda capa de validación (actualmente solo valida secret token, no parámetros de orden)

---

## DEFERRED

### Telegram QA — Features 23-35 (notifications) 🔜 DEFERRED

> **Motivo**: Dead code cleanup SDD debe completarse primero (eliminar `sendTradeExit`, `sendDailySummary`, `sendMacroStatus`, `testConnection`). Escribir tests sobre código que se va a borrar es trabajo desperdiciado.

- **Scope**: notifications — `sendSignal`, `sendAutoExecuteSignal`, `sendTradeConfirmation`, y métodos restantes de `TelegramService`
- **Prerequisito**: completar SDD Telegram Dead Code Cleanup (ver abajo)
- **Retomar cuando**: dead code eliminado y `validateWebhookRequest` wired up en controller

### Migración a Kotlin DDD 🔜 PLACEHOLDER
Idea sin scope todavía. Refactor del backend a Kotlin con arquitectura DDD.
Pendiente: definir alcance, motivación, plan de migración incremental.

### Creador de estrategias 🔜 PLACEHOLDER
Idea sin scope todavía. Tooling para que el sistema genere/proponga estrategias
en lugar de implementarlas a mano.
Pendiente: definir alcance, motivación, plan.

---

## SDD: Telegram Dead Code Cleanup 🔜 DEFERRED

### Scope
- DELETE `sendTradeExit`, `sendDailySummary`, `sendMacroStatus`, `testConnection` de `TelegramService.java`
- WIRE UP `validateWebhookRequest` en `TelegramWebhookController` como segunda capa de validación (actualmente solo valida secret token — no valida que parámetros de la orden coincidan con lo generado)
- Actualizar `TelegramServiceSecurityTest` para reflejar que `validateWebhookRequest` es llamado desde el controller

### Archivos afectados
- `src/main/java/com/fgiaquinta/optionsquant/service/TelegramService.java` — delete 4 métodos
- `src/main/java/com/fgiaquinta/optionsquant/controller/TelegramWebhookController.java` — wire up validateWebhookRequest
- `src/test/java/com/fgiaquinta/optionsquant/service/TelegramServiceSecurityTest.java` — ajustar tests

---

## SDD: yfinance Historical Fallback ✅ DONE

### Estado
- ✅ Explore, Propose, Spec, Design, Tasks — 2026-05-05
- ✅ Apply — 2026-05-05 (13/13 tasks, 504/504 tests GREEN, commit `c31453e`)
- ✅ T12 manual: verificado — sidecar recibe requests trimmeados al período exacto, OUT_OF_RANGE en logs para chunks fuera de ventana
- ✅ Period trimming: computeEffectiveRanges() — chunks trimmed to period intersection before yfinance call
- ✅ start-year: 2007 — 2008-09 period reachable

### Scope
- yfinance como fallback para DAY_1 chunks más viejos de `cutoff-years` (default 5)
- TWS first → yfinance fallback para chunks recientes
- ^VIX via yfinance-only path, alimenta `BacktestEngine.vixAtEntry`
- Python sidecar: `GET /api/v1/historical/{ticker}?from=&to=&interval=1d`

### Engram topic keys
- `sdd/yfinance-historical-fallback/apply-progress` (#1158)
- `sdd/yfinance-historical-fallback/design` (#1144)
- `sdd/yfinance-historical-fallback/tasks` (#1145)

---

## Tech debt

### A1.1 — Startup health check para servicios externos
**Severidad**: ALTA | **Esfuerzo**: S (1 día)

Al startup, detectar y advertir si servicios externos requeridos no están disponibles:
- Analytics service Python (`:8001`) → `WARN` visible si no responde en healthcheck
- TWS/IBKR (paper o live) → `WARN` visible si no hay conexión activa

**Problema actual**: la app levanta sin warning si estos servicios están caídos.
Los bugs aparecen en runtime durante operaciones reales (ej: backfill marca
`COMPLETE_EMPTY` porque `analytics_service` no responde — bug detectado 2026-05-09).

**Relacionado con**: A1 del audit original (Custom Health Indicators con Spring Boot Actuator).

**Implementación sugerida**: `ApplicationReadyEvent` listener o `HealthIndicator` custom
que intente `GET /health` en `:8001` y `isConnected()` en `IbkrService` al startup,
loguee `WARN` con mensaje claro si alguno falla.

---

- **M10 — Schema migrations**: introducir Flyway o Liquibase para versionar el schema SQLite. Hoy el schema se crea de forma idempotente vía `SchemaInitializer.java` con `CREATE TABLE IF NOT EXISTS` y `ALTER TABLE` envuelto en try/catch para columnas (ej. `chunk_origin` agregada en DEL1 live-tail fix). Cada nueva columna requiere ese patrón manual; con Flyway dejaríamos un trail versionado y auditable. Bloqueado por sprint corto previo al paper trading; programar fuera del path crítico.

### A2 — Analytics service — Bugs pendientes (rescatado de info-optionsquant 2026-05-09)
**Severidad**: MEDIA | **Esfuerzo**: S

- **Renombrar** `java_grpc_connected` → `java_rest_connected` en `main.py` de analytics_service.
  El campo no tiene nada de gRPC — usa REST puro (httpx). El nombre incorrecto causó horas de
  diagnóstico en la dirección equivocada.
- **Mover** `JavaApiClient.connect()` del módulo top-level al lifecycle `lifespan` de FastAPI
  (~línea 178 de `main.py`). Bug: si Java no está up en el momento exacto de import →
  `connected = False` para siempre, sin reconexión.
- **Estado actual**: workaround manual aplicado (reinicio del analytics_service).
  Fix permanente pendiente.

## Fixes recientes

| Commit | Descripción |
|--------|-------------|
| `c31453e` | feat(backfill): add yfinance fallback for historical data beyond TWS limits |
| `6cf4165` | fix(telegram): generateSecureOrderId blank masterKey now stores orderId |
| `43d961b` | fix(security): sanitize error responses in LiveModeController |
| `b1492e1` | feat(security): add explicit SecurityFilterChain |
| `17d4db1` | chore(security): bind server to 127.0.0.1 only |

---

## Universe Expansion — Tickers Stooq extra 🔜 DEFERRED

### Contexto
El import de Stooq DAY_1 (2026-05-15) trajo ~11,881 tickers a `candles.db`.
`load_tickers` en `massive_import.py` filtra a los ~510 tickers configurados (MIN_5/HOUR_1).
Quedan ~11,371 tickers adicionales con datos DAY_1 históricos (desde 1984) sin MIN_5/MIN_15.

### Pendiente
Evaluar precio por ticker y potencial ganancia antes de expandir el universo de descarga.
Si la evaluación es positiva: cambiar `load_tickers` para incluir tickers DAY_1-only,
luego correr `massive_import.py` con el universo completo (~11,881 tickers, ~28,500 requests, ~95 horas).

### Prerequisito
- Análisis de viabilidad: ¿qué % de los 11,371 tickers extra tienen liquidez/volumen suficiente?
- Decisión de si vale la tarifa de Massive (actualmente free plan = 5 req/min)

---

## Instrucción para próxima sesión

Al iniciar sesión, leer este archivo primero:
`docs/pending-work.md`

Luego cargar contexto engram:
- `mem_search("sdd/yfinance-historical-fallback/apply-progress")` — estado yfinance SDD
- `mem_search("qa-audit/screen-coverage-status")` — estado QA
- `mem_context` para sesiones recientes

---

## Estado al 2026-05-16

### Pipelines activos

**Polygon massive_import.py** — RE-RUN EJECUTADO (2026-05-16 06:36) — DONE=252, ERROR=0, SKIP=1278
- Paginación implementada (commit ec430d8) — evita truncación >50000 bars
- ATENCIÓN — paginación instalada pero NO resuelve el lag:
  - ~258 tickers MIN_5 hasta ~2026-05-08 (7 días lag)
  - 252 tickers MIN_5 hasta ~2026-04-21 (24 días lag)
  Causa probable: límite de delayed data del plan free de Polygon. Verificar con análisis pendiente (P0).

**Java TWS backfill (PID 56872)** — EN CURSO
- DAY_1: completo (511 LIVE_TAIL + 1 HISTORICAL — blind spot checkpoint)
- HOUR_1: 100% completo
- MIN_15 y MIN_5 históricos en progreso al cierre de sesión. Ver tabla `candles` para conteo actualizado.

### P1 — Commits Java pendientes (sin commitear)
- fix P1 squeeze fixture — StrategyUnitTest.java
- Opción 3: filtro chunk_origin — BackfillCheckpoint.java + HistoricalBackfillService.java
- seam fix BacktestBatchRunner — 4 test files
- ajuste YAML period 2023-01 → 2024-05 — application.yml

### P1 — Blind spot checkpoint live-tail
Walker escribe `chunk_origin=LIVE_TAIL` pero lee como `HISTORICAL`.
Fix: BackfillCheckpoint.java — `getLastDownloaded()`, ~línea 72.

### P2 — Migración candles_stooq (PRÓXIMO)
- Crear tabla `candles_stooq` (DAY_1 tickers no activos, ~8000-10000 Stooq histórico)
- Importar 4570 MIN_5 desde `data/stooq/5_min/nasdaq stocks`
- CET/CEST → usar zoneinfo/pytz, NO aritmética fija
- Ticker sufijo `.US` → stripear
- `volume` Stooq es float — candles.volume es INTEGER (decidir redondear vs cambiar tipo)
- Esperar que terminen massive_import.py Y backfill Java antes de ejecutar

### P2 — Tests pendientes de arreglar (3)
1. `BacktestBatchRunnerResumeTest.whenBacktestFreshFlagPresent_startsFreshWithoutPrompt`
2. `RollbackConfigTest$CsvActiveTest`
3. `TestMainSkipLogic.test_no_fetch_when_all_done`

### P3 — IntelliJ ghost process
JVM huérfana compite por puerto 9090/TWS/candles.db. Correr `netstat -ano | findstr :9090` antes de arrancar.

## Filtro --complete-tickers (NUEVO, pendiente implementación Java)

### Tabla ticker_stats ✅ DONE
Materializada con stats agregadas de candles. Refresh manual con
scripts/refresh_ticker_stats.py.

- Performance: query original 17.9s → ticker_stats 14-43ms (~1000×)
- Cobertura: 510/512 tickers con los 4 timeframes (2 incompletos)
- Threshold elegido: has_all_4_tfs=1 AND bars_total >= 80000
- Universo inicial: 57 tickers califican (subset estricto para backtest)
- Refresh tarda ~41s (full scan candles 24M filas)
- Tabla creada con WITHOUT ROWID + 2 índices
- Rango analizado: desde 2024-05-01 (inicio cobertura Polygon)

### Pendiente — flag --complete-tickers en Java
En BacktestBatchRunner.java, parsear flag, ejecutar al inicio del
backtest:
  SELECT ticker FROM ticker_stats
  WHERE active=1 AND has_all_4_tfs=1 AND bars_total >= 80000;
Cachear resultado, usar como universo del backtest.

### Workflow operativo
1. Después de cada batch significativo de backfill (Polygon o TWS),
   correr: python scripts/refresh_ticker_stats.py
2. Al iniciar backtest con --complete-tickers, query rápida contra
   ticker_stats define universo.
