import { Routes, Route, NavLink } from 'react-router-dom'
import { Activity, BarChart3, HeartPulse } from 'lucide-react'
import LiveDashboard from './pages/LiveDashboard'
import BacktestDashboard from './pages/BacktestDashboard'
import HealthPage from './pages/HealthPage'

export default function App() {
  return (
    <div className="container">
      <div className="header">
        <h1>🧠 Options Quant Platform</h1>
        <div style={{ display: 'flex', gap: 10, alignItems: 'center' }}>
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
        <Route path="/" element={<LiveDashboard />} />
        <Route path="/live" element={<LiveDashboard />} />
        <Route path="/backtest" element={<BacktestDashboard />} />
        <Route path="/health" element={<HealthPage />} />
      </Routes>
    </div>
  )
}
