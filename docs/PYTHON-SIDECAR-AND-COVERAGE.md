# Python sidecar, Streamlit y cobertura (Java + frontend + Python)

Este documento aclara **para qué sirve el código Python** en el repo, **cómo usarlo** en el día a día, y **cómo leer/ejecutar la cobertura** en los tres lenguajes.

---

## Resumen en una frase

- **Java (Spring Boot)** es el **núcleo operativo**: backtest, live, IBKR, grid search, API que consume el **frontend React** empaquetado en el JAR.
- **Python** es un **sidecar opcional**: servicio FastAPI de **analytics** (indicadores, métricas, Monte Carlo) y un **dashboard Streamlit** que lo consume. **No sustituye** al motor de trading ni al backtest principal.
- La **cobertura de tests** se mide por separado: JaCoCo (JVM), Vitest+v8 (React), pytest-cov (Python).

---

## ¿El Python “es útil” o es ruido?

### Cuándo **sí** tiene sentido

| Caso | Qué aporta |
|------|------------|
| Experimentar con **indicadores** (SMA, RSI, MACD, etc.) sobre series que vos mandás en JSON | `POST /api/v1/indicators` en el servicio analytics |
| Calcular **métricas agregadas** sobre listas de trades (Sharpe, Sortino, drawdown, etc.) fuera del flujo Java | `POST /api/v1/metrics` |
| **Monte Carlo** sobre retornos históricos | `POST /api/v1/monte-carlo` |
| **Prototipo de UI** en Streamlit (gráficos Plotly) sin tocar React | `python/ui_service` |
| **Investigación / notebooks mental**: pandas + numpy ya cableados | `analytics_service/engine.py` |

### Cuándo **no** es imprescindible

| Situación | Por qué |
|-----------|---------|
| Solo operás **backtest + grid + live** desde la UI React | Todo eso vive en **Java**; el frontend habla con `:9090`, no con Python. |
| No levantás el sidecar | La app principal sigue funcionando; solo faltan los endpoints `:8001` / Streamlit `:8501`. |
| Buscás **paridad estricta** con la estrategia en producción | Las reglas de negocio y el motor están en **Java**; Python no es la fuente de verdad. |

En la práctica: Python es **útil si lo usás** para analytics extra o prototipos; si nunca abrís el puerto 8001, **no está “rompiendo” nada**, simplemente es **código opcional** en el mismo monorepo.

---

## Componentes Python en el repo

### 1. `python/analytics_service/` — FastAPI (puerto por defecto **8001**)

- **`engine.py`**: `AnalyticsEngine` — indicadores, métricas de performance, Monte Carlo, grid search de laboratorio (`grid_search_optimization`).
- **`main.py`**: FastAPI, rutas `/api/v1/indicators`, `/metrics`, `/monte-carlo`, health, y proxies opcionales a Java (`/api/candles/...`, cuenta) **si** el cliente HTTP puede conectar a Spring (`JAVA_API_HOST` / `JAVA_API_PORT`, por defecto `localhost:9090`).

Variables útiles (ejemplos):

- `JAVA_API_HOST`, `JAVA_API_PORT` — hacia dónde apunta el cliente REST hacia Spring.
- `PORT` — puerto del propio FastAPI (default `8001`).

### 2. `python/ui_service/` — Streamlit (puerto típico **8501**)

- Dashboard que llama al **analytics** en `:8001` (y opcionalmente a Java vía `JAVA_API_URL`).
- Es una capa de **visualización / experimentación**, no el panel principal de producción (ese es React).

### 3. Arranque conjunto (Windows)

El script `scripts/start-full-stack.ps1` (y la tarea Gradle `startFullStack`) pueden levantar Spring + frontend build + servicios Python según cómo lo tengas configurado. Si solo corrés `./gradlew bootRun` + `npm run dev` en `frontend/`, **no necesitás Python**.

---

## Cómo ejecutar tests y cobertura

### Java (JaCoCo, fusionado test + e2e + slow)

```text
.\gradlew coverageReport
```

Informes: `build/reports/jacoco/test/html/index.html` (y XML para CI).

Tras cambios **medianamente complejos**, conviene correr este flujo completo en local para no romper integración.

### Frontend (Vitest + v8)

```text
cd frontend
npm install
npm run test:coverage
```

O desde la raíz del repo:

```text
.\gradlew frontendCoverage
```

Informes: `build/reports/coverage-frontend/index.html` (y `lcov.info`).

**Nota:** Hoy hay tests unitarios de ejemplo (`src/utils/storage.test.js`). El porcentaje **global del bundle** será bajo hasta que añadáis más tests; la UI pesada sigue cubierta sobre todo por **Playwright e2e** en Java (`e2eTest`), no por Vitest.

### Python (pytest + pytest-cov)

Desde `python/`:

```text
cd python
py -m pip install -r requirements.txt
py -m pytest analytics_service/test_engine.py --cov=analytics_service --cov-report=html:../build/reports/coverage-python/html
```

En Linux/macOS suele usarse `python3` en lugar de `py`.

O desde la raíz:

```text
.\gradlew pythonCoverage
```

**Interpretación típica:** `engine.py` tiene cobertura parcial vía tests; `main.py` (FastAPI) suele quedar **0%** hasta que existan tests HTTP (TestClient) o integración. Eso es normal en sidecars sin suite API completa.

### Todo junto (largo)

```text
.\gradlew fullStackCoverage
```

Ejecuta, en paralelo donde Gradle pueda: `coverageReport` + `frontendCoverage` + `pythonCoverage`.

---

## Dónde mirar los números (referencia rápida)

| Stack | Herramienta | Carpeta típica del informe HTML |
|--------|-------------|----------------------------------|
| Java | JaCoCo | `build/reports/jacoco/test/html/` |
| React | Vitest (v8) | `build/reports/coverage-frontend/` |
| Python | pytest-cov | `build/reports/coverage-python/html/` |

Los porcentajes **no son comparables entre lenguajes** (reglas distintas, exclusiones distintas).

---

## Próximos pasos si querés “subir” cobertura

1. **Frontend:** tests de componentes o utilidades puras (`utils/`, helpers); mocks de `fetch` para `api.js`.
2. **Python:** tests con `httpx.AsyncClient` / `TestClient` sobre `main.py` para rutas críticas; o marcar `main.py` como excluido en `.coveragerc` si decidís no testearlo aún.
3. **Java:** ya cubierto por la suite existente; el informe fusionado es el de `coverageReport`.

---

## Referencias de código

- Analytics FastAPI: `python/analytics_service/main.py`
- Motor numérico: `python/analytics_service/engine.py`
- Tests Python: `python/analytics_service/test_engine.py`
- UI Streamlit: `python/ui_service/main.py`
- Dependencias: `python/requirements.txt`
- Tareas Gradle: `build.gradle.kts` (`frontendCoverage`, `pythonCoverage`, `fullStackCoverage`)
