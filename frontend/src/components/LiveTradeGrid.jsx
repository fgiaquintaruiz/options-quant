import React, { useState, useMemo, useEffect } from 'react'
import { Activity, Trash2 } from 'lucide-react'

// Trade lifecycle status config — color/label per state
const TRADE_STATUS = {
  FOUND:    { label: 'FOUND',    color: '#f0883e', bg: '#f0883e22' },
  SCANNING: { label: 'SCANNING', color: '#58a6ff', bg: '#388bfd26' },
  SIGNAL:   { label: 'SIGNAL',   color: '#3fb950', bg: '#23863626' },
  EXECUTED: { label: 'OPEN',     color: '#58a6ff', bg: '#388bfd26' },
  ERROR:    { label: 'ERROR',    color: '#f85149', bg: '#f8514926' },
  STALE:    { label: 'STALE',    color: '#6e7681', bg: '#21262d'   },
  OK:       { label: 'OK',       color: '#3fb950', bg: '#23863626' },
  CANCELLED:{ label: 'CANCEL',   color: '#8b949e', bg: '#21262d'   },
}

// Scan activity status → badge color
const SCAN_STATUS_COLOR = {
  STARTING: '#f0883e',
  SCANNING: '#58a6ff',
  SIGNAL:   '#3fb950',
  ERROR:    '#f85149',
  STALE:    '#6e7681',
  OK:       '#3fb950',
}
const scanColor = (s) => SCAN_STATUS_COLOR[s] || '#8b949e'

/** A trade is deletable when stale, not executed, and not closed. */
const isStaleDeletable = (row) =>
  row.signalStale && row.tradeStatus !== 'EXECUTED' && (!row.exitedAt || row.exitedAt === '-')

// ── Sub-components ─────────────────────────────────────────────────────────

/** Status badge reflecting trade lifecycle state or stale condition. */
function StatusBadge({ row }) {
  if (row.exitedAt && row.exitedAt !== '-') {
    return <span className="badge" style={{ background: '#21262d', color: '#8b949e', border: '1px solid #8b949e44' }}>EXITED</span>
  }
  if (row.signalStale && row.tradeStatus !== 'EXECUTED') {
    return (
      <span className="badge" title="Marca de tiempo de la vela >30 min — ejecución bloqueada"
        style={{ background: '#bb800926', color: '#d29922', border: '1px solid #d2992244', fontWeight: 600 }}>
        &gt;30m
      </span>
    )
  }
  const cfg = TRADE_STATUS[row.tradeStatus] || TRADE_STATUS.OK
  return (
    <span className="badge" style={{ background: cfg.bg, color: cfg.color, border: `1px solid ${cfg.color}44` }}>
      {cfg.label}
    </span>
  )
}

