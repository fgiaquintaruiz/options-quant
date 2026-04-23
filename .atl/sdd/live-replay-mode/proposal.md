# Proposal: Live replay mode for off-hours E2E scanner testing

## Intent

Habilitar un modo "máquina del tiempo" para el Live Dashboard que reproduzca candles históricos ya descargados en `data/` como si el mercado estuviera abierto. Objetivo: validar y tunear la detección E2E del scanner (strategies → signal → grid → bracket → TWS paper → review) **fuera del horario de mercado** (05:00–15:30 hora España), cuando el usuario tiene tiempo de mirar con calma.

**Importante:** en esta fase las órdenes **sí se ejecutan** (en cuenta paper `DUN598126`). El usuario quiere revisar cómo se crean los brackets en TWS. El replay se comporta idéntico al live — sólo cambia la fuente de candles y el reloj.

## Operational Constraints

- **TWS debe estar logueado manualmente en paper** (`DUN598126` en puerto `7497`) antes de iniciar un replay. La app no auto-loguea TWS.
- Replay sólo corre con TWS conectado. Si TWS se desconecta a media sesión, replay aborta y deja constancia en el log.
- Si en el futuro se cambia a cuenta real, **hay que loguear manualmente en TWS real** (puerto `7496`) — ver Future Work: `tws-mode-toggle`.

## Scope

### In Scope

- **Virtual clock**: reloj simulado que avanza en saltos de 15min (cadencia del scanner) a velocidad configurable (presets `30x`, `60x`, `180x`, `360x`).
- **Replay data source**: lector de `data/{TICKER}_{TF}.csv` que expone candles "hasta tiempo virtual T".
- **CSV backfill automático**: si faltan candles para la fecha elegida, se descargan de IBKR reusando `ibkrService.downloadHistoricalData` + `csvService.saveToCsv` (mismo flujo que el live scan en `StrategyScannerService.java:550-620`).
- **Latencia jittered**: delay random `100–800ms` entre cierre virtual y entrega al scanner para mimetizar IBKR real.
- **Pipeline intacto** (órdenes incluidas): la detección, scoring, priorización y ejecución corren sin cambios. Los brackets **sí salen a TWS paper**.
- **Signal tagging para UI/auditoría**: campo `replay=true` en `Signal` — NO bloquea ejecución, solo controla UI (banner/badge) y persistencia separada.
- **Safety rate-limit**: cap configurable `replay.max-orders-per-minute` (default `10`). Excedido → signals se emiten pero `tryAutoExecute` se skipea con log warning.
- **PAPER / LIVE chip (Opción A — read-only)**:
  - Visible siempre en el header del Live Dashboard.
  - Detecta al arrancar: si `ibkr.account-id` empieza con `DU*` → chip verde "PAPER". Sino → chip rojo "LIVE ⚠️".
  - **No toggle** — para cambiar de modo el usuario edita `application.yml` y reinicia.
  - Toggle interactivo queda como SDD futuro (ver Future Work).
- **Configurable replay date**: usuario elige día (ej. `2026-04-22`); sistema backfillea si falta data.
- **UI controls** en Live Dashboard: botón "Start Replay" + date picker + slider de velocidad (`30x/60x/180x/360x`) + status "Virtual time: 14:35 · 30/78 candles".

### Out of Scope

- Replay multi-día encadenado (v1 = un solo día).
- Replay de opciones / cadenas de strikes (sólo equities 5m/15m/1h).
- Mercado "activo en paralelo" — si el mercado real está abierto, el replay se rechaza (evita confusión con órdenes reales sobre el mismo ticker).
- Switch paper ↔ live con reconexión TWS en runtime (ver Future Work `tws-mode-toggle`).
- Comparar resultados del replay vs run histórico anterior (feature futura: "regression test").

## Capabilities

### New Capabilities

- `live-replay-clock`: Virtual clock con velocidad configurable y control start/stop/pause.
- `live-replay-source`: Alternativa a download directo de IBKR que sirve candles CSV truncados al tiempo virtual + backfillea desde IBKR si falta.
- `live-replay-controls`: UI + REST endpoints para controlar replay (start/stop/speed/date).
- `live-paper-live-chip`: Chip indicador PAPER/LIVE detectado desde `account-id` (read-only v1).

### Modified Capabilities

