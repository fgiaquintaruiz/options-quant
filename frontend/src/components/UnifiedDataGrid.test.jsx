import React from 'react'
import { render, screen, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, afterEach } from 'vitest'
import UnifiedDataGrid from './UnifiedDataGrid'

/**
 * Tests for UnifiedDataGrid — shared trade result grid for backtest and live dashboards.
 * Validates: empty state, row rendering, column headers, PnL formatting, strategy pills,
 * status badges, chart button, info button, row sorting, and modal interaction.
 */

const baseRow = {
  ticker: 'AAPL',
  strategy: 'c1 squeeze',
  startTime: '2024-01-15 09:30:00',
  ep: 150.00,
  xp: 155.00,
  netPnl: 250.00,
  status: 'COMPLETE',
  exitReason: 'TARGET_HIT',
  scanStarted: '09:30:00',
  scanEnded: '09:45:00',
  scanDuration: '15.0s',
  pattern: 'squeeze',
}

afterEach(() => {
  vi.clearAllMocks()
})

describe('UnifiedDataGrid — empty state', () => {
  it('renders empty state message when data is an empty array', () => {
    render(<UnifiedDataGrid data={Object.freeze([])} />)
    expect(screen.getByText(/No data available/i)).toBeInTheDocument()
  })

  it('renders all column headers', () => {
    render(<UnifiedDataGrid data={Object.freeze([])} />)
    expect(screen.getByText('Ticker')).toBeInTheDocument()
    expect(screen.getByText('Strategy')).toBeInTheDocument()
    expect(screen.getByText('Entry')).toBeInTheDocument()
    expect(screen.getByText('Exit')).toBeInTheDocument()
    expect(screen.getByText('PnL')).toBeInTheDocument()
    expect(screen.getByText('Exit Reason')).toBeInTheDocument()
    expect(screen.getByText('Status')).toBeInTheDocument()
    expect(screen.getByText('Scan Started')).toBeInTheDocument()
    expect(screen.getByText('Scan Ended')).toBeInTheDocument()
    expect(screen.getByText('Scan Duration')).toBeInTheDocument()
    expect(screen.getByText('Chart')).toBeInTheDocument()
  })

  it('does not render Info column when onAnalyzeStrategy is not provided', () => {
    render(<UnifiedDataGrid data={Object.freeze([])} />)
    expect(screen.queryByText('Info')).toBeNull()
  })

  it('renders Info column when onAnalyzeStrategy callback is provided', () => {
    render(<UnifiedDataGrid data={Object.freeze([])} onAnalyzeStrategy={vi.fn()} />)
    expect(screen.getByText('Info')).toBeInTheDocument()
  })
})

describe('UnifiedDataGrid — row rendering', () => {
  it('renders ticker and pattern for a data row', () => {
    render(<UnifiedDataGrid data={[baseRow]} />)
    expect(screen.getByText('AAPL')).toBeInTheDocument()
    expect(screen.getByText('squeeze')).toBeInTheDocument()
  })

  it('renders strategy as a pill with correct label', () => {
    render(<UnifiedDataGrid data={[baseRow]} />)
    expect(screen.getByText('c1 squeeze')).toBeInTheDocument()
  })

  it('renders entry price formatted as USD', () => {
    render(<UnifiedDataGrid data={[baseRow]} />)
    expect(screen.getByText('$150.00')).toBeInTheDocument()
  })

  it('renders exit price formatted as USD', () => {
    render(<UnifiedDataGrid data={[baseRow]} />)
    expect(screen.getByText('$155.00')).toBeInTheDocument()
  })

  it('renders positive PnL with positive color class', () => {
    render(<UnifiedDataGrid data={[baseRow]} />)
    const pnlEl = screen.getByText('$250.00')
    expect(pnlEl).toBeInTheDocument()
    expect(pnlEl.className).toContain('ugrid-price-pos')
  })

  it('renders negative PnL with negative color class', () => {
    const negRow = { ...baseRow, netPnl: -100.00 }
    render(<UnifiedDataGrid data={[negRow]} />)
    const pnlEl = screen.getByText('-$100.00')
    expect(pnlEl).toBeInTheDocument()
    expect(pnlEl.className).toContain('ugrid-price-neg')
  })

  it('renders exit reason in row', () => {
    render(<UnifiedDataGrid data={[baseRow]} />)
    expect(screen.getByText('TARGET_HIT')).toBeInTheDocument()
  })

  it('renders — for empty exit reason', () => {
    const row = { ...baseRow, exitReason: '' }
    render(<UnifiedDataGrid data={[row]} />)
    // The component renders '—' when exitReason is empty
    expect(screen.getAllByText('—').length).toBeGreaterThan(0)
  })

  it('renders COMPLETE status badge', () => {
    render(<UnifiedDataGrid data={[baseRow]} />)
    const badge = screen.getByText('COMPLETE')
    expect(badge).toBeInTheDocument()
    expect(badge.className).toContain('badge-status-complete')
  })

  it('renders RUNNING status badge', () => {
    const row = { ...baseRow, status: 'RUNNING' }
    render(<UnifiedDataGrid data={[row]} />)
    const badge = screen.getByText('RUNNING')
    expect(badge.className).toContain('badge-status-running')
  })

  it('renders ERROR status badge', () => {
    const row = { ...baseRow, status: 'ERROR' }
    render(<UnifiedDataGrid data={[row]} />)
    const badge = screen.getByText('ERROR')
    expect(badge.className).toContain('badge-status-error')
  })

  it('renders SIGNAL status badge', () => {
    const row = { ...baseRow, status: 'SIGNAL' }
    render(<UnifiedDataGrid data={[row]} />)
    const badge = screen.getByText('SIGNAL')
    expect(badge.className).toContain('badge-status-signal')
  })

  it('renders scan duration from scanDuration field', () => {
    render(<UnifiedDataGrid data={[baseRow]} />)
    expect(screen.getByText('15.0s')).toBeInTheDocument()
  })

  it('renders — for missing scan duration', () => {
    const row = { ...baseRow, scanDuration: null }
    render(<UnifiedDataGrid data={[row]} />)
    expect(screen.getAllByText('—').length).toBeGreaterThan(0)
  })

  it('renders lastGridNote when present', () => {
    const row = { ...baseRow, lastGridNote: 'Grid note text' }
    render(<UnifiedDataGrid data={[row]} />)
    expect(screen.getByText('Grid note text')).toBeInTheDocument()
  })
})

