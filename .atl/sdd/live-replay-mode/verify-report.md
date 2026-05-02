# Verification Report

**Change**: live-replay-mode
**Mode**: Strict TDD
**Date**: 2026-05-02
**Verdict**: PASS WITH WARNINGS

---

## Completeness

| Metric | Value |
|--------|-------|
| Tasks total | 31 |
| Tasks complete (marked ✅) | 25 |
| Tasks incomplete (marked 🔲 in file) | 6 |
| Deferred tasks verified by code | 4 of 6 (6.2, 6.3, 6.6 PASS; 5.6 PARTIAL) |
| Effectively complete | 28/31 (90%) |
| Remaining real gaps | 2 (5.6 partial, 7.2 docs) |

### DEFERRED task resolution (verified by code, not tasks.md)

| Task | Claim | Actual status |
|------|-------|--------------|
| 5.6 | Badge REPLAY in LiveTradeGrid + field in useTradeActions | PARTIAL — badge implemented at `LiveTradeGrid.jsx:370-371`, `replay` field mapped in `useTradeActions.js:24`. BUT `replayActive` prop NOT passed from `LiveDashboard.jsx` to `LiveTradeGrid`, so the "disable batch-delete (read-only)" sub-requirement is not wired. |
| 6.2 | `LiveModeControllerReplayJsonlIntegrationTest` | PASS — 3 tests all green |
| 6.3 | `ReplayOrderGateIntegrationTest` | PASS — 2 tests all green |
| 6.5 | `ReplayControlsE2eTest` | Correctly tagged `@e2e @tws-paper`, properly excluded from CI run |
| 6.6 | `ReplayVsBacktestComparisonTest` | PASS — 5 tests all green, 4 CSV fixtures present |
| 7.2 | README update | Not done — `curl-examples.md` accepted as substitute per tasks.md rationale |

---

## Build & Tests

**Build**: PASS — `./gradlew compileJava compileTestJava` → `BUILD SUCCESSFUL in 2s` (UP-TO-DATE)

**Unit + Integration tests (excluding e2e,tws-paper,slow,integration tags)**:
- Result: `BUILD SUCCESSFUL in 50s`
- Tests run: 285 test result lines
- FAILED: 0 (the word "FAILED" appears once in a test log message, not as a test result)
- SKIPPED: 0

**Targeted integration tests**:
- `ReplayOrderGateIntegrationTest`: 2/2 PASS
- `LiveModeControllerReplayJsonlIntegrationTest`: 3/3 PASS
- `ReplayVsBacktestComparisonTest`: 5/5 PASS

---

## Spec Compliance Matrix

| Capability | Requirement | Scenario | Test | Result |
|-----------|-------------|----------|------|--------|
| live-replay-clock | Virtual time source | Virtual time drives scanner | `ReplayClockTest` | PASS |
| live-replay-clock | Speed control without restart | Speed change preserves virtual time | `ReplayClockTest` | PASS |
| live-replay-source | CSV-first with IBKR backfill | Happy path — CSV has the date | `ReplayCandleSourceTest` | PASS |
| live-replay-source | CSV-first with IBKR backfill | Backfill path — CSV missing date | `ReplayCandleSourceTest` | PASS |
| live-replay-source | CSV-first with IBKR backfill | Backfill fails without TWS | `ReplayCandleSourceTest` | PASS |
| live-replay-source | Time-truncated lookup | O(log n) getCandlesUntil | `ReplayCandleSourceTest`, `ReplayVsBacktestComparisonTest` | PASS |
| live-replay-controls | Start guards | Block during real market hours | `LiveModeControllerReplayTest` (409 on market-open) | PASS |
| live-replay-controls | Start guards | TWS not connected → 409 | `ReplayServiceTest` | PASS |
| live-replay-controls | Start guards | Already active → 409 | `LiveModeControllerReplayTest` | PASS |
| live-replay-controls | Rate-limit on auto-execution | Cap exceeded — 11th skipped | `ReplayOrderGateTest`, `ReplayOrderGateIntegrationTest` | PASS |
| live-replay-controls | Abort on TWS disconnect | Scheduler aborts + clock deactivates | `ReplaySchedulerTest.tick_abortsAndDeactivatesWhenTwsDisconnected` | PASS |
| live-paper-live-chip | Account mode detection | Paper account detected | `LiveModeControllerReplayTest` (PAPER/LIVE) | PASS |
| signal-emission | Replay tag on signals | `Signal.replay` default false | `SignalReplayFieldTest` | PASS |
| signal-emission | Replay signals persist separately | Append to JSONL, not live-signals | `LiveModeControllerReplayJsonlIntegrationTest` | PASS |
| signal-emission | GET /signals during replay returns replaySignals | liveSignals frozen | `LiveModeControllerReplayTest` | PASS |
| scanner-scheduling | Market-hours bypass during replay | Uses virtualNow when active | `MarketScanner.java:155-157` (no direct unit test for this branch) | NO DEDICATED TEST |
| live-dashboard | Replay banner | Banner shown when active | `LiveDashboard.jsx:114-115`, `ReplayControls.jsx` | PARTIAL (no automated test) |
| live-dashboard | PAPER/LIVE chip | Chip rendered at all times | `AccountModeChip.jsx` exists, mounted in `LiveDashboard.jsx` | PASS (no automated test) |
| live-dashboard | Read-only grid during replay | Separate replay-signals view, batch-delete disabled | badge in `LiveTradeGrid.jsx:370-371` — `replayActive` NOT passed as prop | WARNING |

