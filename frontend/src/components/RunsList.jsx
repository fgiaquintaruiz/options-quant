/**
 * RunsList — displays available backtest runs; clicking a row selects it.
 *
 * Props:
 *   runs         — array of run objects { runId, totalTickers, totalTrades, totalPnl }
 *   selectedRunId — currently selected runId (string | null)
 *   onSelect     — (runId: string) => void
 *   loading      — show loading state when true
 */
import React from 'react'

function formatPnl(value) {
  const n = Number(value)
  if (!Number.isFinite(n)) return '—'
  const sign = n >= 0 ? '+' : ''
  return `${sign}$${Math.abs(n).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

export default function RunsList({ runs, selectedRunId, onSelect, loading }) {
  if (loading) {
    return (
      <div data-testid="runs-list" className="card" style={{ padding: '16px', minWidth: 320 }}>
        <p className="color-muted text-xs">Loading runs…</p>
      </div>
    )
  }

  if (!runs || runs.length === 0) {
    return (
      <div data-testid="runs-list" className="card" style={{ padding: '16px', minWidth: 320 }}>
        <p className="color-muted text-xs">No runs found. Run a backtest first.</p>
      </div>
    )
  }

  return (
    <div data-testid="runs-list" className="card" style={{ minWidth: 320, overflowX: 'auto' }}>
      <h4 style={{ margin: '0 0 8px 0', fontSize: '13px' }}>Backtest Runs</h4>
      <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '12px' }}>
        <thead>
          <tr style={{ borderBottom: '1px solid var(--border, #333)' }}>
            <th style={{ textAlign: 'left', padding: '4px 8px', color: 'var(--color-muted, #888)' }}>Run ID</th>
            <th style={{ textAlign: 'right', padding: '4px 8px', color: 'var(--color-muted, #888)' }}>Tickers</th>
            <th style={{ textAlign: 'right', padding: '4px 8px', color: 'var(--color-muted, #888)' }}>Trades</th>
            <th style={{ textAlign: 'right', padding: '4px 8px', color: 'var(--color-muted, #888)' }}>P&L</th>
          </tr>
        </thead>
        <tbody>
          {runs.map((run) => {
            const isSelected = run.runId === selectedRunId
            const pnl = Number(run.totalPnl)
            return (
              <tr
                key={run.runId}
                data-testid={`run-row-${run.runId}`}
                className={isSelected ? 'runs-list-row selected' : 'runs-list-row'}
                onClick={() => onSelect(run.runId)}
                style={{
                  cursor: 'pointer',
                  backgroundColor: isSelected ? 'var(--bg-selected, rgba(99,102,241,0.15))' : 'transparent',
                  borderBottom: '1px solid var(--border, #222)',
                }}
              >
                <td style={{ padding: '6px 8px', fontFamily: 'monospace' }}>{run.runId}</td>
                <td style={{ padding: '6px 8px', textAlign: 'right' }}>{run.totalTickers ?? '—'}</td>
                <td style={{ padding: '6px 8px', textAlign: 'right' }}>{run.totalTrades ?? '—'}</td>
                <td
                  data-testid={`pnl-${run.runId}`}
                  style={{
                    padding: '6px 8px',
                    textAlign: 'right',
                    color: pnl >= 0 ? 'var(--color-success, #22c55e)' : 'var(--color-error, #ef4444)',
                    fontWeight: 600,
                  }}
                >
                  {formatPnl(run.totalPnl)}
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}
