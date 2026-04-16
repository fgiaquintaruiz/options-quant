import React, { useState, useEffect, useCallback, useRef } from 'react'
import { Play, Square, Activity, TrendingUp, TrendingDown, DollarSign, Users } from 'lucide-react'
import { liveApi } from '../api'

export default function LiveDashboard({ twsStatus }) {
  const [status, setStatus] = useState(null)
  const [signals, setSignals] = useState([])
  const [tickers, setTickers] = useState({ allTickers: [], hotTickers: [] })
  const [logs, setLogs] = useState([])
  const [maxConcurrent, setMaxConcurrent] = useState(4)

  const addLog = useCallback((msg, type = 'info') => {
    setLogs(prev => [{ time: new Date().toLocaleTimeString(), msg, type }, ...prev].slice(0, 100))
  }, [])

  // Poll status every 500ms
  const lastLabelRef = useRef('')
  const lastScanCompleteTimeRef = useRef(0)
  useEffect(() => {
    const fetchStatus = async () => {
      try {
        const s = await liveApi.getStatus()
        setStatus(s)
        if (s.maxConcurrentScans !== undefined) {
          setMaxConcurrent(s.maxConcurrentScans)
        }
        // Only log when progress actually changes (avoid spam)
        const label = s.scannerBatchLabel || ''
        if (label && label !== lastLabelRef.current) {
          lastLabelRef.current = label
          addLog(`📊 ${label} (${s.scannerScanned}/${s.scannerTotal})`, 'info')
        }
        if (!s.isScanning && s.lastScanDuration && s.lastScanTime && s.lastScanTime !== lastScanCompleteTimeRef.current) {
          lastScanCompleteTimeRef.current = s.lastScanTime
          addLog(`✅ Scan complete in ${(s.lastScanDuration / 1000).toFixed(1)}s`, 'success')
        }
      } catch (e) { /* silent */ }
    }
    fetchStatus()
    const interval = setInterval(fetchStatus, 500)
    return () => clearInterval(interval)
  }, [addLog])

  // Poll scan activity every 1s for the feed table
  const [scanActivity, setScanActivity] = useState([])
  const feedRef = useRef(null)
  const followFeedRef = useRef(true)
  const lastActivityCompleteKeyRef = useRef('')

  // Auto-scroll feed to bottom when activity changes
  useEffect(() => {
    if (feedRef.current && followFeedRef.current) {
      feedRef.current.scrollTop = feedRef.current.scrollHeight
    }
  }, [scanActivity])

  useEffect(() => {
    const fetchActivity = async () => {
      try {
        const data = await fetch('/live-ui/scan-activity').then(r => r.json())
        let activity = data.activity || []
        // Clean up stale SCANNING entries when no scan is running OR stop was requested
        // This handles cases where app restarts OR user interrupts the scan
        const stopRequested = data.stopScanRequested || false
        if (!data.isScanning || stopRequested) {
          activity = activity.map(a =>
            a.status === 'SCANNING'
              ? { ...a, status: 'STALE', detail: stopRequested ? 'Stopped by user' : 'Interrupted (app restarted)' }
              : a
          )
        }
        setScanActivity(activity)
        if (!data.isScanning && data.scanned > 0) {
          const key = `${data.scanned}-${data.total || ''}-${data.lastScanTime || ''}`
          if (key !== lastActivityCompleteKeyRef.current) {
            lastActivityCompleteKeyRef.current = key
            setScanActivity(prev => [...prev.slice(-50), {
              time: new Date().toLocaleTimeString(),
              ticker: '---',
              status: '✅ Complete',
              detail: `${data.scanned} tickers analyzed`
            }])
          }
        }
      } catch (e) { /* silent */ }
    }
    fetchActivity()
    const interval = setInterval(fetchActivity, 1000)
    return () => clearInterval(interval)
  }, [])

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

  const handleStartScan = async () => {
    if (!twsStatus?.connected) {
      addLog('Please login in TWS with your account before scanning.', 'warn')
      return
    }
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

  const handleMaxConcurrentChange = async (newVal) => {
    const val = parseInt(newVal, 10)
    if (isNaN(val) || val < 1 || val > 16) return
    setMaxConcurrent(val)
    try {
      const res = await liveApi.setMaxConcurrent(val)
      addLog(res.success ? `Max concurrent set to ${val}` : res.message, res.success ? 'success' : 'error')
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
  const twsConnected = twsStatus?.connected || false
  const progress = status?.scannerTotal > 0
    ? (status.scannerScanned / status.scannerTotal * 100) : 0
  const scannedTickers = new Set(
    scanActivity
      .filter(a => a && a.ticker && a.ticker !== '---')
      .filter(a => ['OK', 'SIGNAL', 'ERROR'].includes(a.status))
      .map(a => a.ticker)
  )

  const formatSignalTime = (ts) => {
    if (!ts) return '-'
    const d = new Date(ts)
    if (isNaN(d.getTime())) return String(ts).substring(11, 16) || '-'
    return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
  }

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
          <div className="stat">
            <span className="stat-label">Concurrent Tickers:</span>
            <input type="number" min="1" max="16" value={maxConcurrent}
                   onChange={(e) => handleMaxConcurrentChange(e.target.value)}
                   style={{
                     width: 60, padding: '4px 8px', background: '#0d1117', border: '1px solid #30363d',
                     borderRadius: 6, color: '#c9d1d9', fontSize: 14, marginLeft: 8, textAlign: 'center'
                   }}
                   title="Number of tickers to analyze concurrently (1-16)" />
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
          <button className="btn btn-primary" onClick={handleStartScan} disabled={scanning || !twsConnected}
                  style={{ width: '100%', marginTop: 10, display: scanning || stopRequested ? 'none' : 'inline-flex' }}>
            <Play size={16} /> Start Scan
          </button>
          {!scanning && !stopRequested && !twsConnected && (
            <div style={{ marginTop: 8, color: '#f0883e', fontSize: 12 }}>
              Please login in TWS with your account
            </div>
          )}
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
            <span className="stat-label">Account:</span>
            <span className="stat-value">{twsStatus?.accountId || '-'}</span>
          </div>
          <div className="stat">
            <span className="stat-label">Risk/Trade:</span>
            <span className="stat-value">{twsStatus?.riskPerTrade || '-'}</span>
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

        {/* Tickers Queue */}
        <div className="card">
          <h3>📋 Tickers Queue</h3>
          <div className="ticker-list">
            {tickers.hotTickers.map(t => (
              <div key={t} className="ticker-item">
                <span>
                  <span className="badge badge-hot">HOT</span> {t}
                  {scannedTickers.has(t) && <span style={{ marginLeft: 6, color: '#3fb950' }}>✓</span>}
                </span>
                {scanning && status?.currentTicker === t && <span>Scanning...</span>}
              </div>
            ))}
            {tickers.allTickers
              .filter(t => !tickers.hotTickers.includes(t))
              .map(t => (
                <div key={t} className="ticker-item">
                  <span>
                    {t}
                    {scannedTickers.has(t) && <span style={{ marginLeft: 6, color: '#3fb950' }}>✓</span>}
                  </span>
                  {scanning && status?.currentTicker === t && <span>Scanning...</span>}
                </div>
              ))}
          </div>
        </div>
      </div>

      {/* Side-by-Side: Signals Feed & Console Log */}
      <div className="grid" style={{ gridTemplateColumns: '1.5fr 1fr' }}>
        {/* Live Signals Feed */}
        <div className="card" style={{ display: 'flex', flexDirection: 'column', height: '500px' }}>
          <h3>🎯 Live Signals Feed</h3>
          <div
            className="table-wrap"
            ref={feedRef}
            style={{ flex: 1 }}
            onScroll={() => {
              const el = feedRef.current
              if (!el) return
              const nearBottom = (el.scrollHeight - el.scrollTop - el.clientHeight) < 32
              followFeedRef.current = nearBottom
            }}
          >
            <table>
              <thead>
                <tr>
                  <th>Time</th><th>Ticker</th><th>Strategy</th><th>Dir</th>
                  <th>Price</th><th>TP</th><th>SL</th><th>Pattern</th><th>Status</th>
                </tr>
              </thead>
              <tbody>
                {signals.length === 0 && scanActivity.length === 0 ? (
                  <tr>
                    <td colSpan={9} style={{ textAlign: 'center', padding: 20, color: '#8b949e' }}>
                      {scanning ? 'Waiting for scan activity...' : 'No signals today'}
                    </td>
                  </tr>
                ) : (
                  <>
                    {/* Show actual signals first (pinned to top) */}
                    {signals.map((s, i) => (
                      <tr key={`signal-${i}`} style={{ background: '#23863615' }}>
                        <td>{formatSignalTime(s.timestamp)}</td>
                        <td><strong>{s.ticker}</strong></td>
                        <td>{s.strategy}</td>
                        <td className={s.direction === 'CALL' ? 'positive' : 'negative'}>{s.direction}</td>
                        <td>${s.currentPrice}</td>
                        <td>${s.tradePlan?.takeProfit || '-'}</td>
                        <td>${s.tradePlan?.stopLoss || '-'}</td>
                        <td>{s.candlestickPattern || '-'}</td>
                        <td><span className="badge badge-signal">NEW</span></td>
                      </tr>
                    ))}
                    {/* Show scan activity entries */}
                    {scanActivity.slice(-50).reverse().map((a, i) => (
                      <tr key={`activity-${i}`} style={a.status === 'SCANNING' ? { background: '#1f6feb10' } : {}}>
                        <td>{a.time || '-'}</td>
                        <td><strong>{a.ticker}</strong></td>
                        <td colSpan={5} style={{ color: '#8b949e' }}>{a.detail || ''}</td>
                        <td>-</td>
                        <td><span className={`badge ${
                          a.status === 'ERROR' ? 'badge-error' :
                          a.status === 'SIGNAL' ? 'badge-signal' :
                          a.status === 'OK' ? 'badge-ok' :
                          a.status === 'SCANNING' ? 'badge-scanning' :
                          a.status === 'STALE' ? 'badge-error' :
                          a.status === 'COMPLETE' || a.status === '✅ Complete' ? 'badge-success' :
                          'badge-info'
                        }`}>
                          {a.status}
                        </span></td>
                      </tr>
                    ))}
                  </>
                )}
              </tbody>
            </table>
          </div>
        </div>

        {/* Console Log */}
        <div className="card" style={{ display: 'flex', flexDirection: 'column', height: '500px' }}>
          <h3>💻 Console Log</h3>
          <div className="console-log" style={{ flex: 1, maxHeight: 'none' }}>
            {logs.map((l, i) => (
              <div key={i} className={`log-entry log-${l.type}`}>
                [{l.time}] {l.msg}
              </div>
            ))}
            {logs.length === 0 && <div style={{ color: '#8b949e' }}>Waiting for activity...</div>}
          </div>
        </div>
      </div>
    </div>
  )
}