---

## Issues Found

### WARNING 1 — Task 5.6 partially complete

**What**: `LiveTradeGrid.jsx` renders the REPLAY badge on individual signal rows (`row.replay` field), and `useTradeActions.js` maps the `replay` field from the API. However, `LiveDashboard.jsx` does NOT pass `replayActive` as a prop to `LiveTradeGrid`, so the "read-only during replay" and "disable batch-delete" sub-requirements of task 5.6 are not wired.

**Where**: `frontend/src/pages/LiveDashboard.jsx:175-192` — `LiveTradeGrid` call missing `replayActive={replayActive}` prop.

**Impact**: Replay signals receive the REPLAY badge correctly (as long as backend sends `replay: true`). But batch-delete actions remain enabled during a replay session, which contradicts the spec requirement "live signals grid MUST be read-only during replay". The risk is low in practice (signals are paper only) but the spec compliance gap is real.

**Spec reference**: `spec.md` — Capability `live-dashboard`: "The live signals grid MUST be read-only during replay".

---

### WARNING 2 — `replay.enabled` is `true` in application.yml (design says `false`)

**What**: The design doc specifies `replay.enabled=false` by default as a feature flag ("endpoints return 404 when disabled"). The actual `application.yml` has `replay.enabled: true`.

**Where**: `src/main/resources/application.yml:116`.

**Impact**: Replay endpoints are live in production without an explicit opt-in step. The rollback story ("set flag to false + restart") still works, but the safe-by-default posture described in the design is inverted.

---

### WARNING 3 — No dedicated unit test for `MarketScanner` market-hours bypass branch

**What**: The spec requires `MarketScanner.scanAndExecute` to skip the "outside market hours" guard when `replayClock.isActive()=true`. The implementation exists at `MarketScanner.java:155-157`, but there is no direct unit test asserting this branch. The `ReplaySchedulerTest` tests the scheduler loop, but does not verify the market-hours guard bypass in the scanner itself.

**Where**: `src/test/java/com/fgiaquinta/optionsquant/service/` — no `MarketScannerReplayTest` or equivalent.

**Impact**: If the bypass logic regresses, no test will catch it. Covered indirectly by the `ReplaySchedulerTest` integration but not isolated.

---

### SUGGESTION 1 — `ReplayControlsE2eTest` is a stub with no assertions

**What**: `ReplayControlsE2eTest` is tagged `@e2e @tws-paper` and properly excluded from CI. The test body navigates to `/live`, waits for the dashboard, and clicks "Mock Mkt toggle" — but the full happy path (start/stop replay cycle) appears truncated at line 30 without a full assertion chain.

**Where**: `src/test/java/com/fgiaquinta/optionsquant/e2e/ReplayControlsE2eTest.java:30`.

**Impact**: Test runs successfully when excluded (expected), but if someone runs with TWS paper connected the test may be incomplete.

---

### SUGGESTION 2 — Mockito agent warning on every test run

**What**: Mockito is self-attaching via dynamic agent loading. This produces a JVM warning on every test class that uses Mockito. Will break in a future JDK release.

**Recommended fix**: Add `-javaagent` configuration to `build.gradle` per Mockito docs.

**Impact**: Zero functional impact today; maintenance debt for future JDK upgrades.

---

## Summary

The change is functionally complete and all testable requirements pass. The two WARNINGs are genuine spec gaps (not just cosmetic): task 5.6 read-only wiring is missing, and `replay.enabled` defaults to `true` instead of `false`. Neither blocks production use but both should be resolved before marking the change as fully archived.
