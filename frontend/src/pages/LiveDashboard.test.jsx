import { describe, it, expect, vi } from 'vitest'
import { render, screen, act } from '@testing-library/react'
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

// Mock useExternalPositions so LiveDashboard doesn't poll in tests
vi.mock('../hooks/useExternalPositions', () => ({
  useExternalPositions: () => ({ positions: [], error: null, loading: false, refresh: vi.fn() }),
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

vi.mock('../components/ScanEngineControls', () => ({
  default: () => <div data-testid="scan-engine-controls-mock" />,
}))

vi.mock('../utils/storage', () => ({
  LS: {
    get: vi.fn().mockReturnValue(null),
    set: vi.fn(),
  },
}))

describe('LiveDashboard', () => {
  it('renders LiveTradeGrid', async () => {
    await act(async () => {
      render(<LiveDashboard twsStatus={{}} marketOpen={false} />)
    })
    expect(screen.getByTestId('live-trade-grid')).toBeInTheDocument()
  })

  it('passes externalPositions props to LiveTradeGrid (no separate ExternalPositionsPanel)', async () => {
    await act(async () => {
      render(<LiveDashboard twsStatus={{}} marketOpen={false} />)
    })
    // LiveTradeGrid is rendered; ExternalPositionsPanel is no longer a separate component
    expect(screen.getByTestId('live-trade-grid')).toBeInTheDocument()
    expect(screen.queryByTestId('external-positions-panel')).not.toBeInTheDocument()
  })
})
