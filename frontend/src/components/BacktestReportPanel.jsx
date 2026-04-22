/**
 * BacktestReportPanel — shows KPI stats and equity curve after a backtest run.
 *
 * Props:
 *   report         — API payload from POST /backtest-ui/run (normalizeBacktestReportPayload applied)
 *   tickerFilter   — active ticker filter string (may be empty)
 *   lastScanMeta   — metadata of the last persisted snapshot { savedAt, tickerScope, ... }
 *   equityChartData — downsampled { time, equity } points for Recharts
 *
 * Note: the UI intentionally mixes Spanish and English — Spanish for operational labels
 * (targeting Spanish-speaking traders), English for trading/financial terms and KPI names.
 */
import React from 'react'
import { LineChart, Line, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts'
import { formatUsd, formatSignedPct, formatDrawdownPctPeak } from '../utils/backtestFormatters'

// Returns a CSS color class based on whether value meets or exceeds threshold.
function kpiColorClass(value, threshold = 0) {
  return (Number(value) || 0) >= threshold ? 'color-success' : 'color-error'
}

function ReportKpi({ label, value, hint, colorClass }) {
  return (
    <div title={hint} className="bt-kpi">
      <div className="text-xs color-muted bt-kpi-label">{label}</div>
      <div className={`font-bold bt-kpi-value ${colorClass || 'color-text'}`}>
        {value}
      </div>
    </div>
  )
}

export default function BacktestReportPanel({ report, tickerFilter, lastScanMeta, equityChartData }) {
  if (!report) return null

  return (
    <div className="card bt-report-panel" data-testid="backtest-report-panel">
      <div className="flex-between flex-wrap gap-8 bt-report-header">
        <h3 className="m-0 bt-report-title">Backtest results</h3>
      </div>
      <p className="text-xs color-muted bt-report-meta">
        <strong>{report.tickerScope ?? '—'}</strong>
        {report.tickerCount != null && <> · {report.tickerCount} tickers</>}
        {tickerFilter ? <> · filter {tickerFilter}</> : null}
        {report.initialCapital != null && report.finalCapital != null && (
          <> · ${formatUsd(report.initialCapital)} → ${formatUsd(report.finalCapital)}</>
        )}
        {lastScanMeta?.savedAt && (
          <> · <span title="Persistido en este navegador (localStorage)">último escaneo guardado {new Date(lastScanMeta.savedAt).toLocaleString()}</span></>
        )}
      </p>

      <div className="flex-wrap bt-kpi-row">
        <ReportKpi
          label="Net P&L"
          value={`$${formatUsd(report.netPnl)}`}
          hint="End equity minus start. All trades combined, in USD."
          colorClass={kpiColorClass(report.netPnl)}
        />
        <ReportKpi
          label="Return"
          value={report.totalReturnPct != null ? formatSignedPct(report.totalReturnPct) : '—'}
          hint="Total return on starting capital for this run."
          colorClass={kpiColorClass(report.totalReturnPct)}
        />
        <ReportKpi
          label="Win rate"
          value={report.winRatePct != null ? `${Number(report.winRatePct).toFixed(1)}%` : '—'}
          hint="Percentage of closed trades with positive P&L."
          colorClass={kpiColorClass(report.winRatePct, 50)}
        />
        <ReportKpi
          label="Trades"
          value={
            report.totalTrades != null
              ? `${report.totalTrades}${
                  report.winningTrades != null && report.losingTrades != null
                    ? ` (${report.winningTrades}W/${report.losingTrades}L)`
                    : ''
                }`
              : '—'
          }
          hint="Closed round-trips in this backtest; W/L = wins vs losses."
        />
        <ReportKpi
          label="Profit factor"
          value={report.profitFactor != null ? Number(report.profitFactor).toFixed(2) : '—'}
          hint="Gross profit ÷ gross loss. Above 1.0 means winners beat losers in dollars."
          colorClass={kpiColorClass(report.profitFactor, 1)}
        />
        <ReportKpi
          label="Max drawdown"
          value={
            report.maxDrawdownPct != null
              ? `$${formatUsd(report.maxDrawdown)} (${formatDrawdownPctPeak(report.maxDrawdownPct)} vs peak)`
              : `$${formatUsd(report.maxDrawdown)}`
          }
          hint="Largest drop from a running equity high to a later low (worst streak)."
          colorClass="color-error"
        />
      </div>

      {equityChartData.length > 0 ? (
        <div className="bt-equity-section">
          <div className="text-xs color-muted bt-equity-label">
            Equity — account balance over the simulated period (line downsampled for display)
          </div>
          <div className="chart-container w-full">
            <ResponsiveContainer width="100%" height={220}>
              <LineChart data={equityChartData} margin={{ top: 4, right: 8, left: 0, bottom: 4 }}>
                <CartesianGrid strokeDasharray="3 3" stroke="#21262d" vertical={false} />
                <XAxis
                  dataKey="time"
                  stroke="#8b949e"
                  fontSize={10}
                  tick={{ fill: '#8b949e' }}
                  tickLine={false}
                  axisLine={{ stroke: '#30363d' }}
                  interval={equityChartData.length > 10 ? Math.floor(equityChartData.length / 5) : 0}
                  height={28}
                />
                <YAxis
                  stroke="#8b949e"
                  fontSize={10}
                  tick={{ fill: '#8b949e' }}
                  tickLine={false}
                  axisLine={false}
                  width={48}
                  tickFormatter={(v) =>
                    new Intl.NumberFormat('en-US', { notation: 'compact', maximumFractionDigits: 1 }).format(v)
                  }
                  domain={['auto', 'auto']}
                />
                {/* wrapperClassName targets .recharts-tooltip-wrapper; bt-chart-tooltip applies dark theme */}
                <Tooltip
                  wrapperClassName="bt-chart-tooltip"
                  formatter={(value) => [
                    `$${Number(value).toLocaleString(undefined, { maximumFractionDigits: 0 })}`,
                    'Equity',
                  ]}
                />
                <Line
                  type="monotone"
                  dataKey="equity"
                  stroke="#58a6ff"
                  strokeWidth={2}
                  dot={false}
                  isAnimationActive={false}
                  connectNulls
                />
              </LineChart>
            </ResponsiveContainer>
          </div>
        </div>
      ) : (
        <p className="text-xs color-muted bt-equity-empty">No equity samples for this run.</p>
      )}
    </div>
  )
}