- `scanner-scheduling`: `MarketScanner.scanAndExecute()` — bypass del check "outside market hours" cuando `replayMode=true`; reloj interno toma el tiempo virtual.
- `signal-emission`: `Signal` record agrega campo `replay` (boolean, default `false`). **Solo para UI/auditoría** — no altera ejecución.
- `live-dashboard`: banner "REPLAY MODE" + controles embebidos + chip PAPER/LIVE; `LiveTradeGrid` marca filas replay con badge.

## Approach

1. **Virtual clock service** (`ReplayClock`): singleton Spring con estado `{ active, virtualNow, speedMultiplier, startRealTime, runId }`. Expone `getNow()` que devuelve virtual time cuando active, real time cuando off.
2. **Replay data source** (`ReplayCandleSource`):
   - En `replay/start`: para cada `(ticker, tf)` del universo HOT:
     - `csvService.loadFromCsv(ticker, tf)` → check si fecha target cubierta.
     - Si falta: `ibkrService.downloadHistoricalData(ticker, tf)` + `csvService.saveToCsv(ticker, tf, merged)`.
     - Cargar en `NavigableMap<ZonedDateTime, Candle>` para lookup O(log n) por `virtualNow`.
   - Si TWS no conectado Y falta data: error duro con lista de tickers faltantes.
3. **Wire-up mínimo en scanner**: `StrategyScannerService.downloadTimeframeDelta()` delega a `ReplayCandleSource.getCandlesUntil(ticker, tf, clock.getNow())` cuando `replayClock.isActive()`.
4. **Scheduler** (`ReplayScheduler`): loop que:
   - calcula próximo candle boundary (15min);
   - espera `15_min / speedMultiplier + jitter(100–800ms)`;
   - avanza `replayClock.virtualNow`;
   - invoca `marketScanner.scanAndExecute()`.
5. **Signal tagging UI**: campo `replay` en `Signal` — `processLiveSignalAfterScan` no se bifurca en ejecución. Solo `addLiveSignal` lo usa para enrutar a `replaySignals` (lista separada) en vez de `liveSignals`.
6. **Rate limiter**: `ReplayOrderGate` en `MarketScanner.tryAutoExecute` — cuenta órdenes en ventana rodante de 60s; si excede `replay.max-orders-per-minute`, skipea con log.
7. **Chip PAPER/LIVE**: nuevo endpoint `GET /live-ui/account-mode` → `{ mode: "PAPER" | "LIVE", accountId }`. Frontend consulta al mount y muestra chip.
8. **UI Replay**: nuevo panel `ReplayControls` en LiveDashboard; endpoints REST `/live-ui/replay/start|stop|status|speed`.

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `domain/Signal.java` | Modified | Agregar campo `boolean replay` (default `false`). |
| `service/ReplayClock.java` | New | Virtual time + speed control. |
| `service/ReplayCandleSource.java` | New | CSV + IBKR backfill, lookup truncado a virtual time. |
| `service/ReplayScheduler.java` | New | Loop que avanza reloj virtual y dispara `scanAll()`. |
| `service/ReplayOrderGate.java` | New | Rate-limit de órdenes por minuto en replay. |
| `service/StrategyScannerService.java` | Modified | Branch replay-aware en `downloadTimeframeDelta()`. |
| `service/MarketScanner.java` | Modified | Bypass market-hours check cuando replay; rate-gate en auto-exec. |
| `controller/LiveModeController.java` | Modified | Endpoints `/live-ui/replay/*` + `/live-ui/account-mode`. |
| `config/IbkrProperties.java` | Modified (minor) | Helper `isPaperAccount()` (prefix `DU`). |
| `frontend/src/pages/LiveDashboard.jsx` | Modified | Banner REPLAY + chip PAPER/LIVE + mount `ReplayControls`. |
| `frontend/src/components/ReplayControls.jsx` | New | Date picker + speed slider + start/stop + status. |
| `frontend/src/components/AccountModeChip.jsx` | New | Chip verde PAPER / rojo LIVE. |
| `frontend/src/api.js` | Modified | `replayApi` + `accountApi.getMode()`. |
| `data/replay-signals-{date}-{runId}.jsonl` | New (runtime) | Persistencia separada de signals del replay. |

