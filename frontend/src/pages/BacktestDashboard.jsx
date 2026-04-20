import React, { useState, useEffect, useCallback, useRef } from 'react'
import { Play, Square, ChevronUp, ChevronDown, Monitor } from 'lucide-react'
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts'
import { backtestApi } from '../api'
import UnifiedDataGrid from '../components/UnifiedDataGrid'
import TickerSelector from '../components/TickerSelector'
import SwapButton from '../components/SwapButton'
import { LS } from '../utils/storage'

/** @param {string} start @param {string} end HH:mm:ss same day */
function computeScanDurationHms(start, end) {
  if (!start || !end || end === '-') return '—'
  try {
    const parse = (t) => {
      const p = String(t).split(':').map(Number)
      return (p[0] || 0) * 3600 + (p[1] || 0) * 60 + (p[2] || 0)
    }
    let sec = parse(end) - parse(start)
    if (sec < 0) sec += 86400
    return `${sec.toFixed(1)}s`
  } catch {
    return '—'
  }
}

function computeTradeDuration(entry, exit) {
  if (!entry || !exit || exit === '-') return '—'
  const d1 = Date.parse(String(entry).replace(/_/g, ' '))
  const d2 = Date.parse(String(exit).replace(/_/g, ' '))
  if (Number.isNaN(d1) || Number.isNaN(d2)) return '—'
  const sec = Math.round((d2 - d1) / 1000)
  if (sec < 0) return '—'
  if (sec < 60) return `${sec.toFixed(1)}s`
  const m = Math.floor(sec / 60)
  const s = sec % 60
  if (m < 60) return `${m}m ${s}s`
  const h = Math.floor(m / 60)
  return `${h}h ${m % 60}m`
}

function formatTradeTimeDisplay(t) {
  if (!t || t === '-') return '—'
  const s = String(t).trim()
  if (s.length >= 16 && s.includes(' ')) return s.length > 19 ? s.slice(0, 19) : s
  return s
}

/** Safe for API fields that may be null/undefined */
function formatUsd(v) {
  const n = Number(v)
  return Number.isFinite(n) ? n.toLocaleString() : '—'
}

function formatSignedPct(v) {
  const n = Number(v)
  if (!Number.isFinite(n)) return '—'
  const sign = n > 0 ? '+' : ''
  return `${sign}${n.toFixed(2)}%`
}

/** Backtest engine pool size (1–16), persisted under bt_maxConcurrent */
function normalizeBtMaxConcurrent(v) {
  const n = Number(v)
  if (!Number.isFinite(n)) return 4
  return Math.min(16, Math.max(1, Math.round(n)))
}

/** POST /backtest-ui/run returns winRate, totalPnl; UI historically used winRatePct, netPnl */
function normalizeBacktestReportPayload(data) {
  if (!data || data.success === false) return data
  return {
    ...data,
    winRatePct: data.winRate ?? data.winRatePct,
    netPnl: data.totalPnl ?? data.netPnl,
  }
}

/** Equity points: API uses { time, equity }; ensure numeric equity for Recharts */
function normalizeEquityCurve(curve) {
  if (!curve || !Array.isArray(curve)) return []
  return curve
    .map((p, i) => ({
      time: p.time != null ? String(p.time) : (p.trade != null ? String(p.trade) : `T${i + 1}`),
      equity: Number(p.equity),
    }))
    .filter((p) => Number.isFinite(p.equity))
}

