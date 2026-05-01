# Inventario Funcional — options-quant

> Última actualización: 2026-04-30 | Tests: 619/619 | Estado: todo verde

## Cómo leer este documento

| Ícono | Significado |
|---|---|
| ✅ Unit | Cubierto por tests unitarios (mocks, sin Spring context) |
| ✅ Integration | Cubierto por tests de integración (`@SpringBootTest` o `@WebMvcTest`) |
| ✅ E2E | Cubierto por tests Playwright (browser o HTTP contra app real) |
| ⚠️ Parcial | Cubierto en algunos branches/paths pero no en todos |
| ❌ Sin cobertura | Ningún test automatizado valida esta funcionalidad |

Múltiples íconos = múltiples capas de cobertura activas.

---

## FRONTEND

### Live Dashboard — Visualización y Estado

| Funcionalidad | Descripción usuario | Cobertura | Test files |
|---|---|---|---|
| Carga de la página Live | El usuario navega a `/live` y ve el dashboard principal con toolbar y grid de señales | ✅ E2E | `src/test/java/com/fgiaquinta/optionsquant/e2e/LiveUiDashboardTest.java` |
| Navegación SPA Live↔Backtest↔Health | El usuario usa los links del navbar para cambiar de sección sin recargar la página | ✅ E2E | `src/test/java/com/fgiaquinta/optionsquant/e2e/NavigationTest.java` |
| Grid de señales en vivo (LiveTradeGrid) | El usuario ve las señales activas, trades ejecutados, posiciones externas y chips de scan activity | ✅ Unit | `frontend/src/components/LiveTradeGrid.test.jsx` (51 tests, 98.51%) |
| Indicador PAPER/LIVE (AccountModeChip) | El usuario ve si está operando en cuenta paper o real | ✅ Unit | `frontend/src/components/AccountModeChip.test.jsx` (8 tests) |
| Ticker tooltip con fundamentals | El usuario hace hover sobre un ticker y ve sus datos fundamentales (P/E, beta, etc.) | ✅ Unit | `frontend/src/components/TickerTooltip.test.jsx` (10 tests) |
| Countdown al próximo scan | El usuario ve cuánto falta para el próximo scan de 15 minutos | ✅ Unit | `frontend/src/hooks/useScanCountdown.test.js` (100%) |
| Estado de mercado abierto/cerrado | El usuario ve si el mercado está abierto (polling 30s) | ✅ Unit | `frontend/src/hooks/useMarketOpen.test.js` (100%) |
| Polling de estado del scanner | El usuario ve el estado actual del scanner (isScanning, progreso, tickers) en tiempo real | ✅ Unit | `frontend/src/hooks/useLiveScanner.test.js` (25 tests, 100%) |
| Columnas SCAN STARTED / SCAN FINISHED | El usuario ve cuándo empezó y terminó el análisis de cada ticker | ✅ Unit | `frontend/src/components/LiveTradeGrid.test.jsx` |
| Stale signal indicator (>30min) | El usuario ve una advertencia visual cuando una señal lleva más de 30 minutos sin ejecutar | ✅ Unit | `frontend/src/utils/liveSignalUtils.test.js` (100%) |

### Live Dashboard — Scan Engine Controls

