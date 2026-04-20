# Design: Python Analytics Sidecar + closePositionViaConditions Fix

## Technical Approach

Completar el Python analytics sidecar existente (FastAPI + Streamlit) y corregir el bug `closePositionViaConditions` en Java. El sidecar ya existe con ~1,200 LOC de scaffolding; el trabajo es wiring real de datos desde Streamlit hacia los endpoints `/live-ui/**` y `/backtest-ui/**` de Java, más implementar el grid-search optimizer. El bug fix persiste el contrato de opción + cantidad en memoria al colocar brackets, y los usa para colocar el market sell en el close path.

## Architecture Decisions

### Decision 1: Streamlit vs React Roles

**Choice**: Opción (a) — Streamlit permanece como analytics/research surface; React permanece como trading surface. Roles no overlapearon.

**Alternatives considered**:
- Opción (b): Streamlit removido, FastAPI solo sirve analytics, React gana nueva tab
- Ambas mantener pero con features redundantes

**Rationale**:
1. **Sin overlap real**: React (puerto 3000) cubre live trading, signals, execution, scanning. Streamlit (puerto 8501) cubre analytics, backtesting placeholder, parameter optimization placeholder.
2. **Código existente**: Streamlit ya tiene ~450 LOC funcionando (`ui_service/main.py`). React dashboard es más complejo y ninguna feature de Streamlit justifica reimplementarse en React.
3. **Mantainance burden bajo**: ~450 LOC de Streamlit vs código React más grande. Agregar ~200-300 LOC para wiring no duplica valor significativo.
4. **Usuario (Fabio)**: Usa React para trading en producción. Streamlit es para research/analytics, no para execution.

---

### Decision 2: Python → Java Transport

**Choice**: REST-only. gRPC permanece como future work.

**Alternatives considered**:
- Scoped gRPC implementation en esta change
- REST + gRPC híbrido

**Rationale**:
1. **REST funciona**: `python/ui_service/main.py` ya usa `httpx` para calls a Java. Zero changes needed en Java REST contract.
2. **gRPC no implementado**: Los protos existen (`protos/market_data.proto`, `protos/order_status.proto`) pero no hay server-side implementation en Java. El test directory refiere `MarketDataGrpcService` pero no existe producción.
3. **Scope creep avoidance**: Esta change ya tiene 3 deliverables (Streamlit wiring, grid-search, closePosition fix). Agregar gRPC duplica el scope sin beneficio para Fabian.
4. **Action**: Remover `grpcio` y `grpcio-tools` de `python/requirements.txt`. Mover `protos/` a `docs/future-work/` o marcar como "deferred".

---

### Decision 3: Deployment Topology

**Choice**: Docker Compose con `--profile analytics` gating.

**Alternatives considered**:
- Default-on (sin profile)
- Windows directo con TWS

**Rationale**:
1. **Profile ya existe**: `docker-compose.yml` líneas 65-66 y 84-85 declaran `profiles: [analytics]` para ambos servicios Python.
2. **Opt-in por default**: El profile permite que el usuario corra `docker compose up java-engine redis` (solo trading) sin overhead de Python.
3. **Startup documentado**:
   ```bash
   # Solo trading (Java + Redis)
   docker compose up -d

   # Con analytics (Java + Redis + FastAPI + Streamlit)
   docker compose --profile analytics up -d
   ```

---

### Decision 4: closePositionViaConditions Persistence

**Choice**: In-memory ConcurrentHashMap, crash-recoverable via IBKR open-orders query.

**Alternatives considered**:
- JSON disk-backed (`data/ticker-memory.json`)
- Redis-backed

