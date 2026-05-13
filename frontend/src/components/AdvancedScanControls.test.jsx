import React from 'react'
import { render, screen, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi } from 'vitest'
import AdvancedScanControls from './AdvancedScanControls'

vi.mock('./ReplayControls', () => ({
  default: () => <div data-testid="replay-controls-mock">ReplayControls</div>,
}))

const baseProps = {
  scanning: false,
  mockMarketOpen: false,
  maxConcurrent: 4,
  riskInput: 1.0,
  marketOpen: true,
  onMaxConcurrentChange: vi.fn(),
  onRiskAdjust: vi.fn(),
  onToggleMockMarket: vi.fn(),
  onInjectMockSignal: vi.fn(),
  onReplayActiveChange: vi.fn(),
  onError: vi.fn(),
}

describe('AdvancedScanControls', () => {
  it('renders the trigger button', () => {
    render(<AdvancedScanControls {...baseProps} />)
    expect(screen.getByTestId('advanced-scan-trigger')).toBeInTheDocument()
  })

  it('dropdown is hidden initially', () => {
    render(<AdvancedScanControls {...baseProps} />)
    expect(screen.queryByTestId('advanced-scan-dropdown')).not.toBeInTheDocument()
  })

  it('opens dropdown on trigger click', async () => {
    render(<AdvancedScanControls {...baseProps} />)
    await userEvent.click(screen.getByTestId('advanced-scan-trigger'))
    expect(screen.getByTestId('advanced-scan-dropdown')).toBeInTheDocument()
  })

  it('shows concurrent input in dropdown', async () => {
    render(<AdvancedScanControls {...baseProps} />)
    await userEvent.click(screen.getByTestId('advanced-scan-trigger'))
    const input = screen.getByTestId('advanced-scan-dropdown').querySelector('input[type="number"]')
    expect(input).toBeInTheDocument()
  })

  it('shows risk controls in dropdown', async () => {
    render(<AdvancedScanControls {...baseProps} />)
    await userEvent.click(screen.getByTestId('advanced-scan-trigger'))
    expect(screen.getByTestId('live-risk-up')).toBeInTheDocument()
    expect(screen.getByTestId('live-risk-down')).toBeInTheDocument()
  })

  it('shows mock market toggle in dropdown', async () => {
    render(<AdvancedScanControls {...baseProps} />)
    await userEvent.click(screen.getByTestId('advanced-scan-trigger'))
    expect(screen.getByTestId('live-toggle-mock-market')).toBeInTheDocument()
  })

  it('does not show mock signal button when mockMarketOpen=false', async () => {
    render(<AdvancedScanControls {...baseProps} mockMarketOpen={false} />)
    await userEvent.click(screen.getByTestId('advanced-scan-trigger'))
    expect(screen.queryByTestId('live-inject-mock-signal')).not.toBeInTheDocument()
  })

  it('shows mock signal button when mockMarketOpen=true', async () => {
    render(<AdvancedScanControls {...baseProps} mockMarketOpen={true} />)
    await userEvent.click(screen.getByTestId('advanced-scan-trigger'))
    expect(screen.getByTestId('live-inject-mock-signal')).toBeInTheDocument()
  })

  it('shows ReplayControls when mockMarketOpen=true', async () => {
    render(<AdvancedScanControls {...baseProps} mockMarketOpen={true} />)
    await userEvent.click(screen.getByTestId('advanced-scan-trigger'))
    expect(screen.getByTestId('replay-controls-mock')).toBeInTheDocument()
  })

  it('closes dropdown on second trigger click', async () => {
    render(<AdvancedScanControls {...baseProps} />)
    await userEvent.click(screen.getByTestId('advanced-scan-trigger'))
    await userEvent.click(screen.getByTestId('advanced-scan-trigger'))
    expect(screen.queryByTestId('advanced-scan-dropdown')).not.toBeInTheDocument()
  })

  it('calls onRiskAdjust(1.0) when chevron-up is clicked', async () => {
    const onRiskAdjust = vi.fn()
    render(<AdvancedScanControls {...baseProps} onRiskAdjust={onRiskAdjust} />)
    await userEvent.click(screen.getByTestId('advanced-scan-trigger'))
    await userEvent.click(screen.getByTestId('live-risk-up'))
    expect(onRiskAdjust).toHaveBeenCalledWith(1.0)
  })

  it('calls onRiskAdjust(-1.0) when chevron-down is clicked', async () => {
    const onRiskAdjust = vi.fn()
    render(<AdvancedScanControls {...baseProps} onRiskAdjust={onRiskAdjust} />)
    await userEvent.click(screen.getByTestId('advanced-scan-trigger'))
    await userEvent.click(screen.getByTestId('live-risk-down'))
    expect(onRiskAdjust).toHaveBeenCalledWith(-1.0)
  })

  it('calls onMaxConcurrentChange when concurrent input changes', async () => {
    const onMaxConcurrentChange = vi.fn()
    render(<AdvancedScanControls {...baseProps} onMaxConcurrentChange={onMaxConcurrentChange} />)
    await userEvent.click(screen.getByTestId('advanced-scan-trigger'))
    const dropdown = screen.getByTestId('advanced-scan-dropdown')
    const input = dropdown.querySelector('input[type="number"]')
    fireEvent.change(input, { target: { value: '8' } })
    expect(onMaxConcurrentChange).toHaveBeenCalledWith('8')
  })

  it('calls onInjectMockSignal when Mock Signal button is clicked', async () => {
    const onInjectMockSignal = vi.fn()
    render(<AdvancedScanControls {...baseProps} mockMarketOpen={true} onInjectMockSignal={onInjectMockSignal} />)
    await userEvent.click(screen.getByTestId('advanced-scan-trigger'))
    await userEvent.click(screen.getByTestId('live-inject-mock-signal'))
    expect(onInjectMockSignal).toHaveBeenCalledOnce()
  })
})
