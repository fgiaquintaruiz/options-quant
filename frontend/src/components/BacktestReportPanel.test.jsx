import React from 'react'
import { render, screen } from '@testing-library/react'
import { describe, it, expect, vi, afterEach } from 'vitest'
import BacktestReportPanel from './BacktestReportPanel'

// ── Module mocks ─────────────────────────────────────────────────────────────

// Recharts uses SVG/canvas internals not available in jsdom.
// We stub the entire module so rendering does not throw.
vi.mock('recharts', () => {
  const stub = ({ children }) => <div>{children}</div>
  return {
    LineChart: stub,
    Line: () => null,
    XAxis: () => null,
    YAxis: () => null,
    CartesianGrid: () => null,
    Tooltip: () => null,
    ResponsiveContainer: ({ children }) => <div data-testid="recharts-container">{children}</div>,
  }
})

// ── Fixtures ──────────────────────────────────────────────────────────────────

const baseReport = {
  tickerScope: 'ALL',
  tickerCount: 5,
  initialCapital: 50000,
  finalCapital: 52500,
  netPnl: 2500,
  totalReturnPct: 0.05,         // +5.00%
  winRatePct: 62.5,
  totalTrades: 20,
  winningTrades: 13,
  losingTrades: 7,
  profitFactor: 1.8,
  maxDrawdown: 800,
  maxDrawdownPct: 0.016,        // 1.60% vs peak
}

const equityPoints = [
  { time: '2024-01-01', equity: 50000 },
  { time: '2024-06-01', equity: 51200 },
  { time: '2024-12-31', equity: 52500 },
]

const FROZEN_EMPTY = Object.freeze([])

