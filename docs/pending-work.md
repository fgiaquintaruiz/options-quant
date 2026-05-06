# Pending Work — options-quant

_Last updated: 2026-05-05_

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
- Features 23-35 pendientes (notifications: sendSignal, sendAutoExecuteSignal, sendTradeConfirmation, etc.)
- **Dead code — DECISIÓN TOMADA**:
  - DELETE: `sendTradeExit`, `sendDailySummary`, `sendMacroStatus`, `testConnection` — features huérfanas, sin plan de uso
  - WIRE UP: `validateWebhookRequest` → llamar desde `TelegramWebhookController` como segunda capa de validación (actualmente solo valida secret token, no parámetros de orden)

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

## Fixes recientes

| Commit | Descripción |
|--------|-------------|
| `c31453e` | feat(backfill): add yfinance fallback for historical data beyond TWS limits |
| `6cf4165` | fix(telegram): generateSecureOrderId blank masterKey now stores orderId |
| `43d961b` | fix(security): sanitize error responses in LiveModeController |
| `b1492e1` | feat(security): add explicit SecurityFilterChain |
| `17d4db1` | chore(security): bind server to 127.0.0.1 only |

---

## Instrucción para próxima sesión

Al iniciar sesión, leer este archivo primero:
`docs/pending-work.md`

Luego cargar contexto engram:
- `mem_search("sdd/yfinance-historical-fallback/apply-progress")` — estado yfinance SDD
- `mem_search("qa-audit/screen-coverage-status")` — estado QA
- `mem_context` para sesiones recientes
