import React from 'react'
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react'
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest'
import BacktestDashboard from './BacktestDashboard'

// ── API mock ──────────────────────────────────────────────────────────────────

vi.mock('../api', () => ({
  backtestApi: {
    isRunning:                   vi.fn(),
    runBacktest:                 vi.fn(),
    stopBacktest:                vi.fn(),
    getScheduler:                vi.fn(),
    postScheduler:               vi.fn(),
    setMaxConcurrent:            vi.fn(),
    getTickerMemoryRiskProfiles: vi.fn(),
  },
}))

// ── Child component mocks ─────────────────────────────────────────────────────

vi.mock('../components/UnifiedDataGrid', () => ({
  default: ({ data, onAnalyzeStrategy }) => (
    <div data-testid="unified-data-grid">
      {data.map((row, i) => (
        <div key={i} data-testid={`grid-row-${i}`}>
          <span data-testid={`grid-ticker-${i}`}>{row.ticker}</span>
          <button
            data-testid={`analyze-btn-${i}`}
            onClick={() => onAnalyzeStrategy(row)}
          >
            Analyze {row.strategy}
          </button>
        </div>
      ))}
    </div>
  ),
}))

vi.mock('../components/TickerSelector', () => ({
  default: ({ value, onChange, disabled, scope, onScopeChange }) => (
    <div data-testid="ticker-selector">
      <input
        data-testid="ticker-filter-input"
        value={value}
        disabled={disabled}
        onChange={e => onChange(e.target.value)}
      />
      <select
        data-testid="ticker-scope-select"
        value={scope}
        disabled={disabled}
        onChange={e => onScopeChange(e.target.value)}
      >
        <option value="HOT">HOT</option>
        <option value="ALL">ALL</option>
      </select>
    </div>
  ),
}))

vi.mock('../components/SwapButton', () => ({
  default: ({ active, onText, offText, testId, onClick }) => (
    <button data-testid={testId} onClick={onClick}>
      {active ? onText : offText}
    </button>
  ),
}))

vi.mock('../components/BacktestReportPanel', () => ({
  default: ({ report }) => (
    <div data-testid="backtest-report-panel">
      <span data-testid="report-total-trades">{report?.totalTrades}</span>
    </div>
  ),
}))

vi.mock('../components/MemoryPanel', () => ({
  default: ({ open, rows, loading, error, onToggle, onReload }) => (
    <div data-testid="memory-panel">
      <button data-testid="memory-toggle" onClick={() => onToggle(!open)}>
        {open ? 'Close Memory' : 'Open Memory'}
      </button>
      {open && (
        <div data-testid="memory-panel-content">
          {loading && <span data-testid="memory-loading">Loading memory…</span>}
          {error && <span data-testid="memory-error">{error}</span>}
          {(rows || []).map((r, i) => (
            <span key={i} data-testid={`memory-row-${i}`}>{r.ticker}</span>
          ))}
        </div>
      )}
    </div>
  ),
}))

vi.mock('../components/ImproveModal', () => ({
  default: ({ open, ticker, strategyName, onClose }) => (
    open ? (
      <div data-testid="improve-modal">
        <span data-testid="improve-modal-ticker">{ticker}</span>
        <span data-testid="improve-modal-strategy">{strategyName}</span>
        <button
          data-testid="improve-modal-close"
          onClick={() => onClose({ gridResult: null, ticker: null, strategyName: null, entryTime: null })}
        >
          Close
        </button>
        <button
          data-testid="improve-modal-close-with-result"
          onClick={() => onClose({
            gridResult: {
              success: true,
              optimization: {
                hasWinner: true,
                bestMetricValue: 1500,
                bestParameters: { tpMultiplierDelta: 0.5, slMultiplierDelta: -0.3 },
              },
            },
            ticker,
            strategyName,
            entryTime: '09:30:00',
          })}
        >
          Close with result
        </button>
      </div>
    ) : null
  ),
}))

// ── Storage mock ──────────────────────────────────────────────────────────────

vi.mock('../utils/storage', () => ({
  LS: {
    get:    vi.fn((key, fallback) => fallback),
    set:    vi.fn(),
    remove: vi.fn(),
  },
}))

