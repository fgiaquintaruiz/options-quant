# Cobertura: exclusiones y comandos

Este repo mide cobertura en **tres capas** (JVM, React unitario, Python). Los porcentajes **no son comparables entre capas** (herramientas y alcance distintos).

---

## Java (JaCoCo)

Hay **dos informes JVM**:

| Comando | Qué ejecuta | Informe HTML/XML |
|--------|-------------|------------------|
| `.\gradlew unitCoverageReport` | Solo `:test` + `jacocoUnitOnlyReport` (rápido) | `build/reports/jacoco-unit/html`, `build/reports/jacoco-unit/jacoco.xml` |
| `.\gradlew coverageReport` | `:test` + `e2eTest` + `slowTest` + `jacocoTestReport` (largo; fusiona todos los `.exec`) | `build/reports/jacoco/test/html` |

El porcentaje del **informe unitario** (~**79 %** instrucciones con el conjunto actual de exclusiones) mide sobre todo `backtest/grid` (servicios de grid), `strategy/utils` y algo de `config`. No pretende cubrir línea a línea IBKR, motor de backtest completo ni REST; eso queda en e2e/slow y en exclusiones documentadas.

### Exclusiones del informe (`build.gradle.kts`, `jacocoClassExcludes` + reglas en `jacocoMainClassDirectories()`)

No se excluyen de la **compilación** ni de los tests: solo dejan de contar en el **total** del informe JaCoCo donde aplica.

| Área | Motivo |
|------|--------|
| `OptionsQuantApplication` | Solo arranque Spring Boot |
| `com.fgiaquinta.optionsquant.dto.*` | DTOs / records de transporte |
| `com.fgiaquinta.optionsquant.cli.*` | CLI interactivo (`BacktestCli`) |
| `com.fgiaquinta.optionsquant.infrastructure.*` | Callbacks IBKR; requiere TWS/mocks pesados |
| `*IbkrProperties`, `*GridSearchProperties`, `*ScannerProperties` (clase externa) | Beans de configuración (getters/setters) |
| Varios records en `backtest.grid` (p. ej. `GridSearchRequest`, `WalkForwardFoldResult`, …) | DTOs del resultado; la lógica principal sigue en `GridSearchService` / `PromoteRiskService` |
| `com.fgiaquinta.optionsquant.service/**` | Capa IBKR, escaneo, I/O — validada por e2e / manual |
| `backtest/engine/**`, `backtest/domain/**`, `domain/**`, `trading/**` | Motor backtest y modelos; ejercidos por slow/e2e |
| `com.fgiaquinta.optionsquant/controller/**` | REST; superficie HTTP cubierta por WebMvc/e2e, no por objetivo JaCoCo unitario |
| `CandleDownloader`, `StartupInitializer`, `ChartController` | Arranque / utilidades |
| `WebConfig$*` | Resolver anónimo del SPA (cubierto por Playwright) |
| Implementaciones `strategy/*` (no `strategy/utils`, `model`, `data`, `indicator`) | Estrategias ejecutadas vía motor; se mantienen utils compartidos en el informe |

---

## Frontend (Vitest + v8)

**Comando:** `cd frontend && npm run test:coverage` o `.\gradlew frontendCoverage`  
Informe: `build/reports/coverage-frontend/`

### Alcance

Solo se incluyen en el informe:

- `src/api.js` — cliente HTTP hacia Spring  
- `src/utils/**/*.js` — utilidades puras  

**Excluido a propósito:** `pages/`, `components/`, `App.jsx`, `main.jsx` — la UI se valida con **Playwright** en la suite JVM (`e2eTest`), no con tests de componentes React.

---

## Python (pytest-cov)

**Comando:** `cd python && py -m pytest analytics_service --cov=analytics_service --cov-config=.coveragerc`  
o `.\gradlew pythonCoverage`

Config: `python/.coveragerc` (omite tests y `__init__.py`).

### Notas

- Objetivo en CI/local: **`analytics_service` al 100 %** de líneas y ramas (`pytest-cov` con branch coverage), vía `test_engine.py` (motor) y `test_main.py` (FastAPI + `JavaApiClient`). El `try` de conexión best-effort al importar el módulo usa `# pragma: no cover`.
- `analytics_service/main.py`: tests HTTP con `TestClient` y mocks de `java_client`.  
- `ui_service` (Streamlit) **no** está en el paquete `analytics_service`; no entra en este informe (UI exploratoria, sin tests automatizados aquí).

### Cambios útiles para JSON

- Respuesta de indicadores: serialización segura de `NaN`/`Inf` vía `DataFrame.to_json` + `json.loads`.  
- Métricas: valores numéricos nativos de Python y `profit_factor` infinito → `null` en JSON.

---

## Todo en una pasada

**Rápido (recomendado en local/CI):** JVM unit-only + Vitest + Python

```text
.\gradlew fullStackCoverage
```

Equivale a `unitCoverageReport` + `frontendCoverage` + `pythonCoverage` (sin `slowTest`).

**Largo (noche / cuando quieras fusionar e2e+slow en JaCoCo):**

```text
.\gradlew coverageReport frontendCoverage pythonCoverage
```

o `.\gradlew fullStackCoverageMerged`.
