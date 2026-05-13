import React from 'react'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, it, expect, vi, afterEach } from 'vitest'
import BacktestHistoryPage from './BacktestHistoryPage'

// Wrap component with router since BacktestHistoryPage uses useSearchParams
const renderPage = () => render(<MemoryRouter><BacktestHistoryPage /></MemoryRouter>)

vi.mock('../api', () => ({
  backtestHistoryApi: {
    listRuns: vi.fn(),
  },
}))

vi.mock('../components/RunsList', () => ({
  default: ({ runs, selectedRunId, onSelect }) => (
    <div data-testid="runs-list">
      {(runs || []).map((r) => (
        <button key={r.run_id} onClick={() => onSelect(r.run_id)} data-testid={`run-btn-${r.run_id}`}>
          {r.run_id}
        </button>
      ))}
    </div>
  ),
}))

vi.mock('../components/RunDetailPanel', () => ({
  default: ({ runId }) => <div data-testid="run-detail-panel">detail-{runId}</div>,
}))

import { backtestHistoryApi } from '../api'

const sampleRuns = [
  { run_id: 'run-001', total_tickers: 10, total_trades: 120, total_pnl: 2500 },
  { run_id: 'run-002', total_tickers: 5,  total_trades: 60,  total_pnl: -800 },
]

afterEach(() => { vi.clearAllMocks() })

describe('BacktestHistoryPage', () => {
  it('renders RunsList component', async () => {
    backtestHistoryApi.listRuns.mockResolvedValue(sampleRuns)
    renderPage()
    await waitFor(() => expect(screen.getByTestId('runs-list')).toBeInTheDocument())
  })

  it('does not render RunDetailPanel when no run selected', async () => {
    backtestHistoryApi.listRuns.mockResolvedValue(sampleRuns)
    renderPage()
    await waitFor(() => expect(screen.getByTestId('runs-list')).toBeInTheDocument())
    expect(screen.queryByTestId('run-detail-panel')).toBeNull()
  })

  it('renders RunDetailPanel when a run is selected', async () => {
    backtestHistoryApi.listRuns.mockResolvedValue(sampleRuns)
    renderPage()
    await waitFor(() => expect(screen.getByTestId('run-btn-run-001')).toBeInTheDocument())
    fireEvent.click(screen.getByTestId('run-btn-run-001'))
    expect(screen.getByTestId('run-detail-panel')).toBeInTheDocument()
    expect(screen.getByText('detail-run-001')).toBeInTheDocument()
  })

  it('shows error message when listRuns fails', async () => {
    backtestHistoryApi.listRuns.mockRejectedValue(new Error('network fail'))
    renderPage()
    await waitFor(() => expect(screen.getByText(/error/i)).toBeInTheDocument())
  })

  it('shows loading state initially', () => {
    backtestHistoryApi.listRuns.mockReturnValue(new Promise(() => {}))
    renderPage()
    expect(screen.getByText(/loading/i)).toBeInTheDocument()
  })
})
