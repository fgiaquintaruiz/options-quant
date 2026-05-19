# Testing Debt

Files without automated tests — justification and mitigation plan.

## frontend/src/components/SignalChart.jsx (agregado 2026-05-18)
- `lightweight-charts` usa DOM directo (canvas/WebGL) — no testeable con jsdom
- Estrategia: mock completo del módulo en `SignalChart.test.jsx` via `vi.hoisted()` (necesario porque `vi.mock` se hoist al top del archivo y las variables `const` no estarían disponibles aún)
- Los tests verifican que se llaman los métodos de la API del chart con los argumentos correctos (8 tests, 100% de casos de uso cubiertos)
- Visual regression requiere E2E con browser real (Playwright)
- `calcBollingerBands` es función pura — 100% testeable si se extrae a `utils/`

## frontend/src/pages/StrategiesPage.css
- **Why no tests**: Visual/layout styling only — no logic, state, or behavior
- **Risk**: Low — CSS errors are immediately visible in browser
- **Mitigation**: Manual visual review during development; future: Playwright/Percy visual regression tests

## frontend/src/App.jsx — NavLink and routing
- **Coverage note**: App.jsx overall coverage 90.71% stmts / 61.22% branch; /strategies route and NavLink covered by App.test.jsx (5 tests)
- **Uncovered branches (lines 81-86, 121-126)**: macro regime badge and market status chip conditional rendering — these require specific API state (twsStatus, macroRegime) that is not set up in the current App.test.jsx mocks
- **Not tested**: global error boundary behavior (requires simulated React error — future work)

## scripts/run-live.ps1
- **Why no tests**: PowerShell startup script — no logic, only process launch
- **Risk**: Low — script is short and manually verifiable
- **Mitigation**: Manual review; validate by running and checking log output

## com.fgiaquinta.optionsquant.strategy.config — JaCoCo exclusion
- **Why excluded**: Package `strategy/**` (except utils/model/data/indicator) excluded from JaCoCo reporting via existing rule in `build.gradle.kts`
- **Actual coverage**: 12 tests verified passing — 8 `@WebMvcTest` controller tests + 4 service `partialUpdate` unit tests. Full behavioral coverage confirmed manually.
- **Pending decision**: Include `strategy.config` in JaCoCo reporting? Current exclusion may be intentional (strategy logic is complex/volatile). Revisit when package stabilizes.
- **Risk**: Medium — excluded from automated coverage gate; rely on test count + manual review
- **Mitigation**: All new code in this package requires TDD (test-first) as standing policy
