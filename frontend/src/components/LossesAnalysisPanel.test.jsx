import React from 'react'
import { render, screen } from '@testing-library/react'
import { describe, it, expect, vi } from 'vitest'
import LossesAnalysisPanel from './LossesAnalysisPanel'

// Recharts uses SVG/canvas internals not available in jsdom
vi.mock('recharts', () => {
  const stub = ({ children }) => <div>{children}</div>
  return {
    BarChart: stub,
    Bar: () => null,
    XAxis: () => null,
    YAxis: () => null,
    CartesianGrid: () => null,
    Tooltip: () => null,
    ResponsiveContainer: ({ children }) => <div data-testid="recharts-container">{children}</div>,
    Cell: () => null,
  }
})

// camelCase fixture matching real Spring Boot API response
const mockLosses = {
  byHour: [
    { hour: 9,  count: 5, avgLoss: -120.0 },
    { hour: 14, count: 3, avgLoss: -80.0  },
  ],
  byPattern: [
    { pattern: 'EMA_CROSS', count: 8, avgLoss: -95.0  },
    { pattern: 'BOLLINGER', count: 4, avgLoss: -140.0 },
  ],
  byTicker: [
    { ticker: 'AAPL', count: 6, totalLoss: -500.0 },
    { ticker: 'SPY',  count: 3, totalLoss: -300.0 },
  ],
  worstTrades: [
    { ticker: 'AAPL', strategy: 'EMA_CROSS', signalType: 'CALL', entryPrice: 180.0, exitPrice: 175.0, pnl: -300.0 },
    { ticker: 'SPY',  strategy: 'BOLLINGER',  signalType: 'PUT',  entryPrice: 420.0, exitPrice: 415.0, pnl: -200.0 },
  ],
}

describe('LossesAnalysisPanel', () => {
  it('renders without crashing', () => {
    render(<LossesAnalysisPanel lossesData={mockLosses} />)
    expect(screen.getByTestId('losses-analysis-panel')).toBeInTheDocument()
  })

  it('renders 3 recharts containers (by hour, by pattern, by ticker)', () => {
    render(<LossesAnalysisPanel lossesData={mockLosses} />)
    const containers = screen.getAllByTestId('recharts-container')
    expect(containers.length).toBeGreaterThanOrEqual(3)
  })

  it('renders worst trades table with correct tickers', () => {
    render(<LossesAnalysisPanel lossesData={mockLosses} />)
    expect(screen.getByText('AAPL')).toBeInTheDocument()
    expect(screen.getByText('SPY')).toBeInTheDocument()
  })

  it('renders worst trades table with signal types', () => {
    render(<LossesAnalysisPanel lossesData={mockLosses} />)
    expect(screen.getByText('CALL')).toBeInTheDocument()
    expect(screen.getByText('PUT')).toBeInTheDocument()
  })

  it('shows empty message when lossesData is null', () => {
    render(<LossesAnalysisPanel lossesData={null} />)
    expect(screen.getByText(/no losses data/i)).toBeInTheDocument()
  })

  it('renders "Worst Trades" section heading', () => {
    render(<LossesAnalysisPanel lossesData={mockLosses} />)
    expect(screen.getByText(/worst/i)).toBeInTheDocument()
  })

  it('whenLossesHasCamelCase_rendersWorstTradesWithSignalType', () => {
    render(<LossesAnalysisPanel lossesData={mockLosses} />)
    expect(screen.getByText('CALL')).toBeInTheDocument()
    expect(screen.getByText('PUT')).toBeInTheDocument()
  })

  it('whenLossesHasCamelCase_rendersWorstTradesWithEntryPrice', () => {
    render(<LossesAnalysisPanel lossesData={mockLosses} />)
    // entry_price 180.0 → should render "180.00" not "NaN"
    expect(screen.getByText('180.00')).toBeInTheDocument()
  })

  it('whenLossesHasCamelCase_rendersChartsWithoutNoDataMessage', () => {
    render(<LossesAnalysisPanel lossesData={mockLosses} />)
    // "No data" only shows when arrays are empty — with camelCase fix, arrays are populated
    const noDataMessages = screen.queryAllByText('No data')
    expect(noDataMessages).toHaveLength(0)
  })
})
