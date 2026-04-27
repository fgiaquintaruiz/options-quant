import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import LiveDashboard from './LiveDashboard'

// Mock all external dependencies LiveDashboard uses so it renders in isolation
vi.mock('../api', () => ({
  liveApi: {
    getStatus:          vi.fn().mockResolvedValue({}),
    getScanActivity:    vi.fn().mockResolvedValue({ activity: [] }),
    getScanScores:      vi.fn().mockResolvedValue({ scoresByTicker: {} }),
    getSignals:         vi.fn().mockResolvedValue({ signals: [], closedTrades: {} }),
    setMaxConcurrent:   vi.fn().mockResolvedValue({}),
    setScanFilter:      vi.fn().mockResolvedValue({}),
  },
  replayApi: {
    status: vi.fn().mockResolvedValue({}),
  },
  externalPositionsApi: {
    getExternalPositions: vi.fn().mockResolvedValue({ positions: [] }),
  },
}))

vi.mock('../hooks/useWatchlists', () => ({
  useWatchlists: () => ({
    groups: [],
    addGroup: vi.fn(),
  }),
}))

// Mock ExternalPositionsPanel so it doesn't fetch and has a stable testId
vi.mock('../components/ExternalPositionsPanel', () => ({
  default: () => <div data-testid="external-positions-panel">ExternalPositionsPanel</div>,
}))

// Mock LiveTradeGrid to keep the test focused on composition
vi.mock('../components/LiveTradeGrid', () => ({
  default: () => <div data-testid="live-trade-grid">LiveTradeGrid</div>,
}))

vi.mock('../components/TickerSelector', () => ({
  default: () => <div />,
  resolveTickerEntries: vi.fn().mockReturnValue(''),
  DEFAULT_GROUPS: [],
}))

vi.mock('../components/SwapButton', () => ({
  default: () => <div />,
}))

vi.mock('../utils/storage', () => ({
  LS: {
    get: vi.fn().mockReturnValue(null),
    set: vi.fn(),
  },
}))

describe('LiveDashboard', () => {
  it('renders LiveTradeGrid', () => {
    render(<LiveDashboard twsStatus={{}} marketOpen={false} />)
    expect(screen.getByTestId('live-trade-grid')).toBeInTheDocument()
  })

  it('renders ExternalPositionsPanel below LiveTradeGrid', () => {
    render(<LiveDashboard twsStatus={{}} marketOpen={false} />)
    const grid  = screen.getByTestId('live-trade-grid')
    const panel = screen.getByTestId('external-positions-panel')

    // Both must be present
    expect(grid).toBeInTheDocument()
    expect(panel).toBeInTheDocument()

    // ExternalPositionsPanel must appear AFTER LiveTradeGrid in the DOM
    const position = grid.compareDocumentPosition(panel)
    // DOCUMENT_POSITION_FOLLOWING = 4
    expect(position & Node.DOCUMENT_POSITION_FOLLOWING).toBeGreaterThan(0)
  })
})
