import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import ExternalPositionsPanel from './ExternalPositionsPanel'
import * as api from '../api'

// Mock the hook — ExternalPositionsPanel must use useExternalPositions from this path
vi.mock('../hooks/useExternalPositions', () => ({
  useExternalPositions: vi.fn(),
}))

// Mock the API calls used by action buttons
vi.mock('../api', () => ({
  externalPositionsApi: {
    closeExternalPosition: vi.fn(),
    scheduleClose1450: vi.fn(),
  },
}))

import { useExternalPositions } from '../hooks/useExternalPositions'

const POSITIONS = [
  { ticker: 'AAPL', secType: 'STK', quantity: 100, avgCost: 170.5, snapshotAt: '10:00:00' },
  { ticker: 'NVDA', secType: 'OPT', quantity: 2,   avgCost: 5.5,   snapshotAt: '10:01:00' },
  { ticker: 'SPY',  secType: 'STK', quantity: 50,  avgCost: 500.0, snapshotAt: '10:02:00' },
]

const mockHook = (overrides = {}) => {
  useExternalPositions.mockReturnValue({
    positions: [],
    error: null,
    loading: false,
    refresh: vi.fn(),
    ...overrides,
  })
}

describe('ExternalPositionsPanel', () => {
  beforeEach(() => {
    mockHook()
    api.externalPositionsApi.closeExternalPosition.mockResolvedValue({ message: 'ok', orderId: 42 })
    api.externalPositionsApi.scheduleClose1450.mockResolvedValue({ orderId: 99, message: 'scheduled' })
    // Mock window.confirm — default returns true (user confirms)
    vi.spyOn(window, 'confirm').mockReturnValue(true)
  })

  afterEach(() => {
    vi.clearAllMocks()
  })

  // ── Empty state ──────────────────────────────────────────────────────────

  it('renders empty state message when there are no positions', () => {
    mockHook({ positions: [] })
    render(<ExternalPositionsPanel />)
    expect(screen.getByText(/sin posiciones externas/i)).toBeInTheDocument()
  })

  // ── Table with positions ─────────────────────────────────────────────────

  it('renders 3 positions in table with correct tickers', () => {
    mockHook({ positions: POSITIONS })
    render(<ExternalPositionsPanel />)
    expect(screen.getByText('AAPL')).toBeInTheDocument()
    expect(screen.getByText('NVDA')).toBeInTheDocument()
    expect(screen.getByText('SPY')).toBeInTheDocument()
  })

  it('renders EXTERNAL badge per row', () => {
    mockHook({ positions: POSITIONS })
    render(<ExternalPositionsPanel />)
    const badges = screen.getAllByText('EXTERNAL')
    expect(badges).toHaveLength(3)
  })

  it('renders correct secType column values', () => {
    mockHook({ positions: POSITIONS })
    render(<ExternalPositionsPanel />)
    // STK appears twice (AAPL + SPY), OPT once (NVDA)
    const stkCells = screen.getAllByText('STK')
    expect(stkCells.length).toBeGreaterThanOrEqual(2)
    expect(screen.getByText('OPT')).toBeInTheDocument()
  })

  it('renders quantity and avgCost for each row', () => {
    mockHook({ positions: POSITIONS })
    render(<ExternalPositionsPanel />)
    expect(screen.getByText('100')).toBeInTheDocument()  // AAPL qty
    expect(screen.getByText('170.50')).toBeInTheDocument() // AAPL avgCost
  })

  // ── Error state ──────────────────────────────────────────────────────────

  it('renders error banner when hook returns error', () => {
    mockHook({ error: new Error('HTTP 503: TWS not connected'), positions: [] })
    render(<ExternalPositionsPanel />)
    expect(screen.getByText(/503/)).toBeInTheDocument()
  })

  // ── Close button ─────────────────────────────────────────────────────────

  it('calls confirm() when Close button is clicked', () => {
    mockHook({ positions: [POSITIONS[0]] })
    render(<ExternalPositionsPanel />)
    const closeBtn = screen.getByTestId('close-btn-AAPL')
    fireEvent.click(closeBtn)
    expect(window.confirm).toHaveBeenCalledWith(expect.stringContaining('AAPL'))
  })

  it('calls closeExternalPosition with ticker on confirm', async () => {
    mockHook({ positions: [POSITIONS[0]] })
    render(<ExternalPositionsPanel />)
    fireEvent.click(screen.getByTestId('close-btn-AAPL'))
    await waitFor(() => {
      expect(api.externalPositionsApi.closeExternalPosition).toHaveBeenCalledWith('AAPL')
    })
  })

  it('disables Close button and shows Cerrando... text while closing', async () => {
    // Make the API hang so we can inspect the pending state
    let resolveClose
    api.externalPositionsApi.closeExternalPosition.mockReturnValue(
      new Promise((res) => { resolveClose = res })
    )
    mockHook({ positions: [POSITIONS[0]] })
    render(<ExternalPositionsPanel />)

    fireEvent.click(screen.getByTestId('close-btn-AAPL'))

    await waitFor(() => {
      expect(screen.getByTestId('close-btn-AAPL')).toBeDisabled()
    })
    expect(screen.getByText(/cerrando/i)).toBeInTheDocument()

    // Cleanup: resolve the promise
    resolveClose({ message: 'ok', orderId: 1 })
  })

  it('does NOT call closeExternalPosition when confirm returns false', async () => {
    window.confirm.mockReturnValue(false)
    mockHook({ positions: [POSITIONS[0]] })
    render(<ExternalPositionsPanel />)
    fireEvent.click(screen.getByTestId('close-btn-AAPL'))
    // No API call
    expect(api.externalPositionsApi.closeExternalPosition).not.toHaveBeenCalled()
  })

  // ── 14:50 ET checkbox ────────────────────────────────────────────────────

  it('calls scheduleClose1450 with ticker when checkbox is checked', async () => {
    mockHook({ positions: [POSITIONS[0]] })
    render(<ExternalPositionsPanel />)
    const checkbox = screen.getByTestId('schedule-1450-AAPL')
    fireEvent.click(checkbox)
    await waitFor(() => {
      expect(api.externalPositionsApi.scheduleClose1450).toHaveBeenCalledWith('AAPL')
    })
  })

  it('replaces checkbox with scheduled badge after scheduling', async () => {
    mockHook({ positions: [POSITIONS[0]] })
    render(<ExternalPositionsPanel />)
    const checkbox = screen.getByTestId('schedule-1450-AAPL')
    fireEvent.click(checkbox)
    // After scheduling: checkbox is replaced by a badge
    await waitFor(() => {
      expect(screen.getByTestId('schedule-1450-badge-AAPL')).toBeInTheDocument()
    })
    expect(screen.getByText(/14:50 ET/i)).toBeInTheDocument()
  })
})
