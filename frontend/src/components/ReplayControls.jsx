import React, { useCallback, useEffect, useState } from 'react'
import { Play, Square } from 'lucide-react'
import { replayApi } from '../api'

const SPEED_PRESETS = [30, 60, 180, 360]

const formatVirtualNow = (iso) => {
  if (!iso) return '—'
  try { return new Date(iso).toISOString().slice(0, 16).replace('T', ' ') }
  catch { return iso }
}

/**
 * Replay controls for off-hours E2E scanner testing.
 * Auto-shows when market is closed; hidden during regular session unless replay is active.
 */
export default function ReplayControls({ onActiveChange, marketOpen }) {
  const today = new Date().toISOString().slice(0, 10)
  const [date, setDate]     = useState(today)
  const [speed, setSpeed]   = useState(60)
  const [status, setStatus] = useState({ active: false, virtualNow: null, speed: 0, runId: null, replaySignalsCount: 0 })
  const [error, setError]   = useState(null)
  const [busy, setBusy]     = useState(false)

  const refresh = useCallback(() => {
    replayApi.status()
      .then((s) => { setStatus(s); if (onActiveChange) onActiveChange(s.active) })
      .catch(() => { /* replay disabled — ignore */ })
  }, [onActiveChange])

  useEffect(() => {
    refresh()
    const id = setInterval(refresh, 2000)
    return () => clearInterval(id)
  }, [refresh])

  // Hide during market hours unless a replay is already active
  if (marketOpen && !status.active) return null

  const handleStart = async () => {
    setError(null); setBusy(true)
    try { await replayApi.start(date, speed); refresh() }
    catch (e) { setError(e.message || 'Failed to start replay') }
    finally { setBusy(false) }
  }

  const handleStop = async () => {
    setError(null); setBusy(true)
    try { await replayApi.stop(); refresh() }
    catch (e) { setError(e.message || 'Failed to stop replay') }
    finally { setBusy(false) }
  }

  const handleSpeedChange = async (next) => {
    setSpeed(next)
    if (!status.active) return
    try { await replayApi.setSpeed(next) }
    catch (e) { setError(e.message || 'Failed to set speed') }
  }

  return (
    <div className="replay-controls">
      <div className="replay-controls-row">
        <span className="replay-controls-title">Off-Hours Replay</span>
        {status.active && (
          <span className="replay-controls-virtual">
            Virtual: <strong>{formatVirtualNow(status.virtualNow)}</strong>
            <span className="replay-controls-meta">{status.speed}x · run {status.runId} · {status.replaySignalsCount} signals</span>
          </span>
        )}
        <input
          type="date"
          className="ticker-input"
          value={date}
          max={today}
          disabled={status.active || busy}
          onChange={(e) => setDate(e.target.value)}
        />
        <div className="replay-speed-presets">
          {SPEED_PRESETS.map((s) => (
            <button key={s} type="button"
              className={`replay-speed-btn${speed === s ? ' replay-speed-btn--active' : ''}`}
              disabled={busy}
              onClick={() => handleSpeedChange(s)}>{s}x</button>
          ))}
        </div>
        {!status.active
          ? <button type="button" className="btn btn-primary" disabled={busy} onClick={handleStart}><Play size={14} /> Start</button>
          : <button type="button" className="btn btn-secondary replay-stop-btn" disabled={busy} onClick={handleStop}><Square size={14} /> Stop</button>
        }
      </div>
      {error && <div className="replay-controls-error">{error}</div>}
    </div>
  )
}
