import { useState, useEffect, useCallback } from 'react'
import { Play, Square, Activity, TrendingUp, TrendingDown, DollarSign, Users } from 'lucide-react'
import { liveApi } from '../api'

export default function LiveDashboard() {
  const [status, setStatus] = useState(null)
  const [signals, setSignals] = useState([])
  const [tickers, setTickers] = useState({ allTickers: [], hotTickers: [] })
  const [twsStatus, setTwsStatus] = useState(null)
  const [logs, setLogs] = useState([])

  const addLog = useCallback((msg, type = 'info') => {
    setLogs(prev => [{ time: new Date().toLocaleTimeString(), msg, type }, ...prev].slice(0, 100))
  }, [])

  // Poll status every 500ms
  useEffect(() => {
    const fetchStatus = async () => {
      try {
        const s = await liveApi.getStatus()
        setStatus(s)
        if (s.isScanning && s.scannerBatchLabel) {
          addLog(`Scan: ${s.scannerBatchLabel} (${s.scannerScanned}/${s.scannerTotal})`, 'info')
        }
        if (s.lastScanDuration && !s.isScanning) {
          addLog(`Scan complete in ${(s.lastScanDuration / 1000).toFixed(1)}s`, 'success')
        }
      } catch (e) { /* silent */ }
    }
    fetchStatus()
    const interval = setInterval(fetchStatus, 500)
    return () => clearInterval(interval)
  }, [addLog])

  // Poll signals every 3s
  useEffect(() => {
    const fetchSignals = async () => {
      try {
        const data = await liveApi.getSignals()
        setSignals(data.signals || [])
      } catch (e) { /* silent */ }
    }
    fetchSignals()
    const interval = setInterval(fetchSignals, 3000)
    return () => clearInterval(interval)
  }, [])

  // Poll tickers every 5s
  useEffect(() => {
    const fetchTickers = async () => {
      try {
        const data = await liveApi.getTickers()
        setTickers(data)
      } catch (e) { /* silent */ }
    }
    fetchTickers()
    const interval = setInterval(fetchTickers, 5000)
    return () => clearInterval(interval)
  }, [])

  // Poll TWS status every 10s
  useEffect(() => {
    const fetchTws = async () => {
      try {
        const data = await liveApi.getTwsStatus()
        setTwsStatus(data)
      } catch (e) { /* silent */ }
    }
    fetchTws()
    const interval = setInterval(fetchTws, 10000)
    return () => clearInterval(interval)
  }, [])

  const handleStartScan = async () => {
    try {
      const res = await liveApi.startScan()
      addLog(res.success ? 'Scan started' : res.message, res.success ? 'success' : 'error')
    } catch (e) { addLog(`Error: ${e.message}`, 'error') }
  }

  const handleStopScan = async () => {
    try {
      const res = await liveApi.stopScan()
      addLog(res.success ? 'Scan stopped' : res.message, res.success ? 'success' : 'warn')
    } catch (e) { addLog(`Error: ${e.message}`, 'error') }
  }

  const handleToggleExtendedHours = async () => {
    try {
      const res = await liveApi.toggleExtendedHours()
      addLog(`Extended hours: ${res.extendedHours ? 'ON' : 'OFF'}`, 'info')
    } catch (e) { addLog(`Error: ${e.message}`, 'error') }
  }

  const scanning = status?.isScanning || false
  const stopRequested = status?.stopScanRequested || false
  const progress = status?.scannerTotal > 0
    ? (status.scannerScanned / status.scannerTotal * 100) : 0

  return (
    <div>
      {/* Status Cards */}
      <div className="grid">
        {/* Scanning Status */}
        <div className="card">
          <h3>📡 Scanning Status</h3>
          <div className="stat">
            <span className="stat-label">Status:</span>
            <span className="stat-value">
              {stopRequested ? '⏹ Stopping...' : scanning ? '🔄 Scanning' : '⏸ Idle'}
            </span>
          </div>
          <div className="stat">
            <span className="stat-label">Progress:</span>
            <span className="stat-value">
              {status?.scannerBatchLabel || `${status?.scannerScanned || 0}/${status?.scannerTotal || 0}`}
            </span>
          </div>
          <div className="progress-bar">
            <div className="progress-fill" style={{ width: `${progress}%` }} />
          </div>
          {status?.lastScanTime && (
            <div className="stat">
              <span className="stat-label">Last Scan:</span>
              <span className="stat-value">
                {new Date(status.lastScanTime).toLocaleTimeString()}
                {status.lastScanDuration && ` (${(status.lastScanDuration / 1000).toFixed(1)}s)`}
              </span>
            </div>
          )}
          <button className="btn btn-primary" onClick={handleStartScan} disabled={scanning}
                  style={{ width: '100%', marginTop: 10, display: scanning || stopRequested ? 'none' : 'inline-flex' }}>
            <Play size={16} /> Start Scan
          </button>
          <button className="btn btn-danger" onClick={handleStopScan} disabled={!scanning}
                  style={{ width: '100%', marginTop: 10, display: scanning ? 'inline-flex' : 'none' }}>
            <Square size={16} /> Stop Scan
          </button>
        </div>

        {/* Signals Today */}
        <div className="card">
          <h3>📊 Signals Today</h3>
          <div className="stat">
            <span className="stat-label">Signals:</span>
            <span className="stat-value" style={{ fontSize: 24 }}>{status?.signalsToday || 0}</span>
          </div>
          <div className="stat">
            <span className="stat-label">Auto-Execute:</span>
            <div className="toggle-container">
              <div className={`toggle ${status?.autoExecute ? 'active' : ''}`} />
              <span style={{ fontSize: 12 }}>{status?.autoExecute ? 'ON' : 'OFF'}</span>
            </div>
          </div>
          <div className="stat">
            <span className="stat-label">Extended Hours:</span>
            <div className="toggle-container">
              <div className={`toggle ${status?.extendedHoursEnabled ? 'active' : ''}`}
                   onClick={handleToggleExtendedHours} />
              <span style={{ fontSize: 12 }}>{status?.extendedHoursEnabled ? 'ON' : 'OFF'}</span>
            </div>
          </div>
          <div className="stat">
            <span className="stat-label">Balance:</span>
            <span className="stat-value">
              {twsStatus?.balance > 0 ? `$${twsStatus.balance.toLocaleString()}` : 'N/A (no TWS)'}
            </span>
          </div>
          <div className="stat">
            <span className="stat-label">Active Trades:</span>
            <span className="stat-value">{twsStatus?.activeTrades || 0}</span>
          </div>
        </div>

        {/* TWS Connection */}
        <div className="card">
          <h3>🔌 TWS Connection</h3>
          <div className="stat">
            <span className="stat-label">Host:</span>
            <span className="stat-value">{twsStatus?.host || '-'}</span>
          </div>
          <div className="stat">
            <span className="stat-label">Port:</span>
            <span className="stat-value">{twsStatus?.port || '-'}</span>
          </div>
          <div className="stat">
            <span className="stat-label">Account:</span>
            <span className="stat-value">{twsStatus?.accountId || '-'}</span>
          </div>
          <div className="stat">
            <span className="stat-label">Risk/Trade:</span>
            <span className="stat-value">{twsStatus?.riskPerTrade || '-'}</span>
          </div>
        </div>

        {/* Tickers Queue */}
        <div className="card">
          <h3>📋 Tickers Queue</h3>
          <div className="ticker-list">
            {tickers.hotTickers.map(t => (
              <div key={t} className="ticker-item">
                <span><span className="badge badge-hot">HOT</span> {t}</span>
                {scanning && status?.currentTicker === t && <span>Scanning...</span>}
              </div>
            ))}
            {tickers.allTickers
              .filter(t => !tickers.hotTickers.includes(t))
              .slice(0, 50)
              .map(t => (
                <div key={t} className="ticker-item">
                  <span>{t}</span>
                  {scanning && status?.currentTicker === t && <span>Scanning...</span>}
                </div>
              ))}
            {tickers.allTickers.length > 50 + tickers.hotTickers.length && (
              <div style={{ textAlign: 'center', padding: 10, color: '#8b949e' }}>
                ...+{tickers.allTickers.length - 50 - tickers.hotTickers.length} more
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Live Signals Feed */}
      <div className="card">
        <h3>🎯 Live Signals Feed</h3>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Time</th><th>Ticker</th><th>Strategy</th><th>Dir</th>
                <th>Price</th><th>TP</th><th>SL</th><th>Pattern</th><th>Status</th>
              </tr>
            </thead>
            <tbody>
              {signals.length === 0 ? (
                <tr>
                  <td colSpan={9} style={{ textAlign: 'center', padding: 20, color: '#8b949e' }}>
                    {scanning ? `Scanning: ${status?.scannerBatchLabel || '...'}` : 'No signals today'}
                  </td>
                </tr>
              ) : signals.map((s, i) => (
                <tr key={i}>
                  <td>{s.timestamp?.substring(11, 16) || '-'}</td>
                  <td><strong>{s.ticker}</strong></td>
                  <td>{s.strategy}</td>
                  <td className={s.direction === 'CALL' ? 'positive' : 'negative'}>{s.direction}</td>
                  <td>${s.currentPrice}</td>
                  <td>${s.tradePlan?.takeProfit || '-'}</td>
                  <td>${s.tradePlan?.stopLoss || '-'}</td>
                  <td>{s.candlestickPattern || '-'}</td>
                  <td><span className="badge badge-hot">NEW</span></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {/* Console Log */}
      <div className="card" style={{ marginTop: 20 }}>
        <h3>💻 Console Log</h3>
        <div className="console-log">
          {logs.map((l, i) => (
            <div key={i} className={`log-entry log-${l.type}`}>
              [{l.time}] {l.msg}
            </div>
          ))}
          {logs.length === 0 && <div style={{ color: '#8b949e' }}>Waiting for activity...</div>}
        </div>
      </div>
    </div>
  )
}
