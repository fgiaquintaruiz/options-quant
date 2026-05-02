import React, { useState, useEffect, useMemo } from 'react'
import SaveListPopover from '../components/SaveListPopover'
import { liveApi } from '../api'
import LiveTradeGrid from '../components/LiveTradeGrid'
import ScanEngineControls from '../components/ScanEngineControls'
import TickerSelector, { resolveTickerEntries, DEFAULT_GROUPS } from '../components/TickerSelector'
import { LS } from '../utils/storage'
import { useWatchlists } from '../hooks/useWatchlists'
import { useExternalPositions } from '../hooks/useExternalPositions'
import { useScanCountdown } from '../hooks/useScanCountdown'
import { useLiveScanner } from '../hooks/useLiveScanner'
import { useScanSettings } from '../hooks/useScanSettings'
import { useTradeActions } from '../hooks/useTradeActions'

// ── LiveDashboard ──────────────────────────────────────────────────────────

/**
 * Live trading dashboard — scanner controls, IBKR connection status, signal grid.
 * UI state and trade actions live here; scanner polling is delegated to useLiveScanner.
 * Props: twsStatus (TWS connection state from parent — accountId, balance).
 */
export default function LiveDashboard({ twsStatus, marketOpen }) {
  const [errorMsg, setErrorMsg]           = useState(null)
  const [replayActive, setReplayActive]   = useState(false)
  const [tickerFilter, setTickerFilter]   = useState(() => LS.get('live_tickerFilter', ''))
  const [tickerScope, setTickerScope]     = useState(() => LS.get('live_tickerScope', 'HOT'))
  const { addGroup: addWatchlist }            = useWatchlists()

  const {
    status, signals, closedTrades, scanActivity, scanScores, scanStartedAt, staleClock,
    scanning, stopRequested, exclusiveScanLockHeld, hotTickersList,
    macroRegime, macroMomentum, macroSummary,
    forceStopReleasing, exclusiveLockSinceMs, exclusiveLockMinutes,
    fetchSignals,
    handleStartScan, handleStopScan, handleForceStop, handleToggleScheduler,
  } = useLiveScanner(setErrorMsg)

  const {
    maxConcurrent, riskInput, mockMarketOpen,
    handleMaxConcurrentChange, handleRiskAdjust,
    handleToggleAutoExecute, handleToggleMacroFilter, handleToggleMockMarket,
  } = useScanSettings({ status, setErrorMsg })

  const { nextScanSecs } = useScanCountdown({ scanning })

  const { positions: externalPositions, refresh: refreshExternal } = useExternalPositions()

  const {
    trades, staleSignalCount, pendingActions,
    handleCloseTrade, handleCancelTrade, handleDeleteSignal,
    handleClearStaleBatch, handleClearAllStale,
    handleCloseExternal, handleScheduleClose1450,
  } = useTradeActions({ signals, closedTrades, fetchSignals, setErrorMsg, externalPositions, refreshExternalPositions: refreshExternal, staleClock })

  // Sync filter/scope to backend on change
  useEffect(() => {
    LS.set('live_tickerFilter', tickerFilter)
    LS.set('live_tickerScope', tickerScope)
    const resolved = resolveTickerEntries(tickerFilter, LS.get('ticker_groups', DEFAULT_GROUPS))
    liveApi.setScanFilter(resolved, tickerScope).catch((e) => console.warn('[live] filter sync:', e.message))
  }, [tickerFilter, tickerScope])

  const canScan = (status?.marketHours || mockMarketOpen) && !scanning && !stopRequested

  const handleInjectMockSignal = async () => {
    setErrorMsg(null)
    const hot = hotTickersList.length > 0 ? hotTickersList : ['SPY', 'QQQ', 'AAPL', 'NVDA', 'TSLA']
    const ticker = hot[Math.floor(Math.random() * hot.length)]
    await liveApi.injectMockSignal(ticker)
      .then(fetchSignals)
      .catch(() => setErrorMsg('Injection failed'))
  }

  const resolvedFilterTickers = useMemo(
    () => resolveTickerEntries(tickerFilter, LS.get('ticker_groups', DEFAULT_GROUPS)).split(',').filter(Boolean),
    [tickerFilter]
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

  // ── Render ───────────────────────────────────────────────────────────────

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
            <SaveListPopover
              disabled={!tickerFilter || scanning}
              resolvedTickers={resolvedFilterTickers}
              onSave={(name, tickers) => addWatchlist(name, tickers.join(','))}
            />
            <div className="ld-tickers-field">
              <TickerSelector value={tickerFilter} onChange={setTickerFilter} disabled={scanning} scope={tickerScope} onScopeChange={setTickerScope} hotTickers={hotTickersList} />
            </div>
          </div>

          {/* Row 2: Engine controls */}
          <ScanEngineControls
            scanning={scanning}
            stopRequested={stopRequested}
            canScan={canScan}
            status={status}
            forceStopReleasing={forceStopReleasing}
            exclusiveScanLockHeld={exclusiveScanLockHeld}
            exclusiveLockSinceMs={exclusiveLockSinceMs}
            exclusiveLockMinutes={exclusiveLockMinutes}
            maxConcurrent={maxConcurrent}
            riskInput={riskInput}
            mockMarketOpen={mockMarketOpen}
            nextScanSecs={nextScanSecs}
            marketOpen={marketOpen}
            onStartScan={handleStartScan}
            onStopScan={handleStopScan}
            onForceStop={handleForceStop}
            onToggleScheduler={handleToggleScheduler}
            onToggleAutoExecute={handleToggleAutoExecute}
            onToggleMacroFilter={handleToggleMacroFilter}
            onMaxConcurrentChange={handleMaxConcurrentChange}
            onRiskAdjust={handleRiskAdjust}
            onToggleMockMarket={handleToggleMockMarket}
            onInjectMockSignal={handleInjectMockSignal}
            onReplayActiveChange={setReplayActive}
            onError={setErrorMsg}
          />

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
        replayActive={replayActive}
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
