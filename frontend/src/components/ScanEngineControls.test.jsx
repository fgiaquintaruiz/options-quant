import React from 'react'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi } from 'vitest'
import ScanEngineControls from './ScanEngineControls'

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

describe('ScanEngineControls', () => {
  it('renders idle state with enabled Start button', () => {
    render(<ScanEngineControls {...baseProps} />)
    const btn = screen.getByTestId('live-start-scan')
    expect(btn).toBeTruthy()
    expect(btn.disabled).toBe(false)
  })

  it('renders Stop button when scanning=true', () => {
    render(<ScanEngineControls {...baseProps} scanning={true} stopRequested={false} />)
    expect(screen.getByTestId('live-stop-scan')).toBeTruthy()
    expect(screen.queryByTestId('live-start-scan')).toBeNull()
  })

  it('calls onStartScan when Start is clicked', async () => {
    const onStartScan = vi.fn()
    render(<ScanEngineControls {...baseProps} onStartScan={onStartScan} />)
    await userEvent.click(screen.getByTestId('live-start-scan'))
    expect(onStartScan).toHaveBeenCalledOnce()
  })

  it('calls onStopScan when Stop is clicked', async () => {
    const onStopScan = vi.fn()
    render(<ScanEngineControls {...baseProps} scanning={true} onStopScan={onStopScan} />)
    await userEvent.click(screen.getByTestId('live-stop-scan'))
    expect(onStopScan).toHaveBeenCalledOnce()
  })

  it('disables Start when IBKR lock is active (exclusiveScanLockHeld=true, canScan=false)', () => {
    render(
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

  it('shows MockMarket swap button in active state when mockMarketOpen=true', () => {
    render(<ScanEngineControls {...baseProps} mockMarketOpen={true} />)
    const btn = screen.getByTestId('live-toggle-mock-market')
    expect(btn.textContent).toContain('Mock Mkt')
  })

  it('renders countdown when schedulerEnabled=true and nextScanSecs provided', () => {
    render(
      <ScanEngineControls
        {...baseProps}
        status={{ ...baseProps.status, schedulerEnabled: true }}
        nextScanSecs={45}
      />
    )
    const countdown = document.querySelector('.ld-countdown')
    expect(countdown).toBeTruthy()
    expect(countdown.textContent).toContain('0:45')
  })
})