**Rationale**:
1. **Single-process assumption**: El engine corre en un solo proceso JVM. Si el proceso muere, IBKR es la fuente de verdad para open orders.
2. **Crash recovery**: `OrderExecutionService` puede reconsultar IBKR via `IbkrService.getOpenOrders()` al restart para reconstruír el estado. No se pierde información.
3. **Simplicidad**: Ningún formato de archivo, nenhum parse, nenhum I/O a manejar. El ConcurrentHashMap en memoria es suficiente para el caso de uso.
4. **Action**: Usar `ConcurrentHashMap<String, BracketTradeInfo>` donde `BracketTradeInfo` contiene `(contract, quantity, entryPrice, timestamp)`. Guardar en el momento de `placeBracketOrder()`.

---

### Decision 5: Grid-Search Optimizer Design

**Choice**: Exhaustive grid search sobre search space definido, retorna ranked parameter set por métrica.

**Alternatives considered**:
- Random sample (fast pero coverage irregular)
- Bayesian optimization (más smart pero más complejo de implementar y testear)

**Rationale**:
1. **Bounded search space**: Las estrategias tienen parámetros discretos y bounded. SMA periods: [5, 10, 20, 50, 100, 200]. RSI period: [5, 7, 9, 14, 21]. BB std: [1.5, 2.0, 2.5]. El espacio total es pequeño (~200 combinaciones).
2. **Deterministic para testing**: Exhaustive grid con seed fijo => resultados reproducibles.
3. **Output**: Array de objetos `{params: {...}, metrics: {sharpe: X, sortino: Y, maxDrawdown: Z}}` ordenado por Sharpe descendente.

**Search Space Definido**:

| Parameter | Type | Range | Granularity |
|-----------|------|-------|-----------|
| fast_period | int | 5–50 | 5 |
| slow_period | int | 20–200 | 10 |
| rsi_period | int | 5–21 | 2 |
| bb_period | int | 10–50 | 5 |
| bb_std | float | 1.5–3.0 | 0.5 |

**Algoritmo**:

```
1. Build Cartesian product de param_grid
2. Para cada combinación:
   a. Run estrategia con params en el historical dataset
   b. Calcular métricas (sharpe, sortino, maxDrawdown)
   c. Guardar resultado
3. Ordenar por sharpe_ratio descendente
4. Return top-N resultados
```

---

### Decision 6: Streamlit → Java HTTP Contracts

**Choice**: Documentar los endpoints consumidos y lockear el response contract.

**Monitoring Page → Java Live UI Endpoints**:

| Streamlit Call | Java Endpoint | Response Shape |
|--------------|------------|--------------|
| `fetch_status()` | GET `/live-ui/status` | `{isScanning, currentTicker, signalsToday, twsConnected, ...}` |
| `fetch_signals()` | GET `/live-ui/signals` | `{signals: [...], count, signalsToday, ...}` |
| `fetch_tickers()` | GET `/live-ui/tickers` | `{allTickers: [...], hotTickers: [...], total}` |

**Backtesting Page → Java Backtest Endpoints**:

| Streamlit Call | Java Endpoint | Response Shape |
|--------------|------------|--------------|
| `run_backtest()` | POST `/backtest-ui/run` | `{results: [...], chartData: {...}, metrics: {...}}` |
| `fetch_results()` | GET `/backtest-ui/running` | `{running, checkpoint, ...}` |

**Contract Requirements**:

- `/live-ui/status` debe retornar `Map<String, Object>` con campos: `isScanning`, `currentTicker`, `signalsToday`, `twsConnected`, `autoExecute`, `riskPct`
- `/live-ui/signals` debe retornar `Map<String, Object>` con campo `signals` como `List<Map>` con campos: `ticker`, `strategy`, `direction`, `currentPrice`, `timestamp`
- `/backtest-ui/run` debe aceptar `RequestBody` con `strategy`, `symbol`, `startDate`, `endDate`, `initialCapital`, `params` y retornar backtest completo

---

## Data Flow

