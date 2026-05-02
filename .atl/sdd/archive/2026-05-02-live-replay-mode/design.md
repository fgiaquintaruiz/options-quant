# Design: live-replay-mode

## Technical Approach

Introduce a `ReplayClock` singleton that multiplexes the "current time" source. When active, all scanner-facing time decisions (candle cutoffs, market-hours check, candle source) route through the clock. A `ReplayScheduler` advances the clock in 15-minute virtual steps at configurable speed and triggers `MarketScanner.scanAndExecute` on each step.

Candle data is served by a new `ReplayCandleSource` that reuses the existing `CandleCsvService` + `IbkrService.downloadHistoricalData` pipeline for backfill (same pattern as `StrategyScannerService.java:550-620`) and exposes a time-truncated view via `NavigableMap`.

The execution pipeline (bracket placement, Telegram) is **unchanged** — replay signals flow through to TWS paper. Safety comes from: (a) rate-limit gate `ReplayOrderGate`, (b) hard block when real market is open, (c) abort on TWS disconnect.

Signal tagging (`replay=true`) is UI/audit-only — routes signals to `replaySignals` list + `data/replay-signals-{date}-{runId}.jsonl`.

## Architecture Decisions

| Decision | Choice | Alternatives | Rationale |
|----------|--------|--------------|-----------|
| Where to inject replay | `StrategyScannerService.downloadTimeframeDelta()` branches on `replayClock.isActive()` | Interface abstraction over IbkrService | One-line branch; no refactor of IbkrService; easy to remove. |
| Clock ownership | Singleton `@Service ReplayClock` | Field on `MarketScanner`; ThreadLocal | Spring DI fits existing style; accessible from any service without passing state. |
| Scheduler mechanism | Dedicated `ReplayScheduler` with `ScheduledExecutorService` | Reuse `@Scheduled` cron | Cron can't do sub-15min steps at 360x; explicit executor gives clean start/stop. |
| Signal.replay default | `false`, compact-constructor fallback | New SignalReplay subtype | Record evolution is backward-compatible; no cascading changes. |
| Signal persistence during replay | Separate `replaySignals` CopyOnWriteArrayList + JSONL file per run | Single list with filter on read | Isolation keeps live grid frozen; audit trail per run id. |
| Paper/Live chip | Read-only endpoint `GET /live-ui/account-mode`, detect `DU*` prefix | Frontend env var; full toggle now | Matches v1 scope (chip is read-only); toggle deferred to `tws-mode-toggle` SDD. |
| Rate-limit storage | In-memory ring buffer of timestamps in `ReplayOrderGate` | Persisted counter | Resets on replay stop; no need for cross-run state. |

## Data Flow

```
[POST /live-ui/replay/start?date=...&speed=...]
        ↓
  LiveModeController
        ↓
  ReplayService.start()
        ↓
  ReplayCandleSource.preload(date)     ← CSV + IBKR backfill + saveToCsv
        ↓                                 (reuses existing pipeline)
  ReplayClock.activate(virtualNow, speed)
        ↓
  ReplayScheduler.start()
        │
        ├── loop:
        │    wait (15min / speed) + jitter(100-800ms)
        │    clock.advance(+15min)
        │    marketScanner.scanAndExecute()
        │         ↓
        │    StrategyScannerService.downloadTimeframeDelta()
        │         ↓  (branch: replayClock.isActive())
        │    ReplayCandleSource.getCandlesUntil(ticker, tf, virtualNow)
        │         ↓
        │    strategies run → Signal(replay=true) emitted
        │         ↓
        │    MarketScanner.processLiveSignalAfterScan
        │         ↓
        │    ReplayOrderGate.tryConsume() → placeOptionBracket() (TWS paper)
        │
        └── on TWS disconnect → ReplayScheduler.abort() → clock.deactivate()
```

## File Changes

