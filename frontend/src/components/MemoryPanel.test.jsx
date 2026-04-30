import React from 'react'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, afterEach } from 'vitest'
import MemoryPanel from './MemoryPanel'

vi.mock('../api', () => ({
  backtestApi: {
    deleteTickerMemoryRiskProfile: vi.fn(),
  },
}))

import { backtestApi } from '../api'

const baseProps = {
  open: true,
  rows: [],
  loading: false,
  error: null,
  onToggle: vi.fn(),
  onError: vi.fn(),
  onReload: vi.fn(),
}

const sampleRows = [
  { ticker: 'AAPL', strategy: 'ATR_BREAKOUT', tpAtrMultOverride: 2.5, slAtrMultOverride: 1.2, lastUpdated: 1700000000000 },
  { ticker: 'TSLA', strategy: 'MOMENTUM', tpAtrMultOverride: null, slAtrMultOverride: null, lastUpdated: null },
]

describe('MemoryPanel', () => {
  afterEach(() => {
    vi.clearAllMocks()
  })

  it('renders the summary heading', () => {
    render(<MemoryPanel {...baseProps} />)
    expect(screen.getByText(/Memoria TP\/SL guardada/i)).toBeInTheDocument()
  })

  it('shows empty state message when rows is empty and not loading', () => {
    render(<MemoryPanel {...baseProps} />)
    expect(screen.getByText(/No hay overrides TP\/SL en memoria/i)).toBeInTheDocument()
  })

  it('shows loading indicator when loading=true', () => {
    render(<MemoryPanel {...baseProps} loading={true} />)
    expect(screen.getByText(/Cargando/i)).toBeInTheDocument()
  })

  it('hides empty state message when loading=true', () => {
    render(<MemoryPanel {...baseProps} loading={true} />)
    expect(screen.queryByText(/No hay overrides TP\/SL en memoria/i)).toBeNull()
  })

  it('shows error message in alert role when error is set', () => {
    render(<MemoryPanel {...baseProps} error="Delete failed" />)
    const alert = screen.getByRole('alert')
    expect(alert).toBeInTheDocument()
    expect(alert.textContent).toContain('Delete failed')
  })

  it('renders a table row per entry when rows are provided', () => {
    render(<MemoryPanel {...baseProps} rows={sampleRows} />)
    expect(screen.getByText('AAPL')).toBeInTheDocument()
    expect(screen.getByText('TSLA')).toBeInTheDocument()
  })

  it('formats tpAtrMultOverride to 3 decimal places', () => {
    render(<MemoryPanel {...baseProps} rows={sampleRows} />)
    expect(screen.getByText('2.500')).toBeInTheDocument()
  })

  it('shows dash when tpAtrMultOverride is null', () => {
    render(<MemoryPanel {...baseProps} rows={sampleRows} />)
    // TSLA has null overrides — two dashes expected in sl/tp columns
    const dashes = screen.getAllByText('—')
    expect(dashes.length).toBeGreaterThanOrEqual(2)
  })

  it('renders a Quitar button for each row', () => {
    render(<MemoryPanel {...baseProps} rows={sampleRows} />)
    const btns = screen.getAllByRole('button', { name: /Quitar/i })
    expect(btns).toHaveLength(2)
  })

  it('calls deleteTickerMemoryRiskProfile then onReload on Quitar click', async () => {
    backtestApi.deleteTickerMemoryRiskProfile.mockResolvedValue({})
    const onReload = vi.fn().mockResolvedValue(undefined)
    render(<MemoryPanel {...baseProps} rows={sampleRows} onReload={onReload} />)

    const [firstBtn] = screen.getAllByRole('button', { name: /Quitar/i })
    fireEvent.click(firstBtn)

    await waitFor(() => {
      expect(backtestApi.deleteTickerMemoryRiskProfile).toHaveBeenCalledWith('AAPL', 'ATR_BREAKOUT')
      expect(onReload).toHaveBeenCalledOnce()
    })
  })

  it('calls onError when delete API fails', async () => {
    backtestApi.deleteTickerMemoryRiskProfile.mockRejectedValue(new Error('HTTP 500'))
    const onError = vi.fn()
    render(<MemoryPanel {...baseProps} rows={sampleRows} onError={onError} />)

    const [firstBtn] = screen.getAllByRole('button', { name: /Quitar/i })
    fireEvent.click(firstBtn)

    await waitFor(() => {
      expect(onError).toHaveBeenCalledWith('HTTP 500')
    })
  })

  it('calls onToggle with true when details is opened', () => {
    const onToggle = vi.fn()
    render(<MemoryPanel {...baseProps} open={false} onToggle={onToggle} />)
    const details = screen.getByRole('group')
    // details.open is read-only; simulate the browser toggle event via nativeEvent
    Object.defineProperty(details, 'open', { configurable: true, get: () => true })
    fireEvent(details, new Event('toggle', { bubbles: false }))
    expect(onToggle).toHaveBeenCalledWith(true)
  })

  it('does not show table when rows is empty', () => {
    render(<MemoryPanel {...baseProps} rows={[]} />)
    expect(screen.queryByRole('table')).toBeNull()
  })
})