// ── EventSource mock ──────────────────────────────────────────────────────────

class MockEventSource {
  constructor(url) {
    this.url = url
    this._listeners = {}
    this._onopen = null
    this._onerror = null
    this.closed = false
    MockEventSource.lastInstance = this
  }

  addEventListener(type, fn) { this._listeners[type] = fn }

  close() { this.closed = true }

  set onopen(fn) { this._onopen = fn }
  get onopen() { return this._onopen }
  set onerror(fn) { this._onerror = fn }
  get onerror() { return this._onerror }

  triggerOpen() { if (this._onopen) this._onopen() }
  triggerError() { if (this._onerror) this._onerror() }

  dispatchSseEvent(type, data) {
    if (this._listeners[type]) {
      this._listeners[type]({ data: JSON.stringify(data) })
    }
  }
}
MockEventSource.lastInstance = null

// ── Imports after mocks ───────────────────────────────────────────────────────

import { backtestApi } from '../api'
import { LS } from '../utils/storage'

// ── Fixtures ──────────────────────────────────────────────────────────────────

const defaultReport = {
  totalTrades: 42,
  winRate: 0.6,
  winRatePct: 0.6,
  totalPnl: 8500,
  netPnl: 8500,
  byStrategy: {
    'c1 squeeze': { trades: 20, winRate: 0.65, totalPnl: 5000 },
    'p2 continuation': { trades: 22, winRate: 0.55, totalPnl: 3500 },
  },
  equityCurve: [
    { time: '09:30', equity: 50000 },
    { time: '10:00', equity: 52000 },
    { time: '10:30', equity: 58500 },
  ],
  trades: [
    {
      ticker: 'AAPL',
      pattern: 'squeeze',
      strategy: 'c1 squeeze',
      entryTime: '09:30:00',
      exitTime: '09:45:00',
      netPnl: 500,
    },
    {
      ticker: 'MSFT',
      pattern: 'cont',
      strategy: 'p2 continuation',
      entryTime: '10:00:00',
      exitTime: '10:20:00',
      netPnl: -200,
    },
  ],
}

// ── Helpers ───────────────────────────────────────────────────────────────────

async function renderDashboard() {
  let result
  await act(async () => {
    result = render(<BacktestDashboard />)
  })
  return result
}

/** Clicks Run Backtest and triggers the SSE onopen so runBacktest fires. */
async function startBacktest() {
  await renderDashboard()
  await act(async () => {
    fireEvent.click(screen.getByRole('button', { name: /Run Backtest/i }))
  })
  await act(async () => {
    if (MockEventSource.lastInstance) {
      MockEventSource.lastInstance.triggerOpen()
    }
  })
}

// ── Setup / Teardown ──────────────────────────────────────────────────────────

beforeEach(() => {
  global.EventSource = MockEventSource
  MockEventSource.lastInstance = null

  backtestApi.isRunning.mockResolvedValue({ running: false })
  backtestApi.runBacktest.mockResolvedValue(defaultReport)
  backtestApi.stopBacktest.mockResolvedValue({})
  backtestApi.getScheduler.mockResolvedValue({ schedulerEnabled: false })
  backtestApi.postScheduler.mockResolvedValue({})
  backtestApi.setMaxConcurrent.mockResolvedValue({})
  backtestApi.getTickerMemoryRiskProfiles.mockResolvedValue([])

  LS.get.mockImplementation((key, fallback) => fallback)
  LS.set.mockImplementation(() => {})
})

afterEach(() => {
  vi.clearAllMocks()
})

// ═════════════════════════════════════════════════════════════════════════════
// Initial render
// ═════════════════════════════════════════════════════════════════════════════

