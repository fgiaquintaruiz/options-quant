import React from 'react'
import { render, screen, fireEvent } from '@testing-library/react'
import { describe, it, expect, vi, afterEach } from 'vitest'
import RunsList from './RunsList'

afterEach(() => { vi.clearAllMocks() })

// camelCase — matches the actual API response shape:
// {"runId":"2026-05-12_16-49","totalTickers":511,"completedTickers":511,"totalTrades":14314,"totalPnl":2568151.72}
const sampleRuns = [
  { runId: 'run-001', totalTickers: 10, totalTrades: 120, totalPnl: 2500.50 },
  { runId: 'run-002', totalTickers: 5,  totalTrades: 60,  totalPnl: -800.00 },
]

describe('RunsList', () => {
  it('renders without crashing when runs is empty', () => {
    render(<RunsList runs={[]} selectedRunId={null} onSelect={vi.fn()} />)
    expect(screen.getByTestId('runs-list')).toBeInTheDocument()
  })

  it('shows empty message when runs array is empty', () => {
    render(<RunsList runs={[]} selectedRunId={null} onSelect={vi.fn()} />)
    expect(screen.getByText(/no runs found/i)).toBeInTheDocument()
  })

  // RED → verifies real API shape (camelCase runId) renders the run ID value
  it('renders runId from camelCase API response', () => {
    const apiRun = { runId: '2026-05-12_16-49', totalTickers: 511, totalTrades: 14314, totalPnl: 2568151.72 }
    render(<RunsList runs={[apiRun]} selectedRunId={null} onSelect={vi.fn()} />)
    expect(screen.getByText('2026-05-12_16-49')).toBeInTheDocument()
  })

  it('renders a row for each run', () => {
    render(<RunsList runs={sampleRuns} selectedRunId={null} onSelect={vi.fn()} />)
    expect(screen.getByText('run-001')).toBeInTheDocument()
    expect(screen.getByText('run-002')).toBeInTheDocument()
  })

  it('calls onSelect with runId when a row is clicked', () => {
    const onSelect = vi.fn()
    render(<RunsList runs={sampleRuns} selectedRunId={null} onSelect={onSelect} />)
    fireEvent.click(screen.getByText('run-001'))
    expect(onSelect).toHaveBeenCalledWith('run-001')
  })

  it('highlights the selected row', () => {
    render(<RunsList runs={sampleRuns} selectedRunId="run-002" onSelect={vi.fn()} />)
    const row = screen.getByTestId('run-row-run-002')
    expect(row.className).toMatch(/selected/)
  })

  it('shows loading state', () => {
    render(<RunsList runs={null} selectedRunId={null} onSelect={vi.fn()} loading />)
    expect(screen.getByText(/loading/i)).toBeInTheDocument()
  })

  it('renders totalPnl formatted with dollar sign', () => {
    render(<RunsList runs={sampleRuns} selectedRunId={null} onSelect={vi.fn()} />)
    expect(screen.getByTestId('pnl-run-001').textContent).toContain('$')
  })
})
