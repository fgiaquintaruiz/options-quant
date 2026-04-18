import React, { useState, useMemo } from 'react'
import { Activity } from 'lucide-react'

// Trade lifecycle status — maps to what backend/mock sends
const TRADE_STATUS = {
  FOUND:    { label: 'FOUND',    color: '#f0883e', bg: '#f0883e22' },
  SCANNING: { label: 'SCANNING', color: '#58a6ff', bg: '#388bfd26' },
  SIGNAL:   { label: 'SIGNAL',   color: '#3fb950', bg: '#23863626' },
  EXECUTED: { label: 'OPEN',     color: '#58a6ff', bg: '#388bfd26' },
  ERROR:    { label: 'ERROR',    color: '#f85149', bg: '#f8514926' },
  STALE:    { label: 'STALE',    color: '#6e7681', bg: '#21262d' },
  OK:       { label: 'OK',       color: '#3fb950', bg: '#23863626' },
  CANCELLED:{ label: 'CANCEL',   color: '#8b949e', bg: '#21262d' }
}

const getScanColor = (status) => {
  switch (status) {
    case 'STARTING': return '#f0883e'
    case 'SCANNING': return '#58a6ff'
    case 'SIGNAL':   return '#3fb950'
    case 'ERROR':    return '#f85149'
    case 'STALE':    return '#6e7681'
    case 'OK':       return '#3fb950'
    default:         return '#8b949e'
  }
}

function StatusBadge({ row }) {
  const cfg = TRADE_STATUS[row.tradeStatus] || TRADE_STATUS.OK
  const label = row.endTime && row.endTime !== '-' ? 'EXITED' : cfg.label
  const color = row.endTime && row.endTime !== '-' ? '#8b949e' : cfg.color
  const bg    = row.endTime && row.endTime !== '-' ? '#21262d' : cfg.bg
  
  return (
    <span className="badge" style={{ background: bg, color: color, border: `1px solid ${color}44` }}>
      {label}
    </span>
  )
}

function PriceStack({ ep, tp, sl }) {
  return (
    <div className="flex-col gap-4" style={{ minWidth: 100 }}>
      <div className="flex-between">
        <span className="text-xs color-muted">Entry</span>
        <span className="text-md font-bold">${Number(ep).toFixed(2)}</span>
      </div>
      <div className="flex-between">
        <span className="text-xs color-muted">TP</span>
        <span className="text-sm color-success">${Number(tp).toFixed(2)}</span>
      </div>
      <div className="flex-between">
        <span className="text-xs color-muted">SL</span>
        <span className="text-sm color-error">${Number(sl).toFixed(2)}</span>
      </div>
    </div>
  )
}

