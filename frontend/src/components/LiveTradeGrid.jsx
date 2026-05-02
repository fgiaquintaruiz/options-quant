import React, { useState, useMemo, useEffect } from 'react'
import { Activity, Trash2 } from 'lucide-react'
import TickerTooltip from './TickerTooltip'
import { compareScanRows } from '../utils/scanRowSort'

/** Format an ISO 8601 timestamp to dd/MM HH:mi:ss in browser local time. */
function fmtTimestamp(iso) {
  if (!iso || iso === '—' || iso === '-') return '—'
  const d = new Date(iso)
  if (isNaN(d)) return iso
  const dd  = String(d.getDate()).padStart(2, '0')
  const mm  = String(d.getMonth() + 1).padStart(2, '0')
  const hh  = String(d.getHours()).padStart(2, '0')
  const mi  = String(d.getMinutes()).padStart(2, '0')
  const ss  = String(d.getSeconds()).padStart(2, '0')
  return `${dd}/${mm} ${hh}:${mi}:${ss}`
}

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
  EN_COLA:   '#8b949e',
  LOADING:   '#6e7681',
  STARTING:  '#f0883e',
  SCANNING:  '#58a6ff',
  SIGNAL:    '#3fb950',
  NO_SIGNAL: '#6e7681',
  SKIPPED:   '#d29922',
  ERROR:     '#f85149',
  STALE:     '#6e7681',
  OK:        '#3fb950',
}
const scanColor = (s) => SCAN_STATUS_COLOR[s] || '#8b949e'

/** Text label for in-flight scan statuses. */
function ScanStatusText({ status }) {
  if (status === 'EN_COLA')      return <span className="scan-status-text scan-status-text--loading">En cola</span>
  if (status === 'LOADING')      return <span className="scan-status-text scan-status-text--loading">Cargando...</span>
  if (status === 'SCANNING')     return <span className="scan-status-text scan-status-text--scanning">Escaneando...</span>
  if (status === 'SIGNAL_FOUND') return <span className="scan-status-text scan-status-text--signal">Señal ✓</span>
  if (status === 'NO_SIGNAL')    return <span className="scan-status-text scan-status-text--no-signal">—</span>
  return null
}

/**
 * Buckets a scan row status into one of the five breakdown chips.
 * Returns null for statuses that should not contribute to any chip.
 */
const chipBucketFor = (status) => {
  if (status === 'EN_COLA' || status === '—') return 'EN_COLA'
  if (status === 'LOADING') return 'LOADING'
  if (status === 'SCANNING' || status === 'STARTING') return 'SCANNING'
  if (status === 'OK' || status === 'NO_SIGNAL') return 'OK'
  if (status === 'SIGNAL' || status === 'SIGNAL_FOUND') return 'SIGNAL'
  return null
}

// Horizontal chip order + Spanish labels for the status breakdown.
const CHIP_ORDER = [
  { key: 'EN_COLA',  label: 'En cola' },
  { key: 'LOADING',  label: 'Cargando' },
  { key: 'SCANNING', label: 'Escaneando' },
  { key: 'OK',       label: 'OK' },
  { key: 'SIGNAL',   label: 'Señal' },
]

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
 * Sorts scan rows HOT-first by hotList index, then by hybridScore desc, then alphabetically.
 */