| Funcionalidad | Descripción usuario | Cobertura | Test files |
|---|---|---|---|
| Start Scan manual | El usuario pulsa "Scan Now" para lanzar un análisis inmediato de todos los tickers | ✅ Unit / ✅ E2E | `frontend/src/components/ScanEngineControls.test.jsx`, `LiveModeInteractionTest.java` |
| Stop Scan | El usuario detiene un scan en curso antes de que termine | ✅ Unit / ✅ E2E | `frontend/src/components/ScanEngineControls.test.jsx`, `LiveModeInteractionTest.java` |
| Force Stop / Reset | El usuario fuerza un reset total del estado de scan cuando la app queda colgada | ✅ Unit | `frontend/src/components/ScanEngineControls.test.jsx` |
| Toggle Scheduler (auto-scan cada 15min) | El usuario activa/desactiva el scanner automático programado | ✅ Unit / ✅ E2E | `frontend/src/components/ScanEngineControls.test.jsx`, `LiveUiDashboardTest.java` |
| Toggle Mock Market Open | El usuario simula que el mercado está abierto para poder escanear fuera de horario | ✅ Unit / ✅ E2E | `frontend/src/components/ScanEngineControls.test.jsx`, `LiveUiDashboardTest.java` |
| Toggle Auto Execute | El usuario activa la ejecución automática de señales vía TWS | ✅ Unit / ✅ E2E | `frontend/src/components/ScanEngineControls.test.jsx`, `LiveUiDashboardTest.java` |
| Toggle Extended Hours | El usuario activa/desactiva el horario extendido | ✅ Unit / ✅ E2E | `frontend/src/components/ScanEngineControls.test.jsx`, `LiveModeInteractionTest.java` |
| Toggle Macro Filter | El usuario activa/desactiva el filtro macro (override manual) | ✅ Unit | `frontend/src/components/ScanEngineControls.test.jsx` |
| Adjust Risk % | El usuario ajusta el porcentaje de riesgo por operación en runtime | ✅ Unit | `frontend/src/components/ScanEngineControls.test.jsx` |
| Set Max Concurrent Scans | El usuario configura cuántos tickers analiza en paralelo (1–16) | ✅ Unit | `frontend/src/hooks/useLiveScanner.test.js` |
| Inject Mock Signal | El usuario inyecta una señal simulada para testing sin TWS conectado | ✅ Unit | `frontend/src/components/ScanEngineControls.test.jsx` |
| IBKR Lock (TWS status) | El usuario ve el estado de conexión con TWS (data/account/exec) | ✅ Unit | `frontend/src/components/ScanEngineControls.test.jsx` |
| Scope HOT/ALL | El usuario filtra el scan a tickers calientes o universo completo | ✅ Unit | `frontend/src/hooks/useLiveScanner.test.js` |

### Live Dashboard — Trade Actions

| Funcionalidad | Descripción usuario | Cobertura | Test files |
|---|---|---|---|
| Ejecutar señal manualmente (execute signal) | El usuario pulsa "Open" en una fila de señal para enviar la orden a TWS | ✅ Unit | `frontend/src/hooks/useTradeActions.test.js` (16 tests, ~95%) |
| Cerrar trade (close) | El usuario pulsa "Close" para cancelar las órdenes TP/SL o marcar el trade como cerrado | ✅ Unit | `frontend/src/hooks/useTradeActions.test.js` |
| Cancelar orden (cancel) | El usuario cancela una orden pendiente en TWS por orderId | ✅ Unit | `frontend/src/hooks/useTradeActions.test.js` |
| Eliminar fila de señal (delete row) | El usuario elimina una fila de señal de la grilla cuando no tiene posición abierta | ✅ Unit | `frontend/src/hooks/useTradeActions.test.js` |
| Cerrar posición externa | El usuario coloca una orden de market SELL para una posición externa (no abierta por la app) | ✅ Unit | `frontend/src/hooks/useTradeActions.test.js` |
| Schedule Close 14:50 ET | El usuario programa el cierre automático de una posición externa a las 14:50 hora ET | ✅ Unit | `frontend/src/hooks/useTradeActions.test.js` |
| Posiciones externas (polling 10s) | El usuario ve posiciones abiertas en TWS que no fueron abiertas por la app | ✅ Unit | `frontend/src/hooks/useExternalPositions.test.js` (100%) |
| Bloqueo de ejecución en señal stale (>30min) | El sistema rechaza la ejecución de una señal que tiene más de 30 minutos de antigüedad | ✅ Unit | `frontend/src/hooks/useTradeActions.test.js` |

### Live Dashboard — Replay Mode

| Funcionalidad | Descripción usuario | Cobertura | Test files |
|---|---|---|---|
| ReplayControls UI (date picker + speed picker) | El usuario selecciona una fecha y velocidad para reproducir el mercado histórico | ✅ Unit | `frontend/src/components/ReplayControls.test.jsx` (12 tests, 72%) |
| Start Replay | El usuario inicia la reproducción histórica para esa fecha | ✅ Unit / ✅ E2E | `frontend/src/components/ReplayControls.test.jsx`, `ReplayControlsE2eTest.java` (tag: tws-paper) |
| Stop Replay | El usuario detiene la reproducción en curso | ✅ Unit / ✅ E2E | `frontend/src/components/ReplayControls.test.jsx`, `ReplayControlsE2eTest.java` |
| Change Replay Speed | El usuario ajusta la velocidad de replay en tiempo real | ✅ Unit | `frontend/src/components/ReplayControls.test.jsx` |
| Poll Replay Status | La UI refleja si el replay está activo/inactivo con polling | ✅ Unit | `frontend/src/components/ReplayControls.test.jsx` |

