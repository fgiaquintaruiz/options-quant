# Specification: backtest-grid-search-ticker-params

Master spec for three new capabilities. Delta: all **ADDED** (no modified legacy requirements).

---

## Capability: ticker-risk-overrides

### Requirement: Resolution order for effective risk parameters

The system MUST resolve TP/SL ATR multipliers and optional risk-per-trade for a given `(ticker, strategy display name, isCall)` using this order, first non-absent wins:

1. **Ticker + strategy** override from persisted ticker memory (see schema below).
2. **Global strategy** defaults from `RiskCalculator` static maps / config.
3. **Hard defaults** when no map entry exists.

#### Scenario: Ticker override beats global map

- GIVEN ticker `NVDA` has `strategyParams["p5 continuation"].slAtrMultiplier = 2.8`
- AND global map has `p5continuationput` SL = 2.2
- WHEN generating a plan for `NVDA`, strategy `p5 continuation`, PUT
- THEN effective SL multiplier MUST be 2.8

#### Scenario: Missing ticker override uses global

- GIVEN ticker `X` has no entry for `c1 squeeze`
- WHEN generating a plan for `X`, strategy `c1 squeeze`, CALL
- THEN effective multipliers MUST match `RiskCalculator` map for `c1squeezecall`

### Requirement: Persisted schema version

Ticker memory JSON MUST include an optional `riskParamSchemaVersion` (integer). Unknown fields MUST be ignored on read. Writers MUST bump version when breaking schema changes.

#### Scenario: Forward-compatible load

- GIVEN file has `riskParamSchemaVersion: 1` and new optional blocks
- WHEN loading on older code that only understands version 0
- THEN load MUST succeed and ignore unknown keys without crashing

### Requirement: Strategy key normalization

Overrides MUST be keyed by **canonical keys** equal to `RiskCalculator.resolveMultiplierMapKey(strategyName, isCall)` OR by a documented **display name** alias resolved at save time to canonical form. The system MUST NOT persist duplicate conflicting entries for the same logical strategy.

---

## Capability: backtest-grid-search

### Requirement: Grid definition

The grid search API (or CLI) MUST accept:

- **Universe**: ticker list + date range + scope (match last UI run, HOT, ALL, explicit list) — same semantics as existing backtest.
- **Axes**: ordered list of parameters with **finite** discrete values each (e.g. `slDelta ∈ {0, 0.2, 0.4}`, `tpDelta ∈ {0, 0.2}`).
- **Caps**: maximum total runs per request (hard limit) and optional wall-clock timeout.

#### Scenario: Reject excessive grid

- GIVEN axes produce 50,000 combinations
- AND configured cap is 500
- WHEN user submits grid job
- THEN the system MUST reject or require explicit confirmation — not run silently

### Requirement: Run execution

Each grid cell MUST run one backtest with **only** those parameter deltas applied (same engine as production backtest). Results MUST record at least: `cellId`, metrics (total PnL, win rate, trade count, max DD), and wall time.

#### Scenario: Deterministic cell identity

- GIVEN same inputs and seeds (if any)
- WHEN running the same cell twice
- THEN recorded metrics SHOULD match within floating-point tolerance

### Requirement: Output format

Results MUST be exportable as **CSV** (minimum) with one row per cell. JSON MAY be supported for UI consumption.

---

## Capability: backtest-param-optimization

### Requirement: Objective and constraints

Optimization MUST define:

- **Primary metric** (e.g. total PnL, Sharpe-like proxy, profit factor).
- **Constraints**: minimum trades per cell, maximum max-DD (optional), non-worse than baseline (optional).

#### Scenario: No winner below minimum trades

- GIVEN constraint `minTrades >= 10`
- AND best PnL cell has 3 trades
- THEN the system MUST report **no admissible winner** or fall back to next-best admissible cell

### Requirement: Phase 1 algorithms

The first implementation MUST support:

- **Exhaustive grid** (small grids only, within cap).
- **Random search** sample of N cells from Cartesian product or defined distributions.

MAY defer: Bayesian optimization, genetic algorithms.

### Requirement: Human gate for promotion

The system MUST NOT write ticker-level overrides to production memory **without** an explicit “promote” or API flag. Preview runs MAY use ephemeral or draft storage.

#### Scenario: Preview vs promote

- GIVEN optimization finds candidate params for `AAPL` + `p1 squeeze`
- WHEN user runs “preview only”
- THEN `data/ticker-memory.json` MUST remain unchanged
- WHEN user calls “promote”
- THEN persisted overrides MUST update atomically

---

## Related

- Proposal: `.atl/sdd/backtest-grid-search-ticker-params/proposal.md`
