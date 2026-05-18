import React, { useState, useEffect } from 'react'
import { Link } from 'react-router-dom'
import { Play, Square, Monitor, Cpu, OctagonAlert } from 'lucide-react'
import SwapButton from './SwapButton'
import AdvancedScanControls from './AdvancedScanControls'
import { formatMinSec } from '../utils/liveSignalUtils'
import { strategyConfigApi } from '../api'

/**
 * Row 2 of the LiveDashboard toolbar — engine controls.
 * All state lives in LiveDashboard; this component is purely presentational.
 */
export default function ScanEngineControls({
  scanning,
  stopRequested,
  canScan,
  status,
  forceStopReleasing,
  exclusiveScanLockHeld,
  exclusiveLockSinceMs,
  exclusiveLockMinutes,
  maxConcurrent,
  riskInput,
  mockMarketOpen,
  nextScanSecs,
  marketOpen,
  onStartScan,
  onStopScan,
  onForceStop,
  onToggleScheduler,
  onToggleAutoExecute,
  onToggleMacroFilter,
  onMaxConcurrentChange,
  onRiskAdjust,
  onToggleMockMarket,
  onInjectMockSignal,
  onReplayActiveChange,
  onError,
}) {
  const [liveCount, setLiveCount] = useState(0)

  useEffect(() => {
    const fetchCount = () => strategyConfigApi.findAll()
      .then(list => setLiveCount(list.filter(s => s.enabledLive).length))
      .catch(() => {})
    fetchCount()
    const id = setInterval(fetchCount, 30000)
    return () => clearInterval(id)
  }, [])

  const forceStopClass = forceStopReleasing ? 'ld-force-stop-btn--releasing'
    : exclusiveScanLockHeld ? 'ld-force-stop-btn--locked' : 'ld-force-stop-btn--free'
  const forceStopLabelClass = (forceStopReleasing || exclusiveScanLockHeld)
    ? 'live-force-stop-label ld-force-stop-label--bold'
    : 'live-force-stop-label ld-force-stop-label--normal'
  const forceStopTooltip = forceStopReleasing
    ? 'Esperando confirmación del servidor: interrupción de descargas IBKR y liberación del lock exclusivo…'
    : exclusiveScanLockHeld
      ? `IBKR: bloqueo exclusivo del scanner${status?.exclusiveScanOwnerThread ? ` — ${status.exclusiveScanOwnerThread}` : ''}. ~${exclusiveLockMinutes} min.`
      : 'Sin bloqueo exclusivo del scanner IBKR (libre).'

  return (
    <div className="flex-between flex-wrap gap-10 ld-controls-row">
      <div className="flex-align-center gap-10 flex-wrap">

        {/* Force stop / IBKR lock indicator */}
        <button type="button" data-testid="live-force-stop"
          className={`btn live-force-stop-btn ld-force-stop-btn ${forceStopClass}`}
          onClick={onForceStop} disabled={forceStopReleasing} title={forceStopTooltip}>
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
            onClick={onToggleScheduler}>
            <Monitor size={14} /> Auto Scan
          </button>

          {scanning || stopRequested ? (
            <button type="button" data-testid="live-stop-scan" className="ld-scan-btn-base ld-scan-stop-btn" onClick={onStopScan} title="Detener scan">
              <Square size={13} /> Stop
            </button>
          ) : (
            <button type="button" data-testid="live-start-scan"
              className={`ld-scan-btn-base ${canScan ? 'ld-scan-start-btn--on' : 'ld-scan-start-btn--off'}`}
              onClick={onStartScan} disabled={!canScan}
              title={canScan ? 'Iniciar scan manual' : 'Fuera de horario de mercado'}>
              <Play size={13} /> Scan
            </button>
          )}

          {status?.schedulerEnabled && <span className="ld-countdown" data-testid="scan-countdown">{formatMinSec(nextScanSecs)}</span>}
        </div>

        <SwapButton active={status?.autoExecute} onText="Auto Open" offText="Manual Open" onClick={onToggleAutoExecute} icon={Cpu} testId="live-toggle-auto-execute" />

        <SwapButton
          active={status?.macroFilterEnabled ?? true}
          onText="Macro Filter" offText="Macro OFF"
          onClick={onToggleMacroFilter}
          activeColor="#58a6ff" offColor="#f0883e"
          testId="live-toggle-macro-filter"
        />

        <div className="divider-v ld-divider" />

        <AdvancedScanControls
          scanning={scanning}
          mockMarketOpen={mockMarketOpen}
          maxConcurrent={maxConcurrent}
          riskInput={riskInput}
          marketOpen={marketOpen}
          onMaxConcurrentChange={onMaxConcurrentChange}
          onRiskAdjust={onRiskAdjust}
          onToggleMockMarket={onToggleMockMarket}
          onInjectMockSignal={onInjectMockSignal}
          onReplayActiveChange={onReplayActiveChange}
          onError={onError}
        />

        <Link to="/strategies" data-testid="strategies-badge" className="strategies-badge">
          ⚙ Strategies · {liveCount} active
        </Link>

      </div>
    </div>
  )
}