### Configuración — Settings Page

| Funcionalidad | Descripción usuario | Cobertura | Test files |
|---|---|---|---|
| Editor de tickers calientes (HotTickerCountEditor) | El usuario ajusta cuántos tickers son considerados "hot" (N entre 1 y 100) | ✅ Unit | `frontend/src/components/HotTickerCountEditor.test.jsx` (11 tests, 97%) |
| Editor chip-based de listas (ChipListEditor) | El usuario edita listas de tickers como chips (add/remove) | ✅ Unit | `frontend/src/components/ChipListEditor.test.jsx` (16 tests) |
| Formulario de fundamentals por ticker | El usuario edita los datos fundamentales (P/E, beta, sector, etc.) de un ticker | ✅ Unit | `frontend/src/components/FundamentalTickerForm.test.jsx` (17 tests) |
| CRUD de watchlists | El usuario crea, edita y elimina grupos de watchlists guardados en localStorage | ✅ Unit | `frontend/src/hooks/useWatchlists.test.js` (11 tests, ~90%) |
| Guardar filtro como watchlist (SaveListPopover) | El usuario guarda el filtro actual de tickers como una watchlist con nombre | ✅ Unit | `frontend/src/components/SaveListPopover.test.jsx` (7 tests, 95%) |
| Overrides TP/SL ATR por ticker (MemoryPanel) | El usuario configura multiplicadores personalizados de TP/SL por ticker y estrategia | ✅ Unit | `frontend/src/components/MemoryPanel.test.jsx` (13 tests) |
| Backup de settings a backend (debounced) | La app sincroniza automáticamente los settings del localStorage al backend (debounce 2s) | ✅ Unit | `frontend/src/hooks/useStorageBackup.test.js` (5 tests, ~90%) |
| Sync de settings al scanner (useScanSettings) | La app sincroniza los toggles de configuración con el backend al cambiar | ⚠️ Parcial | Sin test propio; branches de error y mock-market uncovered |
| SettingsPage tabs (Servidor/Watchlists/Preferencias) | El usuario navega entre las 3 pestañas de configuración | ❌ Sin cobertura | — |

### Configuración — TickerSelector

| Funcionalidad | Descripción usuario | Cobertura | Test files |
|---|---|---|---|
| Autocomplete de tickers | El usuario escribe letras y ve sugerencias de tickers filtradas | ✅ Unit | `frontend/src/components/TickerSelector.test.jsx` (41 tests) |
| Expansión de @watchlist | El usuario escribe `@nombre` y se expanden todos los tickers de esa watchlist | ✅ Unit | `frontend/src/components/TickerSelector.test.jsx` |
| Hot chips (selección rápida) | El usuario hace click en los chips de tickers calientes para seleccionarlos | ✅ Unit | `frontend/src/components/TickerSelector.test.jsx` |
| News ticker display | El usuario ve un ticker de noticias en tiempo real | ⚠️ Parcial | Stub cubierto; integración real sin cobertura |

### Backtest Dashboard

