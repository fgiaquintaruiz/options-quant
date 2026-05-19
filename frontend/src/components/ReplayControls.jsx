import React, { useState, useEffect, useRef } from 'react'
import { Play, Square } from 'lucide-react'
import { replayApi } from '../api'
import { LS } from '../utils/storage'
import { useReplayStatus } from '../hooks/useReplayStatus'

const SPEEDS = [30, 60, 180, 360]
const MS_PER_DAY = 86_400_000

function todayStr()     { return new Date().toISOString().split('T')[0] }
function yesterdayStr() { return new Date(Date.now() - MS_PER_DAY).toISOString().split('T')[0] }

/**
 * Parse an ISO 8601 string that may include a Java ZonedDateTime zone-id suffix
 * like "[America/New_York]". Browsers cannot parse that suffix, causing Invalid Date
 * and "NaN/NaN/NaN NaN:NaN" in the UI. Strip it before constructing a Date.
 */
function parseIso(iso) {
  if (!iso) return null
  // Strip optional "[ZoneId]" suffix produced by Java's ZonedDateTime.toString()
  const stripped = iso.replace(/\[[^\]]+\]$/, '')
  const d = new Date(stripped)
  return isNaN(d.getTime()) ? null : d
}

function formatVirtualNow(iso) {
  const d = parseIso(iso)
  if (!d) return null
  const dd   = String(d.getUTCDate()).padStart(2, '0')
  const mm   = String(d.getUTCMonth() + 1).padStart(2, '0')
  const yyyy = d.getUTCFullYear()
  const hh   = String(d.getUTCHours()).padStart(2, '0')
  const min  = String(d.getUTCMinutes()).padStart(2, '0')
  return `${dd}/${mm}/${yyyy} ${hh}:${min}`
}

function formatDateShort(iso) {
  const d = parseIso(iso)
  if (!d) return null
  return `${String(d.getUTCDate()).padStart(2, '0')}/${String(d.getUTCMonth() + 1).padStart(2, '0')}`
}

/**
 * Calculates replay progress % using wall-clock elapsed × speed multiplier,
 * divided by the total virtual range (historical start to today).
 */
function calcProgressPct(wallStartMs, speedMultiplier, vStartMs) {
  const wallNowMs = Date.now()
  const realElapsedMs = wallNowMs - wallStartMs
  const virtualElapsedMs = realElapsedMs * speedMultiplier
  const vRangeMs = wallNowMs - vStartMs
  if (vRangeMs <= 0) return 0
  return Math.min(100, Math.round((virtualElapsedMs / vRangeMs) * 100))
}

export default function ReplayControls({ marketOpen, onActiveChange, onError }) {
  const today     = todayStr()
  const yesterday = yesterdayStr()

  const [replayDate,         setReplayDate]         = useState(() => LS.get('replay_lastDate', yesterday))
  const [replaySpeed,        setReplaySpeed]        = useState(30)
  const [replayActive,       setReplayActive]       = useState(false)
  const [wallStartMs,        setWallStartMs]        = useState(null)
  const [replayStartVirtual, setReplayStartVirtual] = useState(null)
  const lastActiveRef     = useRef(false)
  const onActiveChangeRef = useRef(onActiveChange)

  useEffect(() => {
    onActiveChangeRef.current = onActiveChange;
  }, [onActiveChange])

  // Always poll: even when inactive, we need to detect orphan replays on mount.
  const { status, error } = useReplayStatus({ active: true })

  useEffect(() => {
    if (error && onError) onError(error)
  }, [error, onError])

  const virtualNow  = status?.virtualNow  ?? null
  const statusSpeed = replayActive ? (status?.speed ?? null) : null

  useEffect(() => {
    if (!status) return
    const isActive = !!status.active
    if (isActive !== lastActiveRef.current) {
      lastActiveRef.current = isActive
      setReplayActive(isActive)
      onActiveChangeRef.current?.(isActive)
    }
    if (isActive && replayStartVirtual === null && status.virtualNow) {
      const parsed = parseIso(status.virtualNow)
      if (parsed) setReplayStartVirtual(parsed.getTime())
    }
  }, [status, replayStartVirtual])

  const cycleSpeed = () => setReplaySpeed(s => SPEEDS[(SPEEDS.indexOf(s) + 1) % SPEEDS.length])

  const handleStart = async () => {
    try {
      await replayApi.start(replayDate, replaySpeed)
      LS.set('replay_lastDate', replayDate)
      setWallStartMs(Date.now())
      setReplayStartVirtual(null)
      setReplayActive(true)
      lastActiveRef.current = true
      onActiveChangeRef.current?.(true)
    } catch (e) {
      onError?.(e.message || 'Replay bloqueado durante horario de mercado')
    }
  }

  const handleStop = async () => {
    try {
      await replayApi.stop()
    } catch (e) {
      onError?.(e.message || 'Error al detener el replay')
      return
    }
    setReplayActive(false)
    setWallStartMs(null)
    setReplayStartVirtual(null)
    lastActiveRef.current = false
    onActiveChangeRef.current?.(false)
  }

  const pct = calcProgressPct(wallStartMs, statusSpeed ?? replaySpeed, replayStartVirtual)

  return (
    <>
      {replayActive && virtualNow && (
        <span className="replay-virtual-clock" data-testid="replay-virtual-clock">
          📅 Replay: {formatVirtualNow(virtualNow)}
        </span>
      )}
      {replayActive && statusSpeed != null && (
        <span className="replay-speed-badge" data-testid="replay-speed-badge">
          {statusSpeed}x
        </span>
      )}
      {replayActive && (
        <div className="replay-progress-bar" data-testid="replay-progress-bar">
          <div className="replay-progress-fill" style={{ width: `${pct}%` }} />
          <span className="replay-progress-label">
            {pct}% ({formatDateShort(replayDate)} → {formatDateShort(virtualNow)})
          </span>
        </div>
      )}
      <input
        type="date"
        className="ld-replay-date"
        data-testid="replay-date-input"
        value={replayDate}
        max={today}
        onChange={e => setReplayDate(e.target.value)}
        disabled={replayActive}
      />
      <button className="btn ld-speed-btn" data-testid={`replay-speed-btn-${replaySpeed}`} onClick={cycleSpeed} disabled={replayActive}>
        {replaySpeed}x
      </button>
      <button
        className={`btn ${replayActive ? 'btn-secondary' : 'btn-primary'} ld-replay-start`}
        data-testid="replay-start-btn"
        onClick={replayActive ? handleStop : handleStart}
        disabled={marketOpen && !replayActive}
        title={marketOpen && !replayActive ? 'No disponible durante horario de mercado' : undefined}
      >
        {replayActive ? <Square size={14}/> : <Play size={14}/>}
      </button>
    </>
  )
}
