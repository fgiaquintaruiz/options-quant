import React, { useState, useEffect, useCallback, useRef, useMemo } from 'react'
import { Play, Square, Activity, Settings, RefreshCw, Zap, ChevronUp, ChevronDown, Monitor } from 'lucide-react'
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
  const [logs, setLogs] = useState([])
  const [activities, setActivities] = useState([])
  const [elapsed, setElapsed] = useState(0)
  const [maxConcurrent, setMaxConcurrent] = useState(4)

  // User settings
  const [tickerFilter, setTickerFilter] = useState(() => LS.get('bt_tickerFilter', ''))
  const [tickerScope, setTickerScope] = useState(() => LS.get('bt_tickerScope', 'HOT'))
  const [startParams, setStartParams] = useState(() => LS.get('bt_params', { capital: 50000, risk: 0.02 }))
  const [schedulerEnabled, setSchedulerEnabled] = useState(() => LS.get('bt_schedulerEnabled', false))
  const [schedulerFixedDelayMs, setSchedulerFixedDelayMs] = useState(null)
  
  const eventSourceRef = useRef(null)

  // Side effects belonging in mount hook
  useEffect(() => {
    ;['bt_activities', 'bt_report', 'bt_equity', 'bt_logs', 'bt_running'].forEach(k => LS.remove(k))
  }, [])

  const addLog = useCallback((msg, type = 'info') => {
    setLogs(prev => [{ time: new Date().toLocaleTimeString(), msg, type }, ...prev].slice(0, 100))
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

  useEffect(() => {
    backtestApi.getMaxConcurrent().then(d => {
      if (d.maxConcurrentScans) setMaxConcurrent(d.maxConcurrentScans)
    }).catch(() => {})
  }, [])

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
    setElapsed(0)
    setReport(null)
    setEquityData([])
    setActivities([])
    addLog('Connecting to progress stream...', 'info')
    
    if (eventSourceRef.current) {
      eventSourceRef.current.close()
    }
    
    const source = new EventSource('/backtest-ui/stream')
    eventSourceRef.current = source
    
    source.onopen = () => {
      addLog('🚀 Stream connected! Starting engines...', 'success')
      
      backtestApi.runBacktest(startParams.capital, startParams.risk, tickerFilter, tickerScope).then(data => {
        setRunning(false)
        addLog('🏁 Backend scan loop finished', 'info')
        if (eventSourceRef.current) {
          eventSourceRef.current.close()
          eventSourceRef.current = null
        }
        if (data.stopped) {
          addLog('⏹ Backtest stopped', 'warn')
          return
        }
        if (data.success === false) {
          addLog(`❌ Error: ${data.error}`, 'error')
          return
        }
        setReport(normalizeBacktestReportPayload(data))
        setEquityData(normalizeEquityCurve(data.equityCurve))
        if (data.trades && data.trades.length > 0) {
          setActivities(mapTradesToActivities(data.trades))
        }
        addLog(`✅ Report generated: ${data.totalTrades} trades`, 'success')
      }).catch(e => {
        setRunning(false)
        addLog(`❌ Fatal: ${e.message}`, 'error')
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
           addLog(`📥 Received ${mapped.length} historical trades`, 'info')
        }
      } catch (err) {}
    })

    source.onerror = () => {
      addLog('⚠️ Stream connection issue (waiting for engine...)', 'warn')
    }
  }

  const handleStopScan = async () => {
    try {
      await backtestApi.stopBacktest()
      addLog('⏹ Stop signal sent to backend', 'warn')
    } catch (e) { addLog(`❌ Stop failed: ${e.message}`, 'error') }
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
                    addLog(`Scheduler update failed: ${e.message}`, 'error')
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

      <div className="grid" style={{ gridTemplateColumns: '1fr 350px' }}>
        <div className="flex-col gap-20">
          {report && (
            <div className="card">
              <div className="flex-between mb-12">
                <h3 className="m-0">Report: {report.tickerScope ?? '—'} {tickerFilter ? `(${tickerFilter})` : ''}</h3>
                <span className="badge badge-success" style={{ fontSize: 14 }}>
                  {report.winRatePct != null ? `${report.winRatePct}%` : '—'} Win Rate
                </span>
              </div>
              <div className="grid" style={{ gridTemplateColumns: 'repeat(4, 1fr)', gap: 10 }}>
                <StatCard label="Trades" value={report.totalTrades ?? '—'} />
                <StatCard
                  label="PF"
                  value={report.profitFactor != null ? Number(report.profitFactor).toFixed(2) : '—'}
                  color={(Number(report.profitFactor) || 0) > 1 ? '#3fb950' : '#f85149'}
                />
                <StatCard
                  label="PnL"
                  value={`$${formatUsd(report.netPnl)}`}
                  color={(Number(report.netPnl) || 0) >= 0 ? '#3fb950' : '#f85149'}
                />
                <StatCard label="Drawdown" value={`$${formatUsd(report.maxDrawdown)}`} color="#f85149" />
              </div>
              
              {equityData.length > 0 ? (
                <div className="chart-container" style={{ marginTop: 20, width: '100%' }}>
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
              ) : (
                <p className="text-sm color-muted" style={{ marginTop: 16 }}>
                  No equity curve points in this run (empty backtest or no downsampling output).
                </p>
              )}
            </div>
          )}

          <div className="card">
            <h3>Trade Log</h3>
            <UnifiedDataGrid data={activities} />
          </div>
        </div>

        <div className="card" style={{ display: 'flex', flexDirection: 'column' }}>
          <div className="flex-between mb-12">
            <h3>Backend Logs</h3>
            <button type="button" className="btn" onClick={() => setLogs([])} style={{ padding: '2px 8px', fontSize: 11 }}>Clear</button>
          </div>
          <div className="console-log" style={{ flex: 1, minHeight: 600 }}>
            {logs.map((log, i) => (
              <div key={i} className={`log-entry log-${log.type}`}>
                <span className="color-muted" style={{ marginRight: 8 }}>[{log.time}]</span>
                {log.msg}
              </div>
            ))}
            {logs.length === 0 && <div className="color-muted" style={{ textAlign: 'center', marginTop: 100 }}>No logs yet.</div>}
          </div>
        </div>
      </div>
    </div>
  )
}

function StatCard({ label, value, color }) {
  return (
    <div className="bg-card border-main rounded-md p-10-15 flex-col flex-center">
      <div className="stat-label-sm color-muted">{label}</div>
      <div className="stat-value-md" style={{ color: color || '#c9d1d9', fontSize: 18 }}>{value}</div>
    </div>
  )
}
