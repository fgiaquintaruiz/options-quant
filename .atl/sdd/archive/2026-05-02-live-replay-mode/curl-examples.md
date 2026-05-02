# curl examples — live-replay-mode

Backend runs on `http://localhost:9090`. TWS **must be logged in manually** (paper
account `DUN598126` on port `7497`) before starting a replay. Feature flag
`replay.enabled=true` in `application.yml` enables the endpoints.

## 1) Detect paper vs live account

```bash
curl -s http://localhost:9090/live-ui/account-mode
# → {"mode":"PAPER","accountId":"DUN598126"}
```

## 2) Start a replay

```bash
# Replay 2026-04-22 at 60x speed (1 candle every ~5s real time + jitter)
curl -s -X POST "http://localhost:9090/live-ui/replay/start?date=2026-04-22&speed=60"
# → {"success":true,"runId":"R-abc12345","virtualNow":"2026-04-22T14:30:00Z","speed":60}
```

**Valid speeds**: `30`, `60`, `180`, `360`.

### Rejection paths

```bash
# 409 — real US market is open (10:00–22:00 Europe/Madrid MON-FRI)
curl -s -X POST "http://localhost:9090/live-ui/replay/start?date=2026-04-22&speed=60"
# → HTTP 409 {"success":false,"error":"market-open"}

# 409 — TWS disconnected
# → HTTP 409 {"success":false,"error":"tws-disconnected"}

# 409 — already running
# → HTTP 409 {"success":false,"error":"already-active"}

# 409 — invalid speed
# → HTTP 409 {"success":false,"error":"invalid-speed: 45"}

# 409 — missing candles for target date and TWS disconnected
# → HTTP 409 {"success":false,"error":"Missing candles for replay date 2026-04-22 ..."}
```

## 3) Poll status

```bash
curl -s http://localhost:9090/live-ui/replay/status
# → {"active":true,"virtualNow":"2026-04-22T15:15:00Z","speed":60,
#    "runId":"R-abc12345","replaySignalsCount":7}
```

## 4) Change speed on the fly

```bash
curl -s -X PUT "http://localhost:9090/live-ui/replay/speed?speed=180"
# → {"success":true,"speed":180}
```

## 5) Stop replay

```bash
curl -s -X POST http://localhost:9090/live-ui/replay/stop
# → {"success":true}
```

Stopping clears `replaySignals`, deactivates `ReplayClock`, shuts down the
`ReplayScheduler`, and clears the `ReplayCandleSource` cache.

## Output files

- `data/replay-signals-YYYY-MM-DD.jsonl` — append-only audit of replay signals,
  one JSON object per line.

## Safety

- Brackets **do** fire to TWS paper during replay (by design — lets you review
  order creation in the platform).
- `ReplayOrderGate` caps auto-exec at `replay.max-orders-per-minute` (default 10);
  signals still appear in the grid but bracket placement is skipped with a WARN
  log beyond the cap.
- If TWS disconnects mid-replay the scheduler aborts on the next tick.