/** Entry/TP/SL price stack for a trade row. */
function PriceStack({ ep, tp, sl }) {
  return (
    <div className="flex-col gap-4 ltg-price-stack">
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

// ── Custom hook — separates data orchestration from the view ───────────────

/**
 * Derives display-ready trade and scan rows from raw props.
 * Deduplicates by ticker, sorts active trades first then by recency.
 * Sorts scan rows HOT-first then alphabetically.
 */
function useTradeData(trades, hotTickers, scanActivity) {
  const hotSet = useMemo(
    () => new Set(hotTickers.map((t) => String(t).toUpperCase())),
    [hotTickers]
  )

  // One active trade per ticker; active first, then by latest entryAt
  const activeTrades = useMemo(() => {
    const byTicker = {}
    trades.forEach((t) => { if (!byTicker[t.ticker]) byTicker[t.ticker] = t })
    return Object.values(byTicker).sort((a, b) => {
      const aActive = !a.exitedAt || a.exitedAt === '-'
      const bActive = !b.exitedAt || b.exitedAt === '-'
      if (aActive && !bActive) return -1
      if (!aActive && bActive) return 1
      return String(b.entryAt || '').localeCompare(String(a.entryAt || ''))
    })
  }, [trades])

  // Latest scan entry per ticker; HOT tickers sorted to the top
  const scanRows = useMemo(() => {
    const byTicker = {}
    scanActivity.forEach((a) => { byTicker[a.ticker] = a })
    return Object.values(byTicker).sort((a, b) => {
      const aHot = hotSet.has(String(a.ticker).toUpperCase())
      const bHot = hotSet.has(String(b.ticker).toUpperCase())
      if (aHot && !bHot) return -1
      if (!aHot && bHot) return 1
      return String(a.ticker).localeCompare(String(b.ticker))
    })
  }, [scanActivity, hotSet])

  const staleTickersList = useMemo(
    () => trades.filter(isStaleDeletable).map((t) => t.ticker),
    [trades]
  )

  return { hotSet, activeTrades, scanRows, staleTickersList }
}

// ── Main component ─────────────────────────────────────────────────────────

/**
 * Live signals and scan activity grid.
 * Renders active trades with Open/Close/Cancel actions, stale signal batch management,
 * and a scan activity table with HOT-ticker prioritization.
 * Props: trades, scanActivity, scanning, hotTickers, staleSignalCount, pendingActions,
 *        onCloseTrade, onCancelTrade, onDeleteSignal, onClearStaleBatch, onClearAllStale.
 */
export default function LiveTradeGrid({
  trades = [],
  scanActivity = [],
  scanning = false,
  hotTickers = [],
  staleSignalCount = 0,
  onCloseTrade,
  onCancelTrade,
  onDeleteSignal,
  onClearStaleBatch,
  onClearAllStale,
  pendingActions = {}
}) {
  const [selectedStale, setSelectedStale] = useState(() => new Set())
  const { hotSet, activeTrades, scanRows, staleTickersList } = useTradeData(trades, hotTickers, scanActivity)

  // Keep selection in sync as stale set changes (e.g. after signal refresh)
  useEffect(() => {
    setSelectedStale((prev) => {
      const staleSet = new Set(staleTickersList)
      const next = new Set()
      prev.forEach((t) => { if (staleSet.has(t)) next.add(t) })
      return next
    })
  }, [staleTickersList])

  const toggleStaleSelect = (ticker) =>
    setSelectedStale((prev) => {
      const n = new Set(prev)
      n.has(ticker) ? n.delete(ticker) : n.add(ticker)
      return n
    })

  const selectAllStale = () => setSelectedStale(new Set(staleTickersList))
  const clearSelection = () => setSelectedStale(new Set())

  const isExited = (row) => row.exitedAt && row.exitedAt !== '-'

  // Total column count for the colspan of the empty-state row
  const COL_COUNT = 11

  return (
    <div className="flex-col gap-20" data-testid="live-trade-grid-root">

      {/* ── Active Trades ── */}
      <div className="card" data-testid="live-signals-grid">
        <div className="flex-between mb-12">
          <h3 className="m-0 flex-align-center gap-8">
            <Activity size={18} className="color-info" />
            Live Signals &amp; Positions
          </h3>
          <span className="text-sm color-muted">{activeTrades.length} records</span>
        </div>

        <div className="table-wrap">
          <table className="w-full">
            <thead>
              <tr className="bg-card">
                <th className="ltg-th ltg-th-check" title="Seleccionar señales stale para borrar en bloque">&nbsp;</th>
                <th className="ltg-th">Ticker</th>
                <th className="ltg-th">Strategy</th>
                <th className="ltg-th">Prices</th>
                <th className="ltg-th ltg-th--center">Status</th>
                <th className="ltg-th">Exit Reason</th>
                <th className="ltg-th">Signal found</th>
                <th className="ltg-th">Entry at</th>
                <th className="ltg-th">Exited at</th>
                <th className="ltg-th ltg-th--center">Action</th>
                <th className="ltg-th ltg-th--center" aria-label="Delete row" />
              </tr>
            </thead>
            <tbody>
              {staleSignalCount > 0 && (
                <tr data-testid="stale-signal-banner">
                  <td colSpan={COL_COUNT} className="ltg-stale-banner" style={{ padding: 0 }}>
                    <div className="ltg-stale-banner flex-between gap-12 flex-wrap">
                      <span className="text-sm ltg-stale-label">
                        <strong>{staleSignalCount}</strong>
                        {' '}señal(es) con vela de más de 30 minutos — Open deshabilitado.
                      </span>
                      <div className="flex-align-center gap-8 flex-wrap">
                        <button type="button" className="btn text-xs" onClick={selectAllStale} style={{ padding: '3px 8px' }}>
                          Seleccionar todas
                        </button>
                        <button type="button" className="btn text-xs" onClick={clearSelection} style={{ padding: '3px 8px' }}>
                          Limpiar selección
                        </button>
                        <button
                          type="button"
                          className="btn text-xs"
                          disabled={selectedStale.size === 0}
                          onClick={() => {
                            if (selectedStale.size === 0 || !onClearStaleBatch) return
                            onClearStaleBatch(Array.from(selectedStale))
                            clearSelection()
                          }}
                          style={{ padding: '3px 8px', borderColor: '#d2992266', color: '#d29922' }}
                        >
                          Borrar seleccionadas ({selectedStale.size})
                        </button>
                        <button
                          type="button"
                          className="btn text-xs"
                          onClick={() => onClearAllStale && onClearAllStale()}
                          style={{ padding: '3px 8px', background: '#f8514922', border: '1px solid #f8514944', color: '#f85149' }}
                        >
                          Borrar todas las &gt;30m
                        </button>
                      </div>
                    </div>
                  </td>
                </tr>
              )}

              {activeTrades.map((row, idx) => {
                const exited = isExited(row)
                const isCall = String(row.direction).toUpperCase().includes('CALL')
                const stratBg = isCall ? '#23863618' : '#da363318'
                const stratColor = isCall ? '#3fb950' : '#f85149'
                const staleHighlight = Boolean(row.signalStale && row.tradeStatus !== 'EXECUTED' && !exited)
                const canCheck = isStaleDeletable(row)
                const rowClass = staleHighlight ? 'ltg-row-stale' : exited ? 'ltg-row-exited' : 'ltg-row-normal'

                return (
                  <tr key={`${row.ticker}-${row.entryAt}-${idx}`} className={rowClass}>
                    <td className="ltg-td--center">
                      {canCheck ? (
                        <input
                          type="checkbox"
                          checked={selectedStale.has(row.ticker)}
                          onChange={() => toggleStaleSelect(row.ticker)}
                          aria-label={`Seleccionar ${row.ticker}`}
                        />
                      ) : (
                        <span className="text-xs color-muted">—</span>
                      )}
                    </td>

                    <td className="ltg-td">
                      <div className="flex-align-center gap-4">
                        <strong className="ltg-ticker-label">{row.ticker}</strong>
                        {hotSet.has(String(row.ticker).toUpperCase()) && (
                          <span className="badge badge-hot text-xs" style={{ padding: '1px 4px' }}>HOT</span>
                        )}
                        {staleHighlight && (
                          <span className="ltg-stale-warn" title="Marca de tiempo de vela >30 min">VELA VIEJA</span>
                        )}
                      </div>
                      {row.pattern && row.pattern !== '-' && (
                        <div className="text-xs color-muted mt-2">{row.pattern}</div>
                      )}
                    </td>

                    <td className="ltg-td">
                      <span className="text-sm font-bold" style={{
                        padding: '4px 8px', borderRadius: 4,
                        background: stratBg, color: stratColor, border: `1px solid ${stratColor}33`
                      }}>
                        {row.strategy}
                      </span>
                    </td>

                    <td><PriceStack ep={row.ep} tp={row.tp} sl={row.sl} /></td>
                    <td className="ltg-td--center"><StatusBadge row={row} /></td>

                    <td className="ltg-td text-sm color-muted">
                      {row.exitReason && row.exitReason !== 'LIVE SIGNAL' && row.exitReason !== 'MANUAL_CLOSE' ? row.exitReason : '—'}
                    </td>

                    <td className="ltg-td text-sm color-muted">{row.signalFound || '—'}</td>
                    <td className="ltg-td text-sm color-muted">{row.entryAt || '—'}</td>
                    <td className="ltg-td text-sm color-muted">{row.exitedAt && row.exitedAt !== '-' ? row.exitedAt : '—'}</td>

                    <td className="ltg-td--center">
                      {!exited ? (
                        <div className="flex-center gap-6" style={{ minHeight: 24 }}>
                          {pendingActions[row.ticker] ? (
                            <div className="spinner-sm" />
                          ) : row.tradeStatus !== 'EXECUTED' ? (
                            <button
                              type="button"
                              className="btn text-xs ltg-open-btn"
                              disabled={Boolean(row.signalStale)}
                              title={row.signalStale ? 'Señal >30 min — datos obsoletos' : 'Abrir posición'}
                              onClick={() => !row.signalStale && onCloseTrade && onCloseTrade(row.ticker, row.ep, true)}
                              style={{
                                background: row.signalStale ? '#21262d' : '#23863622',
                                border: `1px solid ${row.signalStale ? '#30363d' : '#23863666'}`,
                                color: row.signalStale ? '#6e7681' : '#3fb950',
                                cursor: row.signalStale ? 'not-allowed' : 'pointer',
                              }}
                            >
                              Open
                            </button>
                          ) : (
                            <div className="flex-row gap-4">
                              <button
                                className="btn text-xs ltg-action-btn"
                                onClick={() => onCloseTrade && onCloseTrade(row.ticker, row.ep, false, row.tpOrderId, row.slOrderId)}
                                style={{ background: '#f8514922', border: '1px solid #f8514966', color: '#f85149' }}
                              >
                                Close
                              </button>
                              {row.orderId && (
                                <button
                                  className="btn text-xs ltg-action-btn"
                                  onClick={() => onCancelTrade && onCancelTrade(row.ticker, row.orderId)}
                                  style={{ background: '#8b949e22', border: '1px solid #8b949e44', color: '#8b949e' }}
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

                    <td className="ltg-td--center">
                      <button
                        type="button"
                        className="btn text-xs ltg-delete-btn"
                        title="Quitar esta señal de la lista"
                        disabled={Boolean(pendingActions[row.ticker])}
                        onClick={() => onDeleteSignal && onDeleteSignal(row.ticker)}
                        style={{ cursor: pendingActions[row.ticker] ? 'not-allowed' : 'pointer' }}
                      >
                        <Trash2 size={16} aria-hidden />
                      </button>
                    </td>
                  </tr>
                )
              })}

              {activeTrades.length === 0 && (
                <tr>
                  <td colSpan={COL_COUNT} className="ltg-empty">
                    No active signals or positions. {scanning ? 'Scanning market…' : 'Start a scan to find opportunities.'}
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* ── Scan Activity ── */}
      {scanRows.length > 0 && (
        <div className="card">
          <div className="stat-label-sm color-muted mb-8 text-uppercase font-bold ltg-section-label">
            Scan Activity — {scanRows.length} tickers
          </div>
          <div className="table-wrap">
            <table className="w-full">
              <thead>
                <tr className="bg-card">
                  <th className="ltg-th-sm">Ticker</th>
                  <th className="ltg-th-sm ltg-th--center">Status</th>
                  <th className="ltg-th-sm">Detail</th>
                  <th className="ltg-th-sm">Scan Started</th>
                  <th className="ltg-th-sm">Scan Ended</th>
                  <th className="ltg-th-sm">Scan Duration</th>
                </tr>
              </thead>
              <tbody>
                {scanRows.map((row, idx) => (
                  <tr key={`scan-${row.ticker}-${idx}`}>
                    <td className="ltg-td-sm">
                      <div className="flex-align-center gap-6">
                        <strong className="text-md">{row.ticker}</strong>
                        {hotSet.has(String(row.ticker).toUpperCase()) && (
                          <span className="badge badge-hot text-xs" style={{ padding: '1px 4px' }}>HOT</span>
                        )}
                      </div>
                    </td>
                    <td className="ltg-td-sm ltg-th--center">
                      <span className="badge ltg-scan-badge" style={{
                        background: `${scanColor(row.status)}22`,
                        color: scanColor(row.status),
                        border: `1px solid ${scanColor(row.status)}44`,
                      }}>
                        {row.status}
                      </span>
                    </td>
                    <td className="ltg-td-sm text-sm color-muted">{row.detail || '—'}</td>
                    <td className="ltg-td-sm text-xs color-muted">{row.scanStarted || '-'}</td>
                    <td className="ltg-td-sm text-xs color-muted">{row.scanEnded || '-'}</td>
                    <td className="ltg-td-sm text-xs color-muted font-bold">{row.duration || '-'}</td>
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
