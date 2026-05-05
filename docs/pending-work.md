# Pending Work — options-quant

_Last updated: 2026-05-04_

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

### Telegram ❌ PENDIENTE
- Audit no realizado aún

---

## Fixes recientes

| Commit | Descripción |
|--------|-------------|
| `a619762` | fix(config): correct TWS account ID (DUN598126 → DUN598216) |
| `43d961b` | fix(security): sanitize error responses in LiveModeController |
| `b1492e1` | feat(security): add explicit SecurityFilterChain |
| `17d4db1` | chore(security): bind server to 127.0.0.1 only |

---

## Instrucción para próxima sesión

Al iniciar sesión, leer este archivo primero:
`docs/pending-work.md`

Luego cargar contexto engram:
- `mem_search("sdd/historical-candles-sqlite/scope")`
- `mem_context` para sesiones recientes
