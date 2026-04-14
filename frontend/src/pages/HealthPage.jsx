import { useState, useEffect } from 'react'
import { CheckCircle, XCircle, Activity } from 'lucide-react'
import { healthApi } from '../api'

export default function HealthPage() {
  const [health, setHealth] = useState(null)
  const [liveness, setLiveness] = useState(null)
  const [readiness, setReadiness] = useState(null)
  const [error, setError] = useState(null)

  useEffect(() => {
    const fetchAll = async () => {
      try {
        const h = await healthApi.getHealth()
        setHealth(h)
        const l = await healthApi.getLiveness()
        setLiveness(l)
        const r = await healthApi.getReadiness()
        setReadiness(r)
        setError(null)
      } catch (e) {
        setError(e.message)
      }
    }
    fetchAll()
    const interval = setInterval(fetchAll, 5000)
    return () => clearInterval(interval)
  }, [])

  if (error) {
    return (
      <div className="card">
        <h3>❌ Health Check Failed</h3>
        <p style={{ color: '#f85149' }}>{error}</p>
      </div>
    )
  }

  if (!health) {
    return <div className="card"><h3>⏳ Loading health status...</h3></div>
  }

  const isUp = health.status === 'UP'

  return (
    <div>
      <div className="card" style={{ marginBottom: 20 }}>
        <h3>
          {isUp ? <CheckCircle color="#3fb950" style={{ marginRight: 8 }} /> : <XCircle color="#f85149" style={{ marginRight: 8 }} />}
          System Health
        </h3>
        <div className="stat">
          <span className="stat-label">Overall Status:</span>
          <span className={`stat-value ${isUp ? 'positive' : 'negative'}`}>
            {health.status}
          </span>
        </div>
        {health.groups && (
          <div className="stat">
            <span className="stat-label">Groups:</span>
            <span className="stat-value">{health.groups.join(', ')}</span>
          </div>
        )}
      </div>

      <div className="grid">
        <div className="card">
          <h3><Activity size={16} style={{ marginRight: 8 }} /> Liveness</h3>
          <div className="stat">
            <span className="stat-label">Status:</span>
            <span className={`stat-value ${liveness?.status === 'UP' ? 'positive' : 'negative'}`}>
              {liveness?.status || 'Unknown'}
            </span>
          </div>
        </div>

        <div className="card">
          <h3><Activity size={16} style={{ marginRight: 8 }} /> Readiness</h3>
          <div className="stat">
            <span className="stat-label">Status:</span>
            <span className={`stat-value ${readiness?.status === 'UP' ? 'positive' : 'negative'}`}>
              {readiness?.status || 'Unknown'}
            </span>
          </div>
        </div>
      </div>
    </div>
  )
}