| Funcionalidad | Descripción usuario | Cobertura | Test files |
|---|---|---|---|
| Carga de la página Backtest | El usuario navega a `/backtest` y ve el dashboard con sus secciones | ✅ E2E | `src/test/java/com/fgiaquinta/optionsquant/e2e/BacktestUiDashboardTest.java` |
| Reporte KPIs + equity curve (BacktestReportPanel) | El usuario ve win rate, total PnL, max drawdown y la curva de equity del backtest | ✅ Unit | `frontend/src/components/BacktestReportPanel.test.jsx` (25 tests) |
| Data grid con sorting y chart modal (UnifiedDataGrid) | El usuario ordena el log de trades y abre el chart modal de un trade | ✅ Unit | `frontend/src/components/UnifiedDataGrid.test.jsx` (38 tests) |
| Iniciar backtest (Run Backtest) | El usuario lanza un backtest completo para los tickers y fechas seleccionados | ✅ E2E | `BacktestUiDashboardTest.java`, `BacktestInteractionTest.java` |
| Detener backtest en curso | El usuario interrumpe un backtest en ejecución | ✅ E2E | `BacktestInteractionTest.java` |
| Auto Run (backtest automático periódico) | El usuario activa el modo de backtest automático | ✅ E2E | `BacktestAutoRunStabilityTest.java` (soak test) |
| Improve strategy (análisis y mejora) | El usuario pulsa "Improve" en una estrategia para ver recomendaciones de parámetros | ✅ E2E | `BacktestInteractionTest.java` |
| Retest strategy con nuevos params | El usuario reejercuta el backtest de una estrategia con los parámetros mejorados | ✅ E2E | `BacktestInteractionTest.java` |
| Improve Modal — análisis grid search (ImproveModal) | El usuario ve el modal con análisis detallado y puede lanzar un grid search desde ahí | ✅ Unit | `frontend/src/components/ImproveModal.test.jsx` (25 tests) |
| Grid Search + Walk-Forward (GridSearchPanel) | El usuario configura y lanza un grid search exhaustivo con walk-forward validation | ✅ Unit / ✅ E2E | `frontend/src/components/GridSearchPanel.test.jsx` (49 tests), `BacktestGridWalkForwardUiE2eTest.java`, `BacktestGridSearchHttpE2eTest.java` |
| Walk-forward fold results | El usuario ve los resultados por fold del walk-forward dentro del panel | ✅ Unit | `frontend/src/components/GridSearchPanel.test.jsx` |
| Apply grid-search to memory | El usuario aplica los parámetros óptimos del grid search a la ticker-memory | ✅ Unit | `frontend/src/hooks/useGridSearch.test.js` (13 tests, ~90%) |
| Promote risk params a ticker-memory | El usuario promueve los parámetros de riesgo del backtest como overrides permanentes | ✅ E2E | `BacktestInteractionTest.java`, `BacktestGridSearchWebMvcTest.java` |
| Formateo de valores (USD, PnL coloring, lookback→date) | Los valores monetarios y PnL se formatean y colorean correctamente en la UI | ✅ Unit | `frontend/src/utils/backtestFormatters.test.js` (97%) |
| Scan row sort (scan activity feed) | Los tickers del feed de scan se ordenan por prioridad HOT | ✅ Unit | `frontend/src/utils/scanRowSort.test.js` (100%) |

### Health Page

| Funcionalidad | Descripción usuario | Cobertura | Test files |
|---|---|---|---|
| Ver estado de salud del sistema | El usuario ve si la app está UP/DOWN | ✅ E2E | `src/test/java/com/fgiaquinta/optionsquant/e2e/HealthEndpointTest.java` (5 tests) |
| HealthPage React component | El usuario ve la página `/health` con el contenido renderizado | ❌ Sin cobertura | — |
| UI styling / responsive layout | La UI se ve correctamente con el tema de colores | ✅ E2E | `src/test/java/com/fgiaquinta/optionsquant/e2e/UiStylingTest.java` (4 tests) |

### API Client (frontend)

| Funcionalidad | Descripción usuario | Cobertura | Test files |
|---|---|---|---|
| Todas las llamadas HTTP (liveApi, replayApi, accountApi, tickerConfigApi, backtestApi) | Los clientes HTTP del frontend llaman a los endpoints correctos | ✅ Unit | `frontend/src/api.test.js` (full coverage) |
| localStorage get/set/remove (storage.js) | El frontend persiste y recupera datos del almacenamiento local | ✅ Unit | `frontend/src/utils/storage.test.js` (100%) |

---

## BACKEND

### Scan Engine — Core

| Funcionalidad | Descripción usuario | Cobertura | Test files |
|---|---|---|---|
| GET /live-ui/status — estado completo del scanner | La UI obtiene el estado actual (isScanning, tickers, progreso, toggles) | ✅ Unit | `LiveModeControllerStatusTest.java` |
| POST /live-ui/scan-now — lanzar scan manual | El usuario lanza un scan; respeta market hours y bloquea doble-scan | ✅ E2E | `LiveModeInteractionTest.java` |
| POST /live-ui/stop-scan | El usuario detiene el scan en curso | ✅ E2E | `LiveModeInteractionTest.java` |
| POST /live-ui/force-stop — reset total de estado | El usuario fuerza un reset cuando la app queda inconsistente | ⚠️ Parcial | Endpoint validado vía E2E; lógica interna sin test unitario directo |
| POST /live-ui/toggle-extended-hours | Toggle de horario extendido | ✅ E2E | `LiveModeInteractionTest.java` |
| POST /live-ui/toggle-scheduler | Toggle del scheduler automático | ❌ Sin cobertura | — |
| POST /live-ui/toggle-mock-market | Toggle mock market open | ❌ Sin cobertura | — |
| POST /live-ui/toggle-auto-execute | Toggle auto execute | ❌ Sin cobertura | — |
| POST /live-ui/toggle-macro-filter | Toggle macro filter | ❌ Sin cobertura | — |
| POST /live-ui/set-max-concurrent | Configurar concurrencia del scanner (1–16) | ❌ Sin cobertura | — |
| POST /live-ui/set-risk | Configurar riesgo por trade en runtime | ❌ Sin cobertura | — |
| GET /live-ui/scan-scores — breakdown HYBRID | Retorna los scores de priorización por ticker (fundamentos + memoria) | ✅ Unit | `LiveModeControllerScanScoresTest.java` |
| ScanPrioritizationService — HYBRID 0.65/0.35 | El scorer calcula el score híbrido fondo+memoria para priorizar el orden de scan | ✅ Unit | `ScanPrioritizationServiceTest.java` |
| ScannerConcurrencyTest — exclusive lock | El scanner respeta la exclusividad del lock bajo concurrencia | ✅ Unit | `ScannerConcurrencyTest.java` |
| MarketScanner.isUSMarketOpen (9:30–16:00 ET) | El scanner detecta correctamente si el mercado está abierto | ✅ Unit | `MarketScannerScanScoresTest.java` |