| File | Action | Description |
|------|--------|-------------|
| `service/ReplayClock.java` | Create | Singleton clock with `active`, `virtualNow`, `speedMultiplier`, `runId`. |
| `service/ReplayCandleSource.java` | Create | CSV load + IBKR backfill + `NavigableMap` lookup. |
| `service/ReplayScheduler.java` | Create | `ScheduledExecutorService` loop: advance clock + trigger scan. |
| `service/ReplayOrderGate.java` | Create | Rolling 60s ring buffer; `tryConsume()` returns boolean. |
| `service/ReplayService.java` | Create | Orchestrator: start/stop/status, coordinates above 4 services. |
| `service/StrategyScannerService.java:766` | Modify | Branch in `downloadTimeframeDelta` to `ReplayCandleSource` when active. |
| `service/MarketScanner.java:144` | Modify | Skip market-hours guard when `replayClock.isActive()`. |
| `service/MarketScanner.java` (tryAutoExecute) | Modify | Gate through `ReplayOrderGate` when active. |
| `service/MarketScanner.java` (addLiveSignal) | Modify | Route `replay=true` signals to `replaySignals` list + JSONL. |
| `domain/Signal.java` (in StrategyScannerService:933) | Modify | Add `boolean replay` field with backward-compatible constructor. |
| `controller/LiveModeController.java` | Modify | Add `/live-ui/replay/{start,stop,status,speed}` + `/live-ui/account-mode`. |
| `config/IbkrProperties.java` | Modify | Add helper `boolean isPaperAccount()` (checks `accountId.startsWith("DU")`). |
| `application.yml` | Modify | Add `replay.enabled`, `replay.max-orders-per-minute`, `replay.jitter-ms-min/max`. |
| `frontend/src/components/ReplayControls.jsx` | Create | Date picker + speed slider (30x/60x/180x/360x) + start/stop + status. |
| `frontend/src/components/AccountModeChip.jsx` | Create | Green PAPER / red LIVE pill. |
| `frontend/src/pages/LiveDashboard.jsx` | Modify | Mount chip in header; mount `ReplayControls` + banner when active. |
| `frontend/src/components/LiveTradeGrid.jsx` | Modify | Show replay signals with badge when `replayActive=true`. |
| `frontend/src/api.js` | Modify | Add `replayApi` + `accountApi.getMode()`. |
| `data/replay-signals-{date}-{runId}.jsonl` | Create (runtime) | Append-only audit of replay signals per run. |

## Interfaces / Contracts

```java
@Service public class ReplayClock {
    public record State(boolean active, ZonedDateTime virtualNow, int speed, String runId) {}
    public State snapshot();
    public ZonedDateTime getNow();                 // virtualNow if active, else ZonedDateTime.now()
    public void activate(ZonedDateTime from, int speed, String runId);
    public void advance(Duration delta);
    public void setSpeed(int speed);
    public void deactivate();
}

public interface ReplayCandleSource {
    void preload(LocalDate date, Set<String> tickers);   // throws if missing + TWS offline
    List<Candle> getCandlesUntil(String ticker, TimeFrame tf, ZonedDateTime virtualNow);
    void clear();
}

// REST payloads
record StartReplayRequest(String date, int speed) {}      // date=YYYY-MM-DD, speed ∈ {30,60,180,360}
record ReplayStatus(boolean active, String virtualNow, int speed, String runId,
                    int candlesProcessed, int candlesTotal, int ordersPlaced, int ordersSkipped) {}
record AccountMode(String mode, String accountId) {}      // mode ∈ {PAPER, LIVE}
```

## Testing Strategy

| Layer | What to Test | Approach |
|-------|-------------|----------|
| Unit | `ReplayClock` state transitions, `ReplayOrderGate` sliding window, `IbkrProperties.isPaperAccount()` | JUnit 5 + AssertJ |
| Unit | `ReplayCandleSource.getCandlesUntil` truncation, preload missing → error | Mock `CandleCsvService` + `IbkrService` |
| Integration | `/live-ui/replay/start` rejected during market hours, with TWS disconnected, with replay already active | MockMvc + clock abstraction |
| Integration | Replay signals land in `replaySignals`, not `liveSignals`; JSONL file created | `@Tag("slow")` |
| Integration | Rate limit: 11th order in 60s is skipped; log asserted | `@Tag("slow")` |
| E2E | Start replay at `60x` for 1 ticker, verify scanner emits signals matching baseline backtest (modulo jitter) | Playwright + Spring Boot real |
| E2E | TWS disconnect mid-replay aborts correctly, UI shows error | Playwright, kill IBKR mock |

## Migration / Rollout

- Feature flag `replay.enabled=false` by default in `application.yml` — endpoints return 404 when disabled.
- `Signal.replay` default `false` is forward-compatible with persisted `live-signals.json`.
- No data migration required; CSVs are append-only; `replay-signals-*.jsonl` are new artifacts.
- Rollback: set flag to `false` + restart; frontend hides controls when endpoint 404s.

## Open Questions

- [ ] Session resumption — if Spring Boot restarts mid-replay, do we auto-resume from last checkpoint or require user to restart manually? **Proposed**: no auto-resume in v1 (simpler); document in UI.
- [ ] Should the jitter seed be persisted per-run for reproducibility, or just log it? **Proposed**: log only in v1; optional "reproducible mode" as follow-up.
