# Grid search & promote — curl examples

Base URL: `http://localhost:9090` (see `server.port` in `application.yml`).

## Deep-link (React backtest dashboard)

The SPA can open the **Grid search & promote** panel automatically:

- Hash: `http://localhost:9090/#grid-promote` (or your app path + `#grid-promote`)
- Query: `http://localhost:9090/?grid=1` — also accepts `grid=true`, `openGrid=1`

Closing the panel clears the `#grid-promote` hash so a refresh does not re-expand it.

## Grid search (synchronous)

Runs one backtest per cell. Respect **`grid-search.max-cells`** and optional **`grid-search.timeout-ms`** in `application.yml`.

```bash
curl -s -X POST "http://localhost:9090/backtest-ui/grid-search" \
  -H "Content-Type: application/json" \
  -d '{
    "tickers": ["SPY"],
    "fromDate": "2025-01-01",
    "toDate": "2025-06-01",
    "initialCapital": 50000,
    "riskPerTradePct": 0.02,
    "slippagePct": 0.005,
    "commissionPerContract": 0.65,
    "maxConcurrentTrades": 3,
    "executionTimeframe": "MIN_15",
    "includeTradePlans": true,
    "deterministicMode": false,
    "axes": [
      { "name": "tpMultiplierDelta", "values": [0.0, 0.1] },
      { "name": "slMultiplierDelta", "values": [0.0, 0.2] }
    ],
    "searchMode": "EXHAUSTIVE",
    "randomSampleCount": null,
    "randomSeed": null,
    "constraintMinTrades": null,
    "constraintMaxDrawdownPct": null,
    "primaryMetric": "TOTAL_PNL",
    "walkForwardTrainDays": null,
    "walkForwardTestDays": null,
    "walkForwardStepDays": null
  }' | jq .
```

### Walk-forward (rolling IS grid + OOS)

Same body as above, but set **`walkForwardTrainDays`** and **`walkForwardTestDays`** (calendar days per window). Optional **`walkForwardStepDays`** (default = test length). Requires **EXHAUSTIVE** mode. Response includes **`walkForwardFolds`** and **`walkForwardSummary`**; **`optimization`** is null. Uses the same **`grid-search.timeout-ms`** budget for the entire chain.

```bash
curl -s -X POST "http://localhost:9090/backtest-ui/grid-search" \
  -H "Content-Type: application/json" \
  -d '{
    "tickers": ["SPY"],
    "fromDate": "2024-01-01",
    "toDate": "2025-06-01",
    "initialCapital": 50000,
    "riskPerTradePct": 0.02,
    "slippagePct": 0.005,
    "commissionPerContract": 0.65,
    "maxConcurrentTrades": 3,
    "executionTimeframe": "MIN_15",
    "includeTradePlans": true,
    "deterministicMode": false,
    "axes": [
      { "name": "tpMultiplierDelta", "values": [0.0, 0.1] },
      { "name": "slMultiplierDelta", "values": [0.0, 0.2] }
    ],
    "searchMode": "EXHAUSTIVE",
    "randomSampleCount": null,
    "randomSeed": null,
    "constraintMinTrades": 10,
    "constraintMaxDrawdownPct": null,
    "primaryMetric": "TOTAL_PNL",
    "walkForwardTrainDays": 90,
    "walkForwardTestDays": 30,
    "walkForwardStepDays": 30
  }' | jq .
```

### Random sample from the same Cartesian product

```bash
curl -s -X POST "http://localhost:9090/backtest-ui/grid-search" \
  -H "Content-Type: application/json" \
  -d '{
    "tickers": ["SPY"],
    "fromDate": "2025-01-01",
    "toDate": "2025-06-01",
    "initialCapital": 50000,
    "riskPerTradePct": 0.02,
    "slippagePct": 0.005,
    "commissionPerContract": 0.65,
    "maxConcurrentTrades": 3,
    "executionTimeframe": "MIN_15",
    "includeTradePlans": true,
    "deterministicMode": false,
    "axes": [
      { "name": "tpMultiplierDelta", "values": [0.0, 0.1, 0.2] },
      { "name": "slMultiplierDelta", "values": [0.0, 0.15] }
    ],
    "searchMode": "RANDOM",
    "randomSampleCount": 3,
    "randomSeed": 42,
    "constraintMinTrades": 5,
    "constraintMaxDrawdownPct": 0.25,
    "primaryMetric": "PROFIT_FACTOR",
    "walkForwardTrainDays": null,
    "walkForwardTestDays": null,
    "walkForwardStepDays": null
  }' | jq .
```

## Promote risk params

Writes **absolute** ATR multipliers to `data/ticker-memory.json` (maps baseline + deltas). **`dryRun: true`** logs only.

```bash
curl -s -X POST "http://localhost:9090/backtest-ui/promote-risk-params" \
  -H "Content-Type: application/json" \
  -d '{
    "ticker": "AAPL",
    "strategyName": "p5 continuation",
    "isCall": false,
    "tpMultiplierDelta": 0.1,
    "slMultiplierDelta": 0.2,
    "dryRun": true
  }' | jq .
```

## Errors

Validation or cap exceeded: **HTTP 400** with JSON `{"error":"..."}`.
