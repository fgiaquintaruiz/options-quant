/**
 * BacktestDashboard — main page for running and reviewing backtests.
 *
 * Orchestrates the scan lifecycle (start/stop/SSE stream), persists the last
 * successful run to localStorage, and delegates rendering to sub-components:
 *   BacktestReportPanel — KPI stats and equity chart
 *   MemoryPanel         — persisted TP/SL ATR multiplier overrides
 *   ImproveModal        — per-strategy grid search and apply-to-memory workflow
 */
import React, { useState, useEffect, useCallback, useRef, useMemo } from 'react'
import { Play, Square, ChevronUp, ChevronDown, Monitor } from 'lucide-react'
import { backtestApi } from '../api'
import UnifiedDataGrid from '../components/UnifiedDataGrid'
import TickerSelector from '../components/TickerSelector'
import SwapButton from '../components/SwapButton'
import BacktestReportPanel from '../components/BacktestReportPanel'
import MemoryPanel from '../components/MemoryPanel'
import ImproveModal from '../components/ImproveModal'
import { LS } from '../utils/storage'
import {
  formatUsd,
  formatWinRatePct,
  computeScanDurationHms,
  computeTradeDuration,
  formatTradeTimeDisplay,
  downsampleEquityForChart,
} from '../utils/backtestFormatters'

const LS_BT_REPORT = 'bt_report'
const LS_BT_EQUITY = 'bt_equity'
const LS_BT_ACTIVITIES = 'bt_activities'
const LS_BT_LAST_META = 'bt_last_scan_meta'

// Clamps the JVM thread pool size to 1–16; defaults to 4 when the stored value is invalid.
function normalizeBtMaxConcurrent(v) {
  const n = Number(v)
  if (!Number.isFinite(n)) return 4
  return Math.min(16, Math.max(1, Math.round(n)))
}

// API field renaming shim: the run endpoint uses winRate/totalPnl; older code expected winRatePct/netPnl.
function normalizeBacktestReportPayload(data) {
  if (!data || data.success === false) return data
  return {
    ...data,
    winRatePct: data.winRate ?? data.winRatePct,
    netPnl: data.totalPnl ?? data.netPnl,
  }
}

// Normalizes equity curve points so Recharts always receives numeric equity values.
function normalizeEquityCurve(curve) {
  if (!curve || !Array.isArray(curve)) return []
  return curve
    .map((p, i) => ({
      time: p.time != null ? String(p.time) : (p.trade != null ? String(p.trade) : `T${i + 1}`),
      equity: Number(p.equity),
    }))
    .filter((p) => Number.isFinite(p.equity))
}

// Writes the last successful run to localStorage; downsamples the equity curve to avoid quota limits.
function persistLastBacktestSnapshot({ report, equityData, activities, meta }) {
  try {
    let eq = equityData || []
    if (eq.length > 1200) eq = downsampleEquityForChart(eq, 1200)
    LS.set(LS_BT_REPORT, report)
    LS.set(LS_BT_EQUITY, eq)
    LS.set(LS_BT_ACTIVITIES, activities || [])
    const fullMeta = { ...meta, savedAt: new Date().toISOString() }
    LS.set(LS_BT_LAST_META, fullMeta)
    return fullMeta
  } catch (e) {
    console.warn('persistLastBacktestSnapshot', e)
    return null
  }
}

