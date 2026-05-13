/**
 * LossesAnalysisPanel — renders loss analysis for a backtest run.
 *
 * Props:
 *   lossesData — result of backtestHistoryApi.getLossesAnalysis:
 *     { by_hour, by_pattern, by_ticker, worst_trades }
 */
import React from 'react'
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Cell,
} from 'recharts'

function formatPnl(v) {
  const n = Number(v)
  if (!Number.isFinite(n)) return '—'
  const sign = n >= 0 ? '+' : ''
  return `${sign}$${Math.abs(n).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

function SectionTitle({ children }) {
  return (
    <h5 style={{ margin: '16px 0 6px 0', fontSize: '12px', color: 'var(--color-muted, #888)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>
      {children}
    </h5>
  )
}

function MiniChart({ data, xKey, yKey, tooltipLabel }) {
  if (!data || data.length === 0) {
    return <p className="color-muted text-xs" style={{ margin: '4px 0 12px 0' }}>No data</p>
  }
  return (
    <ResponsiveContainer width="100%" height={160}>
      <BarChart data={data} margin={{ top: 4, right: 8, left: 0, bottom: 24 }}>
        <CartesianGrid strokeDasharray="3 3" stroke="var(--border, #333)" />
        <XAxis dataKey={xKey} tick={{ fontSize: 10 }} angle={-30} textAnchor="end" />
        <YAxis tick={{ fontSize: 10 }} />
        <Tooltip
          formatter={(value) => [value, tooltipLabel]}
          contentStyle={{ fontSize: 11 }}
        />
        <Bar dataKey={yKey} fill="var(--color-error, #ef4444)" />
      </BarChart>
    </ResponsiveContainer>
  )
}

export default function LossesAnalysisPanel({ lossesData }) {
  if (!lossesData) {
    return (
      <div data-testid="losses-analysis-panel" style={{ padding: '12px' }}>
        <p className="color-muted text-xs">No losses data available.</p>
      </div>
    )
  }

  const { byHour = [], byPattern = [], byTicker = [], worstTrades = [] } = lossesData

  return (
    <div data-testid="losses-analysis-panel">

      {/* By Hour */}
      <SectionTitle>Losses by Hour</SectionTitle>
      <MiniChart data={byHour} xKey="hour" yKey="count" tooltipLabel="losses" />

      {/* By Pattern */}
      <SectionTitle>Losses by Pattern (top 10)</SectionTitle>
      <MiniChart data={byPattern.slice(0, 10)} xKey="pattern" yKey="count" tooltipLabel="losses" />

      {/* By Ticker */}
      <SectionTitle>Losses by Ticker (top 20)</SectionTitle>
      <ResponsiveContainer width="100%" height={160}>
        <BarChart data={byTicker.slice(0, 20)} margin={{ top: 4, right: 8, left: 0, bottom: 24 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="var(--border, #333)" />
          <XAxis dataKey="ticker" tick={{ fontSize: 10 }} angle={-30} textAnchor="end" />
          <YAxis tick={{ fontSize: 10 }} />
          <Tooltip
            formatter={(v) => [formatPnl(v), 'Total Loss']}
            contentStyle={{ fontSize: 11 }}
          />
          <Bar dataKey="totalLoss" fill="var(--color-error, #ef4444)" />
        </BarChart>
      </ResponsiveContainer>

      {/* Worst Trades Table */}
      <SectionTitle>Worst 20 Trades</SectionTitle>
      {worstTrades.length === 0 ? (
        <p className="color-muted text-xs">No losing trades recorded.</p>
      ) : (
        <div style={{ overflowX: 'auto' }}>
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '11px' }}>
            <thead>
              <tr style={{ borderBottom: '1px solid var(--border, #333)' }}>
                {['Ticker', 'Strategy', 'Type', 'Entry', 'Exit', 'P&L'].map((h) => (
                  <th key={h} style={{ padding: '4px 8px', textAlign: 'left', color: 'var(--color-muted, #888)' }}>{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {worstTrades.slice(0, 20).map((t, i) => (
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
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