```
┌──────────────────┐      GET /live-ui/status       ┌────────────────────┐
│                  │ ──────────────────────────────→ │                    │
│  Streamlit      │      GET /live-ui/signals    │  Java Engine     │
│  Monitoring    │ ──────────────────────────────→ │  (Spring Boot)  │
│  Page (8501)  │      GET /live-ui/tickers   │    port 8080    │
│                  │ ─��───────────────────────→ │                    │
└──────────────────┘                          └────────────────────┘

┌──────────────────┐     POST /backtest-ui/run     ┌────────────────────┐
│                  │ ──────────────────────────────→ │                    │
│  Streamlit      │      GET /backtest-ui/running  │  Java Engine     │
│  Backtesting   │ ←───────────────────────── │                    │
│  Page (8501)  │     (SSE para progress)   │                    │
│                  │                          └────────────────────┘
└──────────────────┘

┌─────────────────────────────────────────────────────────────┐
│  closePositionViaConditions Flow                          │
│  ┌───────────────────┐     ┌────────────────────────┐  │
│  │ Bracket Placement │     │ Close Request           │  │
│  │ 1. Save contract │     │ 1. Cancel bracket       │  │
│  │    + qty to map  │     │ 2. Get stored contract │  │
│  │ 2. Place orders  │     │    + qty from map      │  │
│  │                  │     │ 3. Place MARKET sell   │  │
│  │                  │     │    with stored contract│  │
│  └───────────────────┘     └────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

## File Changes

| File | Action | Description |
|------|--------|-------------|
| `python/ui_service/main.py` | Modify | Reemplazar hardcoded placeholders con httpx calls a `/live-ui/**` y `/backtest-ui/**` |
| `python/analytics_service/engine.py` | Modify | Implementar `grid_search_optimization()` (líneas 353-379, placeholder → real) |
| `src/main/java/.../OrderExecutionService.java` | Modify | Fix `closePositionViaConditions`: persist + market close path |
| `python/requirements.txt` | Modify | Remover `grpcio`, `grpcio-tools` |
| `protos/` | Move | Mover `protos/` a `docs/future-work/` o marcar deferred |
| `docker-compose.yml` | None | Ya tiene profile configured |

## Interfaces / Contracts

### ClosePositionViaConditions Data Model

```java
public record BracketTradeInfo(
    Contract contract,      // IBKR Contract (option)
    int quantity,        // Number of contracts
    double entryPrice,   // Entry price para cálculo P&L
    ZonedDateTime entryTime
) {}
```

### Streamlit → Java Monitoring Response

```python
# GET /live-ui/status
{
    "isScanning": false,
    "currentTicker": "",
    "signalsToday": 3,
    "twsConnected": true,
    "autoExecute": true,
    "riskPct": 5.0,
    "hotTickersList": ["SPY", "QQQ", "AAPL", ...]
}

# GET /live-ui/signals
{
    "signals": [
        {"ticker": "AAPL", "strategy": "C1", "direction": "LONG", "currentPrice": 185.50, "timestamp": "2026-04-19T10:30:00Z"},
        ...
    ],
    "count": 1,
    "signalsToday": 3
}
```

## Testing Strategy

| Layer | What to Test | Approach |
|-------|-------------|----------|
| Unit | `grid_search_optimization` con mock strategy | pytest con deterministic seed + asserts en top params |
| Unit | `closePositionViaConditions` con mocked IBKR client | JUnit test verificando: (1) bracket cancel, (2) market order placed |
| Integration | Streamlit Monitoring page → Java `/live-ui/status` | Manual smoke test: valores reales vs placeholder |
| E2E | Full backtest flow: Streamlit → Java → results | Manual: mismo backtest en React y Streamlit debe coincidir |

## Migration / Rollout

No migration required. Los cambios son additive:
- Python sidecar: wiring nuevo, no hay datos que migrar
- closePosition fix: nuevo comportamiento, no hay estado legacy a convertir

Rollout:
1. Apply: Python sidecar wiring + grid-search + Java fix
2. Test: Smoke test live trading flow unchanged
3. Verify: Streamlit Monitoring muestra valores reales, no placeholders

## Open Questions

Ninguno — las 6 decisiones están tomadas y resueltas en este design.