# Testing Debt

Files without automated tests — justification and mitigation plan.

## frontend/src/pages/StrategiesPage.css
- **Why no tests**: Visual/layout styling only — no logic, state, or behavior
- **Risk**: Low — CSS errors are immediately visible in browser
- **Mitigation**: Manual visual review during development; future: Playwright/Percy visual regression tests

## frontend/src/App.jsx — NavLink and routing
- **Coverage note**: App.jsx overall coverage 90.71% stmts / 61.22% branch; /strategies route and NavLink covered by App.test.jsx (5 tests)
- **Uncovered branches (lines 81-86, 121-126)**: macro regime badge and market status chip conditional rendering — these require specific API state (twsStatus, macroRegime) that is not set up in the current App.test.jsx mocks
- **Not tested**: global error boundary behavior (requires simulated React error — future work)