export default function BacktestDashboard() {
  // Transient scan state
  const [running, setRunning] = useState(false)
  const [report, setReport] = useState(null)
  const [equityData, setEquityData] = useState([])
  const [lastRunError, setLastRunError] = useState(null)
  const [activities, setActivities] = useState([])
  const [elapsed, setElapsed] = useState(0)
  const [maxConcurrent, setMaxConcurrent] = useState(() => normalizeBtMaxConcurrent(LS.get('bt_maxConcurrent', 4)))

  // User settings
  const [tickerFilter, setTickerFilter] = useState(() => LS.get('bt_tickerFilter', ''))
  const [tickerScope, setTickerScope] = useState(() => LS.get('bt_tickerScope', 'HOT'))
  const [startParams, setStartParams] = useState(() => LS.get('bt_params', { capital: 50000, risk: 0.02 }))
  const [schedulerEnabled, setSchedulerEnabled] = useState(() => LS.get('bt_schedulerEnabled', false))
  const [schedulerFixedDelayMs, setSchedulerFixedDelayMs] = useState(null)
  
  const eventSourceRef = useRef(null)

  // Side effects belonging in mount hook
  useEffect(() => {
    ;['bt_activities', 'bt_report', 'bt_equity', 'bt_running'].forEach(k => LS.remove(k))
  }, [])

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
          if (d.initialCapital != null) {
            setStartParams(p => ({ ...p, capital: Number(d.initialCapital) }))
          }
          if (d.riskPct != null) {
            setStartParams(p => ({ ...p, risk: Number(d.riskPct) }))
          }
          if (d.tickerScope != null) setTickerScope(String(d.tickerScope))
          if (d.tickerFilter != null) setTickerFilter(String(d.tickerFilter))
        }
      } catch (e) { /* silent */ }
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
      } catch (e) { /* silent */ }
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

  /** Persist pool size in the browser and align JVM once on load (old GET overwrote LS with server default). */
  useEffect(() => {
    const n = normalizeBtMaxConcurrent(LS.get('bt_maxConcurrent', 4))
    setMaxConcurrent(n)
    backtestApi.setMaxConcurrent(n).catch(() => {})
  }, [])

  useEffect(() => {
    LS.set('bt_maxConcurrent', maxConcurrent)
  }, [maxConcurrent])

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
        chartPath: t.chartPath || null
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
    } catch (e) { /* silent */ }
  }, [schedulerEnabled, startParams, tickerFilter, tickerScope])

  const handleStartScan = async () => {
    await syncSchedulerToServer()
    setRunning(true)
    setLastRunError(null)
    setElapsed(0)
    setReport(null)
    setEquityData([])
    setActivities([])

    if (eventSourceRef.current) {
      eventSourceRef.current.close()
    }

    const source = new EventSource('/backtest-ui/stream')
    eventSourceRef.current = source

    source.onopen = () => {
      backtestApi.runBacktest(startParams.capital, startParams.risk, tickerFilter, tickerScope).then(data => {
        setRunning(false)
        if (eventSourceRef.current) {
          eventSourceRef.current.close()
          eventSourceRef.current = null
        }
        if (data.stopped) {
          setLastRunError('Backtest was stopped.')
          return
        }
        if (data.success === false) {
          setLastRunError(data.error || 'Backtest failed.')
          return
        }
        setReport(normalizeBacktestReportPayload(data))
        setEquityData(normalizeEquityCurve(data.equityCurve))
        if (data.trades && data.trades.length > 0) {
          setActivities(mapTradesToActivities(data.trades))
        }
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
              const duration = terminal ? computeScanDurationHms(started, d.time) : '—'
              return prev.map(a =>
                a.ticker === d.ticker && a.status !== 'Complete'
                  ? {
                      ...a,
                      status: d.status,
                      detail: d.detail,
                      scanStarted: started,
                      startTime: started,
                      scanEnded: ended,
                      scanDuration: duration
                    }
                  : a
              )
            }
            const t0 = d.time || new Date().toLocaleTimeString()
            return [...prev, {
              ticker: d.ticker,
              pattern: '-',
              strategy: 'N/A',
              startTime: t0,
              endTime: '-',
              scanStarted: t0,
              scanEnded: '-',
              scanDuration: '—',
              status: d.status,
              detail: d.detail
            }].slice(-500)
          })
        }
      } catch (err) {}
    })

    source.addEventListener('trades', (e) => {
      try {
        const d = JSON.parse(e.data)
        if (d.trades) {
           const mapped = mapTradesToActivities(d.trades)
           setActivities(prev => [...prev, ...mapped].slice(-500))
        }
      } catch (err) {}
    })

    source.onerror = () => { /* SSE reconnects; run is independent */ }
  }

  const handleStopScan = async () => {
    try {
      await backtestApi.stopBacktest()
    } catch (e) {
      setLastRunError(e.message || 'Stop failed')
    }
  }

  const handleMaxConcurrentChange = async (val) => {
    const n = parseInt(val, 10)
    if (isNaN(n) || n < 1 || n > 16) return
    setMaxConcurrent(n)
    try { await backtestApi.setMaxConcurrent(n) } catch (e) {}
  }

  const handleCapitalAdjust = (delta) => {
    const current = Number(startParams.capital) || 0
    const newVal = Math.max(1000, current + delta)
    setStartParams(p => ({ ...p, capital: newVal }))
  }

  const handleRiskAdjust = (delta) => {
    const current = Number(startParams.risk) * 100 || 0
    const newVal = Math.max(0.1, Math.min(10, current + delta))
    setStartParams(p => ({ ...p, risk: Number((newVal / 100).toFixed(4)) }))
  }

  return (
    <div className="flex-col" data-testid="backtest-dashboard">
      
      {/* Toolbar — single-row Live-style */}
      <div className="card" style={{ padding: '12px 20px', marginBottom: 20 }}>
        <div className="flex-col gap-15">
          {/* Row 1: Tickers */}
          <div className="flex-align-center gap-15">
            <div className="stat-label-sm color-muted" style={{ whiteSpace: 'nowrap' }}>Tickers to scan</div>
            <div style={{ flex: 1 }}>
              <TickerSelector
                value={tickerFilter}
                onChange={setTickerFilter}
                disabled={running}
                scope={tickerScope}
                onScopeChange={setTickerScope}
              />
            </div>
          </div>

          {/* Row 2: Engine controls + Stats */}
          <div className="flex-between flex-wrap gap-20">
            
            {/* Left: Engine controls */}
            <div className="flex-align-center gap-10">
              {running ? (
                <button type="button" className="btn btn-danger" onClick={handleStopScan} style={{ padding: '6px 14px', fontSize: 13, minWidth: 120 }}>
                  <Square size={16} /> Stop Backtest
                </button>
              ) : (
                <button type="button" className="btn btn-primary" onClick={handleStartScan} style={{ padding: '6px 14px', fontSize: 13, minWidth: 120 }}>
                  <Play size={16} /> Run Backtest
                </button>
              )}

              <div className="divider-v" style={{ height: 24 }} />

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
                <span className="stat-label-sm color-muted" style={{ whiteSpace: 'nowrap' }} title="Spring scheduled task; delay after each run completes">
                  ~{(schedulerFixedDelayMs / 3600000).toFixed(1)}h between runs
                </span>
              )}

              <div className="divider-v" style={{ height: 24 }} />

              <div className="flex-align-center gap-4">
                <span className="stat-label-sm color-muted">Concurrent:</span>
                <input type="number" min="1" max="16" value={maxConcurrent}
                  onChange={e => handleMaxConcurrentChange(e.target.value)} disabled={running}
                  style={{ width: 40, padding: '4px', background: '#0d1117', border: '1px solid #30363d', borderRadius: 4, color: '#c9d1d9', fontSize: 12, textAlign: 'center' }} />
              </div>

              {running && (
                <>
                  <div className="divider-v" style={{ height: 24 }} />
                  <span className="color-info font-bold" style={{ fontSize: 12 }}>Running… {elapsed}s</span>
                </>
              )}
            </div>

            {/* Right: Backtest Params */}
            <div className="flex-align-center gap-20">
              <div className="stat-box">
                <span className="stat-label-sm color-muted">Capital:</span>
                <strong className="pill pill-info" style={{ fontSize: 14 }}>${formatUsd(startParams?.capital ?? 0)}</strong>
                <div className="flex-col gap-1">
                  <ChevronUp size={14} className="color-muted" style={{ cursor: 'pointer' }} onClick={() => handleCapitalAdjust(5000)} />
                  <ChevronDown size={14} className="color-muted" style={{ cursor: 'pointer' }} onClick={() => handleCapitalAdjust(-5000)} />
                </div>
              </div>

              <div className="divider-v" style={{ height: 24 }} />

              <div className="stat-box">
                <span className="stat-label-sm color-muted">Risk %:</span>
                <div className="flex-align-center gap-6">
                  <strong className="color-text" style={{ fontSize: 14, minWidth: 35 }}>
                    {(Number(startParams.risk) * 100).toFixed(1)}%
                  </strong>
                  <div className="flex-col gap-1">
                    <ChevronUp size={14} className="color-muted" style={{ cursor: 'pointer' }} onClick={() => handleRiskAdjust(0.5)} />
                    <ChevronDown size={14} className="color-muted" style={{ cursor: 'pointer' }} onClick={() => handleRiskAdjust(-0.5)} />
                  </div>
                </div>
              </div>
            </div>

          </div>
        </div>
      </div>

      {lastRunError && (
        <div
          className="card"
          style={{ padding: '10px 16px', marginBottom: 16, borderColor: '#f8514966', background: '#f8514914' }}
          role="alert"
        >
          <span className="text-sm" style={{ color: '#f85149' }}>{lastRunError}</span>
        </div>
      )}

      <div className="flex-col gap-20">
        {report && (
          <div className="card" data-testid="backtest-report-panel">
            <h3 className="m-0">Backtest results</h3>
            <p className="text-sm color-muted" style={{ marginTop: 10, maxWidth: 880, lineHeight: 1.55 }}>
              Summary of the last finished run: simulated closed trades on the ticker universe below, using the capital and risk
              from the toolbar. Dollar amounts are in account currency (USD). Win rate is the percentage of trades that closed with
              a gain; profit factor is gross profits divided by gross losses (above 1.0 means winners outweigh losers in dollars).
            </p>
            <p className="text-xs color-muted" style={{ marginTop: 8 }}>
              <strong>{report.tickerScope ?? '—'}</strong>
              {tickerFilter ? ` · filter (${tickerFilter})` : ''}
              {report.tickerCount != null && <> · {report.tickerCount} tickers</>}
              {report.initialCapital != null && report.finalCapital != null && (
                <> · start ${formatUsd(report.initialCapital)} → end ${formatUsd(report.finalCapital)}</>
              )}
            </p>

            <div className="grid" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: 12, marginTop: 16 }}>
              <ReportMetric
                label="Win rate"
                value={report.winRatePct != null ? `${Number(report.winRatePct).toFixed(1)}%` : '—'}
                sub="Share of trades that closed green"
                color={(Number(report.winRatePct) || 0) >= 50 ? '#3fb950' : '#f85149'}
              />
              <ReportMetric
                label="Closed trades"
                value={report.totalTrades ?? '—'}
                extra={
                  report.winningTrades != null && report.losingTrades != null
                    ? `${report.winningTrades} wins / ${report.losingTrades} losses`
                    : null
                }
                sub="Round-trip fills counted in this backtest"
              />
              <ReportMetric
                label="Net P&amp;L"
                value={`$${formatUsd(report.netPnl)}`}
                sub="End equity minus start (all trades)"
                color={(Number(report.netPnl) || 0) >= 0 ? '#3fb950' : '#f85149'}
              />
              <ReportMetric
                label="Total return"
                value={report.totalReturnPct != null ? formatSignedPct(report.totalReturnPct) : '—'}
                sub="On starting capital"
                color={(Number(report.totalReturnPct) || 0) >= 0 ? '#3fb950' : '#f85149'}
              />
              <ReportMetric
                label="Profit factor"
                value={report.profitFactor != null ? Number(report.profitFactor).toFixed(2) : '—'}
                sub={'Gross profit ÷ gross loss; >1 is good'}
                color={(Number(report.profitFactor) || 0) > 1 ? '#3fb950' : '#f85149'}
              />
              <ReportMetric
                label="Max drawdown"
                value={`$${formatUsd(report.maxDrawdown)}`}
                extra={report.maxDrawdownPct != null ? `${Number(report.maxDrawdownPct).toFixed(2)}% vs peak equity` : null}
                sub="Largest drop from a running high"
                color="#f85149"
              />
            </div>

            {equityData.length > 0 ? (
              <div style={{ marginTop: 22 }}>
                <div className="stat-label-sm color-muted" style={{ marginBottom: 10 }}>
                  Equity curve — account value over the simulated window (sampled timestamps)
                </div>
                <div className="chart-container" style={{ width: '100%' }}>
                  <ResponsiveContainer width="100%" height={300}>
                    <LineChart data={equityData} margin={{ top: 8, right: 12, left: 4, bottom: 4 }}>
                      <CartesianGrid strokeDasharray="3 3" stroke="#21262d" />
                      <XAxis
                        dataKey="time"
                        stroke="#8b949e"
                        fontSize={9}
                        interval="preserveStartEnd"
                        minTickGap={32}
                        tick={{ fill: '#8b949e' }}
                      />
                      <YAxis
                        stroke="#8b949e"
                        fontSize={10}
                        tick={{ fill: '#8b949e' }}
                        tickFormatter={(v) =>
                          new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 }).format(v)
                        }
                        domain={['auto', 'auto']}
                      />
                      <Tooltip
                        contentStyle={{ background: '#161b22', border: '1px solid #30363d', fontSize: 12 }}
                        formatter={(value) => [`$${Number(value).toLocaleString(undefined, { maximumFractionDigits: 0 })}`, 'Equity']}
                        labelFormatter={(label) => label}
                      />
                      <Line type="monotone" dataKey="equity" stroke="#58a6ff" strokeWidth={2} dot={false} isAnimationActive={false} />
                    </LineChart>
                  </ResponsiveContainer>
                </div>
              </div>
            ) : (
              <p className="text-sm color-muted" style={{ marginTop: 16 }}>
                No equity curve points for this run (no trades or engine did not emit curve samples).
              </p>
            )}
          </div>
        )}

        <div className="card">
          <h3 className="m-0 mb-12">Trade log</h3>
          <UnifiedDataGrid data={activities} />
        </div>
      </div>
    </div>
  )
}

function ReportMetric({ label, value, sub, extra, color }) {
  return (
    <div className="bg-card border-main rounded-md p-10-15 flex-col" style={{ minHeight: 102 }}>
      <div className="stat-label-sm color-muted">{label}</div>
      <div className="stat-value-md" style={{ color: color || '#c9d1d9', fontSize: 18, marginTop: 4 }}>{value}</div>
      {extra && <div className="text-xs color-muted" style={{ marginTop: 4 }}>{extra}</div>}
      {sub && <div className="text-xs color-muted" style={{ marginTop: 6, lineHeight: 1.35 }}>{sub}</div>}
    </div>
  )
}