### Scan Engine — Signals

| Funcionalidad | Descripción usuario | Cobertura | Test files |
|---|---|---|---|
| GET /live-ui/signals — listado de señales | La UI obtiene las señales activas (live o replay) con filtro de stale | ✅ Unit | `LiveModeControllerStaleSignalTest.java` |
| Stale signal guard (>30min bloquea execute) | El sistema rechaza ejecutar señales de más de 30 minutos | ✅ Unit | `LiveModeControllerStaleSignalTest.java` |
| DELETE /live-ui/signal — eliminar fila | El sistema permite eliminar una fila solo si no hay posición abierta | ⚠️ Parcial | Lógica cubierta indirectamente; sin test dedicado |
| POST /live-ui/signals/clear-stale | Eliminar en batch todas las señales stale sin posición abierta | ❌ Sin cobertura | — |
| POST /live-ui/signals/batch-delete | Eliminar múltiples señales por ticker en un solo request | ❌ Sin cobertura | — |
| GET /live-ui/scan-activity — feed de actividad | La UI ve el feed de tickers en scanning con tiempos de inicio/fin | ❌ Sin cobertura | — |
| Inject Mock Signal | Inyectar señal simulada (para testing/demo sin TWS) | ❌ Sin cobertura | — |

### Trading — Ejecución de Órdenes

| Funcionalidad | Descripción usuario | Cobertura | Test files |
|---|---|---|---|
| POST /live-ui/execute-signal — ejecutar señal | El usuario envía una orden bracket (entry + TP + SL) a TWS | ❌ Sin cobertura | — (solo TradingService cubierto de forma unitaria) |
| POST /live-ui/close-trade — cerrar trade | Cancela órdenes TP/SL en TWS (o hace cierre local si no hay IDs) | ✅ Unit | `LiveModeControllerCloseTradeTest.java` |
| POST /live-ui/cancel-trade — cancelar orden | Cancela una orden pendiente en TWS por orderId | ❌ Sin cobertura | — |
| OrderExecutionService — bracket order + contractDetails | Construye y envía la orden bracket y recupera el mejor conId | ✅ Unit | `OrderExecutionServiceTest.java` |
| TradingService — scanAndExecute + executeSignal | Orquesta el flujo scan→signal→orden y maneja fallo de ejecución | ✅ Unit | `TradingServiceTest.java` |
| AccountManager — balance, risk rule 2%, positions snapshot | Gestiona balance, cantidad de contratos y snapshot de posiciones | ✅ Unit | `AccountManagerTest.java` |
| PositionPollingScheduler — polling periódico de posiciones | Sincroniza el snapshot de posiciones con TWS cada N segundos | ✅ Unit | `PositionPollingSchedulerTest.java` |
| POST /api/trading/scan-and-execute | Endpoint legacy: escanea y ejecuta en una sola llamada | ❌ Sin cobertura | — |
| POST /api/trading/execute | Endpoint legacy: ejecuta una señal individual | ❌ Sin cobertura | — |
| GET /api/trading/account-status | Retorna balance, riesgo límite y número de trades activos | ❌ Sin cobertura | — |

### Posiciones Externas

