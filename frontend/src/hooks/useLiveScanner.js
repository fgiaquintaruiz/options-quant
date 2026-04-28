import { useState, useEffect, useCallback, useRef } from 'react'
import { liveApi } from '../api'

// Manages all scanner-related state: backend polling, signals, scan activity,
// force-stop flow, and IBKR exclusive lock tracking.
// Returns scanner data, derived status flags, and action handlers.
export function useLiveScanner(setErrorMsg) {
  const [status, setStatus] = useState(null)
  const [signals, setSignals] = useState([])
  const [closedTrades, setClosedTrades] = useState({})
  const [scanActivity, setScanActivity] = useState([])
  const [scanScores, setScanScores] = useState({})
  const [scanStartedAt, setScanStartedAt] = useState(null)
  const [staleClock, setStaleClock] = useState(0)
  const [forceStopReleasing, setFSReleasing] = useState(false)
  const [exclusiveLockSinceMs, setLockSince] = useState(null)
  const [exclusiveLockMinutes, setLockMins] = useState(0)
  const prevLockRef = useRef(false)
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
