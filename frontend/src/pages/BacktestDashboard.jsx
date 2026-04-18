import React, { useState, useEffect, useCallback, useRef, useMemo } from 'react'
import { Play, Square, Activity, Settings, RefreshCw } from 'lucide-react'
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts'
import { backtestApi } from '../api'
import UnifiedDataGrid from '../components/UnifiedDataGrid'
import TickerSelector from '../components/TickerSelector'
import { LS } from '../utils/storage'

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
  
  const eventSourceRef = useRef(null)

  // Side effects belonging in mount hook
  useEffect(() => {
    ;['bt_activities', 'bt_report', 'bt_equity', 'bt_logs', 'bt_running'].forEach(k => LS.remove(k))
  }, [])

  const addLog = useCallback((msg, type = 'info') => {
    setLogs(prev => [{ time: new Date().toLocaleTimeString(), msg, type }, ...prev].slice(0, 100))
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
    return trades.map(t => ({
      ticker: t.ticker,
      pattern: t.pattern,
      strategy: t.strategy,
      direction: t.direction,
      startTime: t.entryTime || new Date().toLocaleTimeString(),
      endTime: t.exitTime || '-',
      status: 'Complete',
      ep: t.ep || t.entryPrice || '0.00',
      xp: t.xp || t.exitPrice || '0.00',
      exitReason: t.exitReason,
      netPnl: t.netPnl,
      chartPath: t.chartPath || null
    }))
  }, [])

  const handleStartScan = async () => {
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
        setReport(data)
        setEquityData(data.equityCurve || [])
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
              return prev.map(a =>
                a.ticker === d.ticker && a.status !== 'Complete'
                  ? { ...a, status: d.status, detail: d.detail }
                  : a
              )
            }
            return [...prev, {
              ticker: d.ticker,
              pattern: '-',
              strategy: 'N/A',
              direction: '-',
              startTime: d.time || new Date().toLocaleTimeString(),
              endTime: '-',
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

  return (
    <div className="flex-col gap-20">
      
      {/* Configuration Toolbar */}
      <div className="card toolbar-card">
        <div className="toolbar-section">
          
          <div className="flex-align-center gap-10">
            {running ? (
              <button className="btn btn-danger" onClick={handleStopScan} style={{ minWidth: 140 }}>
                <Square size={16} /> Stop
              </button>
            ) : (
              <button className="btn btn-primary" onClick={handleStartScan} style={{ minWidth: 140 }}>
                <Play size={16} /> Run Backtest
              </button>
            )}
          </div>

          <div className="divider-v" />

          <div className="stat-box">
            <span className="stat-label-sm">Concurrent:</span>
            <input type="number" min="1" max="16" value={maxConcurrent}
              onChange={e => handleMaxConcurrentChange(e.target.value)} disabled={running}
              style={{ width: 45, padding: '4px', background: '#0d1117', border: '1px solid #30363d', borderRadius: 4, color: '#c9d1d9', fontSize: 13, textAlign: 'center' }} />
          </div>

          <div className="flex-col gap-6" style={{ flex: 1, minWidth: 320 }}>
            <span className="stat-label-sm color-muted">Filter List:</span>
            <TickerSelector
              value={tickerFilter}
              onChange={setTickerFilter}
              disabled={running}
              scope={tickerScope}
              onScopeChange={setTickerScope}
            />
          </div>
          
          {running && (
            <span className="color-info font-bold text-md">Running... {elapsed}s</span>
          )}
        </div>
      </div>

      <div className="grid" style={{ gridTemplateColumns: '1fr 350px' }}>
        <div className="flex-col gap-20">
          {report && (
            <div className="card">
              <div className="flex-between mb-12">
                <h3 className="m-0">Report: {report.tickerScope} {tickerFilter ? `(${tickerFilter})` : ''}</h3>
                <span className="badge badge-success" style={{ fontSize: 14 }}>{report.winRatePct}% Win Rate</span>
              </div>
              <div className="grid" style={{ gridTemplateColumns: 'repeat(4, 1fr)', gap: 10 }}>
                <StatCard label="Trades" value={report.totalTrades} />
                <StatCard label="PF" value={report.profitFactor} color={report.profitFactor > 1 ? '#3fb950' : '#f85149'} />
                <StatCard label="PnL" value={`$${report.netPnl.toLocaleString()}`} color={report.netPnl >= 0 ? '#3fb950' : '#f85149'} />
                <StatCard label="Drawdown" value={`$${report.maxDrawdown.toLocaleString()}`} color="#f85149" />
              </div>
              
              {equityData.length > 0 && (
                <div className="chart-container" style={{ marginTop: 20 }}>
                  <ResponsiveContainer>
                    <LineChart data={equityData}>
                      <CartesianGrid strokeDasharray="3 3" stroke="#21262d" />
                      <XAxis dataKey="trade" stroke="#8b949e" fontSize={10} />
                      <YAxis stroke="#8b949e" fontSize={10} />
                      <Tooltip contentStyle={{ background: '#161b22', border: '1px solid #30363d', fontSize: 12 }} />
                      <Line type="monotone" dataKey="equity" stroke="#58a6ff" strokeWidth={2} dot={false} />
                    </LineChart>
                  </ResponsiveContainer>
                </div>
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
            <button className="btn" onClick={() => setLogs([])} style={{ padding: '2px 8px', fontSize: 11 }}>Clear</button>
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
