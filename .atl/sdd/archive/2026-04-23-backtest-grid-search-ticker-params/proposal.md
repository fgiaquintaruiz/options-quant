# Proposal: Grid search, optimization, and ticker-scoped risk params

## Intent

Hoy los multiplicadores ATR y reglas de riesgo son globales por estrategia (`RiskCalculator` maps) o “trial” puntual en retest. Falta: (1) **búsqueda en rejilla / optimización** sobre un espacio de parámetros acotado con métricas objetivo; (2) **Overrides editables** por **estrategia y por ticker**, persistidos en **memoria por ticker** para que cada símbolo conserve ajustes sin tocar el código.

## Scope

### In Scope

- Modelo de **parámetros efectivos** con orden de resolución: `ticker + strategy` → `strategy (global)` → `defaults`.
- **Persistencia** en la memoria de ticker existente (`TickerMemory` / `data/ticker-memory.json` o equivalente), versionado mínimo de esquema.
- **Grid search**: definir rejillas (p. ej. SL/TP ATR deltas, risk %) y ejecutar backtests batch (misma API motor que hoy), agregando resultados por celda (PnL, Sharpe, max DD, n trades).
- **Optimización (fase 1)**: selección de “mejor” conjunto por métrica primaria + restricciones (p. ej. mín. trades); opcional **búsqueda local** o **random search** antes de algoritmos más pesados.
- **Superficie de integración**: lectura de parámetros efectivos en `RiskCalculator` (o capa fina) durante backtest y, si aplica, live.

### Out of Scope

- Auto-aplicar en vivo sin confirmación humana.
- Optimización bayesiana / genética completa (dejar hooks o fase 2).
- UI React completa (puede ser API + CLI primero); especificar en spec si mín-viable es solo backend.

## Capabilities

### New Capabilities

- `ticker-risk-overrides`: Modelo y persistencia de overrides por `(ticker, strategyKey)` y resolución frente a globales.
- `backtest-grid-search`: Ejecución batch de backtests sobre rejillas de parámetros, resultados tabulares y export.
- `backtest-param-optimization`: Selección de mejor punto bajo métrica y restricciones; extensible a estrategias futuras.

### Modified Capabilities

- None (comportamiento actual se conserva si no hay overrides; cambios son aditivos).

## Approach

1. **Esquema**: extender entradas de memoria por ticker con `strategyParams: { "<strategyKey>": { slAtrMult, tpAtrMult, riskPct?, ... } }` (nombres alineados a `resolveMultiplierMapKey`).
2. **Resolución**: helper `EffectiveRiskParams.resolve(ticker, strategyName, isCall)` usado en `generatePlan` y en sizing.
3. **Grid**: servicio que construye `N` `BacktestConfig` o reutiliza un bucle con mismos tickers/fechas y distintos overrides; ejecuta secuencial o con pool acotado; escribe resultados (CSV/SQLite opcional en fase posterior).
4. **Ticker memory**: lectura/escritura atómica del JSON; tests de migración si falta el bloque nuevo.

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `TickerMemory` / `data/ticker-memory.json` | Modified | Nuevo subdocumento por estrategia bajo cada ticker. |
| `RiskCalculator` | Modified | Fuente de multiplicadores: overrides efectivos. |
| `BacktestEngine` / config | Modified | Inyección opcional de overrides por run (grid). |
| Nuevo paquete `.../optimization` o `.../backtest/grid` | New | Orquestación grid search + resultados. |
| API / CLI | New | Disparar grid y leer mejores parámetros (REST o comando). |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| Sobreajuste por ticker | High | Mínimo de trades, walk-forward, no auto-promote a live. |
| JSON enorme / conflictos de escritura | Med | Límites por ticker, merge atómico, backup de archivo. |
| Tiempo de CPU en grids grandes | High | Rejillas acotadas, mismo “match last run” por defecto, paralelismo limitado. |

## Rollback Plan

- Feature flag o rama; si falla, desactivar lectura de overrides (degradar a maps actuales).
- Revertir migración de esquema: ignorar claves desconocidas en lector (forward-compatible).

## Dependencies

- Backtest estable y determinismo donde aplique.
- Contrato de claves de estrategia alineado con `TradingStrategy#getName()` y maps.

## Success Criteria

- [x] Un ticker puede guardar SL/TP distintos para `p5 continuation` y otro para `c1 squeeze` sin romper el resto. *(Ver `TickerMemory` + `PromoteRiskService` + tests de perfil.)*
- [x] Grid 2D (p. ej. SL × TP) produce tabla de métricas reproducible en CI con dataset mínimo o mock. *(Ver `GridSearchService` + tests `src/test/java/com/fgiaquinta/optionsquant/backtest/`.)*
- [x] Documentación del orden de resolución y del esquema JSON en spec/diseño. *(Ver `spec.md`, `design.md`, `curl-examples.md`.)*

**Next step:** ~~`sdd-spec` / `sdd-design` / `sdd-tasks`~~ → done (see `spec.md`, `design.md`, `tasks.md`). ~~`sdd-apply`~~ → implemented; UI grid modal aplica restricciones de optimización (min trades / max DD) para mitigar sobreajuste.
