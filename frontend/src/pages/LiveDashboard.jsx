import React, { useState, useEffect, useCallback, useRef, useMemo } from 'react'
import { Play, Square, Zap, ChevronUp, ChevronDown, Monitor, Cpu, ShieldCheck, ShieldAlert, OctagonAlert } from 'lucide-react'
import { liveApi } from '../api'
import LiveTradeGrid from '../components/LiveTradeGrid'
import TickerSelector from '../components/TickerSelector'
import SwapButton from '../components/SwapButton'
import { LS } from '../utils/storage'

// ── Utilities ──────────────────────────────────────────────────────────────

/** Formats ISO-8601 timestamp as locale date+time. Returns '—' for null/invalid. */
function formatSignalTimestamp(ts) {
  if (ts == null || ts === '') return '—'
  const d = new Date(ts)
  if (Number.isNaN(d.getTime())) return typeof ts === 'string' ? ts : String(ts)
  return d.toLocaleString('es-ES', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

/** Backend sends HH:mm:ss for executions — combine with today's date for display. */
function formatTodayWallClock(hms) {
  if (hms == null || hms === '' || hms === '-') return '—'
  const parts = String(hms).trim().split(':').map((x) => parseInt(x, 10))
  if (parts.some((n) => Number.isNaN(n))) return String(hms)
  const d = new Date()
  d.setHours(parts[0] ?? 0, parts[1] ?? 0, parts[2] ?? 0, 0)
  return d.toLocaleString('es-ES', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

/** Signals older than 30 minutes are stale — Open/execute is blocked. */
const SIGNAL_STALE_MS = 30 * 60 * 1000
const isSignalStale = (ts) => {
  if (ts == null || ts === '') return true
  const t = new Date(ts).getTime()
  return Number.isNaN(t) || Date.now() - t > SIGNAL_STALE_MS
}

/** Clamps concurrent pool size to [1, 16]. */
const normalizeLiveMaxConcurrent = (v) => {
  const n = Number(v)
  return Number.isFinite(n) ? Math.min(16, Math.max(1, Math.round(n))) : 4
}

const formatMinSec = (s) => {
  if (s == null) return '--:--'
  const m = Math.floor(s / 60)
  return `${String(m).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`
}

// ── useLiveScanner — scanner state, polling, and IBKR lock management ─────

/**
 * Manages all scanner-related state: backend polling, signals, scan activity,
 * force-stop flow, and IBKR exclusive lock tracking.
 * Returns scanner data, derived status flags, and action handlers.
 */
function useLiveScanner(setErrorMsg) {
  const [status, setStatus]               = useState(null)
  const [signals, setSignals]             = useState([])
  const [closedTrades, setClosedTrades]   = useState({})
  const [scanActivity, setScanActivity]   = useState([])
  const [staleClock, setStaleClock]       = useState(0)
  const [forceStopReleasing, setFSReleasing] = useState(false)
  const [exclusiveLockSinceMs, setLockSince] = useState(null)
  const [exclusiveLockMinutes, setLockMins]  = useState(0)
  const prevLockRef      = useRef(false)
  const forceStopPollRef = useRef(null)

  // Initial status fetch
  useEffect(() => {
    liveApi.getStatus()
      .then((s) => setStatus(s))
      .catch((e) => setErrorMsg(`Failed to connect to backend: ${e.message}`))
  }, [setErrorMsg])

  // Status + scan activity polling (1s interval)
  useEffect(() => {
    const tick = async () => {
      await Promise.allSettled([
        liveApi.getStatus().then(setStatus).catch((e) => console.warn('[scanner] status poll:', e.message)),
        liveApi.getScanActivity().then((d) => setScanActivity(d.activity || [])).catch((e) => console.warn('[scanner] activity poll:', e.message)),
      ])
    }
    const id = setInterval(tick, 1000)
    return () => clearInterval(id)
  }, [])

  // Signals polling (1.5s interval)
  const fetchSignals = useCallback(async () => {
    const data = await liveApi.getSignals().catch((e) => { console.warn('[scanner] signals poll:', e.message); return null })
    if (data) { setSignals(data.signals || []); setClosedTrades(data.closedTrades || {}) }
  }, [])

  useEffect(() => {
    fetchSignals()
    const id = setInterval(fetchSignals, 1500)
    return () => clearInterval(id)
  }, [fetchSignals])

  // Stale clock — recomputes stale status every 60s without waiting for a new fetch
  useEffect(() => {
    const id = setInterval(() => setStaleClock((c) => c + 1), 60_000)
    return () => clearInterval(id)
  }, [])

  // Cleanup force-stop poll on unmount
  useEffect(() => () => {
    if (forceStopPollRef.current != null) { clearInterval(forceStopPollRef.current); forceStopPollRef.current = null }
  }, [])

  // Exclusive lock detection — track when lock was first acquired for elapsed time display
  const exclusiveScanLockHeld = status?.exclusiveScanLockHeld
  useEffect(() => {
    const held = !!exclusiveScanLockHeld
    if (held && !prevLockRef.current) setLockSince(Date.now())
    if (!held) setLockSince(null)
    prevLockRef.current = held
  }, [exclusiveScanLockHeld])

  useEffect(() => {
    if (exclusiveLockSinceMs == null) { setLockMins(0); return }
    const tick = () => setLockMins(Math.floor((Date.now() - exclusiveLockSinceMs) / 60_000))
    tick()
    const id = setInterval(tick, exclusiveScanLockHeld ? 5_000 : 10_000)
    return () => clearInterval(id)
  }, [exclusiveLockSinceMs, exclusiveScanLockHeld])

  // ── Scanner action handlers ──────────────────────────────────────────────

  const handleStartScan = async () => {
    setErrorMsg(null)
    await liveApi.startScan().catch((e) => setErrorMsg(`Scan failed: ${e.message}`))
  }

  const handleStopScan = async () => {
    await liveApi.stopScan().catch((e) => setErrorMsg(`Stop scan failed: ${e.message}`))
  }

  const handleToggleScheduler = async () => {
    await liveApi.toggleScheduler().catch((e) => setErrorMsg(`Scheduler toggle failed: ${e.message}`))
  }

  const handleForceStop = async () => {
    setErrorMsg(null)
    if (forceStopPollRef.current != null) { clearInterval(forceStopPollRef.current); forceStopPollRef.current = null }
    setFSReleasing(true)
    try {
      await liveApi.forceStop()
      const s = await liveApi.getStatus()
      setStatus(s)
      if (!s.exclusiveScanLockHeld) {
        setLockSince(null); setLockMins(0); prevLockRef.current = false
      } else {
        setLockSince(Date.now()); setLockMins(0); prevLockRef.current = true
        const started = Date.now()
        forceStopPollRef.current = setInterval(async () => {
          const st = await liveApi.getStatus().catch(() => null)
          if (!st) return
          setStatus(st)
          const released = !st.exclusiveScanLockHeld
          const timedOut = Date.now() - started > 25_000
          if (released || timedOut) {
            clearInterval(forceStopPollRef.current); forceStopPollRef.current = null
            if (released) { setLockSince(null); setLockMins(0); prevLockRef.current = false }
          }
        }, 400)
      }
    } catch (e) {
      setErrorMsg(`Force stop failed: ${e.message}`)
    } finally {
      setFSReleasing(false)
    }
  }

  const scanning       = status?.isScanning || false
  const stopRequested  = status?.stopScanRequested || false
  const hotTickersList = status?.hotTickersList || []

  return {
    status, signals, closedTrades, scanActivity, staleClock,
    scanning, stopRequested, exclusiveScanLockHeld, hotTickersList,
    forceStopReleasing, exclusiveLockSinceMs, exclusiveLockMinutes,
    fetchSignals,
    handleStartScan, handleStopScan, handleForceStop, handleToggleScheduler,
  }
}

// ── LiveDashboard ──────────────────────────────────────────────────────────

/**
 * Live trading dashboard — scanner controls, IBKR connection status, signal grid.
 * UI state and trade actions live here; scanner polling is delegated to useLiveScanner.
 * Props: twsStatus (TWS connection state from parent — accountId, balance).
 */
export default function LiveDashboard({ twsStatus }) {
  const [errorMsg, setErrorMsg]           = useState(null)
  const [maxConcurrent, setMaxConcurrent] = useState(() => normalizeLiveMaxConcurrent(LS.get('live_maxConcurrent', 4)))
  const [tickerFilter, setTickerFilter]   = useState(() => LS.get('live_tickerFilter', ''))
  const [tickerScope, setTickerScope]     = useState(() => LS.get('live_tickerScope', 'HOT'))
  const [riskInput, setRiskInput]         = useState(() => LS.get('live_riskPct', '2.0'))
  const [mockMarketOpen, setMockMarketOpen] = useState(() => LS.get('live_mockMarketOpen', false))
  const [injectedSignals, setInjectedSignals] = useState([])
  const [pendingActions, setPendingActions]   = useState({})
  const [elapsed, setElapsed]                 = useState(0)
  const [nextScanSecs, setNextScanSecs]       = useState(null)

  const {
    status, signals, closedTrades, scanActivity, staleClock,
    scanning, stopRequested, exclusiveScanLockHeld, hotTickersList,
    forceStopReleasing, exclusiveLockSinceMs, exclusiveLockMinutes,
    fetchSignals,
    handleStartScan, handleStopScan, handleForceStop, handleToggleScheduler,
  } = useLiveScanner(setErrorMsg)

  // Restore pool size from localStorage and align JVM on mount
  useEffect(() => {
    const n = normalizeLiveMaxConcurrent(LS.get('live_maxConcurrent', 4))
    setMaxConcurrent(n)
    liveApi.setMaxConcurrent(n).catch((e) => console.warn('[live] concurrent init:', e.message))
  }, [])

  useEffect(() => { LS.set('live_maxConcurrent', maxConcurrent) }, [maxConcurrent])
  useEffect(() => { LS.set('live_mockMarketOpen', mockMarketOpen) }, [mockMarketOpen])

  // Sync filter/scope to backend on change
  useEffect(() => {
    LS.set('live_tickerFilter', tickerFilter)
    LS.set('live_tickerScope', tickerScope)
    liveApi.setScanFilter(tickerFilter, tickerScope).catch((e) => console.warn('[live] filter sync:', e.message))
  }, [tickerFilter, tickerScope])

  // Countdown to next scheduled scan (every 15 minutes on the quarter-hour)
  useEffect(() => {
    const tick = () => {
      const { getMinutes: m, getSeconds: s } = { getMinutes: () => new Date().getMinutes(), getSeconds: () => new Date().getSeconds() }
      let rem = (15 - (m() % 15)) * 60 - s()
      setNextScanSecs(rem <= 0 ? 15 * 60 : rem)
    }
    tick()
    const id = setInterval(tick, 1000)
    return () => clearInterval(id)
  }, [])

  // Elapsed scan timer
  useEffect(() => {
    if (!scanning) { setElapsed(0); return }
    const start = Date.now()
    const id = setInterval(() => setElapsed(Math.floor((Date.now() - start) / 1000)), 1000)
    return () => clearInterval(id)
  }, [scanning])

  // Sync riskInput from backend on first status load
  useEffect(() => {
    if (status?.riskPct != null) setRiskInput(String(status.riskPct))
  }, [status?.riskPct])

  const canScan = (status?.marketHours || mockMarketOpen) && !scanning && !stopRequested

  // ── Settings handlers ────────────────────────────────────────────────────

  const handleMaxConcurrentChange = async (newVal) => {
    const val = parseInt(newVal, 10)
    if (isNaN(val) || val < 1 || val > 16) return
    const prev = maxConcurrent
    setMaxConcurrent(val)
    await liveApi.setMaxConcurrent(val).catch((e) => {
      setMaxConcurrent(prev)
      setErrorMsg(`Concurrent update failed: ${e.message}`)
    })
  }

  const handleRiskAdjust = async (delta) => {
    const current = parseFloat(riskInput)
    const newVal  = Math.max(0.1, Math.min(10, current + delta))
    const formatted = newVal.toFixed(1)
    const prevFormatted = riskInput
    setRiskInput(formatted)
    LS.set('live_riskPct', formatted)
    await liveApi.setRisk(newVal).catch((e) => {
      setRiskInput(prevFormatted)
      LS.set('live_riskPct', prevFormatted)
      setErrorMsg(`Risk update failed: ${e.message}`)
    })
  }

  const handleToggleAutoExecute = async () => {
    await liveApi.toggleAutoExecute().catch((e) => setErrorMsg(`Auto execute toggle failed: ${e.message}`))
  }

  const handleToggleMockMarket = async () => {
    const prev = mockMarketOpen
    setMockMarketOpen(!prev)
    await liveApi.toggleMockMarket().catch((e) => {
      setMockMarketOpen(prev)
      setErrorMsg('Failed to toggle Mock Market')
    })
  }

  const handleInjectMockSignal = async () => {
    setErrorMsg(null)
    const hot = hotTickersList.length > 0 ? hotTickersList : ['SPY', 'QQQ', 'AAPL', 'NVDA', 'TSLA']
    const ticker = hot[Math.floor(Math.random() * hot.length)]
    await liveApi.injectMockSignal(ticker)
      .then(fetchSignals)
      .catch(() => setErrorMsg('Injection failed'))
  }

  // ── Trade action handlers ────────────────────────────────────────────────

  const handleDeleteSignal = async (ticker) => {
    const ok = await liveApi.deleteSignal(ticker).catch((e) => { setErrorMsg(`Delete failed: ${e.message}`); return null })
    if (ok && !ok.success) setErrorMsg(ok.message || 'No se pudo borrar la señal')
    await fetchSignals()
  }

  const handleClearStaleBatch = async (tickers) => {
    await liveApi.batchDeleteSignals(tickers)
      .then(fetchSignals)
      .catch((e) => setErrorMsg(`Batch delete failed: ${e.message}`))
  }

  const handleClearAllStale = async () => {
    await liveApi.clearStaleSignals()
      .then(fetchSignals)
      .catch((e) => setErrorMsg(`Clear stale failed: ${e.message}`))
  }

  const handleCloseTrade = async (ticker, price, isOpenPos = false, tpOrderId = null, slOrderId = null) => {
    setPendingActions((prev) => ({ ...prev, [ticker]: true }))
    const done = () => setPendingActions((prev) => ({ ...prev, [ticker]: false }))
    try {
      if (isOpenPos) {
        const signal = trades.find((t) => t.ticker === ticker && Number(t.ep) === Number(price))
        if (!signal) { setErrorMsg(`Could not find signal for ${ticker}`); done(); return }
        if (signal.signalStale) { setErrorMsg('Señal >30 min — ejecución bloqueada. Revisa velas / fuente.'); done(); return }
        const ok = await liveApi.executeSignal(ticker, signal.direction, price, signal.strategy)
        if (!ok.success) setErrorMsg(`Execution failed: ${ok.message}`)
        setTimeout(() => { done(); fetchSignals() }, 800)
        return
      }
      const ok = await liveApi.closeTrade(ticker, price, tpOrderId, slOrderId)
      if (ok.success) fetchSignals()
    } catch (e) {
      setErrorMsg(`Action failed: ${e.message}`)
    } finally {
      done()
    }
  }

  const handleCancelTrade = async (ticker, orderId) => {
    if (!orderId) return
    setPendingActions((prev) => ({ ...prev, [ticker]: true }))
    const ok = await liveApi.cancelTrade(ticker, orderId).catch((e) => { setErrorMsg(`Cancel failed: ${e.message}`); return null })
    if (ok?.success) fetchSignals()
    setPendingActions((prev) => ({ ...prev, [ticker]: false }))
  }

  // ── Signal mapping ───────────────────────────────────────────────────────

  const mapSignal = useCallback((s) => {
    const candleTs = s.timestamp
    const closed   = closedTrades[s.ticker]
    const signalStale = isSignalStale(candleTs)
    const executed    = s.tradeStatus === 'EXECUTED'
    const entryAt  = executed && s.executeTime ? formatTodayWallClock(s.executeTime) : formatSignalTimestamp(candleTs)
    return {
      ticker: s.ticker, pattern: s.candlestickPattern || 'signal',
      strategy: s.strategy, direction: s.direction, ep: s.currentPrice,
      signalFound: formatSignalTimestamp(s.signalFoundAt), entryAt,
      exitedAt: closed ? formatTodayWallClock(closed.closeTime) : '-',
      tp: s.tradePlan?.takeProfit, sl: s.tradePlan?.stopLoss,
      closePrice: closed ? closed.closePrice : null, xp: '-',
      exitReason: closed ? (closed.exitReason || 'MANUAL_CLOSE') : 'LIVE SIGNAL',
      netPnl: null, executeTime: s.executeTime, tradeStatus: s.tradeStatus,
      orderId: s.orderId, tpOrderId: s.tpOrderId, slOrderId: s.slOrderId, signalStale,
    }
  }, [closedTrades])

  const trades = useMemo(
    () => [...signals.map(mapSignal), ...injectedSignals.map(mapSignal)],
    [signals, injectedSignals, mapSignal, staleClock]
  )

  const staleSignalCount = useMemo(
    () => trades.filter((t) => t.signalStale && (!t.exitedAt || t.exitedAt === '-') && t.tradeStatus !== 'EXECUTED').length,
    [trades]
  )

  const filteredScanActivity = useMemo(
    () => scanActivity.filter((a) => !(a.detail && /^(Hot tickers:|Total:)\s*\d/.test(a.detail))),
    [scanActivity]
  )

  const forceStopTooltip = forceStopReleasing
    ? 'Esperando confirmación del servidor: interrupción de descargas IBKR y liberación del lock exclusivo…'
    : exclusiveScanLockHeld
      ? `IBKR: bloqueo exclusivo del scanner${status?.exclusiveScanOwnerThread ? ` — ${status.exclusiveScanOwnerThread}` : ''}. ~${exclusiveLockMinutes} min.`
      : 'Sin bloqueo exclusivo del scanner IBKR (libre).'

  // ── Render ───────────────────────────────────────────────────────────────

  const forceStopClass = forceStopReleasing ? 'ld-force-stop-btn--releasing'
    : exclusiveScanLockHeld ? 'ld-force-stop-btn--locked' : 'ld-force-stop-btn--free'
  const forceStopLabelClass = (forceStopReleasing || exclusiveScanLockHeld)
    ? 'live-force-stop-label ld-force-stop-label--bold'
    : 'live-force-stop-label ld-force-stop-label--normal'

  return (
    <div className="flex-col" data-testid="live-dashboard">

      {errorMsg && (
        <div className="card mb-12 ld-error-banner">
          <span className="color-error font-bold">{errorMsg}</span>
          <button onClick={() => setErrorMsg(null)} className="color-muted ld-dismiss-btn">Dismiss</button>
        </div>
      )}

      {/* Toolbar */}
      <div className="card ld-toolbar" data-testid="live-toolbar">
        <div className="flex-col gap-15">

          {/* Row 1: Tickers */}
          <div className="flex-align-center gap-15">
            <div className="stat-label-sm color-muted ld-tickers-label">Tickers to scan</div>
            <div className="ld-tickers-field">
              <TickerSelector value={tickerFilter} onChange={setTickerFilter} disabled={scanning} scope={tickerScope} onScopeChange={setTickerScope} />
            </div>
          </div>

          {/* Row 2: Engine controls + Account strip */}
          <div className="flex-between flex-wrap gap-10 ld-controls-row">
            <div className="flex-align-center gap-10 flex-wrap">

              {/* Force stop / IBKR lock indicator */}
              <button type="button" data-testid="live-force-stop"
                className={`btn live-force-stop-btn ld-force-stop-btn ${forceStopClass}`}
                onClick={handleForceStop} disabled={forceStopReleasing} title={forceStopTooltip}>
                <OctagonAlert size={16} className="live-force-stop-ico" aria-hidden />
                <span className={forceStopLabelClass}>
                  {forceStopReleasing ? 'Liberando…'
                    : exclusiveScanLockHeld ? (exclusiveLockSinceMs != null ? `IBKR locked · ${exclusiveLockMinutes}m` : 'IBKR locked')
                    : 'IBKR libre'}
                </span>
              </button>

              <div className="divider-v ld-divider" />

              {/* Auto Scan group */}
              <div className="flex-align-center gap-0 ld-auto-scan-group">
                <button type="button" data-testid="live-toggle-scheduler"
                  className={`ld-scheduler-btn ${status?.schedulerEnabled ? 'ld-scheduler-btn--on' : 'ld-scheduler-btn--off'}`}
                  onClick={handleToggleScheduler}>
                  <Monitor size={14} /> Auto Scan
                </button>

                {scanning || stopRequested ? (
                  <button type="button" data-testid="live-stop-scan" className="ld-scan-btn-base ld-scan-stop-btn" onClick={handleStopScan} title="Detener scan">
                    <Square size={13} /> Stop
                  </button>
                ) : (
                  <button type="button" data-testid="live-start-scan"
                    className={`ld-scan-btn-base ${canScan ? 'ld-scan-start-btn--on' : 'ld-scan-start-btn--off'}`}
                    onClick={handleStartScan} disabled={!canScan}
                    title={canScan ? 'Iniciar scan manual' : 'Fuera de horario de mercado'}>
                    <Play size={13} /> Scan
                  </button>
                )}

                {status?.schedulerEnabled && <span className="ld-countdown">{formatMinSec(nextScanSecs)}</span>}
              </div>

              <SwapButton active={status?.autoExecute} onText="Auto Open" offText="Manual Open" onClick={handleToggleAutoExecute} icon={Cpu} testId="live-toggle-auto-execute" />

              <div className="divider-v ld-divider" />

              <div className="flex-align-center gap-4">
                <span className="stat-label-sm color-muted">Concurrent:</span>
                <input type="number" min="1" max="16" value={maxConcurrent}
                  onChange={(e) => handleMaxConcurrentChange(e.target.value)} disabled={scanning}
                  className="ld-concurrent-input" />
              </div>

              <div className="divider-v ld-divider" />

              <SwapButton active={mockMarketOpen} onText="Mock Mkt" offText="Real Mkt" onClick={handleToggleMockMarket}
                activeColor="#f0883e" offColor="#3fb950" icon={mockMarketOpen ? ShieldAlert : ShieldCheck} testId="live-toggle-mock-market" />

              {mockMarketOpen && (
                <button className="btn ld-mock-btn" onClick={handleInjectMockSignal}>
                  <Zap size={14} /> Mock Signal
                </button>
              )}
            </div>

            {/* Account strip */}
            <div className="flex-align-center gap-8 ld-account-strip" data-testid="live-account-strip">
              <span className="color-muted">Account:</span>
              <strong className="pill pill-info ld-account-id">{twsStatus?.accountId || 'OFFLINE'}</strong>
              <span className="color-muted">·</span>
              <span className="color-muted">Balance:</span>
              <strong className="color-success ld-balance">
                {twsStatus?.balance > 0 ? `$${Number(twsStatus.balance).toLocaleString()}` : '$0'}
              </strong>
              <span className="color-muted">·</span>
              <span className="color-muted">Risk %:</span>
              <strong className="color-text ld-risk-val">{riskInput}</strong>
              <div className="flex-col gap-0">
                <ChevronUp size={13} className="color-muted" style={{ cursor: 'pointer' }} onClick={() => handleRiskAdjust(1.0)} />
                <ChevronDown size={13} className="color-muted" style={{ cursor: 'pointer' }} onClick={() => handleRiskAdjust(-1.0)} />
              </div>
            </div>
          </div>

        </div>
      </div>

      <LiveTradeGrid
        trades={trades}
        scanActivity={filteredScanActivity}
        scanning={scanning}
        hotTickers={hotTickersList}
        staleSignalCount={staleSignalCount}
        onCloseTrade={handleCloseTrade}
        onCancelTrade={handleCancelTrade}
        onDeleteSignal={handleDeleteSignal}
        onClearStaleBatch={handleClearStaleBatch}
        onClearAllStale={handleClearAllStale}
        pendingActions={pendingActions}
      />
    </div>
  )
}
