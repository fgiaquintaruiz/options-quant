import React, { useMemo, useState } from 'react'
import { Activity, Wrench } from 'lucide-react'

// Maps normalized status string to CSS modifier class (defined in index.css)
const STATUS_CLASS = {
  COMPLETE: 'badge-status-complete',
  OK:       'badge-status-complete',
  SCANNING: 'badge-status-running',
  RUNNING:  'badge-status-running',
  LOADING:  'badge-status-running',
  SIGNAL:   'badge-status-signal',
  ERROR:    'badge-status-error',
}

const USD = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', minimumFractionDigits: 2, maximumFractionDigits: 2 })

const formatLabel = (value) => {
  if (!value) return '-'
  return String(value).replace(/_/g, ' ').replace(/\s+/g, ' ').trim()
}

/** @returns {boolean|null} true = call side, false = put, null = unknown */
function isCallStrategy(strategy) {
  if (!strategy || strategy === 'N/A' || strategy === '-') return null
  const s = String(strategy).toLowerCase().replace(/\s+/g, '')
  if (/^c\d/.test(s) || s.includes('call')) return true
  if (/^p\d/.test(s) || s.includes('put'))  return false
  return null
}

// ── Sub-components ─────────────────────────────────────────────────────────

/** Renders a colored pill for strategy name; green for CALL side, red for PUT, neutral otherwise. */
function StrategyPill({ value }) {
  const label = formatLabel(value)
  if (label === 'N/A' || label === '-') {
    return <div><span className="font-bold color-muted text-sm">{label}</span></div>
  }
  const side = isCallStrategy(value)
  const pillClass = side === true ? 'ugrid-pill-call' : side === false ? 'ugrid-pill-put' : 'ugrid-pill-neu'
  return (
    <div>
      <span className={`ugrid-pill text-sm font-bold ${pillClass}`}>{label}</span>
    </div>
  )
}

/** Formats a price as $X.XX; colorClass sets the color variant (e.g. ugrid-price-pos). */
function PriceCell({ value, colorClass }) {
  if (value == null || value === '-') return <span className="color-muted">—</span>
  const num = Number(value)
  if (!Number.isFinite(num)) return <span className="color-muted">—</span>
  return (
    <span className={`ugrid-price font-bold ${colorClass || ''}`}>
      {USD.format(num)}
    </span>
  )
}

/** netPnl from backtest API is in USD. */
function PnlCell({ value }) {
  if (value == null) return <span className="color-muted">—</span>
  const num = Number(value)
  if (!Number.isFinite(num)) return <span className="color-muted">—</span>
  const colorClass = num > 0 ? 'ugrid-price-pos' : num < 0 ? 'ugrid-price-neg' : 'ugrid-price-neu'
  return <span className={`ugrid-price font-bold ${colorClass}`}>{USD.format(num)}</span>
}

/** Maps normalized status to its CSS badge class; falls back to default variant. */
function StatusBadge({ row }) {
  const key = String(row.status || 'Active').toUpperCase()
  const cls = STATUS_CLASS[key] || 'badge-status-default'
  return <span className={`badge ${cls}`}>{key}</span>
}

/** HTML backtest charts are served as full pages — not valid as img src. */
function resolveBacktestChartUrl(chartPath) {
  if (!chartPath || typeof chartPath !== 'string') return ''
  const t = chartPath.trim()
  if (t.startsWith('http://') || t.startsWith('https://')) return t
  if (t.startsWith('/charts/')) return t
  return `/charts/${encodeURIComponent(t)}`
}

/** Full-screen modal to display a backtest chart iframe. */
function ChartModal({ row, onClose }) {
  return (
    <div className="ugrid-modal-overlay" onClick={onClose}>
      <div className="card ugrid-modal-inner" onClick={(e) => e.stopPropagation()}>
        <div className="flex-between mb-12">
          <h3 className="m-0">{row.ticker} — {row.strategy}</h3>
          <button type="button" className="btn ugrid-close-btn" onClick={onClose}>Close</button>
        </div>
        {row.chartPath ? (
          <>
            <div className="flex-between flex-wrap gap-8 ugrid-modal-chart-meta">
              <a href={resolveBacktestChartUrl(row.chartPath)} target="_blank" rel="noreferrer" className="text-sm color-info">
                Open chart in new tab
              </a>
              <span className="text-xs color-muted">HTML chart (TradingView lightweight) from backtest/charts</span>
            </div>
            <div className="ugrid-modal-iframe-wrap">
              <iframe
                title={`Chart ${row.ticker}`}
                src={resolveBacktestChartUrl(row.chartPath)}
                className="ugrid-modal-iframe"
                sandbox="allow-scripts allow-same-origin"
              />
            </div>
          </>
        ) : (
          <div className="ugrid-modal-no-chart">
            <Activity size={48} className="ugrid-no-chart-icon" />
            <div>No chart path for this row.</div>
          </div>
        )}
      </div>
    </div>
  )
}

// ── Main component ──────────────────────────────────────────────────────────

/**
 * Shared trade result grid for backtest and live dashboards.
 * Accepts an array of trade row objects (ticker, strategy, startTime required).
 * Optional onAnalyzeStrategy callback — when provided, renders an Info button per row for drill-down.
 */
