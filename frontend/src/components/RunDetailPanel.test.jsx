import React from 'react'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import RunDetailPanel from './RunDetailPanel'

// Mock the api module
vi.mock('../api', () => ({
  backtestHistoryApi: {
    getRunSummary: vi.fn(),
    getLossesAnalysis: vi.fn(),
  },
}))

// Mock child components to isolate RunDetailPanel
vi.mock('./LossesAnalysisPanel', () => ({
  default: ({ lossesData }) => <div data-testid="losses-panel">{lossesData ? 'losses-loaded' : 'no-losses'}</div>,
}))
vi.mock('./TradesExplorer', () => ({
  default: ({ runId }) => <div data-testid="trades-explorer">trades-{runId}</div>,
}))

import { backtestHistoryApi } from '../api'

// camelCase fixture matching real Spring Boot API response
const mockSummary = {
  runId: 'run-001',
  totalTrades: 150,
  winRate: 62.0,
  totalPnl: 3200.0,
  byStrategy: [
    { strategy: 'EMA_CROSS', trades: 80, winRate: 65.0, totalPnl: 2000 },
    { strategy: 'BOLLINGER',  trades: 70, winRate: 58.0, totalPnl: 1200 },
  ],
  bySignalType: [
    { signalType: 'CALL', trades: 90, winRate: 67.0, totalPnl: 2100 },
    { signalType: 'PUT',  trades: 60, winRate: 55.0, totalPnl: 1100 },
  ],
}

const mockLosses = { by_hour: [], by_pattern: [], by_ticker: [], worst_trades: [] }

afterEach(() => { vi.clearAllMocks(); vi.restoreAllMocks() })