| Funcionalidad | Descripción usuario | Cobertura | Test files |
|---|---|---|---|
| GET /live-ui/external-positions — listar posiciones externas | La UI ve posiciones abiertas en TWS que la app no registró | ✅ Unit / ✅ Integration | `LiveModeControllerExternalPositionsTest.java`, `ExternalPositionsIntegrationTest.java` |
| POST /live-ui/external-positions/{ticker}/close — SELL inmediato | El usuario cierra una posición externa con market order | ✅ Unit / ✅ Integration | `LiveModeControllerExternalPositionsTest.java`, `ExternalPositionsIntegrationTest.java` |
| POST /live-ui/external-positions/{ticker}/schedule-close-1450 — cierre condicional | El usuario programa el cierre automático a las 14:50 ET | ✅ Unit | `LiveModeControllerExternalPositionsTest.java` |
| ExternalPositionDto serialización JSON | El DTO se serializa con snake_case y campos correctos | ✅ Unit / ✅ Integration | `ExternalPositionDtoTest.java`, `ExternalPositionsIntegrationTest.java` |
| PositionSnapshot correctness | El snapshot captura símbolo, secType, cantidad y avgCost | ✅ Unit | `PositionSnapshotTest.java` |
| Startup check de posiciones externas tras restart | La app loguea un WARNING si hay posiciones en TWS pero ningún trade registrado | ✅ Unit | `LiveModeControllerStartupListenerTest.java` |

### Replay Mode (Backend)

| Funcionalidad | Descripción usuario | Cobertura | Test files |
|---|---|---|---|
| POST /live-ui/replay/start — iniciar replay | El usuario inicia la reproducción de una fecha histórica con una velocidad | ✅ Unit | `LiveModeControllerReplayTest.java` |
| POST /live-ui/replay/stop — detener replay | El usuario detiene la reproducción y limpia las señales de replay | ✅ Unit | `LiveModeControllerReplayTest.java` |
| PUT /live-ui/replay/speed — cambiar velocidad | El usuario cambia la velocidad del replay en tiempo real | ✅ Unit | `LiveModeControllerReplayTest.java` |
| GET /live-ui/replay/status — estado del replay | La UI refleja si el replay está activo, el tiempo virtual y la velocidad actual | ✅ Unit | `LiveModeControllerReplayTest.java` |
| GET /live-ui/account-mode — PAPER/LIVE chip | La UI muestra si la cuenta conectada es paper o real | ✅ Unit | `LiveModeControllerReplayTest.java` |
| ReplayService — start/stop/setSpeed/validación horaria | El servicio rechaza replay durante horario de mercado y gestiona el estado | ✅ Unit | `ReplayServiceTest.java` |
| ReplayClock — snapshot/activate/deactivate | El reloj virtual avanza correctamente durante el replay | ✅ Unit | `ReplayClockTest.java` |
| ReplayScheduler — ticks y progreso | El scheduler emite ticks en la cadencia correcta según la velocidad | ✅ Unit | `ReplaySchedulerTest.java` |
| ReplayCandleSource — carga desde CSV para una fecha | El source devuelve las velas históricas del CSV para la fecha seleccionada | ✅ Unit | `ReplayCandleSourceTest.java` |
| ReplayOrderGate — bloqueo de órdenes reales durante replay | Las órdenes reales quedan bloqueadas mientras el replay está activo | ✅ Unit | `ReplayOrderGateTest.java` |
| SignalReplayField — flag replay en señales | Las señales generadas durante replay se marcan con `replay=true` | ✅ Unit | `SignalReplayFieldTest.java` |
| E2E replay happy path (toggle mock + start + stop) | El flujo completo desde UI: activar mock market, iniciar y detener replay | ✅ E2E | `ReplayControlsE2eTest.java` (tag: tws-paper) |

### Configuración de Tickers

| Funcionalidad | Descripción usuario | Cobertura | Test files |
|---|---|---|---|
| GET /api/ticker-config — configuración completa | La UI carga el universo, lista HOT y fundamentales de todos los tickers | ❌ Sin cobertura | — |
| PUT /api/ticker-config — guardar configuración | El usuario guarda cambios al universo, lista HOT y fundamentales | ❌ Sin cobertura | — |
| DELETE /api/ticker-config/symbol/{symbol} — eliminar ticker | El usuario elimina un ticker del universo y de la lista HOT | ❌ Sin cobertura | — |
| GET /api/ticker-config/hot-ticker-count | Retorna el hot-ticker-count efectivo (runtime override vs yml default) | ✅ Unit | `TickerConfigControllerHotTickerCountTest.java` |
| PUT /api/ticker-config/hot-ticker-count | Persiste un override del hot-ticker-count (validación 1–100) | ✅ Unit | `TickerConfigControllerHotTickerCountTest.java` |
| GET /api/ticker-config/validate?symbol= | Valida un ticker contra TWS antes de agregarlo al universo | ⚠️ Parcial | BUG fix (L63) cerrado pero sin test de regresión automatizado |
| TickerService — RUNTIME-WINS GUARD para HOT | El servicio respeta la prioridad: runtime > yml cuando la lista HOT está presente | ✅ Unit | `TickerServiceTest.java` |
| IbkrProperties — validación de configuración | La configuración de IBKR (host, port, risk, etc.) se valida al arranque | ✅ Unit | `IbkrPropertiesTest.java` |