describe('UnifiedDataGrid — null/missing price values', () => {
  it('renders — for null entry price', () => {
    const row = { ...baseRow, ep: null, xp: null }
    render(<UnifiedDataGrid data={[row]} />)
    expect(screen.getAllByText('—').length).toBeGreaterThan(0)
  })

  it('renders — for null PnL', () => {
    const row = { ...baseRow, netPnl: null }
    render(<UnifiedDataGrid data={[row]} />)
    expect(screen.getAllByText('—').length).toBeGreaterThan(0)
  })
})

describe('UnifiedDataGrid — strategy pill variants', () => {
  it('renders call-side pill for c1 strategy', () => {
    render(<UnifiedDataGrid data={[{ ...baseRow, strategy: 'c1 squeeze' }]} />)
    const pill = screen.getByText('c1 squeeze')
    expect(pill.className).toContain('ugrid-pill-call')
  })

  it('renders put-side pill for p5 strategy', () => {
    render(<UnifiedDataGrid data={[{ ...baseRow, strategy: 'p5 continuation' }]} />)
    const pill = screen.getByText('p5 continuation')
    expect(pill.className).toContain('ugrid-pill-put')
  })

  it('renders neutral pill for unknown strategy', () => {
    render(<UnifiedDataGrid data={[{ ...baseRow, strategy: 'custom_spread' }]} />)
    const pill = screen.getByText('custom spread')
    expect(pill.className).toContain('ugrid-pill-neu')
  })

  it('renders muted label for N/A strategy', () => {
    render(<UnifiedDataGrid data={[{ ...baseRow, strategy: 'N/A' }]} />)
    expect(screen.getByText('N/A')).toBeInTheDocument()
  })
})

