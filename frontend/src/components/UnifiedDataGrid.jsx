import React, { useMemo, useState } from 'react'
import { Activity } from 'lucide-react'

const formatLabel = (value) => {
  if (!value) return '-'
  return String(value)
    .replace(/_/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
}

/** @returns {boolean|null} true = call side, false = put, null = unknown / neutral */
function isCallStrategy(strategy) {
  if (!strategy || strategy === 'N/A' || strategy === '-') return null
  const s = String(strategy).toLowerCase().replace(/\s+/g, '')
  if (/^c\d/.test(s)) return true
  if (/^p\d/.test(s)) return false
  if (s.includes('call')) return true
  if (s.includes('put')) return false
  return null
}

function StrategyPill({ value }) {
  const label = formatLabel(value)
  const isNa = label === 'N/A' || label === '-'
  if (isNa) {
    return (
      <div className="flex-col gap-4">
        <span className="font-bold color-muted" style={{ fontSize: 13 }}>{label}</span>
      </div>
    )
  }
  const side = isCallStrategy(value)
  const stratBg = side === true ? '#23863618' : side === false ? '#da363318' : '#21262d'
  const stratColor = side === true ? '#3fb950' : side === false ? '#f85149' : '#8b949e'
  const border = `1px solid ${stratColor}33`
  return (
    <div className="flex-col gap-4">
      <span
        className="text-sm font-bold"
        style={{
          display: 'inline-block',
          padding: '4px 8px',
          borderRadius: 6,
          background: stratBg,
          color: stratColor,
          border,
          maxWidth: 220,
          wordBreak: 'break-word'
        }}
      >
        {label}
      </span>
    </div>
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

/** Trade `netPnl` from backtest API is USD (same as legacy dashboard), not a percent. */
function PnlCell({ value }) {
  if (value == null) return <span className="color-muted">—</span>
  const num = Number(value)
  if (!Number.isFinite(num)) return <span className="color-muted">—</span>
  const color = num > 0 ? '#3fb950' : num < 0 ? '#f85149' : '#8b949e'
  const formatted = new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(num)
  return (
    <span className="font-bold" style={{ color, fontSize: 14 }}>
      {formatted}
    </span>
  )
}

/** Backtest charts are HTML served by GET /charts/{file}.html — not valid as <img src>. */
function resolveBacktestChartUrl(chartPath) {
  if (!chartPath || typeof chartPath !== 'string') return ''
  const t = chartPath.trim()
  if (t.startsWith('http://') || t.startsWith('https://')) return t
  if (t.startsWith('/charts/')) return t
  return `/charts/${encodeURIComponent(t)}`
}

const th = { padding: '10px 12px', borderBottom: '2px solid #21262d', color: '#8b949e', fontSize: 11, fontWeight: 600, textTransform: 'uppercase' }

export default function UnifiedDataGrid({ data = [] }) {
  const [selectedRow, setSelectedTrade] = useState(null)

  const sortedData = useMemo(() => {
    return [...data].sort((a, b) => {
      if (a.status !== 'Complete' && b.status === 'Complete') return -1
      if (a.status === 'Complete' && b.status !== 'Complete') return 1
      return String(b.startTime).localeCompare(String(a.startTime))
    })
  }, [data])

  const renderStatus = (row) => {
    const status = String(row.status || 'Active').toUpperCase()
    let color = '#8b949e'
    let bg = '#21262d'

    if (status === 'COMPLETE' || status === 'OK') { color = '#3fb950'; bg = '#23863626' }
    if (status === 'SCANNING' || status === 'RUNNING' || status === 'LOADING') { color = '#58a6ff'; bg = '#388bfd26' }
    if (status === 'SIGNAL') { color = '#f0883e'; bg = '#f0883e26' }
    if (status === 'ERROR') { color = '#f85149'; bg = '#f8514926' }

    return (
      <span className="badge" style={{ background: bg, color, border: `1px solid ${color}44` }}>
        {status}
      </span>
    )
  }

  const scanStartedDisplay = (row) => row.scanStarted || row.startTime || '—'
  const scanEndedDisplay = (row) => {
    const se = row.scanEnded
    if (se != null && se !== '' && se !== '-') return se
    if (row.status === 'Complete' && row.endTime && row.endTime !== '-') return row.endTime
    return '—'
  }
  const scanDurationDisplay = (row) => row.scanDuration || '—'

  return (
    <div className="flex-col w-full">
      <div className="table-wrap" style={{ border: '1px solid #30363d', borderRadius: 8, overflow: 'hidden' }}>
        <table className="w-full">
          <thead>
            <tr className="bg-card">
              <th style={th}>Ticker</th>
              <th style={th}>Strategy</th>
              <th style={th}>Entry</th>
              <th style={th}>Exit</th>
              <th style={th}>PnL</th>
              <th style={{ ...th, textAlign: 'center' }}>Status</th>
              <th style={th}>Scan Started</th>
              <th style={th}>Scan Ended</th>
              <th style={th}>Scan Duration</th>
              <th style={{ ...th, textAlign: 'center' }}>Chart</th>
            </tr>
          </thead>
          <tbody>
            {sortedData.map((row, idx) => (
              <tr key={`${row.ticker}-${idx}`} style={{ opacity: row.status === 'Complete' ? 0.8 : 1 }}>
                <td style={{ padding: '10px 12px' }}>
                  <div className="flex-col">
                    <strong className="text-md color-text">{row.ticker}</strong>
                    <span className="text-xs color-muted">{row.pattern || '-'}</span>
                  </div>
                </td>
                <td style={{ padding: '10px 12px' }}><StrategyPill value={row.strategy} /></td>
                <td style={{ padding: '10px 12px' }}><PriceCell value={row.ep} /></td>
                <td style={{ padding: '10px 12px' }}><PriceCell value={row.xp} color={Number(row.xp) > Number(row.ep) ? '#3fb950' : '#f85149'} /></td>
                <td style={{ padding: '10px 12px' }}><PnlCell value={row.netPnl} /></td>
                <td style={{ textAlign: 'center', padding: '10px 12px' }}>{renderStatus(row)}</td>
                <td className="text-xs color-muted" style={{ padding: '10px 12px' }}>{scanStartedDisplay(row)}</td>
                <td className="text-xs color-muted" style={{ padding: '10px 12px' }}>{scanEndedDisplay(row)}</td>
                <td className="text-xs color-muted font-bold" style={{ padding: '10px 12px' }}>{scanDurationDisplay(row)}</td>
                <td style={{ textAlign: 'center', padding: '10px 12px' }}>
                  {row.chartPath ? (
                    <button
                      type="button"
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
                <td colSpan={10} style={{ padding: '40px', textAlign: 'center', color: '#8b949e' }}>
                  No data available. Run a scan or backtest to see results.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {selectedRow && (
        <div style={{
          position: 'fixed', top: 0, left: 0, right: 0, bottom: 0,
          background: 'rgba(0,0,0,0.85)', display: 'flex', alignItems: 'center', justifyContent: 'center',
          zIndex: 1000, padding: 40
        }} onClick={() => setSelectedTrade(null)}>
          <div className="card" style={{ maxWidth: 1100, width: '100%', position: 'relative' }} onClick={e => e.stopPropagation()}>
            <div className="flex-between mb-12">
              <h3 className="m-0">{selectedRow.ticker} — {selectedRow.strategy}</h3>
              <button type="button" className="btn" onClick={() => setSelectedTrade(null)} style={{ background: '#21262d' }}>Close</button>
            </div>
            {selectedRow.chartPath ? (
              <>
                <div className="flex-between flex-wrap gap-8" style={{ marginBottom: 10 }}>
                  <a
                    href={resolveBacktestChartUrl(selectedRow.chartPath)}
                    target="_blank"
                    rel="noreferrer"
                    className="text-sm"
                    style={{ color: '#58a6ff' }}
                  >
                    Open chart in new tab
                  </a>
                  <span className="text-xs color-muted">HTML chart (TradingView lightweight) from backtest/charts</span>
                </div>
                <div style={{ background: '#0d1117', borderRadius: 8, overflow: 'hidden', border: '1px solid #30363d' }}>
                  <iframe
                    title={`Chart ${selectedRow.ticker}`}
                    src={resolveBacktestChartUrl(selectedRow.chartPath)}
                    style={{ width: '100%', height: 520, border: 'none', display: 'block', background: '#0d1117' }}
                    sandbox="allow-scripts allow-same-origin"
                  />
                </div>
              </>
            ) : (
              <div style={{ minHeight: 200, alignItems: 'center', justifyContent: 'center', color: '#8b949e', display: 'flex', flexDirection: 'column', gap: 10 }}>
                <Activity size={48} style={{ opacity: 0.3 }} />
                <div>No chart path for this row.</div>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  )
}
