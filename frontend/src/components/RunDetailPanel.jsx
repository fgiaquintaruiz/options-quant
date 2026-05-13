/**
 * RunDetailPanel — fetches and shows full detail for a selected backtest run.
 *
 * Props:
 *   runId — string
 */
import React, { useState, useEffect, useCallback } from 'react'
import { backtestHistoryApi } from '../api'
import LossesAnalysisPanel from './LossesAnalysisPanel'
import TradesExplorer from './TradesExplorer'
import { toCsvRow, downloadCsv } from '../utils/csvExport'

function formatPnl(v) {
  const n = Number(v)
  if (!Number.isFinite(n)) return '—'
  const sign = n >= 0 ? '+' : ''
  return `${sign}$${Math.abs(n).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

function formatPct(v) {
  const n = Number(v)
  if (!Number.isFinite(n)) return '—'
  return `${(n * 100).toFixed(1)}%`
}

function KpiCard({ label, value, testId, colorStyle }) {
  return (
    <div
      data-testid={testId}
      style={{
        background: 'var(--bg-card, #1a1a2e)',
        border: '1px solid var(--border, #333)',
        borderRadius: 6,
        padding: '10px 16px',
        minWidth: 120,
        flex: '1 1 120px',
      }}
    >
      <div style={{ fontSize: 10, color: 'var(--color-muted, #888)', marginBottom: 4, textTransform: 'uppercase' }}>{label}</div>
      <div style={{ fontSize: 18, fontWeight: 700, ...colorStyle }}>{value}</div>
    </div>
  )
}

function SortableTable({ data, columns }) {
  const [sortCol, setSortCol]   = useState(null)
  const [sortDir, setSortDir]   = useState('desc')

  const sorted = sortCol
    ? [...data].sort((a, b) => {
        const va = a[sortCol], vb = b[sortCol]
        if (sortDir === 'asc') return va < vb ? -1 : va > vb ? 1 : 0
        return va > vb ? -1 : va < vb ? 1 : 0
      })
    : data

  const toggleSort = (col) => {
    if (sortCol === col) setSortDir((d) => d === 'asc' ? 'desc' : 'asc')
    else { setSortCol(col); setSortDir('desc') }
  }

  return (
    <div style={{ overflowX: 'auto' }}>
      <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '12px' }}>
        <thead>
          <tr style={{ borderBottom: '1px solid var(--border, #333)' }}>
            {columns.map((c) => (
              <th
                key={c.key}
                onClick={() => toggleSort(c.key)}
                style={{
                  padding: '5px 8px',
                  textAlign: c.align ?? 'left',
                  color: 'var(--color-muted, #888)',
                  cursor: 'pointer',
                  userSelect: 'none',
                  whiteSpace: 'nowrap',
                }}
              >
                {c.label} {sortCol === c.key ? (sortDir === 'asc' ? '▲' : '▼') : ''}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {sorted.map((row, i) => (
            <tr key={i} style={{ borderBottom: '1px solid var(--border, #222)' }}>
              {columns.map((c) => (
                <td
                  key={c.key}
                  style={{
                    padding: '5px 8px',
                    textAlign: c.align ?? 'left',
                    color: c.colorFn ? c.colorFn(row[c.key]) : undefined,
                    fontWeight: c.bold ? 600 : undefined,
                  }}
                >
                  {c.format ? c.format(row[c.key]) : (row[c.key] ?? '—')}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

const pnlColor = (v) => Number(v) >= 0 ? 'var(--color-success, #22c55e)' : 'var(--color-error, #ef4444)'

const strategyColumns = [
  { key: 'strategy',  label: 'Strategy',  bold: true },
  { key: 'trades',    label: 'Trades',    align: 'right' },
  { key: 'winRate',   label: 'Win Rate',  align: 'right', format: (v) => `${Number(v).toFixed(1)}%` },
  { key: 'totalPnl',  label: 'P&L',       align: 'right', format: formatPnl, colorFn: pnlColor },
]

const signalColumns = [
  { key: 'signalType', label: 'Type',     bold: true },
  { key: 'trades',     label: 'Trades',   align: 'right' },
  { key: 'winRate',    label: 'Win Rate', align: 'right', format: (v) => `${Number(v).toFixed(1)}%` },
  { key: 'totalPnl',   label: 'P&L',      align: 'right', format: formatPnl, colorFn: pnlColor },
]

export default function RunDetailPanel({ runId }) {
  const [summary, setSummary]   = useState(null)
  const [losses, setLosses]     = useState(null)
  const [loading, setLoading]   = useState(false)
  const [error, setError]       = useState(null)

  useEffect(() => {
    if (!runId) return
    setLoading(true)
    setError(null)
    setSummary(null)
    setLosses(null)

    Promise.all([
      backtestHistoryApi.getRunSummary(runId),
      backtestHistoryApi.getLossesAnalysis(runId),
    ])
      .then(([s, l]) => {
        setSummary(s)
        setLosses(l)
      })
      .catch((e) => setError(e.message || 'Error loading run details'))
      .finally(() => setLoading(false))
  }, [runId])

  if (loading) {
    return <p className="color-muted text-xs" style={{ padding: 12 }}>Loading run details…</p>
  }

  if (error) {
    return <p className="color-error text-xs" style={{ padding: 12 }}>Error: {error}</p>
  }

  if (!summary) return null

  const winRate = Number(summary.winRate)
  const pnl     = Number(summary.totalPnl)

  const exportSummaryToCsv = () => {
    const sections = []

    // === SUMMARY ===
    sections.push('=== SUMMARY ===')
    sections.push(toCsvRow(['Run ID', 'Total Trades', 'Win Rate', 'Total PnL']))
    sections.push(toCsvRow([runId, summary.totalTrades, summary.winRate, summary.totalPnl]))
    sections.push('')

    // === BY STRATEGY ===
    if (summary.byStrategy?.length > 0) {
      sections.push('=== BY STRATEGY ===')
      sections.push(toCsvRow(['Strategy', 'Trades', 'Win Rate', 'Total PnL', 'Avg Win', 'Avg Loss']))
      summary.byStrategy.forEach((r) => {
        sections.push(toCsvRow([r.strategy, r.trades, r.winRate, r.totalPnl, r.avgWin ?? '', r.avgLoss ?? '']))
      })
      sections.push('')
    }

    // === CALL VS PUT ===
    if (summary.bySignalType?.length > 0) {
      sections.push('=== CALL VS PUT ===')
      sections.push(toCsvRow(['Type', 'Trades', 'Win Rate', 'Total PnL']))
      summary.bySignalType.forEach((r) => {
        sections.push(toCsvRow([r.signalType, r.trades, r.winRate, r.totalPnl]))
      })
      sections.push('')
    }

    // === LOSSES BY HOUR ===
    if (losses?.by_hour?.length > 0) {
      sections.push('=== LOSSES BY HOUR ===')
      sections.push(toCsvRow(['Hour', 'Count', 'Avg Loss']))
      losses.by_hour.forEach((r) => {
        sections.push(toCsvRow([r.hour, r.count, r.avgLoss]))
      })
      sections.push('')
    }

    // === LOSSES BY PATTERN ===
    if (losses?.by_pattern?.length > 0) {
      sections.push('=== LOSSES BY PATTERN ===')
      sections.push(toCsvRow(['Pattern', 'Count', 'Avg Loss']))
      losses.by_pattern.forEach((r) => {
        sections.push(toCsvRow([r.pattern, r.count, r.avgLoss]))
      })
      sections.push('')
    }

    // === LOSSES BY TICKER (TOP 20) ===
    if (losses?.by_ticker?.length > 0) {
      sections.push('=== LOSSES BY TICKER (TOP 20) ===')
      sections.push(toCsvRow(['Ticker', 'Count', 'Total Loss']))
      losses.by_ticker.slice(0, 20).forEach((r) => {
        sections.push(toCsvRow([r.ticker, r.count, r.totalLoss]))
      })
      sections.push('')
    }

    // === WORST 20 TRADES ===
    if (losses?.worst_trades?.length > 0) {
      sections.push('=== WORST 20 TRADES ===')
      sections.push(toCsvRow(['Ticker', 'Strategy', 'Type', 'Entry', 'Exit', 'PnL']))
      losses.worst_trades.slice(0, 20).forEach((r) => {
        sections.push(toCsvRow([r.ticker, r.strategy, r.signalType, r.entryPrice, r.exitPrice, r.pnl]))
      })
    }

    downloadCsv(sections.join('\n'), `backtest-summary-${runId}.csv`)
  }

  return (
    <div>
      {/* KPI Cards + Export Summary button */}
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: 8, flexWrap: 'wrap', marginBottom: 16 }}>
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', flex: 1 }}>
          <KpiCard
            testId="kpi-total-trades"
            label="Total Trades"
            value={summary.totalTrades ?? '—'}
          />
          <KpiCard
            testId="kpi-win-rate"
            label="Win Rate"
            value={`${Number(summary.winRate).toFixed(1)}%`}
            colorStyle={{ color: winRate >= 50 ? 'var(--color-success, #22c55e)' : 'var(--color-error, #ef4444)' }}
          />
          <KpiCard
            testId="kpi-total-pnl"
            label="Total P&L"
            value={formatPnl(summary.totalPnl)}
            colorStyle={{ color: pnl >= 0 ? 'var(--color-success, #22c55e)' : 'var(--color-error, #ef4444)' }}
          />
        </div>
        <button
          data-testid="btn-export-summary"
          onClick={exportSummaryToCsv}
          disabled={!summary || !losses}
          style={{ fontSize: 11, padding: '2px 10px', alignSelf: 'flex-start', marginTop: 2 }}
        >
          Exportar Resumen
        </button>
      </div>

      {/* Strategy breakdown */}
      {summary.byStrategy?.length > 0 && (
        <section style={{ marginBottom: 16 }}>
          <h5 style={{ margin: '0 0 6px 0', fontSize: 11, color: 'var(--color-muted, #888)', textTransform: 'uppercase' }}>
            Performance by Strategy
          </h5>
          <SortableTable data={summary.byStrategy} columns={strategyColumns} />
        </section>
      )}

      {/* CALL vs PUT */}
      {summary.bySignalType?.length > 0 && (
        <section style={{ marginBottom: 16 }}>
          <h5 style={{ margin: '0 0 6px 0', fontSize: 11, color: 'var(--color-muted, #888)', textTransform: 'uppercase' }}>
            CALL vs PUT
          </h5>
          <SortableTable data={summary.bySignalType} columns={signalColumns} />
        </section>
      )}

      {/* Losses Analysis */}
      <section style={{ marginBottom: 16 }}>
        <h5 style={{ margin: '0 0 6px 0', fontSize: 11, color: 'var(--color-muted, #888)', textTransform: 'uppercase' }}>
          Losses Analysis
        </h5>
        <LossesAnalysisPanel lossesData={losses} />
      </section>

      {/* Trades Explorer */}
      <section>
        <h5 style={{ margin: '0 0 6px 0', fontSize: 11, color: 'var(--color-muted, #888)', textTransform: 'uppercase' }}>
          Trades Explorer
        </h5>
        <TradesExplorer runId={runId} />
      </section>
    </div>
  )
}
