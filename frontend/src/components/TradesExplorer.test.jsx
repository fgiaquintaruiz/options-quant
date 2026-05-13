import React from 'react'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import TradesExplorer from './TradesExplorer'

vi.mock('../api', () => ({
  backtestHistoryApi: {
    getTrades: vi.fn(),
  },
}))

import { backtestHistoryApi } from '../api'

// camelCase fixture matching real Spring Boot API response
const makeTrades = (n) =>
  Array.from({ length: n }, (_, i) => ({
    ticker: 'AAPL',
    strategy: 'EMA_CROSS',
    signalType: i % 2 === 0 ? 'CALL' : 'PUT',
    entryPrice: 180 + i,
    exitPrice: 178 + i,
    pnl: i % 3 === 0 ? 50 : -30,
    signalDate: '2025-01-01',
    createdAt: '2025-01-01T11:00:00',
  }))

afterEach(() => { vi.clearAllMocks(); vi.restoreAllMocks() })

describe('TradesExplorer', () => {
  it('shows loading state initially', () => {
    backtestHistoryApi.getTrades.mockReturnValue(new Promise(() => {}))
    render(<TradesExplorer runId="run-001" />)
    expect(screen.getByText(/loading/i)).toBeInTheDocument()
  })

  it('renders trades table after data loads', async () => {
    backtestHistoryApi.getTrades.mockResolvedValue({ trades: makeTrades(3), total: 3 })
    render(<TradesExplorer runId="run-001" />)
    await waitFor(() => expect(screen.getByTestId('trades-table')).toBeInTheDocument())
  })

  it('renders filter selects', async () => {
    backtestHistoryApi.getTrades.mockResolvedValue({ trades: [], total: 0 })
    render(<TradesExplorer runId="run-001" />)
    await waitFor(() => expect(screen.getByTestId('filter-signal-type')).toBeInTheDocument())
    expect(screen.getByTestId('filter-win')).toBeInTheDocument()
  })

  it('refetches when signal_type filter changes', async () => {
    backtestHistoryApi.getTrades.mockResolvedValue({ trades: [], total: 0 })
    render(<TradesExplorer runId="run-001" />)
    await waitFor(() => expect(screen.getByTestId('filter-signal-type')).toBeInTheDocument())

    fireEvent.change(screen.getByTestId('filter-signal-type'), { target: { value: 'CALL' } })
    await waitFor(() => expect(backtestHistoryApi.getTrades).toHaveBeenCalledTimes(2))
    const secondCall = backtestHistoryApi.getTrades.mock.calls[1][1]
    expect(secondCall.signalType).toBe('CALL')
  })

  it('shows Prev/Next pagination buttons', async () => {
    backtestHistoryApi.getTrades.mockResolvedValue({ trades: makeTrades(50), total: 120 })
    render(<TradesExplorer runId="run-001" />)
    await waitFor(() => expect(screen.getByTestId('btn-next')).toBeInTheDocument())
    expect(screen.getByTestId('btn-prev')).toBeInTheDocument()
  })

  it('disables Prev on first page', async () => {
    backtestHistoryApi.getTrades.mockResolvedValue({ trades: makeTrades(3), total: 3 })
    render(<TradesExplorer runId="run-001" />)
    await waitFor(() => expect(screen.getByTestId('btn-prev')).toBeInTheDocument())
    expect(screen.getByTestId('btn-prev')).toBeDisabled()
  })

  it('shows error message when fetch fails', async () => {
    backtestHistoryApi.getTrades.mockRejectedValue(new Error('fail'))
    render(<TradesExplorer runId="run-001" />)
    await waitFor(() => expect(screen.getByText(/error/i)).toBeInTheDocument())
  })

  it('whenTradesHaveCamelCase_rendersSignalTypeNotEmpty', async () => {
    backtestHistoryApi.getTrades.mockResolvedValue({ trades: makeTrades(2), total: 2 })
    render(<TradesExplorer runId="run-001" />)
    const table = await waitFor(() => screen.getByTestId('trades-table'))
    // signalType column should show CALL or PUT in the table rows, not empty
    const callCells = table.querySelectorAll('td')
    const hasSignalType = Array.from(callCells).some((td) => td.textContent === 'CALL' || td.textContent === 'PUT')
    expect(hasSignalType).toBe(true)
  })

  it('whenTradesHaveCamelCase_rendersEntryPriceNotNaN', async () => {
    backtestHistoryApi.getTrades.mockResolvedValue({ trades: makeTrades(1), total: 1 })
    render(<TradesExplorer runId="run-001" />)
    await waitFor(() => expect(screen.getByTestId('trades-table')).toBeInTheDocument())
    // entryPrice 180 → "180.00", never "NaN"
    expect(screen.getByText('180.00')).toBeInTheDocument()
    expect(screen.queryByText('NaN')).toBeNull()
  })

  it('whenTradesHaveCamelCase_rendersSignalDateNotDash', async () => {
    backtestHistoryApi.getTrades.mockResolvedValue({ trades: makeTrades(1), total: 1 })
    render(<TradesExplorer runId="run-001" />)
    await waitFor(() => expect(screen.getByTestId('trades-table')).toBeInTheDocument())
    // signalDate should appear in the row, not be a dash
    expect(screen.getByText('2025-01-01')).toBeInTheDocument()
  })

  // ── TAREA 2: win param fix ──────────────────────────────────────────────────

  it('win param sends 1 for WINS filter', async () => {
    backtestHistoryApi.getTrades.mockResolvedValue({ trades: [], total: 0 })
    render(<TradesExplorer runId="run-001" />)
    await waitFor(() => expect(screen.getByTestId('filter-win')).toBeInTheDocument())

    fireEvent.change(screen.getByTestId('filter-win'), { target: { value: 'WINS' } })
    await waitFor(() => expect(backtestHistoryApi.getTrades).toHaveBeenCalledTimes(2))
    const params = backtestHistoryApi.getTrades.mock.calls[1][1]
    expect(params.win).toBe(1)
  })

  it('win param sends 0 for LOSSES filter', async () => {
    backtestHistoryApi.getTrades.mockResolvedValue({ trades: [], total: 0 })
    render(<TradesExplorer runId="run-001" />)
    await waitFor(() => expect(screen.getByTestId('filter-win')).toBeInTheDocument())

    fireEvent.change(screen.getByTestId('filter-win'), { target: { value: 'LOSSES' } })
    await waitFor(() => expect(backtestHistoryApi.getTrades).toHaveBeenCalledTimes(2))
    const params = backtestHistoryApi.getTrades.mock.calls[1][1]
    expect(params.win).toBe(0)
  })

  it('win param not sent for ALL filter', async () => {
    backtestHistoryApi.getTrades.mockResolvedValue({ trades: [], total: 0 })
    render(<TradesExplorer runId="run-001" />)
    await waitFor(() => expect(screen.getByTestId('filter-win')).toBeInTheDocument())

    // Switch to WINS first, then back to ALL
    fireEvent.change(screen.getByTestId('filter-win'), { target: { value: 'WINS' } })
    await waitFor(() => expect(backtestHistoryApi.getTrades).toHaveBeenCalledTimes(2))
    fireEvent.change(screen.getByTestId('filter-win'), { target: { value: 'ALL' } })
    await waitFor(() => expect(backtestHistoryApi.getTrades).toHaveBeenCalledTimes(3))
    const params = backtestHistoryApi.getTrades.mock.calls[2][1]
    expect(params.win).toBeUndefined()
  })

  // ── TAREA 3A: Export Trades button ─────────────────────────────────────────

  it('export trades button is visible after data loads', async () => {
    backtestHistoryApi.getTrades.mockResolvedValue({ trades: makeTrades(3), total: 3 })
    render(<TradesExplorer runId="run-001" />)
    await waitFor(() => expect(screen.getByTestId('btn-export-trades')).toBeInTheDocument())
  })

  it('export button shows loading state during export', async () => {
    // First call: normal page load. Subsequent calls: export pagination
    backtestHistoryApi.getTrades
      .mockResolvedValueOnce({ trades: makeTrades(3), total: 3 })
      .mockReturnValue(new Promise(() => {})) // hang on export fetch → stays in loading state

    render(<TradesExplorer runId="run-001" />)
    await waitFor(() => expect(screen.getByTestId('btn-export-trades')).toBeInTheDocument())

    fireEvent.click(screen.getByTestId('btn-export-trades'))
    await waitFor(() => expect(screen.getByTestId('btn-export-trades')).toBeDisabled())
    expect(screen.getByTestId('btn-export-trades').textContent).toMatch(/exportando/i)
  })

  it('export button triggers download with correct filename', async () => {
    // Setup URL mock
    const mockUrl = 'blob:mock-url'
    global.URL.createObjectURL = vi.fn().mockReturnValue(mockUrl)
    global.URL.revokeObjectURL = vi.fn()

    backtestHistoryApi.getTrades
      .mockResolvedValueOnce({ trades: makeTrades(3), total: 3 })   // initial page load
      .mockResolvedValueOnce({ trades: makeTrades(3), total: 3 })   // export fetch

    // Render FIRST so React can mount into the DOM before any spies intercept appendChild
    render(<TradesExplorer runId="run-001" />)
    await waitFor(() => expect(screen.getByTestId('btn-export-trades')).toBeInTheDocument())

    // Set up spy AFTER render is complete to avoid interfering with React's mount
    const createdAnchors = []
    const mockAppendChild = vi.spyOn(document.body, 'appendChild').mockImplementation((el) => {
      if (el && el.tagName === 'A') createdAnchors.push(el)
    })
    const mockRemoveChild = vi.spyOn(document.body, 'removeChild').mockImplementation(() => {})

    fireEvent.click(screen.getByTestId('btn-export-trades'))
    await waitFor(() => expect(global.URL.createObjectURL).toHaveBeenCalled())

    expect(createdAnchors.length).toBeGreaterThan(0)
    expect(createdAnchors[0].download).toBe('backtest-trades-run-001.csv')

    mockAppendChild.mockRestore()
    mockRemoveChild.mockRestore()
  })
})