## Risks (actualizados)

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| Rate limit TWS saturado por órdenes a 60x+ | Med | `ReplayOrderGate` con cap default `10 órdenes/min`; signals siguen mostrándose aunque no ejecuten. Log warning visible. |
| Confusión paper vs live al ir a real en el futuro | High | Chip PAPER/LIVE siempre visible. Future SDD `tws-mode-toggle` agregará confirmación interactiva y reconexión runtime. |
| Candles faltantes en `data/` para la fecha | Med | Backfill automático vía `ibkrService.downloadHistoricalData` (mismo patrón que live scan). Si TWS desconectado → error con lista de faltantes. |
| CSV grandes ralentizan lectura repetida | Low | Preload en `NavigableMap`. ~11MB para universo completo, trivial. Eviction al `replay/stop`. |
| Grid mezcla signals replay + reales | Med | Listas separadas (`liveSignals` congelada durante replay, `replaySignals` nueva); banner rojo "REPLAY MODE"; persistencia en `replay-signals-{date}-{runId}.jsonl`. |
| Latency jitter hace replay irreproducible | Low | Seed opcional en UI ("reproducible mode") para debug / regression tests. |
| Replay con mercado real abierto (mismo ticker) | High | Hard block al `replay/start` si hora actual está en `10:00–22:00 Europe/Madrid` MON-FRI. |
| TWS se desconecta a mitad de replay | Med | `ReplayScheduler` detecta `!orderService.isConnected()` → aborta replay con mensaje en UI. |

## Rollback Plan

- Feature flag `replay.enabled=false` en `application.yml` — desactiva endpoints, UI y scheduler.
- Revert del campo `Signal.replay` es forward-compatible (default `false` → pipeline normal).
- Sin cambios destructivos: CSVs son append-only, signals replay en archivo aparte.

## Dependencies

- `data/{TICKER}_{TF}.csv` o conexión TWS paper activa para backfill.
- Scanner estable (confirmado post-SDD `backtest-grid-search-ticker-params`).
- TWS logueado manualmente en paper (`DUN598126` / `7497`) antes de iniciar replay.

## Success Criteria

- [ ] Off-hours (ej. 10:00 AM España, mercado US cerrado) puedo elegir día `2026-04-22`, velocidad `60x`, y ver el scanner procesar ~6.5h de sesión US en ~6.5min virtuales.
- [ ] Las señales emitidas durante replay aparecen en el grid con badge visible + banner "REPLAY MODE".
- [ ] Los brackets **sí se crean en TWS paper** y puedo revisarlos en la plataforma.
- [ ] Rate-limit corta órdenes al exceder el cap configurado y deja log claro.
- [ ] Chip PAPER visible (verde) siempre; si alguna vez config cambia a live, chip rojo ⚠️ aparece inmediato.
- [ ] Puedo cambiar la velocidad sin reiniciar el replay.
- [ ] `Stop Replay` vuelve al estado normal en <2s; el próximo scan real (cron) corre normalmente.
- [ ] Integration test: replay de 1h simulada con 1 ticker (AAPL) produce las mismas señales que un backtest del mismo rango, modulo jitter.
- [ ] Integration test: intento de `replay/start` con mercado real abierto → rechazo.
- [ ] Integration test: TWS desconectado durante replay → abort + mensaje UI.

## Future Work

### `tws-mode-toggle` (SDD siguiente — cuando se pase a cuenta real)

**Cuándo:** al momento de querer trading real en vivo.

**Scope:**
- Refactor `IbkrProperties` a dos perfiles (`ibkr.paper.*` + `ibkr.live.*`) + `ibkr.mode: paper|live`.
- Chip PAPER/LIVE **clickeable** para swap en runtime (disconnect + reconnect al puerto opuesto).
- Modal de confirmación paper→live con typing del `account-id` live para confirmar.
- Switch live→paper: instant, sin confirmación (degradar a seguro).
- Bloqueos: no permite toggle con posiciones abiertas o replay en curso.
- Log auditable cada switch.
- **Requiere TWS logueado manualmente en la cuenta target** — la app no maneja credenciales de TWS.

**Por qué separarlo:** el toggle implica cambios en `IbkrService.connect/disconnect` y manejo de estado compartido entre `OrderExecutionService`, `IbkrService` y `AccountManager`. Es un SDD propio que merece su ciclo completo de spec/design/tasks.

---

**Next step:** `sdd-spec` / `sdd-design` / `sdd-tasks`.