### Backtest Engine

| Funcionalidad | Descripción usuario | Cobertura | Test files |
|---|---|---|---|
| Grid Search — EXHAUSTIVE + validación de axes | El servicio ejecuta todas las combinaciones de parámetros y valida axes conocidos | ✅ Unit | `GridSearchServiceTest.java` |
| Grid Search — HTTP contract (POST /backtest-ui/grid-search) | El endpoint rechaza bodies inválidos (axes desconocidos, tickers vacíos) con 400 | ✅ Integration / ✅ E2E | `BacktestGridSearchWebMvcTest.java`, `BacktestGridSearchHttpE2eTest.java` |
| Walk-Forward validation | El grid search soporta walk-forward con folds train/test/step configurables | ✅ Unit / ✅ E2E | `GridSearchServiceTest.java`, `BacktestGridWalkForwardUiE2eTest.java` |
| Promote risk params (dryRun vs persist) | El usuario puede hacer dry-run o persistir los parámetros de riesgo óptimos a memoria | ✅ Unit | `PromoteRiskServiceTest.java` |
| TickerMemoryProfileMap — aprendizaje por perfil | El mapa de perfiles persiste y recupera correctamente los overrides por ticker+estrategia | ✅ Unit | `TickerMemoryProfileMapTest.java` |
| POST /backtest-ui/run + stop + running check | Ejecutar, detener y consultar estado del backtest en curso | ✅ E2E | `BacktestInteractionTest.java` (slow tag) |
| POST /backtest-ui/improve/{strategy} + retest | Analizar y mejorar una estrategia, luego retestarla | ✅ E2E | `BacktestInteractionTest.java` |
| Backtest scheduler auto-run stability | El backtest auto-run no produce errores de consola ni rompe la UI en soak | ✅ E2E | `BacktestAutoRunStabilityTest.java` |

### Estrategias de Trading

| Funcionalidad | Descripción usuario | Cobertura | Test files |
|---|---|---|---|
| Estrategias C5ContinuationCall / P5ContinuationPut | Las estrategias detectan correctamente el patrón de continuación | ✅ Unit | `StrategyUnitTest.java` |
| C1SqueezeCallStrategy — Worden Stochastic | La estrategia C1 detecta squeeze breakouts usando el indicador de Worden | ✅ Unit | `StrategyUnitTest.java` |
| RiskCalculator — TP/SL a partir de ATR | El calculador de riesgo calcula correctamente los niveles TP/SL usando ATR | ✅ Unit | `RiskCalculatorTest.java` |
| CandlestickPatternDetector — detección de patrones | El detector identifica hammer, engulfing y otros patrones de velas | ✅ Unit | `CandlestickPatternDetectorTest.java` |
| ConditionBuilder — TimeCondition 14:50 ET | El builder crea la condición de tiempo para el cierre condicional correctamente | ✅ Unit | `ConditionBuilderTest.java` |
| ContractFactory — construcción de contratos STK/OPT | La factory construye contratos IBKR correctamente para stocks y opciones | ✅ Unit | `ContractFactoryTest.java` |
| 12 estrategias completas (C1–C6, P1–P6) | Todas las estrategias de call y put están correctamente implementadas | ⚠️ Parcial | Solo C1, C5, P5 con tests directos; resto cubierto indirectamente vía backtest engine |

### Datos de Mercado (Candles)