describe('BacktestDashboard — initial render', () => {
  it('renders the root container', async () => {
    await renderDashboard()
    expect(screen.getByTestId('backtest-dashboard')).toBeInTheDocument()
  })

  it('renders the Run Backtest button when not running', async () => {
    await renderDashboard()
    expect(screen.getByRole('button', { name: /Run Backtest/i })).toBeInTheDocument()
  })

  it('does not render Stop Backtest button when not running', async () => {
    await renderDashboard()
    expect(screen.queryByRole('button', { name: /Stop Backtest/i })).not.toBeInTheDocument()
  })

  it('renders TickerSelector', async () => {
    await renderDashboard()
    expect(screen.getByTestId('ticker-selector')).toBeInTheDocument()
  })

  it('renders the auto-run toggle button', async () => {
    await renderDashboard()
    expect(screen.getByTestId('backtest-auto-run-toggle')).toBeInTheDocument()
  })

  it('shows "Manual Run" label when scheduler is off', async () => {
    await renderDashboard()
    expect(screen.getByTestId('backtest-auto-run-toggle')).toHaveTextContent('Manual Run')
  })

  it('renders the concurrent-threads input', async () => {
    await renderDashboard()
    expect(screen.getByRole('spinbutton')).toBeInTheDocument()
  })

  it('renders Capital stat box', async () => {
    await renderDashboard()
    expect(screen.getByText(/Capital:/i)).toBeInTheDocument()
  })

  it('renders Risk % stat box', async () => {
    await renderDashboard()
    expect(screen.getByText(/Risk %:/i)).toBeInTheDocument()
  })

  it('renders Trade log section heading', async () => {
    await renderDashboard()
    expect(screen.getByRole('heading', { name: /Trade log/i })).toBeInTheDocument()
  })

  it('renders UnifiedDataGrid with empty data initially', async () => {
    await renderDashboard()
    expect(screen.getByTestId('unified-data-grid')).toBeInTheDocument()
  })

  it('does not render BacktestReportPanel when no report', async () => {
    await renderDashboard()
    expect(screen.queryByTestId('backtest-report-panel')).not.toBeInTheDocument()
  })

  it('does not render strategy breakdown when no report', async () => {
    await renderDashboard()
    expect(screen.queryByText(/Por estrategía/)).not.toBeInTheDocument()
  })

  it('calls backtestApi.isRunning on mount', async () => {
    await renderDashboard()
    expect(backtestApi.isRunning).toHaveBeenCalled()
  })

  it('calls backtestApi.getScheduler on mount', async () => {
    await renderDashboard()
    expect(backtestApi.getScheduler).toHaveBeenCalled()
  })

  it('calls backtestApi.setMaxConcurrent on mount', async () => {
    await renderDashboard()
    expect(backtestApi.setMaxConcurrent).toHaveBeenCalled()
  })
})

// ═════════════════════════════════════════════════════════════════════════════
// Scheduler toggle
// ═════════════════════════════════════════════════════════════════════════════

describe('BacktestDashboard — scheduler toggle', () => {
  it('shows "Auto Run" when scheduler API reports enabled', async () => {
    backtestApi.getScheduler.mockResolvedValue({ schedulerEnabled: true, fixedDelayMs: 3600000 })
    await renderDashboard()
    expect(screen.getByTestId('backtest-auto-run-toggle')).toHaveTextContent('Auto Run')
  })

  it('shows delay label when scheduler is enabled with fixedDelayMs', async () => {
    backtestApi.getScheduler.mockResolvedValue({ schedulerEnabled: true, fixedDelayMs: 3600000 })
    await renderDashboard()
    expect(screen.getByText(/between runs/i)).toBeInTheDocument()
  })

  it('calls postScheduler when toggle is clicked', async () => {
    await renderDashboard()
    await act(async () => {
      fireEvent.click(screen.getByTestId('backtest-auto-run-toggle'))
    })
    expect(backtestApi.postScheduler).toHaveBeenCalled()
  })

  it('switches label to "Auto Run" after successful toggle on', async () => {
    await renderDashboard()
    await act(async () => {
      fireEvent.click(screen.getByTestId('backtest-auto-run-toggle'))
    })
    expect(screen.getByTestId('backtest-auto-run-toggle')).toHaveTextContent('Auto Run')
  })

  it('shows error banner when postScheduler fails', async () => {
    backtestApi.postScheduler.mockRejectedValue(new Error('Server error'))
    await renderDashboard()
    await act(async () => {
      fireEvent.click(screen.getByTestId('backtest-auto-run-toggle'))
    })
    expect(screen.getByRole('alert')).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('Scheduler:')
  })
})

// ═════════════════════════════════════════════════════════════════════════════
// Capital & Risk adjustments
// ═════════════════════════════════════════════════════════════════════════════