// ──────────────────────────────────────────────────────────────────────────────
// Main component
// ──────────────────────────────────────────────────────────────────────────────
export default function LiveTradeGrid({ trades = [], scanActivity = [], scanning = false, hotTickers = [], onCloseTrade, onCancelTrade, pendingActions = {} }) {
  const [selectedTrade, setSelectedTrade] = useState(null)

  const hotSet = useMemo(() => new Set(hotTickers.map(t => String(t).toUpperCase())), [hotTickers])

  // Deduplicate trades: keep latest per ticker (live = 1 active trade per ticker)
  const activeTrades = useMemo(() => {
    const byTicker = {}
    trades.forEach(t => {
      if (!byTicker[t.ticker]) byTicker[t.ticker] = t
    })
    return Object.values(byTicker).sort((a, b) => {
      if (a.endTime === '-' && b.endTime !== '-') return -1
      if (a.endTime !== '-' && b.endTime === '-') return 1
      return b.startTime.localeCompare(a.startTime)
    })
  }, [trades])

  const scanRows = useMemo(() => {
    const byTicker = {}
    const activeTickerSet = new Set(activeTrades.map(t => t.ticker))
    scanActivity.forEach(a => {
      if (activeTickerSet.has(a.ticker)) return
      byTicker[a.ticker] = a
    })
    return Object.values(byTicker).sort((a, b) => String(a.ticker).localeCompare(String(b.ticker)))
  }, [scanActivity, activeTrades])

  const isExited = (row) => row.endTime && row.endTime !== '-'

  const th = { padding: '12px', borderBottom: '2px solid #21262d', color: '#8b949e', fontSize: 11, fontWeight: 600, textTransform: 'uppercase' }
  const thSm = { ...th, padding: '8px 12px' }

  return (
    <div className="flex-col gap-20">
      
      {/* ── Active Trades Grid ── */}
      <div className="card">
        <div className="flex-between mb-12">
          <h3 className="m-0 flex-align-center gap-8">
            <Activity size={18} className="color-info" /> 
            Live Signals & Positions
          </h3>
          <span className="text-sm color-muted">{activeTrades.length} records</span>
        </div>

        <div className="table-wrap">
          <table className="w-full">
            <thead>
              <tr className="bg-card">
                <th style={th}>Ticker</th>
                <th style={th}>Strategy</th>
                <th style={th}>Prices</th>
                <th style={{ ...th, textAlign: 'center' }}>Status</th>
                <th style={th}>Exit Reason</th>
                <th style={th}>Start Time</th>
                <th style={th}>End Time</th>
                <th style={{ ...th, textAlign: 'center' }}>Action</th>
              </tr>
            </thead>
            <tbody>
              {activeTrades.map((row, idx) => {
                const exited = isExited(row)
                const isCall = String(row.direction).toUpperCase().includes('CALL')
                const stratBg = isCall ? '#23863618' : '#da363318'
                const stratColor = isCall ? '#3fb950' : '#f85149'

                return (
                  <tr
                    key={`${row.ticker}-${row.startTime}-${idx}`}
                    style={{
                      background: exited ? '#ffffff04' : '#ffffff08',
                      opacity: exited ? 0.65 : 1
                    }}
                  >
                    {/* Ticker */}
                    <td style={{ padding: '10px 12px' }}>
                      <div className="flex-align-center gap-4">
                        <strong className="text-md">{row.ticker}</strong>
                        {hotSet.has(String(row.ticker).toUpperCase()) && (
                          <span className="badge badge-hot text-xs" style={{ padding: '1px 4px' }}>HOT</span>
                        )}
                      </div>
                      {row.pattern && row.pattern !== '-' && (
                        <div className="text-xs color-muted mt-2">{row.pattern}</div>
                      )}
                    </td>

                    {/* Strategy (Colored by Direction) */}
                    <td style={{ padding: '10px 12px' }}>
                      <span className="text-sm font-bold" style={{ 
                        padding: '4px 8px', borderRadius: 4,
                        background: stratBg, color: stratColor, border: `1px solid ${stratColor}33` 
                      }}>
                        {row.strategy}
                      </span>
                    </td>

                    {/* Prices */}
                    <td><PriceStack ep={row.ep} tp={row.tp} sl={row.sl} /></td>

                    {/* Status */}
                    <td style={{ textAlign: 'center' }}><StatusBadge row={row} /></td>

                    {/* Exit Reason */}
                    <td className="text-sm color-muted">
                      {row.exitReason && row.exitReason !== 'LIVE SIGNAL' && row.exitReason !== 'MANUAL_CLOSE' ? row.exitReason : '—'}
                    </td>

                    {/* Start Time */}
                    <td className="text-sm color-muted">{row.startTime || '—'}</td>

                    {/* End Time */}
                    <td className="text-sm color-muted">{row.endTime && row.endTime !== '-' ? row.endTime : '—'}</td>

                    {/* Action button */}
                    <td style={{ textAlign: 'center' }}>
                      {!exited ? (
                        <div className="flex-center gap-6" style={{ minHeight: 24 }}>
                          {pendingActions[row.ticker] ? (
                            <div className="spinner-sm" />
                          ) : row.tradeStatus !== 'EXECUTED' ? (
                            <button
                              onClick={() => onCloseTrade && onCloseTrade(row.ticker, row.ep, true)}
                              className="btn text-xs"
                              style={{ padding: '3px 10px', background: '#23863622', border: '1px solid #23863666', color: '#3fb950' }}
                            >
                              Open
                            </button>
                          ) : (
                            <div className="flex-row gap-4">
                              <button
                                onClick={() => onCloseTrade && onCloseTrade(row.ticker, row.ep)}
                                className="btn text-xs"
                                style={{ padding: '3px 10px', background: '#f8514922', border: '1px solid #f8514966', color: '#f85149' }}
                              >
                                Close
                              </button>
                              {row.orderId && (
                                <button
                                  onClick={() => onCancelTrade && onCancelTrade(row.ticker, row.orderId)}
                                  className="btn text-xs"
                                  style={{ padding: '3px 10px', background: '#8b949e22', border: '1px solid #8b949e44', color: '#8b949e' }}
                                >
                                  Cancel
                                </button>
                              )}
                            </div>
                          )}
                        </div>
                      ) : (
                        <span className="text-xs color-muted">
                          {row.closePrice ? `$${Number(row.closePrice).toFixed(2)}` : '—'}
                        </span>
                      )}
                    </td>
                  </tr>
                )
              })}
              {activeTrades.length === 0 && (
                <tr>
                  <td colSpan="8" style={{ padding: '40px', textAlign: 'center', color: '#8b949e' }}>
                    No active signals or positions. {scanning ? 'Scanning market...' : 'Start a scan to find opportunities.'}
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* ── Scan activity ── */}
      {scanRows.length > 0 && (
        <div className="card">
          <div className="stat-label-sm color-muted mb-8 text-uppercase font-bold" style={{ letterSpacing: 1 }}>
            Scan Activity — {scanRows.length} tickers
          </div>
          <div className="table-wrap">
            <table className="w-full">
              <thead>
                <tr className="bg-card">
                  <th style={thSm}>Ticker</th>
                  <th style={{ ...thSm, textAlign: 'center' }}>Status</th>
                  <th style={thSm}>Detail</th>
                  <th style={thSm}>Scan Started</th>
                  <th style={thSm}>Scan Ended</th>
                  <th style={thSm}>Scan Duration</th>
                </tr>
              </thead>
              <tbody>
                {scanRows.map((row, idx) => (
                  <tr key={`scan-${row.ticker}-${idx}`}>
                    <td style={{ padding: '6px 12px' }}>
                      <div className="flex-align-center gap-6">
                        <strong className="text-md">{row.ticker}</strong>
                        {hotSet.has(String(row.ticker).toUpperCase()) && (
                          <span className="badge badge-hot text-xs" style={{ padding: '1px 4px' }}>HOT</span>
                        )}
                      </div>
                    </td>
                    <td style={{ padding: '6px 12px', textAlign: 'center' }}>
                      <span className="badge" style={{
                        fontSize: 10, background: `${getScanColor(row.status)}22`, color: getScanColor(row.status), border: `1px solid ${getScanColor(row.status)}44`
                      }}>
                        {row.status}
                      </span>
                    </td>
                    <td className="text-sm color-muted">{row.detail || '—'}</td>
                    <td className="text-xs color-muted">{row.scanStarted || '-'}</td>
                    <td className="text-xs color-muted">{row.scanEnded || '-'}</td>
                    <td className="text-xs color-muted font-bold">{row.duration || '-'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  )
}
