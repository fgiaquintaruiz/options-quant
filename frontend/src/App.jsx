import { NavLink, useLocation, Routes, Route, Navigate } from 'react-router-dom'
import { Activity, BarChart3, HeartPulse, Settings, History, Layers } from 'lucide-react'
import { useState, useEffect, useMemo } from 'react'
import { liveApi, accountApi } from './api'
import { LS } from './utils/storage'
import LiveDashboard from './pages/LiveDashboard'
import BacktestDashboard from './pages/BacktestDashboard'
import BacktestHistoryPage from './pages/BacktestHistoryPage'
import HealthPage from './pages/HealthPage'
import SettingsPage from './pages/SettingsPage'
import StrategiesPage from './pages/StrategiesPage'
import AccountMenuDropdown from './components/AccountMenuDropdown'
import { useStorageBackup } from './hooks/useStorageBackup'

export default function App() {
  useStorageBackup()
  const location = useLocation()
  const [twsStatus, setTwsStatus]         = useState(null)
  const [marketStatus, setMarketStatus]   = useState(null)
  const [marketCountdown, setMarketCountdown] = useState(null)
  const [now, setNow]                     = useState(new Date())
  const [macroRegime, setMacroRegime]     = useState(null)
  const [macroMomentum, setMacroMomentum] = useState(null)
  const [macroSummary, setMacroSummary]   = useState(null)
  const [accountMode, setAccountMode]     = useState(null)

  useEffect(() => {
    const id = setInterval(() => setNow(new Date()), 1000)
    return () => clearInterval(id)
  }, [])

  useEffect(() => {
    const fetch = async () => {
      try { setTwsStatus(await liveApi.getTwsStatus()) } catch { /* silent */ }
    }
    fetch()
    const id = setInterval(fetch, 5000)
    return () => clearInterval(id)
  }, [])

  useEffect(() => {
    const fetchMacro = async () => {
      try {
        const s = await liveApi.getStatus()
        setMacroRegime(s?.macroRegime   ?? null)
        setMacroMomentum(s?.macroMomentum ?? null)
        setMacroSummary(s?.macroSummary  ?? null)
      } catch { /* silent */ }
    }
    fetchMacro()
    const id = setInterval(fetchMacro, 5000)
    return () => clearInterval(id)
  }, [])

  useEffect(() => {
    const fetch = async () => {
      try {
        const data = await liveApi.getMarketStatus()
        setMarketStatus(data)
        setMarketCountdown(data?.secondsToNextOpen ?? null)
      } catch { /* silent */ }
    }
    fetch()
    const id = setInterval(fetch, 15000)
    return () => clearInterval(id)
  }, [])

  useEffect(() => {
    accountApi.getMode()
      .then((data) => setAccountMode(data?.mode ?? null))
      .catch(() => { /* silent */ })
  }, [])

  useEffect(() => {
    if (marketCountdown == null || marketCountdown <= 0) return
    const id = setInterval(() => setMarketCountdown((p) => (p == null ? null : Math.max(0, p - 1))), 1000)
    return () => clearInterval(id)
  }, [marketCountdown])

  const formatCountdown = (s) => {
    if (s == null) return ''
    const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = s % 60
    if (h > 0) return `${h}h ${m}m`
    if (m > 0) return `${m}m ${sec}s`
    return `${sec}s`
  }

  const isLive    = location.pathname === '/' || location.pathname.startsWith('/live')
  const marketOpen = marketStatus?.session === 'REGULAR'

  // Compute Spain (Europe/Madrid) equivalent of ET market hours — accounts for DST correctly
  const spainMarketHours = useMemo(() => {
    const now  = new Date()
    const et   = new Date(now.toLocaleString('en-US', { timeZone: 'America/New_York' }))
    const mad  = new Date(now.toLocaleString('en-US', { timeZone: 'Europe/Madrid' }))
    const diffMin = Math.round((mad - et) / 60000)
    const fmt = (h, m) => {
      const t = h * 60 + m + diffMin
      return `${String(Math.floor(t / 60) % 24).padStart(2, '0')}:${String(t % 60).padStart(2, '0')}`
    }
    return `${fmt(9, 30)}–${fmt(16, 0)}`
  }, [])

  return (
    <div className="container">
      <div className="header-compact">
        <div className="flex-align-center gap-20">
          <h1 className="header-title">🧠 Options Quant Platform</h1>
          <div className="nav-compact">
            <NavLink to="/live"     className={({ isActive }) => isActive ? 'active' : ''}><Activity size={14} /> Loving Mode</NavLink>
            <NavLink to="/backtest" className={({ isActive }) => isActive ? 'active' : ''}><BarChart3 size={14} /> Loved Mode</NavLink>
            <NavLink to="/backtest-history" className={({ isActive }) => isActive ? 'active' : ''}><History size={14} /> History</NavLink>
            <NavLink to="/settings" className={({ isActive }) => isActive ? 'active' : ''}><Settings size={14} /> Config</NavLink>
            <NavLink to="/health"   className={({ isActive }) => isActive ? 'active' : ''}><HeartPulse size={14} /> Health</NavLink>
            <NavLink to="/strategies" className={({ isActive }) => isActive ? 'active' : ''} data-testid="navlink-strategies"><Layers size={14} /> Strategies</NavLink>
          </div>
        </div>

        <div className="status-bar">
          {isLive && macroRegime && (
            <span
              className={`ld-macro-badge ld-macro-badge--${macroRegime.toLowerCase().replace('_', '-')}`}
              title={macroSummary || macroRegime}
            >
              {macroRegime.replace('_', ' ')} · {macroMomentum}
            </span>
          )}

          {isLive && (
            <span className={`market-status-chip ${marketOpen ? 'market-status-chip--open' : 'market-status-chip--closed'}`}>
              REGULAR · {spainMarketHours} ES
              {!marketOpen && marketCountdown != null && marketCountdown > 0 && ` · ${formatCountdown(marketCountdown)}`}
            </span>
          )}

          {isLive && (
            <AccountMenuDropdown
              twsStatus={twsStatus}
              accountMode={accountMode}
              now={now}
            />
          )}

          <span className="color-muted hdr-clock">
            {now.toLocaleTimeString('es-ES', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false })}
          </span>
        </div>
      </div>

      <Routes>
        <Route path="/"        element={<Navigate to="/live" replace />} />
        <Route path="/live"    element={<LiveDashboard twsStatus={twsStatus} marketOpen={marketOpen} />} />
        <Route path="/backtest" element={<BacktestDashboard />} />
        <Route path="/backtest-history" element={<BacktestHistoryPage />} />
        <Route path="/settings" element={<SettingsPage />} />
        <Route path="/health"  element={<HealthPage />} />
        <Route path="/strategies" element={<StrategiesPage />} />
        <Route path="*"        element={<Navigate to="/live" replace />} />
      </Routes>
    </div>
  )
}
