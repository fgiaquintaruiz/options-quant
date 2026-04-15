import { useState, useEffect, useCallback } from 'react'
import { Play, Square, TrendingUp, TrendingDown, DollarSign, BarChart3, RefreshCw } from 'lucide-react'
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts'
import { backtestApi } from '../api'

export default function BacktestDashboard() {
  const [running, setRunning] = useState(false)
  const [report, setReport] = useState(null)
  const [equityData, setEquityData] = useState([])
  const [logs, setLogs] = useState([])
  const [elapsed, setElapsed] = useState(0)
  const [checkpoint, setCheckpoint] = useState(null)
  const [maxConcurrent, setMaxConcurrent] = useState(4)

  const addLog = useCallback((msg, type = 'info') => {
    setLogs(prev => [{ time: new Date().toLocaleTimeString(), msg, type }, ...prev].slice(0, 100))
  }, [])

  // Poll running state every 1s
  useEffect(() => {
    const checkRunning = async () => {
      try {
        const data = await backtestApi.isRunning()
        if (data.running && !running) {
          setRunning(true)
          addLog('Backtest started', 'info')
        } else if (!data.running && running) {
          setRunning(false)
          addLog('Backtest completed', 'success')
        }
      } catch (e) { /* silent */ }
    }
    checkRunning()
    const interval = setInterval(checkRunning, 1000)
    return () => clearInterval(interval)
  }, [running, addLog])

  // Update elapsed time while running
  useEffect(() => {
    if (!running) return
    const start = Date.now()
    const interval = setInterval(() => {
      setElapsed(Math.floor((Date.now() - start) / 1000))
    }, 1000)
    return () => clearInterval(interval)
  }, [running])

  // Check for checkpoint
  useEffect(() => {
    backtestApi.getCheckpoint().then(setCheckpoint).catch(() => {})
  }, [])

  // Fetch max concurrent scans on mount
  useEffect(() => {
    backtestApi.getMaxConcurrent().then(d => {
      if (d.maxConcurrentScans) setMaxConcurrent(d.maxConcurrentScans)
    }).catch(() => {})
  }, [])

  const handleRun = async () => {
    setRunning(true)
    setElapsed(0)
    setReport(null)
    addLog('Starting backtest...', 'info')
    try {
      const data = await backtestApi.runBacktest()
      if (data.stopped) {
        addLog('Backtest was stopped by user', 'warn')
        setRunning(false)
        return
      }
      if (data.success === false) {
        addLog(`Error: ${data.error}`, 'error')
        setRunning(false)
        return
      }

      setReport(data)
      setRunning(false)
      addLog(`Backtest complete: ${data.totalTrades} trades, ${data.winRate.toFixed(1)}% WR, $${data.totalPnl.toFixed(2)} PnL`, 'success')

      // Process equity curve
      if (data.equityCurve && data.equityCurve.length > 0) {
        setEquityData(data.equityCurve.map(p => ({ time: p.time, equity: p.equity })))
      }
    } catch (e) {
      addLog(`Error: ${e.message}`, 'error')
      setRunning(false)
    }
  }

  const handleStop = async () => {
    try {
      await backtestApi.stopBacktest()
      addLog('Stop requested', 'warn')
    } catch (e) { addLog(`Error: ${e.message}`, 'error') }
  }

  const handleResume = async () => {
    addLog(`Resuming from checkpoint (${checkpoint?.processedCount || 0} tickers done)...`, 'info')
    handleRun()
  }

  const handleMaxConcurrentChange = async (newVal) => {
    const val = parseInt(newVal, 10)
    if (isNaN(val) || val < 1 || val > 16) return
    setMaxConcurrent(val)
    try {
      const res = await backtestApi.setMaxConcurrent(val)
      addLog(res.success ? `Max concurrent set to ${val}` : res.message, res.success ? 'success' : 'error')
    } catch (e) { addLog(`Error: ${e.message}`, 'error') }
  }

  return (
    <div>
      {/* Run Backtest Card */}
      <div className="card" style={{ marginBottom: 20 }}>
        <h3>🚀 Run Backtest</h3>
        <p style={{ color: '#8b949e', marginBottom: 15 }}>
          Default: Hot tickers first, 1 year history, $50k capital, 2% risk
        </p>

        <button className="btn btn-primary" onClick={handleRun} disabled={running}
                style={{ display: running ? 'none' : 'inline-flex' }}>
          <Play size={16} /> Run Backtest
        </button>
        <button className="btn btn-danger" onClick={handleStop} disabled={!running}
                style={{ display: running ? 'inline-flex' : 'none', marginLeft: 10 }}>
          <Square size={16} /> Stop Run
        </button>

        {checkpoint?.exists && (
          <button className="btn btn-warning" onClick={handleResume} disabled={running}
                  style={{ marginLeft: 10 }}>
            <RefreshCw size={16} /> Resume ({checkpoint.processedCount} tickers)
          </button>
        )}

        <div style={{ marginTop: 15, display: 'flex', alignItems: 'center', gap: 10 }}>
          <span style={{ color: '#8b949e', fontSize: 14 }}>Concurrent Tickers:</span>
          <input type="number" min="1" max="16" value={maxConcurrent}
                 onChange={(e) => handleMaxConcurrentChange(e.target.value)}
                 disabled={running}
                 style={{
                   width: 60, padding: '4px 8px', background: '#0d1117', border: '1px solid #30363d',
                   borderRadius: 6, color: '#c9d1d9', fontSize: 14, textAlign: 'center'
                 }}
                 title="Number of tickers to analyze concurrently (1-16). Takes effect on next backtest run." />
        </div>

        {running && (
          <span style={{ marginLeft: 15, color: '#58a6ff' }}>
            Running... {elapsed}s elapsed
          </span>
        )}
      </div>

      {/* Results */}
      {report && (
        <>
          {/* Stats Cards */}
          <div className="grid">
            <div className="card">
              <h3>📊 Results</h3>
              <div className="stat">
                <span className="stat-label">Total Trades:</span>
                <span className="stat-value">{report.totalTrades}</span>
              </div>
              <div className="stat">
                <span className="stat-label">Win Rate:</span>
                <span className={`stat-value ${report.winRate >= 50 ? 'positive' : 'negative'}`}>
                  {report.winRate.toFixed(1)}%
                </span>
              </div>
              <div className="stat">
                <span className="stat-label">Total PnL:</span>
                <span className={`stat-value ${report.totalPnl >= 0 ? 'positive' : 'negative'}`}>
                  ${report.totalPnl.toFixed(2)}
                </span>
              </div>
              <div className="stat">
                <span className="stat-label">Profit Factor:</span>
                <span className="stat-value">{report.profitFactor.toFixed(2)}</span>
              </div>
              <div className="stat">
                <span className="stat-label">Max Drawdown:</span>
                <span className="stat-value">${report.maxDrawdown.toFixed(2)}</span>
              </div>
            </div>

            {/* Equity Curve Chart */}
            <div className="card" style={{ gridColumn: 'span 2' }}>
              <h3>📈 Equity Curve</h3>
              {equityData.length > 0 ? (
                <div className="chart-container">
                  <ResponsiveContainer width="100%" height="100%">
                    <LineChart data={equityData}>
                      <CartesianGrid strokeDasharray="3 3" stroke="#21262d" />
                      <XAxis dataKey="time" tick={{ fill: '#8b949e' }} />
                      <YAxis tick={{ fill: '#8b949e' }} />
                      <Tooltip contentStyle={{ background: '#161b22', border: '1px solid #30363d' }} />
                      <Line type="monotone" dataKey="equity" stroke="#58a6ff" strokeWidth={2} dot={false} />
                    </LineChart>
                  </ResponsiveContainer>
                </div>
              ) : (
                <p style={{ color: '#8b949e' }}>No equity data</p>
              )}
            </div>
          </div>

          {/* Strategy Performance */}
          {report.byStrategy && Object.keys(report.byStrategy).length > 0 && (
            <div className="card" style={{ marginBottom: 20 }}>
              <h3>🎯 Performance by Strategy</h3>
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>Strategy</th><th>Trades</th><th>Win Rate</th>
                      <th>Total PnL</th><th>Profit Factor</th><th>Max DD</th>
                    </tr>
                  </thead>
                  <tbody>
                    {Object.entries(report.byStrategy).map(([name, stats]) => (
                      <tr key={name}>
                        <td>{name}</td>
                        <td>{stats.trades}</td>
                        <td className={stats.winRate >= 50 ? 'positive' : 'negative'}>
                          {(stats.winRate * 100).toFixed(1)}%
                        </td>
                        <td className={stats.totalPnl >= 0 ? 'positive' : 'negative'}>
                          ${stats.totalPnl.toFixed(2)}
                        </td>
                        <td>{stats.profitFactor.toFixed(2)}</td>
                        <td>${stats.maxDrawdown.toFixed(2)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}

          {/* Ticker Performance */}
          {report.byTicker && Object.keys(report.byTicker).length > 0 && (
            <div className="card">
              <h3>📋 Performance by Ticker</h3>
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>Ticker</th><th>Trades</th><th>Win Rate</th>
                      <th>Total PnL</th><th>Profit Factor</th>
                    </tr>
                  </thead>
                  <tbody>
                    {Object.entries(report.byTicker).map(([ticker, stats]) => (
                      <tr key={ticker}>
                        <td><strong>{ticker}</strong></td>
                        <td>{stats.trades}</td>
                        <td className={stats.winRate >= 50 ? 'positive' : 'negative'}>
                          {(stats.winRate * 100).toFixed(1)}%
                        </td>
                        <td className={stats.totalPnl >= 0 ? 'positive' : 'negative'}>
                          ${stats.totalPnl.toFixed(2)}
                        </td>
                        <td>{stats.profitFactor.toFixed(2)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}
        </>
      )}

      {/* Console Log */}
      <div className="card" style={{ marginTop: 20 }}>
        <h3>💻 Console Log</h3>
        <div className="console-log">
          {logs.map((l, i) => (
            <div key={i} className={`log-entry log-${l.type}`}>
              [{l.time}] {l.msg}
            </div>
          ))}
          {logs.length === 0 && <div style={{ color: '#8b949e' }}>Waiting for backtest...</div>}
        </div>
      </div>
    </div>
  )
}
