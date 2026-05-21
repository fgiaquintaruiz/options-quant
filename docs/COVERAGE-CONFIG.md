# Coverage: exclusions and commands

This repo measures coverage in **three layers** (JVM, React unit, Python). Percentages **are not comparable across layers** (different tools and scope).

---

## Java (JaCoCo)

There are **two JVM reports**:

| Command | What it runs | HTML/XML report |
|--------|-------------|------------------|
| `.\gradlew unitCoverageReport` | Only `:test` + `jacocoUnitOnlyReport` (fast) | `build/reports/jacoco-unit/html`, `build/reports/jacoco-unit/jacoco.xml` |
| `.\gradlew coverageReport` | `:test` + `e2eTest` + `slowTest` + `jacocoTestReport` (slow; merges all `.exec` files) | `build/reports/jacoco/test/html` |

The **unit-only report** percentage (~**79%** instructions with the current exclusion set) primarily covers `backtest/grid` (grid services), `strategy/utils`, and some `config`. It is not intended to cover every line of IBKR, the full backtest engine, or REST; those are handled by e2e/slow tests and documented exclusions.

### Report exclusions (`build.gradle.kts`, `jacocoClassExcludes` + rules in `jacocoMainClassDirectories()`)

These are NOT excluded from **compilation** or tests: they only stop counting toward the JaCoCo **total** where applicable.

| Area | Reason |
|------|--------|
| `OptionsQuantApplication` | Spring Boot startup only |
| `com.fgiaquinta.optionsquant.dto.*` | Transport DTOs / records |
| `com.fgiaquinta.optionsquant.cli.*` | Interactive CLI (`BacktestCli`) |
| `com.fgiaquinta.optionsquant.infrastructure.*` | IBKR callbacks; requires TWS / heavy mocks |
| `*IbkrProperties`, `*GridSearchProperties`, `*ScannerProperties` (outer class) | Configuration beans (getters/setters) |
| Various records in `backtest.grid` (e.g. `GridSearchRequest`, `WalkForwardFoldResult`, …) | Result DTOs; core logic remains in `GridSearchService` / `PromoteRiskService` |
| `com.fgiaquinta.optionsquant.service/**` | IBKR layer, scanning, I/O — validated by e2e / manual |
| `backtest/engine/**`, `backtest/domain/**`, `domain/**`, `trading/**` | Backtest engine and models; exercised by slow/e2e |
| `com.fgiaquinta.optionsquant/controller/**` | REST; HTTP surface covered by WebMvc/e2e, not by JaCoCo unit target |
| `CandleDownloader`, `StartupInitializer`, `ChartController` | Startup / utilities |
| `WebConfig$*` | Anonymous SPA resolver (covered by Playwright) |
| `strategy/*` implementations (excluding `strategy/utils`, `model`, `data`, `indicator`) | Strategies run via engine; shared utils kept in the report |

---

## Frontend (Vitest + v8)

**Command:** `cd frontend && npm run test:coverage` or `.\gradlew frontendCoverage`  
Report: `build/reports/coverage-frontend/`

### Scope

Only these are included in the report:

- `src/api.js` — HTTP client toward Spring  
- `src/utils/**/*.js` — pure utilities  

**Intentionally excluded:** `pages/`, `components/`, `App.jsx`, `main.jsx` — the UI is validated with **Playwright** in the JVM suite (`e2eTest`), not with React component tests.

---

## Python (pytest-cov)

**Command:** `cd python && py -m pytest analytics_service --cov=analytics_service --cov-config=.coveragerc`  
or `.\gradlew pythonCoverage`

Config: `python/.coveragerc` (omits tests and `__init__.py`).

### Notes

- CI/local target: **`analytics_service` at 100%** lines and branches (`pytest-cov` with branch coverage), via `test_engine.py` (engine) and `test_main.py` (FastAPI + `JavaApiClient`). The best-effort connection `try` on module import uses `# pragma: no cover`.
- `analytics_service/main.py`: HTTP tests with `TestClient` and `java_client` mocks.  
- `ui_service` (Streamlit) is **not** part of the `analytics_service` package; it is not included in this report (exploratory UI, no automated tests here).

### Useful notes for JSON

- Indicator response: safe serialization of `NaN`/`Inf` via `DataFrame.to_json` + `json.loads`.  
- Metrics: native Python numeric values and infinite `profit_factor` → `null` in JSON.

---

## Everything in one pass

**Fast (recommended for local/CI):** JVM unit-only + Vitest + Python

```text
.\gradlew fullStackCoverage
```

Equivalent to `unitCoverageReport` + `frontendCoverage` + `pythonCoverage` (without `slowTest`).

**Slow (nightly / when you want to merge e2e+slow into JaCoCo):**

```text
.\gradlew coverageReport frontendCoverage pythonCoverage
```

or `.\gradlew fullStackCoverageMerged`.
