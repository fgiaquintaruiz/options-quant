import { NavLink, useLocation } from 'react-router-dom'
import { Activity, BarChart3, HeartPulse } from 'lucide-react'
import { useState, useEffect } from 'react'
import { liveApi } from './api'
import LiveDashboard from './pages/LiveDashboard'
import BacktestDashboard from './pages/BacktestDashboard'
import HealthPage from './pages/HealthPage'

export default function App() {
  const location = useLocation()
  const [twsStatus, setTwsStatus] = useState(null)
  const [marketStatus, setMarketStatus] = useState(null)
  const [marketCountdown, setMarketCountdown] = useState(null)
  const [now, setNow] = useState(new Date())

  // Clock interval updates every second
  useEffect(() => {
    const interval = setInterval(() => setNow(new Date()), 1000)
    return () => clearInterval(interval)
  }, [])

  // Poll TWS status every 5s
  useEffect(() => {
    const fetchTws = async () => {
      try {
        const data = await liveApi.getTwsStatus()
        setTwsStatus(data)
      } catch (e) { /* silent */ }
    }
    fetchTws()
    const interval = setInterval(fetchTws, 5000)
    return () => clearInterval(interval)
  }, [])

  useEffect(() => {
    const fetchMarket = async () => {
      try {
        const data = await liveApi.getMarketStatus()
        setMarketStatus(data)
        setMarketCountdown(data?.secondsToNextOpen ?? null)
      } catch (e) { /* silent */ }
    }
    fetchMarket()
    const interval = setInterval(fetchMarket, 15000)
    return () => clearInterval(interval)
  }, [])

  // Fixed dependency array: [marketCountdown] instead of [marketCountdown === null]
  useEffect(() => {
    if (marketCountdown == null || marketCountdown <= 0) return
    const interval = setInterval(() => {
      setMarketCountdown(prev => (prev == null ? null : Math.max(0, prev - 1)))
    }, 1000)
    return () => clearInterval(interval)
  }, [marketCountdown])

  const formatCountdown = (seconds) => {
    if (seconds == null) return ''
    const h = Math.floor(seconds / 3600)
    const m = Math.floor((seconds % 3600) / 60)
    const s = seconds % 60
    if (h > 0) return `${h}h ${m}m`
    if (m > 0) return `${m}m ${s}s`
    return `${s}s`
  }

  const isLive     = location.pathname === '/' || location.pathname.startsWith('/live')
  const isBacktest = location.pathname.startsWith('/backtest')
  const isHealth   = location.pathname.startsWith('/health')

  return (
    <div className="container">
      <div className="header-compact">
        <div className="flex-align-center gap-20">
          <h1 className="header-title">🧠 Options Quant Platform</h1>
          
          <div className="nav-compact">
            <NavLink to="/live" className={({ isActive }) => isActive ? 'active' : ''}>
              <Activity size={14} /> Loving Mode
            </NavLink>
            <NavLink to="/backtest" className={({ isActive }) => isActive ? 'active' : ''}>
              <BarChart3 size={14} /> Loved Mode
            </NavLink>
            <NavLink to="/health" className={({ isActive }) => isActive ? 'active' : ''}>
              <HeartPulse size={14} /> Health
            </NavLink>
          </div>
        </div>

        <div className="status-bar">
          {isLive && marketStatus && (
            <span className="market-badge">
              {marketStatus.session}
              {marketCountdown != null && marketStatus.session !== 'REGULAR' && marketCountdown > 0 && (
                <> · {formatCountdown(marketCountdown)}</>
              )}
            </span>
          )}
          
          <span className={`badge ${twsStatus?.connected ? 'badge-success' : 'badge-error'}`}>
            {twsStatus?.connected ? '🔌 TWS' : '🔐 TWS'}
          </span>

          <span className="color-muted text-md" style={{ minWidth: 65, textAlign: 'right' }}>
            {now.toLocaleTimeString()}
          </span>
        </div>
      </div>

      {/* Page Content */}
      <div style={{ display: isLive ? 'block' : 'none' }}>
        <LiveDashboard twsStatus={twsStatus} />
      </div>
      <div style={{ display: isBacktest ? 'block' : 'none' }}>
        <BacktestDashboard />
      </div>
      <div style={{ display: isHealth ? 'block' : 'none' }}>
        <HealthPage />
      </div>
    </div>
  )
}