describe('BacktestDashboard — capital & risk controls', () => {
  // The capital pill renders as `$` + `{formatUsd(...)}` = two text nodes.
  // Use querySelector + textContent to match the full value.
  const getCapitalText = () => document.querySelector('.bt-capital-pill')?.textContent ?? ''

  it('increases capital by 5000 when Capital ChevronUp is clicked', async () => {
    await renderDashboard()
    // Capital up is the 1st bt-chevron element in the DOM.
    const chevrons = document.querySelectorAll('.bt-chevron')
    fireEvent.click(chevrons[0])
    expect(getCapitalText()).toContain('55,000')
  })

  it('decreases capital by 5000 when Capital ChevronDown is clicked', async () => {
    await renderDashboard()
    const chevrons = document.querySelectorAll('.bt-chevron')
    fireEvent.click(chevrons[1])
    expect(getCapitalText()).toContain('45,000')
  })

  it('capital does not go below 1000', async () => {
    LS.get.mockImplementation((key, fallback) => {
      if (key === 'bt_params') return { capital: 1000, risk: 0.02 }
      return fallback
    })
    await renderDashboard()
    const chevrons = document.querySelectorAll('.bt-chevron')
    fireEvent.click(chevrons[1]) // Capital down — should clamp at 1000
    expect(getCapitalText()).toContain('1,000')
  })

  it('increases risk by 0.5% when Risk ChevronUp is clicked', async () => {
    await renderDashboard()
    // Risk up is the 3rd bt-chevron (index 2)
    const chevrons = document.querySelectorAll('.bt-chevron')
    fireEvent.click(chevrons[2])
    // Default 2.0% + 0.5% = 2.5%
    expect(screen.getByText('2.5%')).toBeInTheDocument()
  })

  it('decreases risk when Risk ChevronDown is clicked', async () => {
    await renderDashboard()
    const chevrons = document.querySelectorAll('.bt-chevron')
    fireEvent.click(chevrons[3])
    // Default 2.0% − 0.5% = 1.5%
    expect(screen.getByText('1.5%')).toBeInTheDocument()
  })
})

// ═════════════════════════════════════════════════════════════════════════════
// Max concurrent threads
// ═════════════════════════════════════════════════════════════════════════════

describe('BacktestDashboard — concurrent input', () => {
  it('calls setMaxConcurrent API when value is changed to a valid number', async () => {
    await renderDashboard()
    await act(async () => {
      fireEvent.change(screen.getByRole('spinbutton'), { target: { value: '8' } })
    })
    expect(backtestApi.setMaxConcurrent).toHaveBeenCalledWith(8)
  })

  it('ignores NaN input for maxConcurrent', async () => {
    await renderDashboard()
    const callsBefore = backtestApi.setMaxConcurrent.mock.calls.length
    fireEvent.change(screen.getByRole('spinbutton'), { target: { value: 'abc' } })
    expect(backtestApi.setMaxConcurrent.mock.calls.length).toBe(callsBefore)
  })

  it('ignores out-of-range value (>16) for maxConcurrent', async () => {
    await renderDashboard()
    const callsBefore = backtestApi.setMaxConcurrent.mock.calls.length
    fireEvent.change(screen.getByRole('spinbutton'), { target: { value: '99' } })
    expect(backtestApi.setMaxConcurrent.mock.calls.length).toBe(callsBefore)
  })

  it('ignores out-of-range value (<1) for maxConcurrent', async () => {
    await renderDashboard()
    const callsBefore = backtestApi.setMaxConcurrent.mock.calls.length
    fireEvent.change(screen.getByRole('spinbutton'), { target: { value: '0' } })
    expect(backtestApi.setMaxConcurrent.mock.calls.length).toBe(callsBefore)
  })
})

// ═════════════════════════════════════════════════════════════════════════════
// Running state — button swap and elapsed timer
// ═════════════════════════════════════════════════════════════════════════════

