# Specification: live-replay-mode

Master spec for the 4 new + 3 modified capabilities. All **ADDED** (no legacy spec to modify).

---

## Capability: live-replay-clock

### Requirement: Virtual time source

The system MUST expose a singleton `ReplayClock` with state `{ active, virtualNow, speedMultiplier, runId }`. When `active=true`, all scanner time queries MUST use `virtualNow`; when `active=false`, behavior is unchanged (real wall time).

#### Scenario: Virtual time drives scanner

- GIVEN replay is started with `speedMultiplier=60` and `virtualNow=2026-04-22T14:30Z`
- WHEN the scanner queries current time
- THEN it MUST receive `2026-04-22T14:30Z`, not real wall time

### Requirement: Speed control without restart

The clock MUST support changing `speedMultiplier` at runtime without stopping the replay. Valid presets: `30`, `60`, `180`, `360`.

#### Scenario: Speed change preserves virtual time

- GIVEN replay running at 30x, virtualNow past half-session
- WHEN user switches to 180x
- THEN virtual time progression continues from the same point at the new rate

---

## Capability: live-replay-source

### Requirement: CSV-first with IBKR backfill

For each `(ticker, timeframe)` needed at `replay/start`, the system MUST load candles from `data/{TICKER}_{TF}.csv`. If the target date range is missing from the CSV, the system MUST download it via `ibkrService.downloadHistoricalData` and write it back via `csvService.saveToCsv`.

#### Scenario: Happy path — CSV has the date

- GIVEN `data/AAPL_5min.csv` contains candles for `2026-04-22`
- WHEN replay starts for that date
- THEN the source MUST NOT call IBKR for AAPL 5min

#### Scenario: Backfill path — CSV missing date

- GIVEN `data/NVDA_5min.csv` has data only up to `2026-04-21`
- AND TWS is connected
- WHEN replay starts for `2026-04-22`
- THEN the source MUST call `ibkrService.downloadHistoricalData(NVDA, MIN_5)` and persist merged data

#### Scenario: Backfill fails without TWS

- GIVEN candles are missing for 3 HOT tickers for the target date
- AND TWS is not connected
- WHEN user tries to start replay
- THEN the system MUST reject with an error listing the missing tickers

### Requirement: Time-truncated lookup

The source MUST expose `getCandlesUntil(ticker, timeframe, virtualNow)` returning only candles whose close time is ≤ `virtualNow`. Lookup MUST be O(log n) per call.

---

## Capability: live-replay-controls

### Requirement: Start guards

`replay/start` MUST reject if: (a) the real US market is open (10:00–22:00 Europe/Madrid, MON-FRI), (b) TWS is not connected, (c) a replay is already active. Rejections MUST return HTTP 409 with a human-readable reason.

#### Scenario: Block during real market hours

- GIVEN current wall time is `2026-04-23T16:00 Europe/Madrid` (Thursday)
- WHEN user calls `POST /live-ui/replay/start`
- THEN the system MUST respond 409 with reason `market-open`

### Requirement: Rate-limit on auto-execution

During replay, `MarketScanner.tryAutoExecute` MUST skip bracket placement when exceeding `replay.max-orders-per-minute` (default `10`) on a rolling 60-second window. Signals MUST still be emitted to the grid and logged.

#### Scenario: Cap exceeded

- GIVEN 10 brackets placed within the last 60s during replay
- WHEN an 11th signal triggers auto-exec
- THEN bracket placement MUST be skipped with a WARN log; the signal MUST still appear in the grid

### Requirement: Abort on TWS disconnect

If TWS disconnects mid-replay, `ReplayScheduler` MUST detect on the next tick, stop the replay, and persist a termination log entry. UI MUST display the abort reason.

---

## Capability: live-paper-live-chip

### Requirement: Account mode detection

The system MUST expose `GET /live-ui/account-mode` returning `{ mode, accountId }` where `mode=PAPER` if `accountId` starts with `DU`, else `mode=LIVE`. Detection is read-only; no runtime toggle in this change.

#### Scenario: Paper account detected

- GIVEN `ibkr.account-id=DUN598126`
- WHEN frontend calls `GET /live-ui/account-mode`
- THEN response MUST be `{ mode: "PAPER", accountId: "DUN598126" }`

---

## Capability (modified): signal-emission

### Requirement: Replay tag on signals

The `Signal` record MUST include `boolean replay` (default `false`). Signals emitted while `replayClock.active=true` MUST carry `replay=true`. The tag MUST NOT alter bracket/Telegram behavior — those remain governed by existing `autoExecute` and rate-limit gates.

#### Scenario: Replay signals persist separately

- GIVEN replay active with `runId=R1`, date `2026-04-22`
- WHEN a signal is emitted
- THEN it MUST be appended to `data/replay-signals-2026-04-22-R1.jsonl` and NOT to `data/live-signals.json`

---

## Capability (modified): scanner-scheduling

### Requirement: Market-hours bypass during replay

`MarketScanner.scanAndExecute` MUST skip the "outside market hours" guard when `replayClock.active=true` and MUST use `replayClock.virtualNow` for timestamp decisions.

---

## Capability (modified): live-dashboard

### Requirement: Replay banner and mode chip

The dashboard MUST render a visible "REPLAY MODE" banner while replay is active and a PAPER/LIVE chip at all times. The live signals grid MUST be read-only during replay; a separate replay-signals view MUST be shown instead.

---

## Related

- Proposal: `.atl/sdd/live-replay-mode/proposal.md`