| Funcionalidad | Descripción usuario | Cobertura | Test files |
|---|---|---|---|
| POST /api/candles/download — descargar candles de IBKR | El sistema descarga datos históricos para un ticker/timeframe desde TWS | ❌ Sin cobertura | — |
| POST /api/candles/download-all — todos los timeframes | Descarga todos los timeframes para un ticker | ❌ Sin cobertura | — |
| POST /api/candles/download-all-tickers — universo completo | Descarga datos de todos los tickers configurados | ❌ Sin cobertura | — |
| GET /api/candles/local — cargar desde CSV | Lee candles históricos del CSV local | ✅ Unit | `CandleCsvServiceTest.java` |
| GET /api/candles/local/exists | Verifica si existen datos locales para un ticker/timeframe | ✅ Unit | `CandleCsvServiceTest.java` |
| Candle domain object — validación y construcción | El objeto de dominio Candle se construye y valida correctamente | ✅ Unit | `CandleTest.java` |

### Backup y Configuración

| Funcionalidad | Descripción usuario | Cobertura | Test files |
|---|---|---|---|
| POST /api/backup — guardar snapshot de settings | La app persiste en el servidor los settings del localStorage como backup | ❌ Sin cobertura | — |
| GET /live-ui/market-status — estado del mercado | La app retorna la sesión actual (REGULAR/OPEN EXT/CLOSED) y segundos al próximo open | ❌ Sin cobertura | — |
| GET /live-ui/tws-status — estado de conexión TWS | La UI ve el estado detallado de las 3 conexiones IBKR con auto-reconnect | ❌ Sin cobertura | Solo TwsPaperConnectivityTest requiere TWS real |

### Infraestructura y Health

| Funcionalidad | Descripción usuario | Cobertura | Test files |
|---|---|---|---|
| GET /actuator/health — liveness/readiness probes | La app reporta UP con los grupos liveness y readiness | ✅ E2E | `HealthEndpointTest.java` |
| Conectividad TWS real (paper account) | La app se conecta correctamente a TWS paper y reporta accountConnected=true | ✅ E2E | `TwsPaperConnectivityTest.java` (tag: tws-paper, requiere TWS) |
| MarketScanner scan scores (scheduled scan) | El scanner programado calcula y almacena scores correctamente al arrancar el scan | ✅ Unit | `MarketScannerScanScoresTest.java` |

---

## Resumen

| Sección | Funcionalidades | ✅ Cubierto | ⚠️ Parcial | ❌ Sin cobertura |
|---|---|---|---|---|
| Frontend — Live Dashboard | 31 | 28 (90%) | 2 | 1 |
| Frontend — Configuración/Settings | 12 | 9 (75%) | 2 | 1 |
| Frontend — Backtest | 15 | 14 (93%) | 0 | 1 |
| **Frontend total** | **58** | **51 (88%)** | **4** | **3** |
| Backend — Scan Engine | 15 | 8 (53%) | 1 | 6 |
| Backend — Trading/Órdenes | 10 | 4 (40%) | 0 | 6 |
| Backend — Posiciones Externas | 6 | 6 (100%) | 0 | 0 |
| Backend — Replay Mode | 12 | 12 (100%) | 0 | 0 |
| Backend — Ticker Config | 8 | 4 (50%) | 1 | 3 |
| Backend — Backtest Engine | 8 | 8 (100%) | 0 | 0 |
| Backend — Estrategias | 6 | 3 (50%) | 2 | 1 |
| Backend — Candles | 6 | 2 (33%) | 0 | 4 |
| Backend — Backup/Infra | 3 | 1 (33%) | 0 | 2 |
| **Backend total** | **74** | **48 (65%)** | **4** | **22** |
| **TOTAL** | **132** | **99 (75%)** | **8** | **25** |

---

## Gaps críticos — Riesgo operacional

### Frontend
1. **`SettingsPage` tabs** — 0% coverage directo de la página
2. **`useScanSettings`** — branches de error y mock-market sin cubrir

### Backend — CRÍTICO
1. **`POST /live-ui/execute-signal`** — el flujo end-to-end de ejecución de órdenes a TWS no tiene test de integración
2. **`GET /live-ui/tws-status`** — el endpoint de estado de conexión con auto-reconnect no tiene test propio
3. **`POST /api/candles/download*`** (3 endpoints) — falla silenciosa posible en la descarga desde IBKR
4. **`POST /api/backup`** — el backup de settings del frontend no tiene test
5. **Toggles de runtime** (scheduler/mock-market/auto-execute/macro-filter/set-risk/set-max-concurrent) — 6 endpoints sin test unitario
6. **9 estrategias de las 12** — C2–C4, C6, P1–P4, P6 solo cubiertos indirectamente vía backtest engine
7. **`GET /api/ticker-config/validate`** — fix del BUG en L63 sin test de regresión automatizado