describe('RunDetailPanel', () => {
  it('shows loading state initially', () => {
    backtestHistoryApi.getRunSummary.mockReturnValue(new Promise(() => {}))
    backtestHistoryApi.getLossesAnalysis.mockReturnValue(new Promise(() => {}))
    render(<RunDetailPanel runId="run-001" />)
    expect(screen.getByText(/loading/i)).toBeInTheDocument()
  })

  it('renders KPI cards after data loads', async () => {
    backtestHistoryApi.getRunSummary.mockResolvedValue(mockSummary)
    backtestHistoryApi.getLossesAnalysis.mockResolvedValue(mockLosses)
    render(<RunDetailPanel runId="run-001" />)
    await waitFor(() => expect(screen.getByTestId('kpi-total-trades')).toBeInTheDocument())
    expect(screen.getByTestId('kpi-total-trades').textContent).toContain('150')
  })

  it('renders by_strategy table after data loads', async () => {
    backtestHistoryApi.getRunSummary.mockResolvedValue(mockSummary)
    backtestHistoryApi.getLossesAnalysis.mockResolvedValue(mockLosses)
    render(<RunDetailPanel runId="run-001" />)
    await waitFor(() => expect(screen.getByText('EMA_CROSS')).toBeInTheDocument())
    expect(screen.getByText('BOLLINGER')).toBeInTheDocument()
  })

  it('renders CALL vs PUT table after data loads', async () => {
    backtestHistoryApi.getRunSummary.mockResolvedValue(mockSummary)
    backtestHistoryApi.getLossesAnalysis.mockResolvedValue(mockLosses)
    render(<RunDetailPanel runId="run-001" />)
    await waitFor(() => expect(screen.getByText('CALL')).toBeInTheDocument())
    expect(screen.getByText('PUT')).toBeInTheDocument()
  })

  it('renders LossesAnalysisPanel and TradesExplorer after data loads', async () => {
    backtestHistoryApi.getRunSummary.mockResolvedValue(mockSummary)
    backtestHistoryApi.getLossesAnalysis.mockResolvedValue(mockLosses)
    render(<RunDetailPanel runId="run-001" />)
    await waitFor(() => expect(screen.getByTestId('losses-panel')).toBeInTheDocument())
    expect(screen.getByTestId('trades-explorer')).toBeInTheDocument()
  })

  it('shows error message when fetch fails', async () => {
    backtestHistoryApi.getRunSummary.mockRejectedValue(new Error('Network error'))
    backtestHistoryApi.getLossesAnalysis.mockResolvedValue(mockLosses)
    render(<RunDetailPanel runId="run-001" />)
    await waitFor(() => expect(screen.getByText(/error/i)).toBeInTheDocument())
  })

  it('whenSummaryHasCamelCase_displaysCorrectTotalTrades', async () => {
    backtestHistoryApi.getRunSummary.mockResolvedValue(mockSummary)
    backtestHistoryApi.getLossesAnalysis.mockResolvedValue(mockLosses)
    render(<RunDetailPanel runId="run-001" />)
    await waitFor(() => expect(screen.getByTestId('kpi-total-trades')).toBeInTheDocument())
    expect(screen.getByTestId('kpi-total-trades').textContent).toContain('150')
  })

  it('whenSummaryHasCamelCase_displaysWinRate', async () => {
    backtestHistoryApi.getRunSummary.mockResolvedValue(mockSummary)
    backtestHistoryApi.getLossesAnalysis.mockResolvedValue(mockLosses)
    render(<RunDetailPanel runId="run-001" />)
    await waitFor(() => expect(screen.getByTestId('kpi-win-rate')).toBeInTheDocument())
    expect(screen.getByTestId('kpi-win-rate').textContent).not.toBe('—')
  })

  it('whenSummaryHasCamelCase_displaysTotalPnl', async () => {
    backtestHistoryApi.getRunSummary.mockResolvedValue(mockSummary)
    backtestHistoryApi.getLossesAnalysis.mockResolvedValue(mockLosses)
    render(<RunDetailPanel runId="run-001" />)
    await waitFor(() => expect(screen.getByTestId('kpi-total-pnl')).toBeInTheDocument())
    expect(screen.getByTestId('kpi-total-pnl').textContent).not.toBe('—')
  })

  it('whenSummaryHasCamelCase_rendersByStrategyTable', async () => {
    backtestHistoryApi.getRunSummary.mockResolvedValue(mockSummary)
    backtestHistoryApi.getLossesAnalysis.mockResolvedValue(mockLosses)
    render(<RunDetailPanel runId="run-001" />)
    await waitFor(() => expect(screen.getByText('EMA_CROSS')).toBeInTheDocument())
    expect(screen.getByText('BOLLINGER')).toBeInTheDocument()
  })

  it('whenSummaryHasCamelCase_rendersBySignalTypeTable', async () => {
    backtestHistoryApi.getRunSummary.mockResolvedValue(mockSummary)
    backtestHistoryApi.getLossesAnalysis.mockResolvedValue(mockLosses)
    render(<RunDetailPanel runId="run-001" />)
    await waitFor(() => expect(screen.getByText('CALL')).toBeInTheDocument())
    expect(screen.getByText('PUT')).toBeInTheDocument()
  })

  // ── TAREA 3B: Export Summary button ────────────────────────────────────────

  it('export summary button is disabled when data not loaded', () => {
    backtestHistoryApi.getRunSummary.mockReturnValue(new Promise(() => {}))
    backtestHistoryApi.getLossesAnalysis.mockReturnValue(new Promise(() => {}))
    render(<RunDetailPanel runId="run-001" />)
    // While loading, no export button should be present (or it should be disabled)
    // The component renders loading state when data is null, so the button won't be there yet
    expect(screen.queryByTestId('btn-export-summary')).toBeNull()
  })

  it('export summary button is enabled when data loaded', async () => {
    backtestHistoryApi.getRunSummary.mockResolvedValue(mockSummary)
    backtestHistoryApi.getLossesAnalysis.mockResolvedValue(mockLosses)
    render(<RunDetailPanel runId="run-001" />)
    await waitFor(() => expect(screen.getByTestId('btn-export-summary')).toBeInTheDocument())
    expect(screen.getByTestId('btn-export-summary')).not.toBeDisabled()
  })

  it('export summary button triggers download with correct filename', async () => {
    global.URL.createObjectURL = vi.fn().mockReturnValue('blob:mock-url')
    global.URL.revokeObjectURL = vi.fn()

    backtestHistoryApi.getRunSummary.mockResolvedValue(mockSummary)
    backtestHistoryApi.getLossesAnalysis.mockResolvedValue(mockLosses)

    // Render FIRST — set up spy only after React has mounted
    render(<RunDetailPanel runId="run-001" />)
    await waitFor(() => expect(screen.getByTestId('btn-export-summary')).toBeInTheDocument())

    const createdAnchors = []
    const mockAppendChild = vi.spyOn(document.body, 'appendChild').mockImplementation((el) => {
      if (el && el.tagName === 'A') createdAnchors.push(el)
    })
    const mockRemoveChild = vi.spyOn(document.body, 'removeChild').mockImplementation(() => {})

    fireEvent.click(screen.getByTestId('btn-export-summary'))
    await waitFor(() => expect(global.URL.createObjectURL).toHaveBeenCalled())

    expect(createdAnchors.length).toBeGreaterThan(0)
    expect(createdAnchors[0].download).toBe('backtest-summary-run-001.csv')

    mockAppendChild.mockRestore()
    mockRemoveChild.mockRestore()
  })
})