describe('BacktestDashboard — running state (API reports running)', () => {
  it('shows Stop Backtest button when isRunning returns true', async () => {
    backtestApi.isRunning.mockResolvedValue({ running: true })
    await renderDashboard()
    expect(screen.getByRole('button', { name: /Stop Backtest/i })).toBeInTheDocument()
  })

  it('hides Run Backtest button when API reports running', async () => {
    backtestApi.isRunning.mockResolvedValue({ running: true })
    await renderDashboard()
    expect(screen.queryByRole('button', { name: /Run Backtest/i })).not.toBeInTheDocument()
  })

  it('disables TickerSelector input when running', async () => {
    backtestApi.isRunning.mockResolvedValue({ running: true })
    await renderDashboard()
    expect(screen.getByTestId('ticker-filter-input')).toBeDisabled()
  })
})

// ═════════════════════════════════════════════════════════════════════════════
// Start backtest — button transitions
// ═════════════════════════════════════════════════════════════════════════════

describe('BacktestDashboard — start scan button state', () => {
  it('shows Stop Backtest button immediately after clicking Run', async () => {
    await renderDashboard()
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Run Backtest/i }))
    })
    expect(screen.getByRole('button', { name: /Stop Backtest/i })).toBeInTheDocument()
  })

  it('shows elapsed timer section after clicking Run', async () => {
    await renderDashboard()
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Run Backtest/i }))
    })
    // "Running… 0s" — check by class since text is split across nodes
    expect(document.querySelector('.bt-elapsed')).toBeInTheDocument()
  })

  it('elapsed timer counts up with fake timers', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: false })
    await renderDashboard()
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Run Backtest/i }))
    })
    act(() => { vi.advanceTimersByTime(3000) })
    const elapsedEl = document.querySelector('.bt-elapsed')
    expect(elapsedEl?.textContent).toMatch(/3s/)
    vi.useRealTimers()
  })

  it('calls postScheduler before starting the run', async () => {
    await renderDashboard()
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Run Backtest/i }))
    })
    expect(backtestApi.postScheduler).toHaveBeenCalled()
  })
})

// ═════════════════════════════════════════════════════════════════════════════
// Start backtest — complete flow (SSE onopen triggers runBacktest)
// ═════════════════════════════════════════════════════════════════════════════

describe('BacktestDashboard — start backtest full flow', () => {
  it('calls runBacktest with correct params after SSE opens', async () => {
    await startBacktest()
    expect(backtestApi.runBacktest).toHaveBeenCalled()
    const [capital, risk, , tickerScope] = backtestApi.runBacktest.mock.calls[0]
    expect(capital).toBe(50000)
    expect(risk).toBe(0.02)
    expect(tickerScope).toBe('HOT')
  })

  it('shows BacktestReportPanel after successful run', async () => {
    await startBacktest()
    await waitFor(() => {
      expect(screen.getByTestId('backtest-report-panel')).toBeInTheDocument()
    })
  })

  it('shows strategy breakdown after successful run', async () => {
    await startBacktest()
    await waitFor(() => {
      expect(screen.getByText(/Por estrategía/)).toBeInTheDocument()
    })
  })

  it('populates trade log grid with trade rows after successful run', async () => {
    await startBacktest()
    await waitFor(() => {
      expect(screen.getByTestId('grid-row-0')).toBeInTheDocument()
    })
  })

  it('returns to Run Backtest button after run completes', async () => {
    await startBacktest()
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /Run Backtest/i })).toBeInTheDocument()
    })
  })

  it('persists report to localStorage after successful run', async () => {
    await startBacktest()
    await waitFor(() => {
      expect(LS.set).toHaveBeenCalledWith('bt_report', expect.any(Object))
    })
  })
})

// ═════════════════════════════════════════════════════════════════════════════
// Error states
// ═════════════════════════════════════════════════════════════════════════════

describe('BacktestDashboard — error states', () => {
  it('shows error banner when runBacktest returns success:false', async () => {
    backtestApi.runBacktest.mockResolvedValue({ success: false, error: 'No tickers matched' })
    await startBacktest()
    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
      expect(screen.getByRole('alert')).toHaveTextContent('No tickers matched')
    })
  })

  it('shows stopped message when runBacktest returns stopped:true', async () => {
    backtestApi.runBacktest.mockResolvedValue({ stopped: true })
    await startBacktest()
    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
      expect(screen.getByRole('alert')).toHaveTextContent('stopped')
    })
  })

  it('shows error banner when runBacktest rejects (network error)', async () => {
    backtestApi.runBacktest.mockRejectedValue(new Error('Network failure'))
    await startBacktest()
    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
      expect(screen.getByRole('alert')).toHaveTextContent('Network failure')
    })
  })

  it('shows error banner when stopBacktest fails', async () => {
    backtestApi.isRunning.mockResolvedValue({ running: true })
    backtestApi.stopBacktest.mockRejectedValue(new Error('Stop failed'))
    await renderDashboard()
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Stop Backtest/i }))
    })
    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
    })
  })
})

