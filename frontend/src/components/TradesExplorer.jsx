/**
 * TradesExplorer — fetches and displays trades for a run with filters + pagination.
 *
 * Props:
 *   runId — string
 */
import React, { useState, useEffect, useCallback } from 'react'
import { backtestHistoryApi } from '../api'
import { toCsvRow, downloadCsv } from '../utils/csvExport'

const PAGE_SIZE = 50

function formatPnl(v) {
  const n = Number(v)
  if (!Number.isFinite(n)) return '—'
  const sign = n >= 0 ? '+' : ''
  return `${sign}$${Math.abs(n).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

export default function TradesExplorer({ runId }) {
  const [trades, setTrades]           = useState([])
  const [total, setTotal]             = useState(0)
  const [page, setPage]               = useState(0)
  const [signalType, setSignalType]   = useState('ALL')
  const [winFilter, setWinFilter]     = useState('ALL')
  const [loading, setLoading]         = useState(false)
  const [error, setError]             = useState(null)
  const [exporting, setExporting]     = useState(false)

  const fetchTrades = useCallback(async () => {
    if (!runId) return
    setLoading(true)
    setError(null)
    try {
      const params = {
        offset: page * PAGE_SIZE,
        limit: PAGE_SIZE,
        sort: 'pnl',
        dir: 'asc',
      }
      if (signalType !== 'ALL') params.signalType = signalType
      if (winFilter === 'WINS')   params.win = 1
      if (winFilter === 'LOSSES') params.win = 0

      const result = await backtestHistoryApi.getTrades(runId, params)
      setTrades(result.trades ?? [])
      setTotal(result.total ?? 0)
    } catch (e) {
      setError(e.message || 'Error loading trades')
    } finally {
      setLoading(false)
    }
  }, [runId, page, signalType, winFilter])

  useEffect(() => { fetchTrades() }, [fetchTrades])

  const exportTradesToCsv = useCallback(async () => {
    setExporting(true)
    try {
      const EXPORT_LIMIT = 1000
      const baseParams = { limit: EXPORT_LIMIT, sort: 'pnl', dir: 'asc' }
      if (signalType !== 'ALL') baseParams.signalType = signalType
      if (winFilter === 'WINS')   baseParams.win = 1
      if (winFilter === 'LOSSES') baseParams.win = 0

      const TRADE_FIELDS = ['ticker', 'strategy', 'timeframe', 'signalDate', 'signalType',
        'entryPrice', 'exitPrice', 'pnl', 'win', 'pattern', 'exitReason']

      const header = toCsvRow(TRADE_FIELDS.filter((f) => {
        // We'll detect which fields exist from the first batch; include all for now
        return true
      }))

      let offset = 0
      let allTrades = []
      let runTotal = total // use current total as upper bound; may differ if data changed

      // Fetch first batch to detect available fields
      const firstBatch = await backtestHistoryApi.getTrades(runId, { ...baseParams, offset: 0 })
      allTrades = allTrades.concat(firstBatch.trades ?? [])
      runTotal = firstBatch.total ?? runTotal
      offset += EXPORT_LIMIT

      while (offset < runTotal) {
        const batch = await backtestHistoryApi.getTrades(runId, { ...baseParams, offset })
        allTrades = allTrades.concat(batch.trades ?? [])
        offset += EXPORT_LIMIT
      }

      // Detect available fields from first trade
      const firstTrade = allTrades[0] ?? {}
      const availableFields = TRADE_FIELDS.filter((f) => f !== 'exitReason' || f in firstTrade)

      const csvHeader = toCsvRow(availableFields)
      const csvRows = allTrades.map((t) => toCsvRow(availableFields.map((f) => t[f])))
      const csvContent = [csvHeader, ...csvRows].join('\n')

      downloadCsv(csvContent, `backtest-trades-${runId}.csv`)
    } finally {
      setExporting(false)
    }
  }, [runId, signalType, winFilter, total])

  // Reset to page 0 when filters change
  const handleSignalType = (v) => { setPage(0); setSignalType(v) }
  const handleWinFilter  = (v) => { setPage(0); setWinFilter(v) }

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE))

  if (loading && trades.length === 0) {
    return <p className="color-muted text-xs" style={{ padding: '8px' }}>Loading trades…</p>
  }

  if (error) {
    return <p className="color-error text-xs" style={{ padding: '8px' }}>Error: {error}</p>
  }

  return (
    <div>
      {/* Filters */}
      <div style={{ display: 'flex', gap: 8, marginBottom: 8, flexWrap: 'wrap', alignItems: 'center' }}>
        <label style={{ fontSize: 11, color: 'var(--color-muted, #888)' }}>Type:</label>
        <select
          data-testid="filter-signal-type"
          value={signalType}
          onChange={(e) => handleSignalType(e.target.value)}
          style={{ fontSize: 11, padding: '2px 6px' }}
        >
          <option value="ALL">ALL</option>
          <option value="CALL">CALL</option>
          <option value="PUT">PUT</option>
        </select>

        <label style={{ fontSize: 11, color: 'var(--color-muted, #888)' }}>Result:</label>
        <select
          data-testid="filter-win"
          value={winFilter}
          onChange={(e) => handleWinFilter(e.target.value)}
          style={{ fontSize: 11, padding: '2px 6px' }}
        >
          <option value="ALL">ALL</option>
          <option value="WINS">WINS</option>
          <option value="LOSSES">LOSSES</option>
        </select>

        <span style={{ fontSize: 11, color: 'var(--color-muted, #888)', marginLeft: 'auto' }}>
          {total} trades · page {page + 1}/{totalPages}
        </span>
      </div>

      {/* Table */}
      {trades.length === 0 ? (
        <p className="color-muted text-xs">No trades match the current filters.</p>
      ) : (
        <div style={{ overflowX: 'auto' }}>
          <table data-testid="trades-table" style={{ width: '100%', borderCollapse: 'collapse', fontSize: '11px' }}>
            <thead>
              <tr style={{ borderBottom: '1px solid var(--border, #333)' }}>
                {['Ticker', 'Strategy', 'Type', 'Entry', 'Exit', 'P&L', 'Entry Time', 'Exit Time'].map((h) => (
                  <th key={h} style={{ padding: '4px 8px', textAlign: 'left', color: 'var(--color-muted, #888)', whiteSpace: 'nowrap' }}>{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {trades.map((t, i) => (
                <tr key={i} style={{ borderBottom: '1px solid var(--border, #222)' }}>
                  <td style={{ padding: '4px 8px', fontWeight: 600 }}>{t.ticker}</td>
                  <td style={{ padding: '4px 8px' }}>{t.strategy}</td>
                  <td style={{ padding: '4px 8px' }}>{t.signalType}</td>
                  <td style={{ padding: '4px 8px' }}>{Number(t.entryPrice).toFixed(2)}</td>
                  <td style={{ padding: '4px 8px' }}>{Number(t.exitPrice).toFixed(2)}</td>
                  <td style={{
                    padding: '4px 8px',
                    color: Number(t.pnl) >= 0 ? 'var(--color-success, #22c55e)' : 'var(--color-error, #ef4444)',
                    fontWeight: 600,
                  }}>
                    {formatPnl(t.pnl)}
                  </td>
                  <td style={{ padding: '4px 8px', color: 'var(--color-muted, #888)' }}>{t.signalDate ?? '—'}</td>
                  <td style={{ padding: '4px 8px', color: 'var(--color-muted, #888)' }}>{t.createdAt ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Pagination */}
      <div style={{ display: 'flex', gap: 8, marginTop: 8, alignItems: 'center' }}>
        <button
          data-testid="btn-prev"
          onClick={() => setPage((p) => Math.max(0, p - 1))}
          disabled={page === 0}
          style={{ fontSize: 11, padding: '2px 10px' }}
        >
          Prev
        </button>
        <button
          data-testid="btn-next"
          onClick={() => setPage((p) => Math.min(totalPages - 1, p + 1))}
          disabled={page >= totalPages - 1}
          style={{ fontSize: 11, padding: '2px 10px' }}
        >
          Next
        </button>
        <button
          data-testid="btn-export-trades"
          onClick={exportTradesToCsv}
          disabled={exporting || total === 0}
          style={{ fontSize: 11, padding: '2px 10px', marginLeft: 'auto' }}
        >
          {exporting ? 'Exportando...' : 'Exportar Trades'}
        </button>
      </div>
    </div>
  )
}
