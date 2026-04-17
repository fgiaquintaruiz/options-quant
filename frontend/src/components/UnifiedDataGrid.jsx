import React, { useMemo, useState } from 'react'
import { Activity } from 'lucide-react'

const formatLabel = (value) => {
  if (!value) return '-'
  return String(value)
    .replace(/_/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
}

// Sub-components to avoid JSX-returning functions inside render
function StratCell({ value, ticker }) {
  const label = formatLabel(value)
  const isNa = label === 'N/A' || label === '-'
  return (
    <div className="flex-col">
      <span className="font-bold color-text" style={{ fontSize: 13 }}>{label}</span>
      {!isNa && <span className="text-xs color-muted">{ticker}</span>}
    </div>
  )
}

function DirectionCell({ value }) {
  const isCall = String(value).toUpperCase().includes('CALL')
  return (
    <span className={`badge ${isCall ? 'badge-success' : 'badge-error'}`} style={{ minWidth: 50 }}>
      {value}
    </span>
  )
}

function PriceCell({ value, color }) {
  if (value == null || value === '-' || value === 0) return <span className="color-muted">—</span>
  return (
    <span className="font-bold" style={{ color: color || '#c9d1d9', fontSize: 14 }}>
      ${Number(value).toFixed(2)}
    </span>
  )
}

function PnlCell({ value }) {
  if (value == null) return <span className="color-muted">—</span>
  const num = Number(value)
  const color = num > 0 ? '#3fb950' : num < 0 ? '#f85149' : '#8b949e'
  const sign = num > 0 ? '+' : ''
  return (
    <span className="font-bold" style={{ color, fontSize: 14 }}>
      {sign}{num.toFixed(2)}%
    </span>
  )
}

export default function UnifiedDataGrid({ data = [] }) {
  const [selectedRow, setSelectedTrade] = useState(null)

  const sortedData = useMemo(() => {
    return [...data].sort((a, b) => {
      // Keep active/pending at top
      if (a.status !== 'Complete' && b.status === 'Complete') return -1
      if (a.status === 'Complete' && b.status !== 'Complete') return 1
      // Sort by start time descending
      return String(b.startTime).localeCompare(String(a.startTime))
    })
  }, [data])

  const renderStatus = (row) => {
    const status = row.status || 'Active'
    let color = '#8b949e'
    let bg = '#21262d'
    
    if (status === 'Complete' || status === 'OK') { color = '#3fb950'; bg = '#23863626' }
    if (status === 'Scanning' || status === 'Running') { color = '#58a6ff'; bg = '#388bfd26' }
    if (status === 'Signal') { color = '#f0883e'; bg = '#f0883e26' }
    if (status === 'Error') { color = '#f85149'; bg = '#f8514926' }

    return (
      <span className="badge" style={{ background: bg, color, border: `1px solid ${color}44` }}>
        {status.toUpperCase()}
      </span>
    )
  }

  return (
    <div className="flex-col w-full">
      <div className="table-wrap" style={{ border: '1px solid #30363d', borderRadius: 8, overflow: 'hidden' }}>
        <table className="w-full">
          <thead>
            <tr className="bg-card">
              <th>Ticker</th>
              <th>Strategy</th>
              <th style={{ textAlign: 'center' }}>Dir</th>
              <th>Entry</th>
              <th>Exit</th>
              <th>PnL</th>
              <th>Status</th>
              <th>Time</th>
              <th style={{ textAlign: 'center' }}>Chart</th>
            </tr>
          </thead>
          <tbody>
            {sortedData.map((row, idx) => (
              <tr key={`${row.ticker}-${idx}`} style={{ opacity: row.status === 'Complete' ? 0.8 : 1 }}>
                <td>
                  <div className="flex-col">
                    <strong className="text-md color-text">{row.ticker}</strong>
                    <span className="text-xs color-muted">{row.pattern || '-'}</span>
                  </div>
                </td>
                <td><StratCell value={row.strategy} ticker={row.ticker} /></td>
                <td style={{ textAlign: 'center' }}><DirectionCell value={row.direction} /></td>
                <td><PriceCell value={row.ep} /></td>
                <td><PriceCell value={row.xp} color={Number(row.xp) > Number(row.ep) ? '#3fb950' : '#f85149'} /></td>
                <td><PnlCell value={row.netPnl} /></td>
                <td style={{ textAlign: 'center' }}>{renderStatus(row)}</td>
                <td>
                  <div className="flex-col text-xs color-muted">
                    <span>In: {row.startTime}</span>
                    <span>Out: {row.endTime || '-'}</span>
                  </div>
                </td>
                <td style={{ textAlign: 'center' }}>
                  {row.chartPath ? (
                    <button
                      className="btn"
                      onClick={() => setSelectedTrade(row)}
                      style={{ padding: '4px', background: 'none', color: '#58a6ff' }}
                    >
                      <Activity size={16} />
                    </button>
                  ) : <span className="color-muted">—</span>}
                </td>
              </tr>
            ))}
            {sortedData.length === 0 && (
              <tr>
                <td colSpan="9" style={{ padding: '40px', textAlign: 'center', color: '#8b949e' }}>
                  No data available. Run a scan or backtest to see results.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {/* Chart Overlay */}
      {selectedRow && (
        <div style={{
          position: 'fixed', top: 0, left: 0, right: 0, bottom: 0,
          background: 'rgba(0,0,0,0.85)', display: 'flex', alignItems: 'center', justifyContent: 'center',
          zIndex: 1000, padding: 40
        }} onClick={() => setSelectedTrade(null)}>
          <div className="card" style={{ maxWidth: 1000, width: '100%', position: 'relative' }} onClick={e => e.stopPropagation()}>
            <div className="flex-between mb-12">
              <h3>{selectedRow.ticker} — {selectedRow.strategy}</h3>
              <button className="btn" onClick={() => setSelectedTrade(null)} style={{ background: '#21262d' }}>Close</button>
            </div>
            <div style={{ background: '#0d1117', borderRadius: 8, overflow: 'hidden', minHeight: 500 }}>
              {selectedRow.chartPath ? (
                <img
                  src={selectedRow.chartPath}
                  alt="Trade Chart"
                  style={{ width: '100%', height: 'auto', display: 'block' }}
                  onError={(e) => {
                    e.target.style.display = 'none'
                    e.target.nextSibling.style.display = 'flex'
                  }}
                />
              ) : null}
              <div style={{ display: 'none', height: 500, alignItems: 'center', justifyContent: 'center', color: '#8b949e', flexDirection: 'column', gap: 10 }}>
                <Activity size={48} style={{ opacity: 0.3 }} />
                <div>Trade chart not available.</div>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