// ═════════════════════════════════════════════════════════════════════════════
// SSE ticker_progress events
// ═════════════════════════════════════════════════════════════════════════════

describe('BacktestDashboard — SSE ticker_progress events', () => {
  it('adds ticker row to activities when ticker_progress event fires', async () => {
    backtestApi.runBacktest.mockReturnValue(new Promise(() => {})) // never resolves
    await renderDashboard()
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Run Backtest/i }))
    })
    await act(async () => {
      const es = MockEventSource.lastInstance
      es.triggerOpen()
      es.dispatchSseEvent('ticker_progress', { ticker: 'TSLA', status: 'Scanning', time: '09:31:00' })
    })
    expect(screen.getByText('TSLA')).toBeInTheDocument()
  })

  it('does not duplicate ticker row when same ticker receives a second event', async () => {
    backtestApi.runBacktest.mockReturnValue(new Promise(() => {}))
    await renderDashboard()
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Run Backtest/i }))
    })
    await act(async () => {
      const es = MockEventSource.lastInstance
      es.triggerOpen()
      es.dispatchSseEvent('ticker_progress', { ticker: 'TSLA', status: 'Scanning', time: '09:31:00' })
      es.dispatchSseEvent('ticker_progress', { ticker: 'TSLA', status: 'OK', time: '09:32:00' })
    })
    // Should be exactly one row for TSLA (updated, not duplicated)
    expect(screen.getAllByText('TSLA')).toHaveLength(1)
  })

  it('ignores ticker_progress events with ticker === "ALL"', async () => {
    backtestApi.runBacktest.mockReturnValue(new Promise(() => {}))
    await renderDashboard()
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Run Backtest/i }))
    })
    await act(async () => {
      const es = MockEventSource.lastInstance
      es.triggerOpen()
      es.dispatchSseEvent('ticker_progress', { ticker: 'ALL', status: 'Complete', time: '09:35:00' })
    })
    // No grid row should contain 'ALL' as a ticker (the <option> element with "ALL" exists in TickerSelector mock but is not a grid row)
    const grid = screen.getByTestId('unified-data-grid')
    expect(grid.textContent).not.toContain('Analyze ALL')
  })

  it('adds trade rows from SSE trades event', async () => {
    backtestApi.runBacktest.mockReturnValue(new Promise(() => {}))
    await renderDashboard()
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Run Backtest/i }))
    })
    await act(async () => {
      const es = MockEventSource.lastInstance
      es.triggerOpen()
      es.dispatchSseEvent('trades', {
        trades: [
          { ticker: 'NVDA', pattern: 'squeeze', strategy: 'c1 squeeze', entryTime: '09:30:00', exitTime: '-' },
        ],
      })
    })
    expect(screen.getByText('NVDA')).toBeInTheDocument()
  })
})

// ═════════════════════════════════════════════════════════════════════════════
// ImproveModal — open and close
// ═════════════════════════════════════════════════════════════════════════════

