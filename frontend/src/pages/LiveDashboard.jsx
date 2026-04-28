import React, { useState, useEffect, useCallback, useRef, useMemo } from 'react'
import { Play, Square, Zap, ChevronUp, ChevronDown, Monitor, Cpu, ShieldCheck, ShieldAlert, OctagonAlert, BookmarkPlus } from 'lucide-react'
import { liveApi, replayApi, externalPositionsApi } from '../api'
import LiveTradeGrid from '../components/LiveTradeGrid'
import TickerSelector, { resolveTickerEntries, DEFAULT_GROUPS } from '../components/TickerSelector'
import SwapButton from '../components/SwapButton'
import { LS } from '../utils/storage'
import { useWatchlists } from '../hooks/useWatchlists'
import { useExternalPositions } from '../hooks/useExternalPositions'

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

// ── Market hours ─────────────────────────────────────────────────────────────

function checkMarketOpen() {
  const et = new Date(new Date().toLocaleString('en-US', { timeZone: 'America/New_York' }))
  const day = et.getDay()
  if (day === 0 || day === 6) return false
  const mins = et.getHours() * 60 + et.getMinutes()
  return mins >= 9 * 60 + 30 && mins < 16 * 60
}

function useMarketOpen() {
  const [open, setOpen] = useState(checkMarketOpen)
  useEffect(() => {
    const id = setInterval(() => setOpen(checkMarketOpen()), 30_000)
    return () => clearInterval(id)
  }, [])
  return open
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
  const [scanScores, setScanScores]       = useState({})
  const [scanStartedAt, setScanStartedAt] = useState(null)
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
        liveApi.getScanScores().then((d) => {
          setScanScores(d?.scoresByTicker || {})
          setScanStartedAt(d?.scanStartedAt || null)
        }).catch((e) => console.warn('[scanner] scan-scores poll:', e.message)),
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
    const result = await liveApi.startScan().catch((e) => { setErrorMsg(`Scan failed: ${e.message}`); return null })
    if (result && result.success === false) {
      setErrorMsg(result.message || 'Scan blocked')
    }
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
  const macroRegime   = status?.macroRegime   || null
  const macroMomentum = status?.macroMomentum || null
  const macroSummary  = status?.macroSummary  || null

  return {
    status, signals, closedTrades, scanActivity, scanScores, scanStartedAt, staleClock,
    scanning, stopRequested, exclusiveScanLockHeld, hotTickersList,
    macroRegime, macroMomentum, macroSummary,
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
export default function LiveDashboard({ twsStatus, marketOpen }) {
  const [errorMsg, setErrorMsg]           = useState(null)
  const [replayActive, setReplayActive]   = useState(false)
  const [maxConcurrent, setMaxConcurrent] = useState(() => normalizeLiveMaxConcurrent(LS.get('live_maxConcurrent', 4)))
  const [tickerFilter, setTickerFilter]   = useState(() => LS.get('live_tickerFilter', ''))
  const [tickerScope, setTickerScope]     = useState(() => LS.get('live_tickerScope', 'HOT'))
  const [riskInput, setRiskInput]         = useState(() => LS.get('live_riskPct', '2.0'))
  const [mockMarketOpen, setMockMarketOpen] = useState(() => LS.get('live_mockMarketOpen', false))
  const [injectedSignals, setInjectedSignals] = useState([])
  const [pendingActions, setPendingActions]   = useState({})
  const [elapsed, setElapsed]                 = useState(0)
  const [nextScanSecs, setNextScanSecs]       = useState(null)
  const today                                 = new Date().toISOString().split('T')[0]
  const yesterday                             = new Date(Date.now() - 86_400_000).toISOString().split('T')[0]
  const [replayDate, setReplayDate]           = useState(() => LS.get('replay_lastDate', yesterday))
  const [replaySpeed, setReplaySpeed]         = useState(30)
  const SPEEDS                                = [30, 60, 180, 360]
  const cycleSpeed                            = () => setReplaySpeed(s => SPEEDS[(SPEEDS.indexOf(s) + 1) % SPEEDS.length])
  const [saveListPopover, setSaveListPopover] = useState(false)
  const [saveListName, setSaveListName]       = useState('')
  const saveListInputRef                      = useRef(null)
  const saveListWrapRef                       = useRef(null)
  const { addGroup: addWatchlist }            = useWatchlists()

  useEffect(() => {
    if (!saveListPopover) return
    const close = (e) => { if (saveListWrapRef.current && !saveListWrapRef.current.contains(e.target)) { setSaveListPopover(false); setSaveListName('') } }
    document.addEventListener('mousedown', close)
    return () => document.removeEventListener('mousedown', close)
  }, [saveListPopover])

  const {
    status, signals, closedTrades, scanActivity, scanScores, scanStartedAt, staleClock,
    scanning, stopRequested, exclusiveScanLockHeld, hotTickersList,
    macroRegime, macroMomentum, macroSummary,
    forceStopReleasing, exclusiveLockSinceMs, exclusiveLockMinutes,
    fetchSignals,
    handleStartScan, handleStopScan, handleForceStop, handleToggleScheduler,
  } = useLiveScanner(setErrorMsg)

  const { positions: externalPositions, refresh: refreshExternal } = useExternalPositions()

  const handleCloseExternal = async (ticker) => {
    await externalPositionsApi.closeExternalPosition(ticker)
    refreshExternal()
  }

  const handleScheduleClose1450 = async (ticker) => {
    await externalPositionsApi.scheduleClose1450(ticker)
  }

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
    const resolved = resolveTickerEntries(tickerFilter, LS.get('ticker_groups', DEFAULT_GROUPS))
    liveApi.setScanFilter(resolved, tickerScope).catch((e) => console.warn('[live] filter sync:', e.message))
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

  const handleToggleMacroFilter = async () => {
    await liveApi.toggleMacroFilter().catch((e) => setErrorMsg(`Macro filter toggle failed: ${e.message}`))
  }

  const handleToggleMockMarket = async () => {
    const prev = mockMarketOpen
    setMockMarketOpen(!prev)
    await liveApi.toggleMockMarket().catch((e) => {
      setMockMarketOpen(prev)
      setErrorMsg('Failed to toggle Mock Market')
    })
  }

  const handleStartReplay = async () => { await replayApi.start(replayDate, replaySpeed); LS.set('replay_lastDate', replayDate); setReplayActive(true) }
  const handleStopReplay  = async () => { await replayApi.stop(); setReplayActive(false) }

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

  const resolvedFilterTickers = useMemo(
    () => resolveTickerEntries(tickerFilter, LS.get('ticker_groups', DEFAULT_GROUPS)).split(',').filter(Boolean),
    [tickerFilter]
  )

  const handleSaveList = useCallback(() => {
    const name = saveListName.trim()
    if (!name || !resolvedFilterTickers.length) return
    addWatchlist(name, resolvedFilterTickers.join(','))
    setSaveListPopover(false)
    setSaveListName('')
  }, [saveListName, resolvedFilterTickers, addWatchlist])

  const trades = useMemo(
    () => [...signals.map(mapSignal), ...injectedSignals.map(mapSignal)],
    [signals, injectedSignals, mapSignal, staleClock]
  )

  const staleSignalCount = useMemo(
    () => trades.filter((t) => t.signalStale && (!t.exitedAt || t.exitedAt === '-') && t.tradeStatus !== 'EXECUTED').length,
    [trades]
  )

  // Seed the feed with stubs for every ticker in the scan universe DURING an active scan only.
  // During a scan: missing tickers appear as "EN_COLA" (queued) until the real entry arrives.
  // After the scan ends: stubs are dropped — only tickers that were actually scanned remain.
  //   Backend retains rows (no mid-scan eviction) so the table shows real results, not phantoms.
  // Merge strategy: real entries (from backend) always overwrite stubs — Map preserves
  // insertion order and overwriting a key keeps the latest value.
  const filteredScanActivity = useMemo(() => {
    const base = scanActivity.filter((a) => !(a.detail && /^(Hot tickers:|Total:)\s*\d/.test(a.detail)))

    // When scan is idle, show only the real entries (no stubs). Filters out any leftover queued stubs.
    if (!scanning) return base

    // During active scan: pre-populate every ticker in the universe as EN_COLA.
    // Prefer allScanTickers (full 503 universe); fall back to scanningTickers (HOT batch).
    const scanningList = status?.allScanTickers?.length ? status.allScanTickers : (status?.scanningTickers ?? [])
    if (scanningList.length === 0) return base

    // Build map: ticker → activity; real entries from backend overwrite stubs
    const byTicker = new Map()
    // Insert stubs first (lowest priority)
    for (const ticker of scanningList) {
      byTicker.set(ticker, { ticker, status: 'EN_COLA', detail: '—', scanStarted: '-', scanEnded: '-', duration: '-' })
    }
    // Real backend entries overwrite stubs (higher priority)
    for (const a of base) {
      byTicker.set(a.ticker, a)
    }
    return Array.from(byTicker.values())
  }, [scanActivity, scanning, status?.allScanTickers, status?.scanningTickers])

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

      {replayActive && (
        <div className="replay-banner">⚠ REPLAY MODE ACTIVE — signals/brackets are replay runs (PAPER) ⚠</div>
      )}


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
          <div className="flex-align-center gap-10 ld-tickers-row">
            <div className="stat-label-sm color-muted ld-tickers-label">Tickers to scan</div>
            <div className="ld-save-list-wrap" ref={saveListWrapRef}>
              <button
                type="button"
                className="btn ld-save-list-btn"
                title="Guardar como Watchlist"
                disabled={!tickerFilter || scanning}
                onClick={() => {
                  setSaveListPopover((v) => !v)
                  setTimeout(() => saveListInputRef.current?.focus(), 50)
                }}
              >
                <BookmarkPlus size={15} />
              </button>
              {saveListPopover && (
                <div className="ld-save-list-popover">
                  <div className="text-xs color-muted mb-6">Guardar como Watchlist</div>
                  <input
                    ref={saveListInputRef}
                    type="text"
                    className="ticker-input ld-save-list-input"
                    placeholder="Nombre de la lista…"
                    value={saveListName}
                    onChange={(e) => setSaveListName(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') { e.preventDefault(); handleSaveList() }
                      if (e.key === 'Escape') { setSaveListPopover(false); setSaveListName('') }
                    }}
                  />
                  <div className="text-xs color-muted ld-save-list-preview">
                    {resolvedFilterTickers.slice(0, 6).join(', ')}
                    {resolvedFilterTickers.length > 6 ? ` +${resolvedFilterTickers.length - 6} más` : ''}
                  </div>
                  <div className="flex-align-center gap-8 mt-8">
                    <button type="button" className="btn btn-primary btn-sm" onClick={handleSaveList} disabled={!saveListName.trim()}>Guardar</button>
                    <button type="button" className="btn btn-secondary btn-sm" onClick={() => { setSaveListPopover(false); setSaveListName('') }}>Cancelar</button>
                  </div>
                </div>
              )}
            </div>
            <div className="ld-tickers-field">
              <TickerSelector value={tickerFilter} onChange={setTickerFilter} disabled={scanning} scope={tickerScope} onScopeChange={setTickerScope} hotTickers={hotTickersList} />
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

              <SwapButton
                active={status?.macroFilterEnabled ?? true}
                onText="Macro Filter" offText="Macro OFF"
                onClick={handleToggleMacroFilter}
                activeColor="#58a6ff" offColor="#f0883e"
                testId="live-toggle-macro-filter"
              />

              <div className="divider-v ld-divider" />

              <div className="flex-align-center gap-4">
                <span className="stat-label-sm color-muted">Concurrent:</span>
                <input type="number" min="1" max="16" value={maxConcurrent}
                  onChange={(e) => handleMaxConcurrentChange(e.target.value)} disabled={scanning}
                  className="ld-concurrent-input" />
              </div>

              <div className="divider-v ld-divider" />

              <div className="flex-align-center gap-4">
                <span className="stat-label-sm color-muted">Risk%:</span>
                <strong className="color-text ld-risk-val">{riskInput}</strong>
                <div className="flex-col gap-0">
                  <ChevronUp size={12} className="color-muted" style={{ cursor: 'pointer' }} onClick={() => handleRiskAdjust(1.0)} />
                  <ChevronDown size={12} className="color-muted" style={{ cursor: 'pointer' }} onClick={() => handleRiskAdjust(-1.0)} />
                </div>
              </div>

              <div className="divider-v ld-divider" />

              <SwapButton active={mockMarketOpen} onText="Mock Mkt" offText="Real Mkt" onClick={handleToggleMockMarket}
                activeColor="#f0883e" offColor="#3fb950" icon={mockMarketOpen ? ShieldAlert : ShieldCheck} testId="live-toggle-mock-market" />

              {mockMarketOpen && (
                <>
                  <button className="btn ld-mock-btn" onClick={handleInjectMockSignal} data-testid="live-inject-mock-signal">
                    <Zap size={14}/> Mock Signal
                  </button>
                  <div className="divider-v ld-divider"/>
                  <input
                    type="date"
                    className="ld-replay-date"
                    value={replayDate}
                    max={today}
                    onChange={e => setReplayDate(e.target.value)}
                    disabled={replayActive}
                  />
                  <button className="btn ld-speed-btn" onClick={cycleSpeed} disabled={replayActive}>
                    {replaySpeed}x
                  </button>
                  <button
                    className={`btn ${replayActive ? 'btn-secondary' : 'btn-primary'} ld-replay-start`}
                    onClick={replayActive ? handleStopReplay : handleStartReplay}
                  >
                    {replayActive ? <Square size={14}/> : <Play size={14}/>}
                  </button>
                </>
              )}

            </div>

          </div>

        </div>
      </div>

      <LiveTradeGrid
        trades={trades}
        scanActivity={filteredScanActivity}
        scanning={scanning}
        hotTickers={hotTickersList}
        scanScores={scanScores}
        macroRegime={macroRegime}
        staleSignalCount={staleSignalCount}
        onCloseTrade={handleCloseTrade}
        onCancelTrade={handleCancelTrade}
        onDeleteSignal={handleDeleteSignal}
        onClearStaleBatch={handleClearStaleBatch}
        onClearAllStale={handleClearAllStale}
        pendingActions={pendingActions}
        externalPositions={externalPositions}
        onCloseExternal={handleCloseExternal}
        onScheduleClose1450={handleScheduleClose1450}
      />
    </div>
  )
}