export default function BacktestDashboard() {
  const [running, setRunning] = useState(false)
  const [report, setReport] = useState(() => LS.get(LS_BT_REPORT, null))
  const [equityData, setEquityData] = useState(() => LS.get(LS_BT_EQUITY, []))
  const [lastRunError, setLastRunError] = useState(null)
  const [activities, setActivities] = useState(() => LS.get(LS_BT_ACTIVITIES, []))
  const [lastScanMeta, setLastScanMeta] = useState(() => LS.get(LS_BT_LAST_META, null))
  const [elapsed, setElapsed] = useState(0)
  const [maxConcurrent, setMaxConcurrent] = useState(() => normalizeBtMaxConcurrent(LS.get('bt_maxConcurrent', 4)))

  const [tickerFilter, setTickerFilter] = useState(() => LS.get('bt_tickerFilter', ''))
  const [tickerScope, setTickerScope] = useState(() => LS.get('bt_tickerScope', 'HOT'))
  const [startParams, setStartParams] = useState(() => LS.get('bt_params', { capital: 50000, risk: 0.02 }))
  const [schedulerEnabled, setSchedulerEnabled] = useState(() => LS.get('bt_schedulerEnabled', false))
  const [schedulerFixedDelayMs, setSchedulerFixedDelayMs] = useState(null)

  const [improveOpen, setImproveOpen] = useState(false)
  const [improveTicker, setImproveTicker] = useState('')
  const [improveStrategyName, setImproveStrategyName] = useState(null)
  const [improveEntryTime, setImproveEntryTime] = useState(null)

  const [memoryPanelOpen, setMemoryPanelOpen] = useState(false)
  const [memoryRows, setMemoryRows] = useState([])
  const [memoryLoading, setMemoryLoading] = useState(false)
  const [memoryError, setMemoryError] = useState(null)

  const eventSourceRef = useRef(null)

  const equityChartData = useMemo(() => downsampleEquityForChart(equityData, 160), [equityData])

  const strategyBreakdownRows = useMemo(() => {
    const bs = report?.byStrategy
    if (!bs || typeof bs !== 'object') return []
    return Object.entries(bs)
      .map(([name, s]) => ({ name, trades: s?.trades, winRate: s?.winRate, totalPnl: s?.totalPnl }))
      .sort((a, b) => (Number(a.totalPnl) || 0) - (Number(b.totalPnl) || 0))
  }, [report])

  useEffect(() => {
    const loadScheduler = async () => {
      try {
        const d = await backtestApi.getScheduler()
        if (typeof d.schedulerEnabled === 'boolean') {
          setSchedulerEnabled(d.schedulerEnabled)
          LS.set('bt_schedulerEnabled', d.schedulerEnabled)
        }
        if (d.fixedDelayMs != null) setSchedulerFixedDelayMs(d.fixedDelayMs)
        if (d.schedulerEnabled) {
          if (d.initialCapital != null) setStartParams(p => ({ ...p, capital: Number(d.initialCapital) }))
          if (d.riskPct != null) setStartParams(p => ({ ...p, risk: Number(d.riskPct) }))
          if (d.tickerScope != null) setTickerScope(String(d.tickerScope))
          if (d.tickerFilter != null) setTickerFilter(String(d.tickerFilter))
        }
      } catch (e) { console.warn('[BacktestDashboard] loadScheduler:', e.message) }
    }
    loadScheduler()
  }, [])

  useEffect(() => {
    const checkRunning = async () => {
      if (eventSourceRef.current) return
      try {
        const data = await backtestApi.isRunning()
        setRunning(prev => {
          if (data.running && !prev) return true
          if (!data.running && prev) return false
          return prev
        })
        if (typeof data.schedulerEnabled === 'boolean') {
          setSchedulerEnabled(data.schedulerEnabled)
          LS.set('bt_schedulerEnabled', data.schedulerEnabled)
        }
        if (data.fixedDelayMs != null) setSchedulerFixedDelayMs(data.fixedDelayMs)
      } catch (e) { console.warn('[BacktestDashboard] checkRunning:', e.message) }
    }
    checkRunning()
    const interval = setInterval(checkRunning, 2000)
    return () => clearInterval(interval)
  }, [])

  useEffect(() => {
    if (!running) { setElapsed(0); return }
    const start = Date.now()
    const interval = setInterval(() => setElapsed(Math.floor((Date.now() - start) / 1000)), 1000)
    return () => clearInterval(interval)
  }, [running])

  useEffect(() => {
    const n = normalizeBtMaxConcurrent(LS.get('bt_maxConcurrent', 4))
    setMaxConcurrent(n)
    backtestApi.setMaxConcurrent(n).catch(() => {})
  }, [])

  useEffect(() => { LS.set('bt_maxConcurrent', maxConcurrent) }, [maxConcurrent])
  useEffect(() => { LS.set('bt_params', startParams) }, [startParams])
  useEffect(() => { LS.set('bt_tickerScope', tickerScope) }, [tickerScope])
  useEffect(() => { LS.set('bt_tickerFilter', tickerFilter) }, [tickerFilter])

  const mapTradesToActivities = useCallback((trades = []) => {
    return trades.map(t => {
      const entry = t.entryTime || ''
      const exit = t.exitTime || '-'
      return {
        ticker: t.ticker,
        pattern: t.pattern,
        strategy: t.strategy,
        startTime: entry || new Date().toLocaleTimeString(),
        endTime: exit,
        scanStarted: formatTradeTimeDisplay(entry),
        scanEnded: exit !== '-' ? formatTradeTimeDisplay(exit) : '—',
        scanDuration: computeTradeDuration(entry, exit),
        status: 'Complete',
        ep: t.ep || t.entryPrice || '0.00',
        xp: t.xp || t.exitPrice || '0.00',
        exitReason: t.exitReason,
        netPnl: t.netPnl,
        chartPath: t.chartPath || null,
      }
    })
  }, [])

  const syncSchedulerToServer = useCallback(async () => {
    try {
      await backtestApi.postScheduler({
        enabled: schedulerEnabled,
        initialCapital: startParams.capital,
        riskPct: startParams.risk,
        tickerFilter: tickerFilter || '',
        tickerScope: tickerScope || 'HOT',
      })
    } catch (e) { console.warn('[BacktestDashboard] syncScheduler:', e.message) }
  }, [schedulerEnabled, startParams, tickerFilter, tickerScope])

  const handleStartScan = useCallback(async () => {
    await syncSchedulerToServer()
    setRunning(true)
    setLastRunError(null)
    setElapsed(0)
    setReport(null)
    setEquityData([])
    setActivities([])

    if (eventSourceRef.current) eventSourceRef.current.close()

    const source = new EventSource('/backtest-ui/stream')
    eventSourceRef.current = source

    source.onopen = () => {
      backtestApi.runBacktest(startParams.capital, startParams.risk, tickerFilter, tickerScope).then(data => {
        setRunning(false)
        if (eventSourceRef.current) {
          eventSourceRef.current.close()
          eventSourceRef.current = null
        }
        if (data.stopped) { setLastRunError('Backtest was stopped.'); return }
        if (data.success === false) { setLastRunError(data.error || 'Backtest failed.'); return }
        const normalizedReport = normalizeBacktestReportPayload(data)
        const normalizedEquity = normalizeEquityCurve(data.equityCurve)
        const mappedActivities = data.trades?.length > 0 ? mapTradesToActivities(data.trades) : []
        setReport(normalizedReport)
        setEquityData(normalizedEquity)
        setActivities(mappedActivities)
        const meta = persistLastBacktestSnapshot({
          report: normalizedReport, equityData: normalizedEquity, activities: mappedActivities,
          meta: {
            tickerScope: tickerScope || 'HOT',
            tickerFilter: (tickerFilter || '').trim(),
            capital: startParams.capital,
            riskPct: startParams.risk,
          },
        })
        if (meta) setLastScanMeta(meta)
      }).catch(e => {
        setRunning(false)
        setLastRunError(e.message || 'Request failed')
        console.error(e)
      })
    }

    source.addEventListener('ticker_progress', (e) => {
      try {
        const d = JSON.parse(e.data)
        if (d.ticker && d.ticker !== 'ALL') {
          setActivities(prev => {
            const existing = prev.find(a => a.ticker === d.ticker && a.status !== 'Complete')
            if (existing) {
              const terminal = d.status === 'OK' || String(d.status).toUpperCase() === 'ERROR'
              const started = existing.scanStarted || existing.startTime
              const ended = terminal ? (d.time || started) : '-'
              return prev.map(a =>
                a.ticker === d.ticker && a.status !== 'Complete'
                  ? { ...a, status: d.status, detail: d.detail, scanStarted: started, startTime: started, scanEnded: ended, scanDuration: terminal ? computeScanDurationHms(started, d.time) : '—' }
                  : a
              )
            }
            const t0 = d.time || new Date().toLocaleTimeString()
            return [...prev, { ticker: d.ticker, pattern: '-', strategy: 'N/A', startTime: t0, endTime: '-', scanStarted: t0, scanEnded: '-', scanDuration: '—', status: d.status, detail: d.detail }].slice(-500)
          })
        }
      } catch (err) { console.warn('[BacktestDashboard] ticker_progress parse error:', err.message) }
    })

    source.addEventListener('trades', (e) => {
      try {
        const d = JSON.parse(e.data)
        if (d.trades) {
          const mapped = mapTradesToActivities(d.trades)
          setActivities(prev => [...prev, ...mapped].slice(-500))
        }
      } catch (err) { console.warn('[BacktestDashboard] trades parse error:', err.message) }
    })

    // SSE reconnects automatically on transient network errors; the run completes independently via the REST call.
    source.onerror = () => console.warn('[BacktestDashboard] SSE stream error — reconnecting automatically')
  }, [syncSchedulerToServer, startParams, tickerFilter, tickerScope, mapTradesToActivities])

  const handleStopScan = useCallback(async () => {
    try {
      await backtestApi.stopBacktest()
    } catch (e) {
      setLastRunError(e.message || 'Stop failed')
    }
  }, [])

  const loadMemoryRiskProfiles = useCallback(async () => {
    setMemoryLoading(true)
    setMemoryError(null)
    try {
      const rows = await backtestApi.getTickerMemoryRiskProfiles()
      setMemoryRows(Array.isArray(rows) ? rows : [])
    } catch (e) {
      setMemoryError(e.message || 'No se pudo cargar la memoria')
      setMemoryRows([])
    } finally {
      setMemoryLoading(false)
    }
  }, [])

  useEffect(() => {
    if (memoryPanelOpen) loadMemoryRiskProfiles()
  }, [memoryPanelOpen, loadMemoryRiskProfiles])

  const handleMaxConcurrentChange = useCallback(async (val) => {
    const n = parseInt(val, 10)
    if (isNaN(n) || n < 1 || n > 16) return
    setMaxConcurrent(n)
    try { await backtestApi.setMaxConcurrent(n) } catch (e) { console.warn('[BacktestDashboard] setMaxConcurrent:', e.message) }
  }, [])

  const handleCapitalAdjust = useCallback((delta) => {
    setStartParams(p => ({ ...p, capital: Math.max(1000, (Number(p.capital) || 0) + delta) }))
  }, [])

  const handleRiskAdjust = useCallback((delta) => {
    setStartParams(p => {
      const newVal = Math.max(0.1, Math.min(10, Number(p.risk) * 100 + delta))
      return { ...p, risk: Number((newVal / 100).toFixed(4)) }
    })
  }, [])

  const openImproveModal = useCallback((ctx) => {
    const strategyName = typeof ctx === 'string' ? ctx : ctx?.strategy
    if (!strategyName || !String(strategyName).trim()) return
    const ticker = typeof ctx === 'string' ? '' : String(ctx?.ticker || '').trim().toUpperCase()
    const entryTime = typeof ctx === 'string' ? null : ctx?.startTime ?? null
    setImproveTicker(ticker)
    setImproveStrategyName(String(strategyName).trim())
    setImproveEntryTime(entryTime)
    setImproveOpen(true)
  }, [])

  const handleModalClose = useCallback(({ gridResult, ticker: t, strategyName: strat, entryTime: et }) => {
    setImproveOpen(false)
    if (!t || !strat || et == null) return
    const wfOk = gridResult?.success && gridResult?.walkForwardSummary
    const gridWinnerOk = gridResult?.success && gridResult?.optimization?.hasWinner
    if (!wfOk && !gridWinnerOk) return
    let note = 'Grid listo'
    if (gridResult.walkForwardSummary) {
      const w = gridResult.walkForwardSummary
      note = `Walk-forward · ${w.foldsWithOosBacktest ?? 0} OOS · PnL agreg. ${formatUsd(w.aggregateOosPnl)}`
    } else {
      const opt = gridResult.optimization
      if (opt?.bestMetricValue != null) {
        note = `Grid · ${Number(opt.bestMetricValue).toFixed(0)} · tpΔ${Number(opt.bestParameters?.tpMultiplierDelta ?? 0).toFixed(1)} slΔ${Number(opt.bestParameters?.slMultiplierDelta ?? 0).toFixed(1)}`
      }
    }
    setActivities(prev =>
      prev.map(a =>
        a.ticker === t && String(a.strategy) === String(strat) && a.startTime === et
          ? { ...a, lastGridNote: note }
          : a
      )
    )
  }, [])

  return (
    <div className="flex-col" data-testid="backtest-dashboard">
      <div className="card bt-toolbar">
        <div className="flex-col gap-15">
          <div className="flex-align-center gap-15">
            <div className="stat-label-sm color-muted bt-toolbar-label">Tickers to scan</div>
            <div className="bt-ticker-selector-wrap">
              <TickerSelector
                value={tickerFilter}
                onChange={setTickerFilter}
                disabled={running}
                scope={tickerScope}
                onScopeChange={setTickerScope}
              />
            </div>
          </div>

          <div className="flex-between flex-wrap gap-20">
            <div className="flex-align-center gap-10">
              {running ? (
                <button type="button" className="btn btn-danger bt-run-btn" onClick={handleStopScan}>
                  <Square size={16} /> Stop Backtest
                </button>
              ) : (
                <button type="button" className="btn btn-primary bt-run-btn" onClick={handleStartScan}>
                  <Play size={16} /> Run Backtest
                </button>
              )}

              <div className="divider-v" />

              <SwapButton
                active={schedulerEnabled}
                onText="Auto Run"
                offText="Manual Run"
                testId="backtest-auto-run-toggle"
                onClick={async () => {
                  const next = !schedulerEnabled
                  try {
                    await backtestApi.postScheduler({
                      enabled: next,
                      initialCapital: startParams.capital,
                      riskPct: startParams.risk,
                      tickerFilter: tickerFilter || '',
                      tickerScope: tickerScope || 'HOT',
                    })
                    setSchedulerEnabled(next)
                    LS.set('bt_schedulerEnabled', next)
                  } catch (e) {
                    setLastRunError(`Scheduler: ${e.message}`)
                  }
                }}
                icon={Monitor}
              />

              {schedulerEnabled && schedulerFixedDelayMs != null && (
                <span className="stat-label-sm color-muted bt-scheduler-delay" title="Spring scheduled task; delay after each run completes">
                  ~{(schedulerFixedDelayMs / 3600000).toFixed(1)}h between runs
                </span>
              )}

              <div className="divider-v" />

              <div className="flex-align-center gap-4">
                <span className="stat-label-sm color-muted">Concurrent:</span>
                <input
                  type="number" min="1" max="16"
                  value={maxConcurrent}
                  onChange={e => handleMaxConcurrentChange(e.target.value)}
                  disabled={running}
                  className="bt-concurrent-input"
                />
              </div>

              {running && (
                <>
                  <div className="divider-v" />
                  <span className="color-info font-bold bt-elapsed">Running… {elapsed}s</span>
                </>
              )}
            </div>

            <div className="flex-align-center gap-20">
              <div className="stat-box">
                <span className="stat-label-sm color-muted">Capital:</span>
                <strong className="pill pill-info bt-capital-pill">${formatUsd(startParams?.capital ?? 0)}</strong>
                <div className="flex-col gap-1">
                  <ChevronUp size={14} className="color-muted bt-chevron" onClick={() => handleCapitalAdjust(5000)} />
                  <ChevronDown size={14} className="color-muted bt-chevron" onClick={() => handleCapitalAdjust(-5000)} />
                </div>
              </div>

              <div className="divider-v" />

              <div className="stat-box">
                <span className="stat-label-sm color-muted">Risk %:</span>
                <div className="flex-align-center gap-6">
                  <strong className="color-text bt-risk-value">
                    {(Number(startParams.risk) * 100).toFixed(1)}%
                  </strong>
                  <div className="flex-col gap-1">
                    <ChevronUp size={14} className="color-muted bt-chevron" onClick={() => handleRiskAdjust(0.5)} />
                    <ChevronDown size={14} className="color-muted bt-chevron" onClick={() => handleRiskAdjust(-0.5)} />
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      {lastRunError && (
        <div className="card bt-error-banner" role="alert">
          <span className="text-sm color-error">{lastRunError}</span>
        </div>
      )}

      <div className="flex-col gap-14">
        {report && (
          <BacktestReportPanel
            report={report}
            tickerFilter={tickerFilter}
            lastScanMeta={lastScanMeta}
            equityChartData={equityChartData}
          />
        )}

        <div className={`card ${report ? 'bt-tradelog-card--with-report' : ''}`}>
          <h3 className="m-0 mb-6">Trade log</h3>
          <p className="text-xs color-muted bt-tradelog-desc">
            <strong>Info</strong>: análisis + grid en el mismo modal. Orden: peor P&L primero.
          </p>
          <UnifiedDataGrid
            data={activities}
            onAnalyzeStrategy={(row) => openImproveModal(row)}
          />
        </div>

        {strategyBreakdownRows.length > 0 && (
          <div className="card bt-strategy-breakdown">
            <span className="color-muted">
              <strong>Por estrategía</strong> — peor P&L arriba:
            </span>
            {' '}
            {strategyBreakdownRows.map((row, i) => (
              <span key={row.name}>
                {i > 0 ? ' · ' : ''}
                <span className="bt-strategy-name">{row.name}</span>
                {' '}
                N{row.trades ?? '—'} WR{formatWinRatePct(row.winRate)}{' '}
                <span className={`font-bold ${(Number(row.totalPnl) || 0) >= 0 ? 'color-success' : 'color-error'}`}>
                  ${formatUsd(row.totalPnl)}
                </span>
              </span>
            ))}
          </div>
        )}

        <MemoryPanel
          open={memoryPanelOpen}
          rows={memoryRows}
          loading={memoryLoading}
          error={memoryError}
          onToggle={setMemoryPanelOpen}
          onError={setMemoryError}
          onReload={loadMemoryRiskProfiles}
        />
      </div>

      <ImproveModal
        open={improveOpen}
        ticker={improveTicker}
        strategyName={improveStrategyName}
        entryTime={improveEntryTime}
        startParams={startParams}
        maxConcurrent={maxConcurrent}
        running={running}
        onClose={handleModalClose}
        onMemoryApplied={() => { if (memoryPanelOpen) loadMemoryRiskProfiles() }}
        onStartScan={handleStartScan}
      />
    </div>
  )
}