afterEach(() => {
  vi.clearAllMocks()
})

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('BacktestReportPanel', () => {
  // ── Null guard ──────────────────────────────────────────────────────────────

  it('renders nothing when report is null', () => {
    const { container } = render(
      <BacktestReportPanel report={null} tickerFilter="" lastScanMeta={null} equityChartData={FROZEN_EMPTY} />
    )
    expect(container.firstChild).toBeNull()
  })

  // ── Root element ────────────────────────────────────────────────────────────

  it('renders the panel root with data-testid="backtest-report-panel"', () => {
    render(
      <BacktestReportPanel report={baseReport} tickerFilter="" lastScanMeta={null} equityChartData={FROZEN_EMPTY} />
    )
    expect(screen.getByTestId('backtest-report-panel')).toBeInTheDocument()
  })

  it('renders the "Backtest results" heading', () => {
    render(
      <BacktestReportPanel report={baseReport} tickerFilter="" lastScanMeta={null} equityChartData={FROZEN_EMPTY} />
    )
    expect(screen.getByRole('heading', { name: /backtest results/i })).toBeInTheDocument()
  })

  // ── Meta row ────────────────────────────────────────────────────────────────

  it('renders tickerScope in the meta row', () => {
    render(
      <BacktestReportPanel report={baseReport} tickerFilter="" lastScanMeta={null} equityChartData={FROZEN_EMPTY} />
    )
    expect(screen.getByText('ALL')).toBeInTheDocument()
  })

  it('renders ticker count in the meta row', () => {
    render(
      <BacktestReportPanel report={baseReport} tickerFilter="" lastScanMeta={null} equityChartData={FROZEN_EMPTY} />
    )
    expect(screen.getByText(/5 tickers/)).toBeInTheDocument()
  })

  it('renders active ticker filter in the meta row', () => {
    render(
      <BacktestReportPanel
        report={baseReport}
        tickerFilter="AAPL"
        lastScanMeta={null}
        equityChartData={FROZEN_EMPTY}
      />
    )
    expect(screen.getByText(/filter AAPL/)).toBeInTheDocument()
  })

  it('does not render ticker filter text when tickerFilter is empty', () => {
    render(
      <BacktestReportPanel report={baseReport} tickerFilter="" lastScanMeta={null} equityChartData={FROZEN_EMPTY} />
    )
    expect(screen.queryByText(/filter/)).toBeNull()
  })

  it('renders lastScanMeta savedAt when provided', () => {
    const meta = { savedAt: new Date('2024-06-15T10:30:00').getTime() }
    render(
      <BacktestReportPanel report={baseReport} tickerFilter="" lastScanMeta={meta} equityChartData={FROZEN_EMPTY} />
    )
    expect(screen.getByText(/último escaneo guardado/i)).toBeInTheDocument()
  })

  it('does not render lastScanMeta block when null', () => {
    render(
      <BacktestReportPanel report={baseReport} tickerFilter="" lastScanMeta={null} equityChartData={FROZEN_EMPTY} />
    )
    expect(screen.queryByText(/último escaneo guardado/i)).toBeNull()
  })

  // ── KPI — Net P&L ──────────────────────────────────────────────────────────

  it('renders Net P&L label', () => {
    render(
      <BacktestReportPanel report={baseReport} tickerFilter="" lastScanMeta={null} equityChartData={FROZEN_EMPTY} />
    )
    expect(screen.getByTitle(/end equity minus start/i)).toBeInTheDocument()
  })

  it('renders Net P&L value with dollar sign', () => {
    render(
      <BacktestReportPanel report={baseReport} tickerFilter="" lastScanMeta={null} equityChartData={FROZEN_EMPTY} />
    )
    // formatUsd returns locale-formatted number
    expect(screen.getByTitle(/end equity minus start/i).textContent).toContain('$')
  })

  // ── KPI — Return ────────────────────────────────────────────────────────────

  it('renders Return KPI with formatted signed percentage', () => {
    render(
      <BacktestReportPanel report={baseReport} tickerFilter="" lastScanMeta={null} equityChartData={FROZEN_EMPTY} />
    )
    expect(screen.getByTitle(/total return on starting capital/i).textContent).toContain('+5.00%')
  })

  it('renders Return as "—" when totalReturnPct is null', () => {
    render(
      <BacktestReportPanel
        report={{ ...baseReport, totalReturnPct: null }}
        tickerFilter=""
        lastScanMeta={null}
        equityChartData={FROZEN_EMPTY}
      />
    )
    const kpi = screen.getByTitle(/total return on starting capital/i)
    expect(kpi.textContent).toContain('—')
  })

  // ── KPI — Win rate ──────────────────────────────────────────────────────────

  it('renders win rate formatted with one decimal and percent sign', () => {
    render(
      <BacktestReportPanel report={baseReport} tickerFilter="" lastScanMeta={null} equityChartData={FROZEN_EMPTY} />
    )
    expect(screen.getByTitle(/percentage of closed trades/i).textContent).toContain('62.5%')
  })

  it('renders win rate as "—" when winRatePct is null', () => {
    render(
      <BacktestReportPanel
        report={{ ...baseReport, winRatePct: null }}
        tickerFilter=""
        lastScanMeta={null}
        equityChartData={FROZEN_EMPTY}
      />
    )
    expect(screen.getByTitle(/percentage of closed trades/i).textContent).toContain('—')
  })

  // ── KPI — Trades ────────────────────────────────────────────────────────────

  it('renders total trades with W/L breakdown', () => {
    render(
      <BacktestReportPanel report={baseReport} tickerFilter="" lastScanMeta={null} equityChartData={FROZEN_EMPTY} />
    )
    const tradesKpi = screen.getByTitle(/closed round-trips/i)
    expect(tradesKpi.textContent).toContain('20')
    expect(tradesKpi.textContent).toContain('13W/7L')
  })

  it('renders trades as "—" when totalTrades is null', () => {
    render(
      <BacktestReportPanel
        report={{ ...baseReport, totalTrades: null }}
        tickerFilter=""
        lastScanMeta={null}
        equityChartData={FROZEN_EMPTY}
      />
    )
    expect(screen.getByTitle(/closed round-trips/i).textContent).toContain('—')
  })

  // ── KPI — Profit factor ─────────────────────────────────────────────────────

  it('renders profit factor with two decimal places', () => {
    render(
      <BacktestReportPanel report={baseReport} tickerFilter="" lastScanMeta={null} equityChartData={FROZEN_EMPTY} />
    )
    expect(screen.getByTitle(/gross profit/i).textContent).toContain('1.80')
  })

  it('renders profit factor as "—" when null', () => {
    render(
      <BacktestReportPanel
        report={{ ...baseReport, profitFactor: null }}
        tickerFilter=""
        lastScanMeta={null}
        equityChartData={FROZEN_EMPTY}
      />
    )
    expect(screen.getByTitle(/gross profit/i).textContent).toContain('—')
  })

  // ── KPI — Max drawdown ──────────────────────────────────────────────────────

  it('renders max drawdown with dollar and percentage when maxDrawdownPct provided', () => {
    render(
      <BacktestReportPanel report={baseReport} tickerFilter="" lastScanMeta={null} equityChartData={FROZEN_EMPTY} />
    )
    const ddKpi = screen.getByTitle(/largest drop from a running equity/i)
    expect(ddKpi.textContent).toContain('$')
    expect(ddKpi.textContent).toContain('%')
  })

  it('renders max drawdown without percentage when maxDrawdownPct is null', () => {
    render(
      <BacktestReportPanel
        report={{ ...baseReport, maxDrawdownPct: null }}
        tickerFilter=""
        lastScanMeta={null}
        equityChartData={FROZEN_EMPTY}
      />
    )
    const ddKpi = screen.getByTitle(/largest drop from a running equity/i)
    // Should still show the dollar amount but no "vs peak"
    expect(ddKpi.textContent).not.toContain('vs peak')
  })

  // ── Equity chart ────────────────────────────────────────────────────────────

  it('renders the equity chart section when equityChartData is non-empty', () => {
    render(
      <BacktestReportPanel
        report={baseReport}
        tickerFilter=""
        lastScanMeta={null}
        equityChartData={equityPoints}
      />
    )
    expect(screen.getByTestId('recharts-container')).toBeInTheDocument()
    expect(screen.getByText(/equity — account balance/i)).toBeInTheDocument()
  })

  it('renders empty message when equityChartData is empty', () => {
    render(
      <BacktestReportPanel
        report={baseReport}
        tickerFilter=""
        lastScanMeta={null}
        equityChartData={FROZEN_EMPTY}
      />
    )
    expect(screen.getByText(/no equity samples/i)).toBeInTheDocument()
    expect(screen.queryByTestId('recharts-container')).toBeNull()
  })

  // ── Capital range in meta ───────────────────────────────────────────────────

  it('renders initial→final capital when both are provided', () => {
    render(
      <BacktestReportPanel report={baseReport} tickerFilter="" lastScanMeta={null} equityChartData={FROZEN_EMPTY} />
    )
    // Both formatted by formatUsd — expect at least the arrow separator
    const meta = screen.getByTestId('backtest-report-panel')
    expect(meta.textContent).toContain('→')
  })

  it('does not render capital range when initialCapital is null', () => {
    render(
      <BacktestReportPanel
        report={{ ...baseReport, initialCapital: null }}
        tickerFilter=""
        lastScanMeta={null}
        equityChartData={FROZEN_EMPTY}
      />
    )
    expect(screen.queryByText(/→/)).toBeNull()
  })
})
