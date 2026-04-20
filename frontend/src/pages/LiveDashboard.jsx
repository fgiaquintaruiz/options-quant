import React, { useState, useEffect, useCallback, useRef, useMemo } from 'react'
import { Play, Square, Zap, ChevronUp, ChevronDown, Monitor, Cpu, ShieldCheck, ShieldAlert, FlaskConical, BarChartHorizontal } from 'lucide-react'
import { liveApi } from '../api'
import LiveTradeGrid from '../components/LiveTradeGrid'
import TickerSelector from '../components/TickerSelector'
import SwapButton from '../components/SwapButton'
import { LS } from '../utils/storage'


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

  // Sync settings to backend
  const syncFilter = useCallback(async (filter, scope) => {
    try { await liveApi.setScanFilter(filter, scope) } catch (e) {}
  }, [])

  useEffect(() => {
    LS.set('live_tickerFilter', tickerFilter)
    LS.set('live_tickerScope', tickerScope)
    syncFilter(tickerFilter, tickerScope)
  }, [tickerFilter, tickerScope, syncFilter])

  useEffect(() => {
    LS.set('live_mockMarketOpen', mockMarketOpen)
  }, [mockMarketOpen])

  const handleFilterChange = (val) => setTickerFilter(val)
  const handleScopeChange = (val) => setTickerScope(val)

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
    try { await liveApi.startScan() } catch (e) { setErrorMsg(`Scan failed: ${e.message}`) }
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

  const handleToggleMockMarket = async () => {
    const prev = mockMarketOpen
    setMockMarketOpen(!prev)
    try { await liveApi.toggleMockMarket() } catch (e) {
      setMockMarketOpen(prev)
      setErrorMsg('Failed to toggle Mock Market')
    }
  }

  const handleRiskAdjust = async (delta) => {
    const current = parseFloat(riskInput)
    const newVal = Math.max(0.1, Math.min(10, current + delta))
    const formatted = newVal.toFixed(1)
    setRiskInput(formatted)
    LS.set('live_riskPct', formatted)
    try { await liveApi.setRisk(newVal) } catch (e) {}
  }

  const handleInjectMockSignal = async () => {
    setErrorMsg(null)
    try {
      const hot = hotTickersList.length > 0 ? hotTickersList : ['SPY', 'QQQ', 'AAPL', 'NVDA', 'TSLA']
      const ticker = hot[Math.floor(Math.random() * hot.length)]
      await liveApi.injectMockSignal(ticker)
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
      orderId: s.orderId,
      tpOrderId: s.tpOrderId,
      slOrderId: s.slOrderId
    }
  }, [closedTrades])

  const trades = useMemo(() => [
    ...signals.map(mapSignal),
    ...injectedSignals.map(mapSignal)
  ], [signals, injectedSignals, mapSignal])

  const handleCloseTrade = async (ticker, price, isOpenPos = false, tpOrderId = null, slOrderId = null) => {
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
        return;
      }
      // Close position: send market order to trigger TP/SL conditions
      const ok = await liveApi.closeTrade(ticker, price, tpOrderId, slOrderId)
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
    <div className="flex-col" data-testid="live-dashboard">
      
      {errorMsg && (
        <div className="card mb-12" style={{ background: '#f8514922', border: '1px solid #f8514944', padding: '10px 15px', display: 'flex', justifyContent: 'space-between' }}>
          <span className="color-error font-bold">{errorMsg}</span>
          <button onClick={() => setErrorMsg(null)} className="color-muted" style={{ background: 'none', border: 'none', cursor: 'pointer' }}>Dismiss</button>
        </div>
      )}

      {/* Toolbar */}
      <div className="card" style={{ padding: '12px 20px', marginBottom: 20 }} data-testid="live-toolbar">
        <div className="flex-col gap-15">
          {/* Row 1: Tickers */}
          <div className="flex-align-center gap-15">
            <div className="stat-label-sm color-muted" style={{ whiteSpace: 'nowrap' }}>Tickers to scan</div>
            <div style={{ flex: 1 }}>
              <TickerSelector
                value={tickerFilter}
                onChange={handleFilterChange}
                disabled={scanning}
                scope={tickerScope}
                onScopeChange={handleScopeChange}
              />
            </div>
          </div>

          {/* Row 2: Controls & Toggles & Stats */}
          <div className="flex-between flex-wrap gap-20">
            
            {/* Left: Engine Controls */}
            <div className="flex-align-center gap-10">
              {scanning || stopRequested ? (
                <button type="button" data-testid="live-stop-scan" className="btn btn-danger" onClick={handleStopScan} style={{ padding: '6px 14px', fontSize: 13, minWidth: 120 }}>
                  <Square size={16} /> Stop Scan
                </button>
              ) : (
                <button type="button" data-testid="live-start-scan" className="btn btn-primary" onClick={handleStartScan}
                  disabled={!canScan} style={{ padding: '6px 14px', fontSize: 13, minWidth: 120 }}>
                  <Play size={16} /> Start Scan
                </button>
              )}

              <div className="divider-v" style={{ height: 24 }} />

              <SwapButton 
                active={status?.schedulerEnabled} 
                onText="Auto Scan" 
                offText="Manual Scan" 
                onClick={() => liveApi.toggleScheduler().catch(() => {})}
                icon={Monitor}
                testId="live-toggle-scheduler"
              />

              <SwapButton 
                active={status?.autoExecute} 
                onText="Auto Open" 
                offText="Manual Open" 
                onClick={handleToggleAutoExecute}
                icon={Cpu}
                testId="live-toggle-auto-execute"
              />

              <div className="divider-v" style={{ height: 24 }} />

              <div className="flex-align-center gap-4">
                <span className="stat-label-sm color-muted">Concurrent:</span>
                <input type="number" min="1" max="16" value={maxConcurrent}
                  onChange={e => handleMaxConcurrentChange(e.target.value)} disabled={scanning}
                  style={{ width: 40, padding: '4px', background: '#0d1117', border: '1px solid #30363d', borderRadius: 4, color: '#c9d1d9', fontSize: 12, textAlign: 'center' }} />
              </div>

              <div className="divider-v" style={{ height: 24 }} />

              <SwapButton 
                active={mockMarketOpen} 
                onText="Mock Mkt" 
                offText="Real Mkt" 
                onClick={handleToggleMockMarket}
                activeColor="#f0883e"
                offColor="#3fb950"
                icon={mockMarketOpen ? ShieldAlert : ShieldCheck}
                testId="live-toggle-mock-market"
              />

               {mockMarketOpen && (
                 <button className="btn" onClick={handleInjectMockSignal}
                   style={{ 
                     padding: '6px 12px', fontSize: 12, 
                     background: '#f0883e22', 
                     border: `1px solid #f0883e66`, 
                     color: '#f0883e'
                   }}>
                   <Zap size={14} /> Mock Signal
                 </button>
               )}

              {status?.schedulerEnabled && (
                <div className="flex-col" style={{ marginLeft: 5 }}>
                  <span className="color-info font-bold" style={{ fontSize: 11 }}>{formatMinSec(nextScanSecs)}</span>
                </div>
              )}
            </div>

            {/* Right: Account Stats */}
            <div className="flex-align-center gap-20">
               <div className="stat-box">
                 <span className="stat-label-sm color-muted">Account:</span>
                 <strong className="pill pill-info" style={{ fontSize: 14 }}>{twsStatus?.accountId || 'OFFLINE'}</strong>
               </div>
               
               <div className="divider-v" style={{ height: 24 }} />
               
               <div className="stat-box">
                 <span className="stat-label-sm color-muted">Balance:</span>
                 <strong className="pill pill-success" style={{ fontSize: 15 }}>
                   {twsStatus?.balance > 0 ? `$${Number(twsStatus.balance).toLocaleString()}` : '$0'}
                 </strong>
               </div>

              <div className="divider-v" style={{ height: 24 }} />

              <div className="stat-box">
                <span className="stat-label-sm color-muted">Risk %:</span>
                <div className="flex-align-center gap-6">
                  <strong className="color-text" style={{ fontSize: 14, minWidth: 25 }}>{riskInput}</strong>
                  <div className="flex-col gap-1">
                    <ChevronUp size={14} className="color-muted" style={{ cursor: 'pointer' }} onClick={() => handleRiskAdjust(1.0)} />
                    <ChevronDown size={14} className="color-muted" style={{ cursor: 'pointer' }} onClick={() => handleRiskAdjust(-1.0)} />
                  </div>
                </div>
              </div>
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