export default function UnifiedDataGrid({ data = [], onAnalyzeStrategy }) {
  const [selectedRow, setSelectedTrade] = useState(null)
  const showInfo = typeof onAnalyzeStrategy === 'function'

  const sortedData = useMemo(() => {
    return [...data].sort((a, b) => {
      const aDone = String(a.status || '').toUpperCase() === 'COMPLETE'
      const bDone = String(b.status || '').toUpperCase() === 'COMPLETE'
      if (!aDone && bDone) return -1
      if (aDone && !bDone) return 1
      if (aDone && bDone) {
        const pa = Number(a.netPnl), pb = Number(b.netPnl)
        if (Number.isFinite(pa) && Number.isFinite(pb) && pa !== pb) return pa - pb
      }
      return String(b.startTime).localeCompare(String(a.startTime))
    })
  }, [data])

  // Ticker, Strategy, Entry, Exit, PnL, ExitReason, Status, ScanStarted, ScanEnded, ScanDuration, Chart
  const BASE_COLS = 11
  const colSpanEmpty = BASE_COLS + (showInfo ? 1 : 0)

  return (
    <div className="ugrid-root">
      <div className="ugrid-wrap table-wrap">
        <table className="w-full ugrid-table">
          <thead className="ugrid-thead">
            <tr>
              <th className="ugrid-th">Ticker</th>
              <th className="ugrid-th">Strategy</th>
              <th className="ugrid-th">Entry</th>
              <th className="ugrid-th">Exit</th>
              <th className="ugrid-th">PnL</th>
              <th className="ugrid-th">Exit Reason</th>
              <th className="ugrid-th ugrid-th--center">Status</th>
              <th className="ugrid-th">Scan Started</th>
              <th className="ugrid-th">Scan Ended</th>
              <th className="ugrid-th">Scan Duration</th>
              <th className="ugrid-th ugrid-th--center">Chart</th>
              {showInfo && <th className="ugrid-th ugrid-th--center ugrid-th--nowrap">Info</th>}
            </tr>
          </thead>
          <tbody>
            {sortedData.map((row) => {
              const pnl = Number(row.netPnl)
              const neg = Number.isFinite(pnl) && pnl < 0
              const reason = row.exitReason != null && row.exitReason !== '' ? String(row.exitReason) : '—'
              const rowKey = `${row.ticker}-${row.strategy}-${row.startTime}`
              const done = String(row.status || '').toUpperCase() === 'COMPLETE'
              const scanEnded = row.scanEnded && row.scanEnded !== '-' ? row.scanEnded
                : (done && row.endTime && row.endTime !== '-' ? row.endTime : '—')
              const exitPriceColorClass = Number(row.xp) > Number(row.ep) ? 'ugrid-price-pos' : 'ugrid-price-neg'

              return (
                <tr key={rowKey} className={done ? 'ugrid-row-complete' : ''}>
                  <td className="ugrid-td">
                    <strong className="text-md color-text">{row.ticker}</strong>
                    <div className="text-xs color-muted">{row.pattern || '-'}</div>
                  </td>
                  <td className="ugrid-td"><StrategyPill value={row.strategy} /></td>
                  <td className="ugrid-td"><PriceCell value={row.ep} /></td>
                  <td className="ugrid-td"><PriceCell value={row.xp} colorClass={exitPriceColorClass} /></td>
                  <td className="ugrid-td">
                    <PnlCell value={row.netPnl} />
                    {row.lastGridNote && (
                      <div className="text-xs color-muted ugrid-grid-note">{row.lastGridNote}</div>
                    )}
                  </td>
                  <td className="ugrid-td ugrid-exit-reason">
                    <span className={`text-xs ${neg ? 'color-error font-bold' : 'color-muted'}`} title={reason}>
                      {reason}
                    </span>
                  </td>
                  <td className="ugrid-td--center"><StatusBadge row={row} /></td>
                  <td className="ugrid-td text-xs color-muted">{row.scanStarted || row.startTime || '—'}</td>
                  <td className="ugrid-td text-xs color-muted">{scanEnded}</td>
                  <td className="ugrid-td text-xs color-muted font-bold">{row.scanDuration || '—'}</td>
                  <td className="ugrid-td--center">
                    {row.chartPath ? (
                      <button type="button" className="btn ugrid-chart-btn" onClick={() => setSelectedTrade(row)}>
                        <Activity size={16} />
                      </button>
                    ) : <span className="color-muted">—</span>}
                  </td>
                  {showInfo && (
                    <td className="ugrid-td--info">
                      {row.strategy && (
                        <button
                          type="button"
                          className="btn btn-secondary ugrid-analyze-btn"
                          data-testid="bt-trade-row-info"
                          title="Análisis y grid (mismo ticker y estrategia)"
                          onClick={() => onAnalyzeStrategy(row)}
                        >
                          <Wrench size={12} className="ugrid-analyze-icon" />
                          Info
                        </button>
                      )}
                    </td>
                  )}
                </tr>
              )
            })}
            {sortedData.length === 0 && (
              <tr>
                <td colSpan={colSpanEmpty} className="ugrid-empty">
                  No data available. Run a scan or backtest to see results.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {selectedRow && <ChartModal row={selectedRow} onClose={() => setSelectedTrade(null)} />}
    </div>
  )
}