describe('BacktestDashboard — ImproveModal', () => {
  it('ImproveModal is not open on initial render', async () => {
    await renderDashboard()
    expect(screen.queryByTestId('improve-modal')).not.toBeInTheDocument()
  })

  it('opens ImproveModal when Analyze button is clicked on a grid row', async () => {
    await startBacktest()
    await waitFor(() => {
      expect(screen.getByTestId('grid-row-0')).toBeInTheDocument()
    })
    await act(async () => {
      fireEvent.click(screen.getByTestId('analyze-btn-0'))
    })
    expect(screen.getByTestId('improve-modal')).toBeInTheDocument()
  })

  it('sets correct ticker in ImproveModal', async () => {
    await startBacktest()
    await waitFor(() => screen.getByTestId('analyze-btn-0'))
    await act(async () => {
      fireEvent.click(screen.getByTestId('analyze-btn-0'))
    })
    expect(screen.getByTestId('improve-modal-ticker')).toHaveTextContent('AAPL')
  })

  it('sets correct strategyName in ImproveModal', async () => {
    await startBacktest()
    await waitFor(() => screen.getByTestId('analyze-btn-0'))
    await act(async () => {
      fireEvent.click(screen.getByTestId('analyze-btn-0'))
    })
    expect(screen.getByTestId('improve-modal-strategy')).toHaveTextContent('c1 squeeze')
  })

  it('closes ImproveModal when close button is clicked', async () => {
    await startBacktest()
    await waitFor(() => screen.getByTestId('analyze-btn-0'))
    await act(async () => {
      fireEvent.click(screen.getByTestId('analyze-btn-0'))
    })
    await act(async () => {
      fireEvent.click(screen.getByTestId('improve-modal-close'))
    })
    expect(screen.queryByTestId('improve-modal')).not.toBeInTheDocument()
  })

  it('closes ImproveModal and keeps grid intact after close with result', async () => {
    await startBacktest()
    await waitFor(() => screen.getByTestId('analyze-btn-0'))
    await act(async () => {
      fireEvent.click(screen.getByTestId('analyze-btn-0'))
    })
    await act(async () => {
      fireEvent.click(screen.getByTestId('improve-modal-close-with-result'))
    })
    expect(screen.queryByTestId('improve-modal')).not.toBeInTheDocument()
    // Grid row should still be visible
    expect(screen.getByTestId('grid-row-0')).toBeInTheDocument()
  })
})

// ═════════════════════════════════════════════════════════════════════════════
// Memory panel
// ═════════════════════════════════════════════════════════════════════════════

describe('BacktestDashboard — MemoryPanel', () => {
  it('renders MemoryPanel component', async () => {
    await renderDashboard()
    expect(screen.getByTestId('memory-panel')).toBeInTheDocument()
  })

  it('MemoryPanel content is hidden by default', async () => {
    await renderDashboard()
    expect(screen.queryByTestId('memory-panel-content')).not.toBeInTheDocument()
  })

  it('opens MemoryPanel when toggle button is clicked', async () => {
    await renderDashboard()
    await act(async () => {
      fireEvent.click(screen.getByTestId('memory-toggle'))
    })
    expect(screen.getByTestId('memory-panel-content')).toBeInTheDocument()
  })

  it('calls getTickerMemoryRiskProfiles when memory panel is opened', async () => {
    await renderDashboard()
    await act(async () => {
      fireEvent.click(screen.getByTestId('memory-toggle'))
    })
    expect(backtestApi.getTickerMemoryRiskProfiles).toHaveBeenCalled()
  })

  it('shows memory rows when load succeeds', async () => {
    backtestApi.getTickerMemoryRiskProfiles.mockResolvedValue([
      { ticker: 'AAPL', strategy: 'c1 squeeze' },
    ])
    await renderDashboard()
    await act(async () => {
      fireEvent.click(screen.getByTestId('memory-toggle'))
    })
    await waitFor(() => {
      expect(screen.getByTestId('memory-row-0')).toHaveTextContent('AAPL')
    })
  })

  it('shows error in memory panel when load fails', async () => {
    backtestApi.getTickerMemoryRiskProfiles.mockRejectedValue(new Error('Load failed'))
    await renderDashboard()
    await act(async () => {
      fireEvent.click(screen.getByTestId('memory-toggle'))
    })
    await waitFor(() => {
      expect(screen.getByTestId('memory-error')).toBeInTheDocument()
    })
  })
})

// ═════════════════════════════════════════════════════════════════════════════
// localStorage restore on mount
// ═════════════════════════════════════════════════════════════════════════════

