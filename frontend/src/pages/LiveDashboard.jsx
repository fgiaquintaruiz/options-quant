import React, { useState, useEffect, useCallback, useRef, useMemo } from 'react'
import { Play, Square } from 'lucide-react'
import { liveApi } from '../api'
import LiveTradeGrid from '../components/LiveTradeGrid'
import TickerSelector from '../components/TickerSelector'
import { LS } from '../utils/storage'

// Inline ON/OFF pill
function OnOffToggle({ value, onClick, disabled }) {
  return (
    <strong
      onClick={!disabled ? onClick : undefined}
      className={`badge ${value ? 'badge-success' : 'badge-error'}`}
      style={{ cursor: disabled ? 'default' : 'pointer', opacity: disabled ? 0.6 : 1, width: 45, textAlign: 'center' }}
    >
      {value ? 'ON' : 'OFF'}
    </strong>
  )
}

function Divider() {
  return <div className="divider-v" />
}

export default function LiveDashboard({ twsStatus }) {
  const [status, setStatus] = useState(null)
  const [signals, setSignals] = useState([])
  const [injectedSignals, setInjectedSignals] = useState([])
  const [scanActivity, setScanActivity] = useState([])
  const [elapsed, setElapsed] = useState(0)
  const [maxConcurrent, setMaxConcurrent] = useState(4)
  const [errorMsg, setErrorMsg] = useState(null)

  // Local storage persisted settings
  const [tickerFilter, setTickerFilter] = useState(() => LS.get('live_tickerFilter', ''))
  const [tickerScope, setTickerScope] = useState(() => LS.get('live_tickerScope', 'HOT'))
  const [riskInput, setRiskInput] = useState(() => LS.get('live_riskPct', '2.0'))
  
  // Mock market open state
  const [mockMarketOpen, setMockMarketOpen] = useState(() => LS.get('live_mockMarketOpen', false))
  const mockSignalIdx = useRef(0)

  // Fetch initial data
  useEffect(() => {
    const init = async () => {
      try {
        const s = await liveApi.getStatus()
        setStatus(s)
        setMaxConcurrent(s.maxConcurrentScans || 4)
        setRiskInput(String(s.riskPct || '2.0'))
      } catch (e) { setErrorMsg('Failed to connect to backend') }
    }
    init()
  }, [])

  // Poll activity and status
  useEffect(() => {
    const fetchActivity = async () => {
      try {
        const data = await liveApi.getScanActivity()
        setScanActivity(data.activity || [])
      } catch (e) {}
    }
    const fetchStatus = async () => {
      try {
        const s = await liveApi.getStatus()
        setStatus(s)
      } catch (e) {}
    }
    const interval = setInterval(() => {
      fetchActivity()
      fetchStatus()
    }, 1000)
    return () => clearInterval(interval)
  }, [])

  const [closedTrades, setClosedTrades] = useState({})
  const [pendingActions, setPendingActions] = useState({}) // ticker -> true/false

  // Poll signals
  const fetchSignals = useCallback(async () => {
    try {
      const data = await liveApi.getSignals()
      setSignals(data.signals || [])
      setClosedTrades(data.closedTrades || {})
    } catch (e) {}
  }, [])

  useEffect(() => {
    fetchSignals()
    const interval = setInterval(fetchSignals, 1500)
    return () => clearInterval(interval)
  }, [fetchSignals])

  // Sync settings to backend when they change locally
  const syncFilter = useCallback(async (filter, scope) => {
    try {
      await liveApi.setScanFilter(filter, scope)
    } catch (e) {}
  }, [])

  useEffect(() => {
    LS.set('live_tickerFilter', tickerFilter)
    LS.set('live_tickerScope', tickerScope)
    syncFilter(tickerFilter, tickerScope)
  }, [tickerFilter, tickerScope, syncFilter])

  useEffect(() => {
    LS.set('live_mockMarketOpen', mockMarketOpen)
  }, [mockMarketOpen])

  const handleFilterChange = (val) => {
    setTickerFilter(val)
  }

  const handleScopeChange = (val) => {
    setTickerScope(val)
  }

  // Countdown timer logic
  const [nextScanSecs, setNextScanSecs] = useState(null)
  useEffect(() => {
    const updateCountdown = () => {
      const now = new Date()
      const mins = now.getMinutes()
      const secs = now.getSeconds()
      const nextMin = 15 - (mins % 15)
      let remaining = (nextMin * 60) - secs
      if (remaining <= 0) remaining = 15 * 60
      setNextScanSecs(remaining)
    }
    updateCountdown()
    const interval = setInterval(updateCountdown, 1000)
    return () => clearInterval(interval)
  }, [])

  const formatMinSec = (s) => {
    if (s == null) return '--:--'
    const m = Math.floor(s / 60)
    const rs = s % 60
    return `${String(m).padStart(2, '0')}:${String(rs).padStart(2, '0')}`
  }

  const scanning      = status?.isScanning || false
  const stopRequested = status?.stopScanRequested || false
  const canScan       = (status?.marketHours || mockMarketOpen) && !scanning && !stopRequested
  const hotTickersList = status?.hotTickersList || []

  // Elapsed timer
  useEffect(() => {
    if (!scanning) { setElapsed(0); return }
    const start = Date.now()
    const interval = setInterval(() => setElapsed(Math.floor((Date.now() - start) / 1000)), 1000)
    return () => clearInterval(interval)
  }, [scanning])

  const handleStartScan = async () => {
    if (!canScan) return
    setErrorMsg(null)
    try {
      await liveApi.startScan()
    } catch (e) { setErrorMsg(`Scan failed: ${e.message}`) }
  }

  const handleStopScan = async () => {
    try { await liveApi.stopScan() } catch (e) {}
  }

  const handleMaxConcurrentChange = async (newVal) => {
    const val = parseInt(newVal, 10)
    if (isNaN(val) || val < 1 || val > 16) return
    setMaxConcurrent(val)
    try { await liveApi.setMaxConcurrent(val) } catch (e) {}
  }

  const handleToggleAutoExecute = async () => {
    try { await liveApi.toggleAutoExecute() } catch (e) {}
  }

  const handleToggleExtendedHours = async () => {
    try { await liveApi.toggleExtendedHours() } catch (e) {}
  }

  const handleToggleMockMarket = async () => {
    const prev = mockMarketOpen
    setMockMarketOpen(!prev)
    try {
      await liveApi.toggleMockMarket()
    } catch (e) {
      setMockMarketOpen(prev)
      setErrorMsg('Failed to toggle Mock Market')
    }
  }

  const handleRiskBlur = async () => {
    const val = parseFloat(riskInput)
    if (isNaN(val) || val < 0.1 || val > 10) return
    LS.set('live_riskPct', riskInput)
    try { await liveApi.setRisk(val) } catch (e) {}
  }

  const handleInjectMockSignal = async () => {
    setErrorMsg(null)
    try {
      const hot = hotTickersList.length > 0 ? hotTickersList : ['SPY', 'QQQ', 'AAPL', 'NVDA', 'TSLA']
      const ticker = hot[Math.floor(Math.random() * hot.length)]
      const res = await liveApi.injectMockSignal(ticker)
      if (!res.autoExecuted && status?.autoExecute) {
         console.warn('Mock signal injected but not auto-executed')
      }
      fetchSignals()
    } catch (e) { setErrorMsg('Injection failed') }
  }

  const mapSignal = useCallback((s) => {
    const timeMatch = s.timestamp ? s.timestamp.match(/T(\d{2}:\d{2}:\d{2})/) : null
    const startTime = timeMatch ? timeMatch[1] : new Date().toLocaleTimeString()
    const closed = closedTrades[s.ticker]
    return {
      ticker: s.ticker,
      pattern: s.candlestickPattern || 'signal',
      strategy: s.strategy,
      direction: s.direction,
      ep: s.currentPrice,
      startTime,
      endTime: closed ? closed.closeTime : '-',
      tp: s.tradePlan?.takeProfit,
      sl: s.tradePlan?.stopLoss,
      closePrice: closed ? closed.closePrice : null,
      xp: '-',
      exitReason: closed ? (closed.exitReason || 'MANUAL_CLOSE') : 'LIVE SIGNAL',
      netPnl: null,
      executeTime: s.executeTime,
      tradeStatus: s.tradeStatus,
      orderId: s.orderId
    }
  }, [closedTrades])

  // Fixed violation: Memoized derived state
  const trades = useMemo(() => [
    ...signals.map(mapSignal),
    ...injectedSignals.map(mapSignal)
  ], [signals, injectedSignals, mapSignal])

  const handleCloseTrade = async (ticker, price, isOpenPos = false) => {
    try {
      setPendingActions(prev => ({ ...prev, [ticker]: true }))
      if (isOpenPos) {
        const signal = trades.find(t => t.ticker === ticker && Number(t.ep) === Number(price))
        if (!signal) {
          setErrorMsg(`Could not find signal for ${ticker}`)
          setPendingActions(prev => ({ ...prev, [ticker]: false }))
          return
        }
        const ok = await liveApi.executeSignal(ticker, signal.direction, price, signal.strategy)
        if (!ok.success) setErrorMsg(`Execution failed: ${ok.message}`)
        setTimeout(() => {
          setPendingActions(prev => ({ ...prev, [ticker]: false }))
          fetchSignals()
        }, 800)
        return
      }
      const ok = await liveApi.closeTrade(ticker, price)
      if (ok.success) fetchSignals()
      setPendingActions(prev => ({ ...prev, [ticker]: false }))
    } catch (e) {
      setErrorMsg(`Action failed: ${e.message}`)
      setPendingActions(prev => ({ ...prev, [ticker]: false }))
    }
  }

  const handleCancelTrade = async (ticker, orderId) => {
    if (!orderId) return
    try {
      setPendingActions(prev => ({ ...prev, [ticker]: true }))
      const ok = await liveApi.cancelTrade(ticker, orderId)
      if (ok.success) fetchSignals()
      setPendingActions(prev => ({ ...prev, [ticker]: false }))
    } catch (e) {
      setErrorMsg(`Cancel failed: ${e.message}`)
      setPendingActions(prev => ({ ...prev, [ticker]: false }))
    }
  }

  const filteredScanActivity = useMemo(() => scanActivity.filter(
    a => !(a.detail && /^(Hot tickers:|Total:)\s*\d/.test(a.detail))
  ), [scanActivity])

  return (
    <div className="flex-col">
      
      {errorMsg && (
        <div className="card mb-12" style={{ background: '#f8514922', border: '1px solid #f8514944', padding: '10px 15px', display: 'flex', justifyContent: 'space-between' }}>
          <span className="color-error font-bold">{errorMsg}</span>
          <button onClick={() => setErrorMsg(null)} className="color-muted" style={{ background: 'none', border: 'none', cursor: 'pointer' }}>Dismiss</button>
        </div>
      )}

      {mockMarketOpen && (
        <div className="card mb-12" style={{ background: '#f0883e18', border: '1px solid #f0883e55', padding: '7px 14px' }}>
          <div className="flex-align-center gap-8 text-sm">
            <span style={{ fontSize: 16 }}>⚠️</span>
            <strong style={{ color: '#f0883e' }}>Mock Market Open</strong>
            <span className="color-muted">— scanning from existing CSVs, no data download, no TWS required</span>
          </div>
        </div>
      )}

      {/* ── Toolbar ── */}
      <div className="card" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20, padding: '12px 20px', flexWrap: 'wrap', gap: 20 }}>

        {/* Left: Tickers + Controls */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 20, flexWrap: 'wrap', flex: 1 }}>
          <div style={{ minWidth: 320, flex: 1 }}>
            <div className="stat-label-sm color-muted mb-6">Tickers to scan</div>
            <TickerSelector
              value={tickerFilter}
              onChange={handleFilterChange}
              disabled={scanning}
            />
          </div>

          <Divider />

          <div style={{ display: 'flex', alignItems: 'center', gap: 15 }}>
            {scanning || stopRequested ? (
              <button className="btn btn-danger" onClick={handleStopScan} style={{ padding: '8px 16px', display: 'flex', alignItems: 'center', gap: 8 }}>
                <Square size={16} /> Stop Scan
              </button>
            ) : (
              <button className="btn btn-primary" onClick={handleStartScan}
                disabled={!canScan} style={{ padding: '8px 16px', display: 'flex', alignItems: 'center', gap: 8, opacity: canScan ? 1 : 0.5, cursor: canScan ? 'pointer' : 'not-allowed' }}>
                <Play size={16} /> Start Scan
              </button>
            )}

            {mockMarketOpen && (
              <button
                className="btn"
                onClick={handleInjectMockSignal}
                style={{ padding: '6px 12px', background: '#f0883e22', border: '1px solid #f0883e66', color: '#f0883e', fontSize: 13, borderRadius: 6, cursor: 'pointer' }}
              >
                ⚡ Inject Signal
              </button>
            )}

            <div className="flex-col gap-4">
              <label className="flex-align-center gap-4 text-md color-muted" style={{ cursor: 'pointer' }}>
                <input
                  type="checkbox"
                  checked={status?.schedulerEnabled || false}
                  onChange={() => liveApi.toggleScheduler().catch(() => {})}
                  style={{ accentColor: '#58a6ff' }}
                />
                Auto-start
              </label>
              {status?.schedulerEnabled && (
                <span className="color-info font-bold" style={{ fontSize: 10 }}>
                  Next: {formatMinSec(nextScanSecs)}
                </span>
              )}
            </div>
          </div>

          <Divider />

          {/* Engine Params */}
          <div className="flex-align-center gap-20">
            <div className="stat-box">
              <span className="stat-label-sm">Concurrent:</span>
              <input type="number" min="1" max="16" value={maxConcurrent}
                onChange={e => handleMaxConcurrentChange(e.target.value)} disabled={scanning}
                style={{ width: 50, padding: '4px 6px', background: '#0d1117', border: '1px solid #30363d', borderRadius: 4, color: '#c9d1d9', fontSize: 13, textAlign: 'center' }} />
            </div>

            <div className="stat-box">
              <span className="stat-label-sm">Universe:</span>
              <div className="flex-align-center gap-4">
                <button
                  className="btn"
                  disabled={scanning}
                  onClick={() => handleScopeChange('ALL')}
                  style={{ padding: '4px 8px', background: tickerScope === 'ALL' ? '#1f6feb' : '#21262d', color: '#fff', fontSize: 11, border: '1px solid #30363d' }}
                >
                  All
                </button>
                <button
                  className="btn"
                  disabled={scanning}
                  onClick={() => handleScopeChange('HOT')}
                  style={{ padding: '4px 8px', background: tickerScope === 'HOT' ? '#9e6a03' : '#21262d', color: '#fff', fontSize: 11, border: '1px solid #30363d' }}
                >
                  Hot
                </button>
              </div>
            </div>
          </div>
        </div>

        {/* Right: Settings + Account Info */}
        <div style={{ display: 'flex', gap: 20, fontSize: 13, color: '#8b949e', alignItems: 'center', flexWrap: 'wrap' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <div className="flex-align-center gap-8">
              <span>Auto-Exec:</span>
              <OnOffToggle value={status?.autoExecute} onClick={handleToggleAutoExecute} />
            </div>
            <div className="flex-align-center gap-8">
              <span>Ext. Hours:</span>
              <OnOffToggle value={status?.extendedHoursEnabled} onClick={handleToggleExtendedHours} />
            </div>
            <div className="flex-align-center gap-8">
              <span>Mock:</span>
              <OnOffToggle value={mockMarketOpen} onClick={handleToggleMockMarket} />
            </div>
          </div>

          <Divider />

          <div style={{ display: 'flex', gap: 15, alignItems: 'center' }}>
            <div className="stat-box">
              <span className="stat-label-sm">Account:</span>
              <strong className="stat-value-md">{twsStatus?.accountId || 'OFFLINE'}</strong>
            </div>
            <div className="stat-box">
              <span className="stat-label-sm">Risk:</span>
              <div className="flex-align-center gap-4">
                <input
                  type="number" step="0.1" min="0.1" max="10"
                  value={riskInput}
                  onChange={e => setRiskInput(e.target.value)}
                  onBlur={handleRiskBlur}
                  style={{ width: 45, padding: '2px 4px', background: '#0d1117', border: '1px solid #30363d', borderRadius: 4, color: '#c9d1d9', fontSize: 12, textAlign: 'right' }}
                />
                <span className="text-xs">%</span>
              </div>
            </div>
            <div className="stat-box">
              <span className="stat-label-sm">Balance:</span>
              <strong className="stat-value-md color-success">
                {twsStatus?.balance > 0 ? `$${Number(twsStatus.balance).toLocaleString()}` : '$0'}
              </strong>
            </div>
          </div>
        </div>
      </div>

      {/* Main Grid */}
      <LiveTradeGrid
        trades={trades}
        scanActivity={filteredScanActivity}
        scanning={scanning}
        hotTickers={hotTickersList}
        onCloseTrade={handleCloseTrade}
        onCancelTrade={handleCancelTrade}
        pendingActions={pendingActions}
      />
    </div>
  )
}
