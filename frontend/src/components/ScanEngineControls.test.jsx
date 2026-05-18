import React from 'react'
import { render, screen, waitFor, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import ScanEngineControls from './ScanEngineControls'

vi.mock('../api', () => ({
  strategyConfigApi: { findAll: vi.fn() },
}))

// Default: all tests get a silent resolved empty array so the badge useEffect doesn't crash
import { strategyConfigApi } from '../api'
beforeEach(() => {
  strategyConfigApi.findAll.mockResolvedValue([])
})

/**
 * Tests for ScanEngineControls — toolbar row 2 of LiveDashboard.
 * Advanced controls (concurrent, risk, mock market, inject signal) are now
 * delegated to AdvancedScanControls (tested separately).
 * This suite validates: force-stop, auto-scan group, auto-execute, macro-filter,
 * and prop pass-through to AdvancedScanControls (via mock).
 */

// Mock AdvancedScanControls — it has its own test suite
vi.mock('./AdvancedScanControls', () => ({
  default: ({ onRiskAdjust, onMaxConcurrentChange, onInjectMockSignal, onToggleMockMarket, mockMarketOpen }) => (
    <div data-testid="advanced-scan-controls-mock">
      <button data-testid="live-risk-up"             onClick={() => onRiskAdjust(1.0)}>+</button>
      <button data-testid="live-risk-down"           onClick={() => onRiskAdjust(-1.0)}>-</button>
      <input  type="number" min="1" max="16"         onChange={(e) => onMaxConcurrentChange(e.target.value)} />
      <button data-testid="live-toggle-mock-market"  onClick={onToggleMockMarket}>{mockMarketOpen ? 'Mock Mkt' : 'Real Mkt'}</button>
      {mockMarketOpen && (
        <button data-testid="live-inject-mock-signal" onClick={onInjectMockSignal}>Mock Signal</button>
      )}
    </div>
  ),
}))

const baseProps = {
  scanning: false,
  stopRequested: false,
  canScan: true,
  status: { schedulerEnabled: false, autoExecute: false, macroFilterEnabled: true },
  forceStopReleasing: false,
  exclusiveScanLockHeld: false,
  exclusiveLockSinceMs: null,
  exclusiveLockMinutes: 0,
  maxConcurrent: 4,
  riskInput: 1.0,
  mockMarketOpen: false,
  nextScanSecs: 0,
  marketOpen: true,
  onStartScan: vi.fn(),
  onStopScan: vi.fn(),
  onForceStop: vi.fn(),
  onToggleScheduler: vi.fn(),
  onToggleAutoExecute: vi.fn(),
  onToggleMacroFilter: vi.fn(),
  onMaxConcurrentChange: vi.fn(),
  onRiskAdjust: vi.fn(),
  onToggleMockMarket: vi.fn(),
  onInjectMockSignal: vi.fn(),
  onReplayActiveChange: vi.fn(),
  onError: vi.fn(),
}

const renderWithRouter = (ui) => render(<MemoryRouter>{ui}</MemoryRouter>)

describe('ScanEngineControls', () => {
  it('renders idle state with enabled Start button', () => {
    renderWithRouter(<ScanEngineControls {...baseProps} />)
    const btn = screen.getByTestId('live-start-scan')
    expect(btn).toBeInTheDocument()
    expect(btn.disabled).toBe(false)
  })

  it('renders Stop button when scanning=true', () => {
    renderWithRouter(<ScanEngineControls {...baseProps} scanning={true} stopRequested={false} />)
    expect(screen.getByTestId('live-stop-scan')).toBeInTheDocument()
    expect(screen.queryByTestId('live-start-scan')).toBeNull()
  })

  it('calls onStartScan when Start is clicked', async () => {
    const onStartScan = vi.fn()
    renderWithRouter(<ScanEngineControls {...baseProps} onStartScan={onStartScan} />)
    await userEvent.click(screen.getByTestId('live-start-scan'))
    expect(onStartScan).toHaveBeenCalledOnce()
  })

  it('calls onStopScan when Stop is clicked', async () => {
    const onStopScan = vi.fn()
    renderWithRouter(<ScanEngineControls {...baseProps} scanning={true} onStopScan={onStopScan} />)
    await userEvent.click(screen.getByTestId('live-stop-scan'))
    expect(onStopScan).toHaveBeenCalledOnce()
  })

  it('disables Start when IBKR lock is active (exclusiveScanLockHeld=true, canScan=false)', () => {
    renderWithRouter(
      <ScanEngineControls
        {...baseProps}
        canScan={false}
        exclusiveScanLockHeld={true}
        exclusiveLockSinceMs={Date.now() - 60000}
        exclusiveLockMinutes={1}
      />
    )
    const btn = screen.getByTestId('live-start-scan')
    expect(btn.disabled).toBe(true)
  })

  it('renders countdown when schedulerEnabled=true and nextScanSecs provided', () => {
    renderWithRouter(
      <ScanEngineControls
        {...baseProps}
        status={{ ...baseProps.status, schedulerEnabled: true }}
        nextScanSecs={45}
      />
    )
    const countdown = screen.getByTestId('scan-countdown')
    expect(countdown).toBeInTheDocument()
    expect(countdown.textContent).toContain('0:45')
  })

  it('calls onForceStop once when force-stop button is clicked', async () => {
    const onForceStop = vi.fn()
    renderWithRouter(<ScanEngineControls {...baseProps} forceStopReleasing={false} exclusiveScanLockHeld={false} onForceStop={onForceStop} />)
    await userEvent.click(screen.getByTestId('live-force-stop'))
    expect(onForceStop).toHaveBeenCalledOnce()
  })

  it('disables force-stop button and shows Liberando text when forceStopReleasing=true', () => {
    renderWithRouter(<ScanEngineControls {...baseProps} forceStopReleasing={true} />)
    const btn = screen.getByTestId('live-force-stop')
    expect(btn.disabled).toBe(true)
    expect(btn.textContent).toContain('Liberando…')
  })

  it('calls onToggleScheduler once when Auto Scan button is clicked', async () => {
    const onToggleScheduler = vi.fn()
    renderWithRouter(<ScanEngineControls {...baseProps} onToggleScheduler={onToggleScheduler} />)
    await userEvent.click(screen.getByTestId('live-toggle-scheduler'))
    expect(onToggleScheduler).toHaveBeenCalledOnce()
  })

  it('calls onToggleAutoExecute once when auto-execute swap button is clicked', async () => {
    const onToggleAutoExecute = vi.fn()
    renderWithRouter(<ScanEngineControls {...baseProps} onToggleAutoExecute={onToggleAutoExecute} />)
    await userEvent.click(screen.getByTestId('live-toggle-auto-execute'))
    expect(onToggleAutoExecute).toHaveBeenCalledOnce()
  })

  it('calls onToggleMacroFilter once when macro-filter swap button is clicked', async () => {
    const onToggleMacroFilter = vi.fn()
    renderWithRouter(<ScanEngineControls {...baseProps} onToggleMacroFilter={onToggleMacroFilter} />)
    await userEvent.click(screen.getByTestId('live-toggle-macro-filter'))
    expect(onToggleMacroFilter).toHaveBeenCalledOnce()
  })

  it('renders AdvancedScanControls and passes handlers through', () => {
    renderWithRouter(<ScanEngineControls {...baseProps} />)
    expect(screen.getByTestId('advanced-scan-controls-mock')).toBeInTheDocument()
  })

  // Proxy tests — verify props flow from ScanEngineControls → AdvancedScanControls mock

  it('passes onRiskAdjust(1.0) through AdvancedScanControls', async () => {
    const onRiskAdjust = vi.fn()
    renderWithRouter(<ScanEngineControls {...baseProps} onRiskAdjust={onRiskAdjust} />)
    await userEvent.click(screen.getByTestId('live-risk-up'))
    expect(onRiskAdjust).toHaveBeenCalledWith(1.0)
  })

  it('passes onRiskAdjust(-1.0) through AdvancedScanControls', async () => {
    const onRiskAdjust = vi.fn()
    renderWithRouter(<ScanEngineControls {...baseProps} onRiskAdjust={onRiskAdjust} />)
    await userEvent.click(screen.getByTestId('live-risk-down'))
    expect(onRiskAdjust).toHaveBeenCalledWith(-1.0)
  })

  it('shows mock signal button in AdvancedScanControls when mockMarketOpen=true', () => {
    renderWithRouter(<ScanEngineControls {...baseProps} mockMarketOpen={true} />)
    expect(screen.getByTestId('live-inject-mock-signal')).toBeInTheDocument()
  })

  it('passes onInjectMockSignal through AdvancedScanControls', async () => {
    const onInjectMockSignal = vi.fn()
    renderWithRouter(<ScanEngineControls {...baseProps} mockMarketOpen={true} onInjectMockSignal={onInjectMockSignal} />)
    await userEvent.click(screen.getByTestId('live-inject-mock-signal'))
    expect(onInjectMockSignal).toHaveBeenCalledOnce()
  })
})

describe('Strategies badge', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('renders strategies badge with zero count when api returns empty array', async () => {
    const { strategyConfigApi } = await import('../api')
    strategyConfigApi.findAll.mockResolvedValue([])
    renderWithRouter(<ScanEngineControls {...baseProps} />)
    await waitFor(() => {
      expect(screen.getByTestId('strategies-badge')).toBeInTheDocument()
    })
    expect(screen.getByTestId('strategies-badge').textContent).toContain('0 active')
  })

  it('fetches strategy count on mount', async () => {
    const { strategyConfigApi } = await import('../api')
    strategyConfigApi.findAll.mockResolvedValue([])
    renderWithRouter(<ScanEngineControls {...baseProps} />)
    await waitFor(() => {
      expect(strategyConfigApi.findAll).toHaveBeenCalledOnce()
    })
  })

  it('renders live-active count after fetch', async () => {
    const { strategyConfigApi } = await import('../api')
    strategyConfigApi.findAll.mockResolvedValue([
      { name: 'A', enabledLive: true },
      { name: 'B', enabledLive: true },
      { name: 'C', enabledLive: false },
    ])
    renderWithRouter(<ScanEngineControls {...baseProps} />)
    await waitFor(() => {
      expect(screen.getByTestId('strategies-badge').textContent).toContain('2 active')
    })
  })

  it('badge is a link to /strategies', async () => {
    const { strategyConfigApi } = await import('../api')
    strategyConfigApi.findAll.mockResolvedValue([])
    renderWithRouter(<ScanEngineControls {...baseProps} />)
    await waitFor(() => {
      const badge = screen.getByTestId('strategies-badge')
      expect(badge.getAttribute('href')).toBe('/strategies')
    })
  })

  it('badge has correct testId', async () => {
    const { strategyConfigApi } = await import('../api')
    strategyConfigApi.findAll.mockResolvedValue([])
    renderWithRouter(<ScanEngineControls {...baseProps} />)
    await waitFor(() => {
      expect(screen.getByTestId('strategies-badge')).toBeInTheDocument()
    })
  })

  it('updates count after refresh interval', async () => {
    vi.useFakeTimers()
    const { strategyConfigApi } = await import('../api')
    strategyConfigApi.findAll.mockResolvedValue([])
    renderWithRouter(<ScanEngineControls {...baseProps} />)
    // initial mount call
    await act(async () => { await Promise.resolve() })
    expect(strategyConfigApi.findAll).toHaveBeenCalledTimes(1)
    // advance 30s
    await act(async () => { vi.advanceTimersByTime(30000) })
    await act(async () => { await Promise.resolve() })
    expect(strategyConfigApi.findAll).toHaveBeenCalledTimes(2)
  })
})