describe('BacktestDashboard — localStorage restore', () => {
  it('renders BacktestReportPanel when a saved report exists in LS', async () => {
    LS.get.mockImplementation((key, fallback) => {
      if (key === 'bt_report') return defaultReport
      if (key === 'bt_equity') return []
      if (key === 'bt_activities') return []
      return fallback
    })
    await renderDashboard()
    expect(screen.getByTestId('backtest-report-panel')).toBeInTheDocument()
  })

  it('restores saved activities to UnifiedDataGrid', async () => {
    const savedActivities = [
      { ticker: 'NVDA', pattern: 'squeeze', strategy: 'c1 squeeze', startTime: '09:30:00', status: 'Complete' },
    ]
    LS.get.mockImplementation((key, fallback) => {
      if (key === 'bt_activities') return savedActivities
      return fallback
    })
    await renderDashboard()
    expect(screen.getByTestId('grid-row-0')).toBeInTheDocument()
    expect(screen.getByText('NVDA')).toBeInTheDocument()
  })

  it('uses saved capital from localStorage', async () => {
    LS.get.mockImplementation((key, fallback) => {
      if (key === 'bt_params') return { capital: 100000, risk: 0.02 }
      return fallback
    })
    await renderDashboard()
    // Capital pill renders as `$` + `{formatUsd(...)}` — two text nodes.
    const capitalText = document.querySelector('.bt-capital-pill')?.textContent ?? ''
    expect(capitalText).toContain('100,000')
  })

  it('uses saved risk from localStorage', async () => {
    LS.get.mockImplementation((key, fallback) => {
      if (key === 'bt_params') return { capital: 50000, risk: 0.05 }
      return fallback
    })
    await renderDashboard()
    expect(screen.getByText('5.0%')).toBeInTheDocument()
  })
})

// ═════════════════════════════════════════════════════════════════════════════
// Strategy breakdown
// ═════════════════════════════════════════════════════════════════════════════

describe('BacktestDashboard — strategy breakdown', () => {
  it('shows strategy names after a successful run', async () => {
    await startBacktest()
    await waitFor(() => {
      expect(screen.getByText('c1 squeeze')).toBeInTheDocument()
      expect(screen.getByText('p2 continuation')).toBeInTheDocument()
    })
  })

  it('does not render breakdown when report has no byStrategy', async () => {
    backtestApi.runBacktest.mockResolvedValue({ ...defaultReport, byStrategy: null })
    await startBacktest()
    await waitFor(() => {
      expect(screen.getByTestId('backtest-report-panel')).toBeInTheDocument()
    })
    expect(screen.queryByText(/Por estrategía/)).not.toBeInTheDocument()
  })

  it('does not render breakdown when byStrategy is an empty object', async () => {
    backtestApi.runBacktest.mockResolvedValue({ ...defaultReport, byStrategy: {} })
    await startBacktest()
    await waitFor(() => {
      expect(screen.getByTestId('backtest-report-panel')).toBeInTheDocument()
    })
    expect(screen.queryByText(/Por estrategía/)).not.toBeInTheDocument()
  })
})

// ═════════════════════════════════════════════════════════════════════════════
// Ticker filter and scope
// ═════════════════════════════════════════════════════════════════════════════

describe('BacktestDashboard — ticker filter and scope', () => {
  it('persists tickerFilter to LS when TickerSelector onChange fires', async () => {
    await renderDashboard()
    fireEvent.change(screen.getByTestId('ticker-filter-input'), { target: { value: 'AAPL,MSFT' } })
    expect(LS.set).toHaveBeenCalledWith('bt_tickerFilter', 'AAPL,MSFT')
  })

  it('persists tickerScope to LS when scope select changes', async () => {
    await renderDashboard()
    fireEvent.change(screen.getByTestId('ticker-scope-select'), { target: { value: 'ALL' } })
    expect(LS.set).toHaveBeenCalledWith('bt_tickerScope', 'ALL')
  })

  it('passes updated tickerFilter to runBacktest when starting scan', async () => {
    await renderDashboard()
    fireEvent.change(screen.getByTestId('ticker-filter-input'), { target: { value: 'AAPL' } })
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /Run Backtest/i }))
    })
    await act(async () => {
      if (MockEventSource.lastInstance) MockEventSource.lastInstance.triggerOpen()
    })
    await waitFor(() => {
      expect(backtestApi.runBacktest).toHaveBeenCalled()
    })
    expect(backtestApi.runBacktest.mock.calls[0][2]).toBe('AAPL')
  })
})
