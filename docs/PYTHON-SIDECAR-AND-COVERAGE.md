# Python sidecar, Streamlit, and coverage (Java + frontend + Python)

This document explains **what the Python code in this repo is for**, **how to use it** day-to-day, and **how to read/run coverage** across the three languages.

---

## Summary in one sentence

- **Java (Spring Boot)** is the **operational core**: backtest, live, IBKR, grid search, and the API consumed by the **React frontend** packaged in the JAR.
- **Python** is an **optional sidecar**: a FastAPI **analytics** service (indicators, metrics, Monte Carlo) and a **Streamlit dashboard** that consumes it. It does **not replace** the trading engine or the main backtest.
- **Test coverage** is measured separately: JaCoCo (JVM), Vitest+v8 (React), pytest-cov (Python).

---

## Is Python "useful" or just noise?

### When it **does** make sense

| Use case | What it provides |
|----------|-----------------|
| Experiment with **indicators** (SMA, RSI, MACD, etc.) on series you send as JSON | `POST /api/v1/indicators` in the analytics service |
| Calculate **aggregate metrics** on trade lists (Sharpe, Sortino, drawdown, etc.) outside the Java flow | `POST /api/v1/metrics` |
| **Monte Carlo** on historical returns | `POST /api/v1/monte-carlo` |
| **Streamlit UI prototype** (Plotly charts) without touching React | `python/ui_service` |
| **Research / mental notebooks**: pandas + numpy already wired up | `analytics_service/engine.py` |

### When it is **not required**

| Situation | Why |
|-----------|-----|
| You only run **backtest + grid + live** from the React UI | All of that lives in **Java**; the frontend talks to `:9090`, not Python. |
| You don't start the sidecar | The main app still works; only the `:8001` / Streamlit `:8501` endpoints are missing. |
| You need **strict parity** with the production strategy | Business rules and the engine are in **Java**; Python is not the source of truth. |

In practice: Python is **useful if you use it** for extra analytics or prototypes; if you never open port 8001, **nothing is broken** — it's just **optional code** in the same monorepo.

---

## Python components in the repo

### 1. `python/analytics_service/` — FastAPI (default port **8001**)

- **`engine.py`**: `AnalyticsEngine` — indicators, performance metrics, Monte Carlo, lab grid search (`grid_search_optimization`).
- **`main.py`**: FastAPI, routes `/api/v1/indicators`, `/metrics`, `/monte-carlo`, health, and optional proxies to Java (`/api/candles/...`, account) **if** the HTTP client can connect to Spring (`JAVA_API_HOST` / `JAVA_API_PORT`, default `localhost:9090`).

Useful environment variables (examples):

- `JAVA_API_HOST`, `JAVA_API_PORT` — where the REST client points toward Spring.
- `PORT` — port of the FastAPI service itself (default `8001`).

### 2. `python/ui_service/` — Streamlit (typical port **8501**)

- Dashboard that calls **analytics** at `:8001` (and optionally Java via `JAVA_API_URL`).
- It is a **visualization / experimentation layer**, not the main production panel (that is React).

### 3. Joint startup (Windows)

The `scripts/start-full-stack.ps1` script (and the `startFullStack` Gradle task) can start Spring + frontend build + Python services depending on your configuration. If you only run `./gradlew bootRun` + `npm run dev` in `frontend/`, **you don't need Python**.

---

## How to run tests and coverage

### Java (JaCoCo, merged test + e2e + slow)

```text
.\gradlew coverageReport
```

Reports: `build/reports/jacoco/test/html/index.html` (and XML for CI).

After **moderately complex** changes, it's worth running this full flow locally to avoid breaking integration.

### Frontend (Vitest + v8)

```text
cd frontend
npm install
npm run test:coverage
```

Or from the repo root:

```text
.\gradlew frontendCoverage
```

Reports: `build/reports/coverage-frontend/index.html` (and `lcov.info`).

**Note:** There are example unit tests (`src/utils/storage.test.js`). The **global bundle** percentage will be low until more tests are added; the heavy UI is still covered mainly by **Playwright e2e** in Java (`e2eTest`), not by Vitest.

### Python (pytest + pytest-cov)

From `python/`:

```text
cd python
py -m pip install -r requirements.txt
py -m pytest analytics_service/test_engine.py --cov=analytics_service --cov-report=html:../build/reports/coverage-python/html
```

On Linux/macOS, use `python3` instead of `py`.

Or from the root:

```text
.\gradlew pythonCoverage
```

**Typical interpretation:** `engine.py` has partial coverage via tests; `main.py` (FastAPI) often shows **0%** until HTTP tests (TestClient) or integration tests exist. That is normal for sidecars without a complete API test suite.

### Everything in one pass (slow)

```text
.\gradlew fullStackCoverage
```

Runs, in parallel where Gradle allows: `coverageReport` + `frontendCoverage` + `pythonCoverage`.

---

## Where to find the numbers (quick reference)

| Stack | Tool | Typical HTML report folder |
|--------|------|---------------------------|
| Java | JaCoCo | `build/reports/jacoco/test/html/` |
| React | Vitest (v8) | `build/reports/coverage-frontend/` |
| Python | pytest-cov | `build/reports/coverage-python/html/` |

Percentages **are not comparable across languages** (different rules and exclusions).

---

## Next steps if you want to "raise" coverage

1. **Frontend:** component tests or pure utilities (`utils/`, helpers); `fetch` mocks for `api.js`.
2. **Python:** tests with `httpx.AsyncClient` / `TestClient` on `main.py` for critical routes; or mark `main.py` as excluded in `.coveragerc` if you decide not to test it yet.
3. **Java:** already covered by the existing suite; the merged report is from `coverageReport`.

---

## Code references

- Analytics FastAPI: `python/analytics_service/main.py`
- Numerical engine: `python/analytics_service/engine.py`
- Python tests: `python/analytics_service/test_engine.py`
- Streamlit UI: `python/ui_service/main.py`
- Dependencies: `python/requirements.txt`
- Gradle tasks: `build.gradle.kts` (`frontendCoverage`, `pythonCoverage`, `fullStackCoverage`)
