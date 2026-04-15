import { Routes, Route, NavLink, useLocation } from 'react-router-dom'
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

  // Poll TWS status every 10s
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

  useEffect(() => {
    if (marketCountdown == null) return
    const interval = setInterval(() => {
      setMarketCountdown(prev => (prev == null ? prev : Math.max(0, prev - 1)))
    }, 1000)
    return () => clearInterval(interval)
  }, [marketCountdown != null])

  const formatCountdown = (seconds) => {
    if (seconds == null) return ''
    const h = Math.floor(seconds / 3600)
    const m = Math.floor((seconds % 3600) / 60)
    const s = seconds % 60
    if (h > 0) return `${h}h ${m}m`
    if (m > 0) return `${m}m ${s}s`
    return `${s}s`
  }

  return (
    <div className="container">
      <div className="header">
        <h1>🧠 Options Quant Platform</h1>
        <div style={{ display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap', justifyContent: 'flex-end' }}>
          {location.pathname.startsWith('/live') && (
            <span className="badge badge-hot" style={{ fontSize: 12 }}>LIVE MODE</span>
          )}
          {location.pathname.startsWith('/live') && marketStatus && (
            <span style={{ color: '#8b949e', fontSize: 12 }}>
              {marketStatus.session}
              {marketCountdown != null && marketStatus.session !== 'REGULAR' && (
                <> · opens in {formatCountdown(marketCountdown)}</>
              )}
            </span>
          )}
          {location.pathname.startsWith('/live') && twsStatus && (
            <span style={{ color: '#8b949e', fontSize: 12 }}>
              {twsStatus.host}:{twsStatus.port}
            </span>
          )}
          <span className={`badge ${twsStatus?.connected ? 'badge-success' : 'badge-error'}`} style={{ fontSize: 12 }}>
            {twsStatus?.connected ? '🔌 TWS Connected' : '🔐 Please login in TWS with your account'}
          </span>
          <span id="clock" style={{ color: '#8b949e', fontSize: 14 }}>
            {new Date().toLocaleTimeString()}
          </span>
        </div>
      </div>

      <div className="nav">
        <NavLink to="/live" className={({ isActive }) => isActive ? 'active' : ''}>
          <Activity size={16} /> Live Trading
        </NavLink>
        <NavLink to="/backtest" className={({ isActive }) => isActive ? 'active' : ''}>
          <BarChart3 size={16} /> Backtest
        </NavLink>
        <NavLink to="/health" className={({ isActive }) => isActive ? 'active' : ''}>
          <HeartPulse size={16} /> Health
        </NavLink>
      </div>

      <Routes>
        <Route path="/" element={<LiveDashboard twsStatus={twsStatus} />} />
        <Route path="/live" element={<LiveDashboard twsStatus={twsStatus} />} />
        <Route path="/backtest" element={<BacktestDashboard />} />
        <Route path="/health" element={<HealthPage />} />
      </Routes>
    </div>
  )
}