function useTradeData(trades, hotTickers, scanActivity, scanScores) {
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

  // Latest scan entry per ticker; HOT-first by hotList index, then hybridScore desc, then alpha
  const scanRows = useMemo(() => {
    const byTicker = {}
    scanActivity.forEach((a) => { byTicker[a.ticker] = a })
    return Object.values(byTicker).sort((a, b) => compareScanRows(a, b, hotTickers, scanScores))
  }, [scanActivity, hotTickers, scanScores])

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
// Stable empty references for default props — prevents new-ref-per-render loop
// (see useMemo on line 158 + useEffect on line 227 in this file)
const EMPTY_ARRAY = Object.freeze([])
const EMPTY_OBJECT = Object.freeze({})

const isMacroConflict = (direction, regime) => {
  if (!regime || !direction) return false
  const bull = regime.includes('BULLISH')
  const bear = regime.includes('BEARISH')
  return (direction === 'PUT' && bull) || (direction === 'CALL' && bear)
}

export default function LiveTradeGrid({
  trades = EMPTY_ARRAY,
  scanActivity = EMPTY_ARRAY,
  scanning = false,
  hotTickers = EMPTY_ARRAY,
  scanScores = EMPTY_OBJECT,
  staleSignalCount = 0,
  macroRegime = null,
  replayActive = false,
  onCloseTrade,
  onCancelTrade,
  onDeleteSignal,
  onClearStaleBatch,
  onClearAllStale,
  pendingActions = EMPTY_OBJECT,
  externalPositions = EMPTY_ARRAY,
  onCloseExternal,
  onScheduleClose1450,
}) {
  const [selectedStale, setSelectedStale] = useState(() => new Set())
  const [closingExternal, setClosingExternal] = useState(() => new Set())
  const [scheduled1450, setScheduled1450] = useState(() => new Set())
  const { hotSet, activeTrades, scanRows, staleTickersList } = useTradeData(trades, hotTickers, scanActivity, scanScores)

  // Status breakdown counts — derived from the real scanRows (post-dedupe).
  // "En cola" is only surfaced during an active scan.
  const statusCounts = useMemo(() => {
    const counts = { EN_COLA: 0, LOADING: 0, SCANNING: 0, OK: 0, SIGNAL: 0 }
    for (const row of scanRows) {
      const bucket = chipBucketFor(row.status)
      if (bucket) counts[bucket]++
    }
    return counts
  }, [scanRows])

  const chipsToShow = useMemo(
    () => CHIP_ORDER
      .filter(({ key }) => {
        if (key === 'EN_COLA') return scanning && statusCounts.EN_COLA > 0
        return statusCounts[key] > 0
      })
      .map(({ key, label }) => ({ key, label, count: statusCounts[key], color: scanColor(key) })),
    [statusCounts, scanning]
  )

  // Keep selection in sync as stale set changes (e.g. after signal refresh)
  useEffect(() => {
    setSelectedStale((prev) => {
      const staleSet = new Set(staleTickersList)
      const next = new Set()
      prev.forEach((t) => { if (staleSet.has(t)) next.add(t) })
      // Bail out if contents are unchanged — prevents re-render loop when
      // staleTickersList is recomputed but resolves to the same membership
      if (next.size === prev.size) {
        let same = true
        for (const t of next) {
          if (!prev.has(t)) { same = false; break }
        }
        if (same) return prev
      }
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
          <div className="flex-align-center gap-12">
            {replayActive && (
              <span className="text-xs color-muted">Solo lectura durante replay</span>
            )}
            <span className="text-sm color-muted">{activeTrades.length + externalPositions.length} records</span>
          </div>
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
                        <button type="button" className="btn text-xs" disabled={replayActive} onClick={selectAllStale} style={{ padding: '3px 8px', opacity: replayActive ? 0.4 : 1 }}>
                          Seleccionar todas
                        </button>
                        <button type="button" className="btn text-xs" disabled={replayActive} onClick={clearSelection} style={{ padding: '3px 8px', opacity: replayActive ? 0.4 : 1 }}>
                          Limpiar selección
                        </button>
                        <button
                          type="button"
                          className="btn text-xs"
                          disabled={selectedStale.size === 0 || replayActive}
                          onClick={() => {
                            if (selectedStale.size === 0 || !onClearStaleBatch) return
                            onClearStaleBatch(Array.from(selectedStale))
                            clearSelection()
                          }}
                          style={{ padding: '3px 8px', borderColor: '#d2992266', color: '#d29922', opacity: replayActive ? 0.4 : 1 }}
                        >
                          Borrar seleccionadas ({selectedStale.size})
                        </button>
                        <button
                          type="button"
                          className="btn text-xs"
                          disabled={replayActive}
                          onClick={() => onClearAllStale && onClearAllStale()}
                          style={{ padding: '3px 8px', background: '#f8514922', border: '1px solid #f8514944', color: '#f85149', opacity: replayActive ? 0.4 : 1 }}
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
                        <TickerTooltip ticker={row.ticker}>
                          <strong className="ltg-ticker-label">{row.ticker}</strong>
                        </TickerTooltip>
                        {hotSet.has(String(row.ticker).toUpperCase()) && (
                          <span className="badge badge-hot text-xs" style={{ padding: '1px 4px' }}>HOT</span>
                        )}
                        {row.replay && (
                          <span className="badge text-xs" style={{ background: '#1f3a5f', color: '#58a6ff', border: '1px solid #1f6feb', padding: '1px 4px' }}>REPLAY</span>
                        )}
                        {row.newsBias === 'CALL' && (
                          <span className="badge text-xs" style={{ background: '#23863618', color: '#3fb950', border: '1px solid #2ea04326', padding: '1px 4px' }}>NEWS↑</span>
                        )}
                        {row.newsBias === 'PUT' && (
                          <span className="badge text-xs" style={{ background: '#da363318', color: '#f85149', border: '1px solid #da363326', padding: '1px 4px' }}>NEWS↓</span>
                        )}
                        {row.earningsAlert && (
                          <span className="badge badge-hot text-xs" style={{ padding: '1px 4px' }}>⚡EARN</span>
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
                    <td className="ltg-td text-sm color-muted">{fmtTimestamp(row.entryAt)}</td>
                    <td className="ltg-td text-sm color-muted">{fmtTimestamp(row.exitedAt)}</td>

                    <td className="ltg-td--center">
                      {!exited ? (
                        <div className="flex-center gap-6" style={{ minHeight: 24 }}>
                          {pendingActions[row.ticker] ? (
                            <div className="spinner-sm" />
                          ) : row.tradeStatus !== 'EXECUTED' ? (() => {
                            const conflict = isMacroConflict(row.direction, macroRegime)
                            const stale = Boolean(row.signalStale)
                            return (
                              <button
                                type="button"
                                className="btn text-xs ltg-open-btn"
                                disabled={stale}
                                title={
                                  stale
                                    ? 'Señal >30 min — datos obsoletos'
                                    : conflict
                                      ? `⚠ Contra macro: ${macroRegime} — podés ejecutar igual`
                                      : 'Abrir posición'
                                }
                                onClick={() => !stale && onCloseTrade && onCloseTrade(row.ticker, row.ep, true)}
                                style={{
                                  background: stale ? '#21262d' : conflict ? '#f0883e18' : '#23863622',
                                  border: `1px solid ${stale ? '#30363d' : conflict ? '#f0883e88' : '#23863666'}`,
                                  color: stale ? '#6e7681' : conflict ? '#f0883e' : '#3fb950',
                                  cursor: stale ? 'not-allowed' : 'pointer',
                                }}
                              >
                                {conflict ? '⚠ Open' : 'Open'}
                              </button>
                            )
                          })() : (
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
                        title={replayActive ? 'Solo lectura durante replay' : 'Quitar esta señal de la lista'}
                        disabled={Boolean(pendingActions[row.ticker]) || replayActive}
                        onClick={() => onDeleteSignal && onDeleteSignal(row.ticker)}
                        style={{ cursor: (pendingActions[row.ticker] || replayActive) ? 'not-allowed' : 'pointer', opacity: replayActive ? 0.4 : 1 }}
                      >
                        <Trash2 size={16} aria-hidden />
                      </button>
                    </td>
                  </tr>
                )
              })}

              {externalPositions.map((pos) => {
                const isClosing   = closingExternal.has(pos.ticker)
                const isScheduled = scheduled1450.has(pos.ticker)

                const handleCloseExt = async () => {
                  const ok = window.confirm(`Cerrar posición ${pos.ticker} a mercado?`)
                  if (!ok) return
                  setClosingExternal((prev) => new Set(prev).add(pos.ticker))
                  try {
                    await onCloseExternal?.(pos.ticker)
                  } finally {
                    setClosingExternal((prev) => { const n = new Set(prev); n.delete(pos.ticker); return n })
                  }
                }

                const handleScheduleExt = async () => {
                  setScheduled1450((prev) => new Set(prev).add(pos.ticker))
                  try {
                    await onScheduleClose1450?.(pos.ticker)
                  } catch {
                    setScheduled1450((prev) => { const n = new Set(prev); n.delete(pos.ticker); return n })
                  }
                }

                return (
                  <tr key={`ext-${pos.ticker}`} className="ltg-row-normal">
                    <td className="ltg-td--center">
                      <span className="text-xs color-muted">—</span>
                    </td>

                    <td className="ltg-td">
                      <div className="flex-align-center gap-4">
                        <strong className="ltg-ticker-label">{pos.ticker}</strong>
                        <span className="badge badge-external text-xs" style={{ padding: '1px 4px' }}>EXTERNAL</span>
                      </div>
                    </td>

                    <td className="ltg-td text-sm color-muted">—</td>

                    <td className="ltg-td">
                      <div className="flex-col gap-4 ltg-price-stack">
                        <div className="flex-between">
                          <span className="text-xs color-muted">Avg</span>
                          <span className="text-md font-bold">${Number(pos.avg_cost).toFixed(2)}</span>
                        </div>
                      </div>
                    </td>

                    <td className="ltg-td--center">
                      <span className="badge" style={{ background: '#d2992222', color: '#d29922', border: '1px solid #d2992244' }}>EXTERNAL</span>
                    </td>

                    <td className="ltg-td text-sm color-muted">—</td>
                    <td className="ltg-td text-sm color-muted">—</td>
                    <td className="ltg-td text-sm color-muted">{pos.snapshot_timestamp || '—'}</td>
                    <td className="ltg-td text-sm color-muted">—</td>

                    <td className="ltg-td--center">
                      <div className="flex-center gap-6">
                        <button
                          type="button"
                          className="btn text-xs ltg-action-btn"
                          disabled={isClosing}
                          onClick={handleCloseExt}
                          style={{ background: '#f8514922', border: '1px solid #f8514966', color: isClosing ? '#8b949e' : '#f85149' }}
                        >
                          {isClosing ? 'Cerrando…' : 'Close'}
                        </button>
                        {isScheduled ? (
                          <span className="badge badge-scheduled-1450">14:50 ET</span>
                        ) : (
                          <label className="flex-align-center gap-4 text-xs color-muted" style={{ cursor: 'pointer' }}>
                            <input
                              type="checkbox"
                              disabled={isScheduled}
                              onChange={handleScheduleExt}
                              style={{ cursor: 'pointer' }}
                            />
                            14:50 ET
                          </label>
                        )}
                      </div>
                    </td>

                    <td className="ltg-td--center">
                      <span className="text-xs color-muted">—</span>
                    </td>
                  </tr>
                )
              })}

              {activeTrades.length === 0 && externalPositions.length === 0 && (
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
      <div className="card">
        {chipsToShow.length > 0 && (
          <div className="flex-align-center flex-wrap gap-8 mb-8" data-testid="scan-status-breakdown">
            {chipsToShow.map(({ key, label, count, color }, idx) => (
              <React.Fragment key={key}>
                {idx > 0 && <span className="color-muted text-xs">·</span>}
                <span
                  className="badge ltg-scan-badge"
                  data-testid={`scan-chip-${key}`}
                  style={{
                    background: `${color}22`,
                    color,
                    border: `1px solid ${color}44`,
                    padding: '2px 8px',
                    fontSize: 11,
                  }}
                >
                  {label}: {count}
                </span>
              </React.Fragment>
            ))}
          </div>
        )}
        {scanRows.length === 0 ? (
          <p style={{ color: '#8b949e', padding: '12px 0', fontSize: '13px' }}>
            Waiting for first scan...
          </p>
        ) : (
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
                  <th className="ltg-th-sm ltg-th--center">Fund</th>
                  <th className="ltg-th-sm ltg-th--center">Mem</th>
                  <th className="ltg-th-sm ltg-th--center">Score</th>
                </tr>
              </thead>
              <tbody>
                {scanRows.map((row, idx) => (
                  <tr key={`scan-${row.ticker}-${idx}`}>
                    <td className="ltg-td-sm">
                      <div className="flex-align-center gap-6">
                        <TickerTooltip ticker={row.ticker}>
                          <strong className="text-md">{row.ticker}</strong>
                        </TickerTooltip>
                        {hotSet.has(String(row.ticker).toUpperCase()) && (
                          <span className="badge badge-hot text-xs" style={{ padding: '1px 4px' }}>HOT</span>
                        )}
                      </div>
                    </td>
                    <td className="ltg-td-sm ltg-th--center">
                      <div className="flex-center gap-4">
                        <ScanStatusText status={row.status} />
                        <span className="badge ltg-scan-badge" style={{
                          background: `${scanColor(row.status)}22`,
                          color: scanColor(row.status),
                          border: `1px solid ${scanColor(row.status)}44`,
                        }}>
                          {row.status}
                        </span>
                      </div>
                    </td>
                    <td className="ltg-td-sm text-sm color-muted">{row.detail || '—'}</td>
                    <td className="ltg-td-sm text-xs color-muted">{row.scanStarted || '-'}</td>
                    <td className="ltg-td-sm text-xs color-muted">{row.scanEnded || '-'}</td>
                    <td className="ltg-td-sm text-xs color-muted font-bold">{row.duration || '-'}</td>
                    <td className="ltg-td-sm ltg-th--center text-xs color-muted">
                      {scanScores[row.ticker]?.fundamentalScore?.toFixed(2) ?? '—'}
                    </td>
                    <td className="ltg-td-sm ltg-th--center text-xs color-muted">
                      {scanScores[row.ticker]?.memoryScore?.toFixed(2) ?? '—'}
                    </td>
                    <td className="ltg-td-sm ltg-th--center text-xs font-bold">
                      {scanScores[row.ticker]?.hybridScore?.toFixed(2) ?? '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  )
}