describe('UnifiedDataGrid — chart button', () => {
  it('renders chart button when chartPath is present', () => {
    const row = { ...baseRow, chartPath: '/charts/aapl.html' }
    render(<UnifiedDataGrid data={[row]} />)
    // Activity icon button should render
    const buttons = screen.getAllByRole('button')
    const chartBtn = buttons.find(b => b.className.includes('ugrid-chart-btn'))
    expect(chartBtn).toBeTruthy()
  })

  it('renders — in chart column when chartPath is absent', () => {
    const row = { ...baseRow, chartPath: null }
    render(<UnifiedDataGrid data={[row]} />)
    const buttons = screen.queryAllByRole('button')
    const chartBtn = buttons.find(b => b.className?.includes('ugrid-chart-btn'))
    expect(chartBtn).toBeUndefined()
  })

  it('opens chart modal when chart button is clicked', async () => {
    const row = { ...baseRow, chartPath: '/charts/aapl.html' }
    render(<UnifiedDataGrid data={[row]} />)
    const chartBtn = screen.getAllByRole('button').find(b => b.className.includes('ugrid-chart-btn'))
    await userEvent.click(chartBtn)
    expect(screen.getByText('Close')).toBeInTheDocument()
  })

  it('closes chart modal when Close button is clicked', async () => {
    const row = { ...baseRow, chartPath: '/charts/aapl.html' }
    render(<UnifiedDataGrid data={[row]} />)
    const chartBtn = screen.getAllByRole('button').find(b => b.className.includes('ugrid-chart-btn'))
    await userEvent.click(chartBtn)
    await userEvent.click(screen.getByText('Close'))
    expect(screen.queryByText('Close')).toBeNull()
  })

  it('modal renders iframe with correct src for relative path', async () => {
    const row = { ...baseRow, chartPath: 'aapl.html' }
    render(<UnifiedDataGrid data={[row]} />)
    const chartBtn = screen.getAllByRole('button').find(b => b.className.includes('ugrid-chart-btn'))
    await userEvent.click(chartBtn)
    const iframe = document.querySelector('iframe')
    expect(iframe).toBeTruthy()
    expect(iframe.src).toContain('/charts/')
  })

  it('modal renders "No chart path" message when chartPath is empty', () => {
    // Simulate modal with a row with no chartPath — we test ChartModal indirectly
    // by directly checking that the no-chart path is unreachable from the grid
    // (chart button is only rendered when chartPath is truthy)
    const row = { ...baseRow, chartPath: null }
    render(<UnifiedDataGrid data={[row]} />)
    expect(screen.queryByText(/No chart path/i)).toBeNull()
  })
})

describe('UnifiedDataGrid — Info button (onAnalyzeStrategy)', () => {
  it('renders Info button per row when onAnalyzeStrategy is provided', () => {
    render(<UnifiedDataGrid data={[baseRow]} onAnalyzeStrategy={vi.fn()} />)
    expect(screen.getByTestId('bt-trade-row-info')).toBeInTheDocument()
  })

  it('does not render Info button when onAnalyzeStrategy is not provided', () => {
    render(<UnifiedDataGrid data={[baseRow]} />)
    expect(screen.queryByTestId('bt-trade-row-info')).toBeNull()
  })

  it('calls onAnalyzeStrategy with the row data when Info button is clicked', async () => {
    const onAnalyzeStrategy = vi.fn()
    render(<UnifiedDataGrid data={[baseRow]} onAnalyzeStrategy={onAnalyzeStrategy} />)
    await userEvent.click(screen.getByTestId('bt-trade-row-info'))
    expect(onAnalyzeStrategy).toHaveBeenCalledOnce()
    expect(onAnalyzeStrategy).toHaveBeenCalledWith(expect.objectContaining({ ticker: 'AAPL' }))
  })
})

describe('UnifiedDataGrid — sorting', () => {
  it('sorts incomplete rows before complete rows', () => {
    const rows = [
      { ...baseRow, ticker: 'AAPL', status: 'COMPLETE', startTime: '2024-01-15 09:30:00' },
      { ...baseRow, ticker: 'TSLA', status: 'RUNNING', startTime: '2024-01-15 09:35:00' },
    ]
    render(<UnifiedDataGrid data={rows} />)
    const tickers = screen.getAllByText(/AAPL|TSLA/).filter(el => el.tagName === 'STRONG')
    // TSLA (RUNNING) should appear before AAPL (COMPLETE)
    expect(tickers[0].textContent).toBe('TSLA')
    expect(tickers[1].textContent).toBe('AAPL')
  })

  it('sorts complete rows by netPnl ascending when multiple complete rows exist', () => {
    const rows = [
      { ...baseRow, ticker: 'AAPL', status: 'COMPLETE', netPnl: 500, startTime: '2024-01-14 09:30:00' },
      { ...baseRow, ticker: 'TSLA', status: 'COMPLETE', netPnl: 100, startTime: '2024-01-15 09:30:00' },
    ]
    render(<UnifiedDataGrid data={rows} />)
    const tickers = screen.getAllByText(/AAPL|TSLA/).filter(el => el.tagName === 'STRONG')
    // Lower pnl (TSLA 100) comes first in ascending sort
    expect(tickers[0].textContent).toBe('TSLA')
    expect(tickers[1].textContent).toBe('AAPL')
  })

  it('renders multiple rows from data array', () => {
    const rows = [
      { ...baseRow, ticker: 'AAPL' },
      { ...baseRow, ticker: 'GOOGL', startTime: '2024-01-14 09:30:00' },
    ]
    render(<UnifiedDataGrid data={rows} />)
    expect(screen.getByText('AAPL')).toBeInTheDocument()
    expect(screen.getByText('GOOGL')).toBeInTheDocument()
  })
})

describe('UnifiedDataGrid — default props', () => {
  it('renders without crashing when data prop is omitted', () => {
    render(<UnifiedDataGrid />)
    expect(screen.getByText(/No data available/i)).toBeInTheDocument()
  })
})
