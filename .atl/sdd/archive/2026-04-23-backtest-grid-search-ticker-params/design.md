# Design: Grid search, optimization, ticker-scoped risk params

## Technical approach

Implement **effective risk params** as a thin layer used by `RiskCalculator.generatePlan`: inputs are `(StrategyData, ticker, entryTime, isCall, entryPrice, strategyName)`; before applying map lookups, merge **ticker+strategy overrides** loaded from `TickerMemory` (or a dedicated `TickerRiskConfigStore` if we avoid bloating profiles).

Grid search reuses `BacktestEngine.run(BacktestConfig)` with a **per-run override carrier** already introduced for retest (`AtomicReference` deltas) — extend to **structured overrides per strategy** or pass a `Map<String, ParamOverride>` in `BacktestConfig` (preferred: explicit config over globals).

Optimization is a **loop** over grid or random samples calling the grid runner and filtering by constraints.

## Architecture decisions

### Decision: Where to persist per-ticker overrides

**Choice**: Extend `data/ticker-memory.json` with a new top-level or per-profile block `riskOverrides: { "TICKER": { "p5continuationput": { "slAtrMult": 2.8, "tpAtrMult": 3.0 } } }` OR nest under existing `TickerStrategyProfile` as optional `riskParams`.

**Alternatives**: New SQLite file; separate `risk-overrides.json`.

**Rationale**: Single file already backs `TickerMemory`; atomic save exists. Nesting under `TickerStrategyProfile` keeps `ticker::strategy` locality consistent with `recordTrade`.

### Decision: Override application order

**Choice**: `ticker+strategy` > `RiskCalculator` maps > defaults.

**Rationale**: Matches user mental model; global maps remain migration path for strategies without ticker data.

### Decision: Grid execution parallelism

**Choice**: Serial by default for grid cells; optional `maxConcurrentCells` capped (e.g. 1–2) to avoid IBKR/data-store contention.

**Rationale**: Reproducibility and resource safety; same process as current backtest pool already stresses CPU.

### Decision: API surface (v1)

**Choice**: `POST /backtest-ui/grid-search` with JSON body `{ axes, universe, constraints, maxCells }` returning job id + poll `GET .../grid-search/{id}` OR synchronous for tiny grids only.

**Alternatives**: CLI-only v1.

**Rationale**: UI can come later; REST matches existing `BacktestDashboardController` style.

## Data flow

```
JSON load → TickerMemory / profiles
                 ↓
BacktestEngine → RiskCalculator.generatePlan
                 ↑
         EffectiveRiskParams.resolve(ticker, strategyName, isCall)
                 ↑
    [Global maps] ← merge → [Ticker overrides]

GridSearchService:
  for cell in grid:
    BacktestConfig + cell overrides → run → append Row
  OptimizationService:
    filter(rows, constraints) → best cell → optional promote → write JSON (atomic)
```

## File changes (planned)

| File | Action | Description |
|------|--------|-------------|
| `EffectiveRiskParams.java` (new) | Create | Resolve overrides + maps. |
| `RiskCalculator.java` | Modify | Call resolver with ticker when available; thread-safe cell overrides via config, not only Atomic deltas. |
| `BacktestConfig.java` | Modify | Optional `RiskOverrideModel` or `Map` for grid cells. |
| `TickerStrategyProfile` / JSON DTO | Modify | Optional `riskParams` block. |
| `TickerMemory.java` | Modify | Load/save new fields; atomic promote. |
| `GridSearchService.java` (new) | Create | Build cells, cap, run loop, CSV. |
| `OptimizationService.java` (new) | Create | Select best; preview vs promote. |
| `BacktestDashboardController` or new `@RestController` | Modify | Endpoints for grid + promote. |

## Interfaces

```text
EffectiveRiskParams.resolve(String ticker, String strategyName, boolean isCall)
  → record(double slMult, double tpMult, Optional<Double> riskPct)

GridSearchRequest: universe, axes[], maxCells, metrics[]
GridSearchResult: rows[], jobStatus, csvPath?
PromoteRequest: ticker, strategyKey, slMult, tpMult, dryRun: boolean
```

## Testing

- Unit: resolution order, JSON round-trip with new fields, cap rejection.
- Integration: small 2×2 grid on two tickers with stub data or `@Tag("slow")` full engine.

## Rollback

Feature-flag endpoints; JSON reader ignores unknown keys; `BacktestConfig` defaults preserve current behavior.
