import React from 'react'
import { render, screen, fireEvent } from '@testing-library/react'
import { describe, it, expect, vi, afterEach } from 'vitest'
import FundamentalTickerForm from './FundamentalTickerForm'

afterEach(() => {
  vi.clearAllMocks()
})

const baseProps = {
  symbol: 'AAPL',
  initial: null,
  onSave: vi.fn(),
  onCancel: vi.fn(),
}

const fullInitial = {
  companyName: 'Apple Inc.',
  sector: 'Technology',
  marketCapBillion: 3200,
  peRatio: 28.5,
  dividendYield: 0.5,
  beta: 1.2,
  epsGrowth: 10.5,
  revenueGrowth: 8.3,
  debtToEquity: 1.7,
  roic: 45.2,
  notes: 'Top holding',
}

describe('FundamentalTickerForm', () => {
  // ── Render ────────────────────────────────────────────────────────────────

  it('renders the form title with the symbol', () => {
    render(<FundamentalTickerForm {...baseProps} />)
    expect(screen.getByText(/Fundamentales · AAPL/i)).toBeInTheDocument()
  })

  it('renders Save and Cancel buttons', () => {
    render(<FundamentalTickerForm {...baseProps} />)
    expect(screen.getByRole('button', { name: /guardar fundamentales/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /cancelar/i })).toBeInTheDocument()
  })

  it('renders all field labels', () => {
    render(<FundamentalTickerForm {...baseProps} />)
    expect(screen.getByText('Empresa')).toBeInTheDocument()
    expect(screen.getByText('Sector')).toBeInTheDocument()
    expect(screen.getByText(/market cap/i)).toBeInTheDocument()
    expect(screen.getByText('P/E')).toBeInTheDocument()
    expect(screen.getByText(/dividend yield/i)).toBeInTheDocument()
    expect(screen.getByText('Beta')).toBeInTheDocument()
    expect(screen.getByText(/EPS growth/i)).toBeInTheDocument()
    expect(screen.getByText(/Revenue growth/i)).toBeInTheDocument()
    expect(screen.getByText(/Debt \/ Equity/i)).toBeInTheDocument()
    expect(screen.getByText(/ROIC/i)).toBeInTheDocument()
    expect(screen.getByText('Notas')).toBeInTheDocument()
  })

  it('starts with all inputs empty when initial is null', () => {
    render(<FundamentalTickerForm {...baseProps} />)
    const inputs = screen.getAllByRole('textbox')
    // All text inputs (including textarea) should be empty
    inputs.forEach((input) => {
      expect(input.value).toBe('')
    })
  })

  // ── Initial data population ───────────────────────────────────────────────

  it('pre-fills inputs when initial data is provided', () => {
    render(<FundamentalTickerForm {...baseProps} initial={fullInitial} />)
    expect(screen.getByDisplayValue('Apple Inc.')).toBeInTheDocument()
    expect(screen.getByDisplayValue('Technology')).toBeInTheDocument()
    expect(screen.getByDisplayValue('3200')).toBeInTheDocument()
    expect(screen.getByDisplayValue('28.5')).toBeInTheDocument()
    expect(screen.getByDisplayValue('1.2')).toBeInTheDocument()
    expect(screen.getByDisplayValue('Top holding')).toBeInTheDocument()
  })

  it('resets to empty fields when initial changes to null', () => {
    const { rerender } = render(<FundamentalTickerForm {...baseProps} initial={fullInitial} />)
    expect(screen.getByDisplayValue('Apple Inc.')).toBeInTheDocument()

    rerender(<FundamentalTickerForm {...baseProps} initial={null} />)
    const inputs = screen.getAllByRole('textbox')
    inputs.forEach((input) => {
      expect(input.value).toBe('')
    })
  })

  it('repopulates when initial changes to a new value', () => {
    const { rerender } = render(<FundamentalTickerForm {...baseProps} initial={null} />)
    rerender(<FundamentalTickerForm {...baseProps} initial={{ ...fullInitial, companyName: 'Microsoft Corp' }} />)
    expect(screen.getByDisplayValue('Microsoft Corp')).toBeInTheDocument()
  })

  // ── Input editing ─────────────────────────────────────────────────────────

  it('updates companyName field on change', () => {
    render(<FundamentalTickerForm {...baseProps} />)
    // First textbox is Empresa (companyName)
    const empresaInput = screen.getAllByRole('textbox')[0]
    fireEvent.change(empresaInput, { target: { value: 'Tesla Inc.' } })
    expect(empresaInput.value).toBe('Tesla Inc.')
  })

  it('updates notes textarea on change', () => {
    render(<FundamentalTickerForm {...baseProps} />)
    // The textarea is the last textbox in the form
    const allInputs = screen.getAllByRole('textbox')
    const notesTextarea = allInputs[allInputs.length - 1]
    fireEvent.change(notesTextarea, { target: { value: 'Important stock' } })
    expect(notesTextarea.value).toBe('Important stock')
  })

  // ── Submit ─────────────────────────────────────────────────────────────────

  it('calls onSave with correct payload on submit with empty fields', () => {
    const onSave = vi.fn()
    render(<FundamentalTickerForm {...baseProps} onSave={onSave} />)

    fireEvent.click(screen.getByRole('button', { name: /guardar fundamentales/i }))

    expect(onSave).toHaveBeenCalledOnce()
    expect(onSave).toHaveBeenCalledWith({
      companyName: null,
      sector: null,
      marketCapBillion: null,
      peRatio: null,
      dividendYield: null,
      beta: null,
      epsGrowth: null,
      revenueGrowth: null,
      debtToEquity: null,
      roic: null,
      notes: null,
    })
  })

  it('calls onSave with parsed numeric values when initial is provided', () => {
    const onSave = vi.fn()
    render(<FundamentalTickerForm {...baseProps} initial={fullInitial} onSave={onSave} />)

    fireEvent.click(screen.getByRole('button', { name: /guardar fundamentales/i }))

    expect(onSave).toHaveBeenCalledOnce()
    const payload = onSave.mock.calls[0][0]
    expect(payload.companyName).toBe('Apple Inc.')
    expect(payload.sector).toBe('Technology')
    expect(payload.marketCapBillion).toBe(3200)
    expect(payload.peRatio).toBe(28.5)
    expect(payload.beta).toBe(1.2)
    expect(payload.epsGrowth).toBe(10.5)
    expect(payload.revenueGrowth).toBe(8.3)
    expect(payload.dividendYield).toBe(0.5)
    expect(payload.debtToEquity).toBe(1.7)
    expect(payload.roic).toBe(45.2)
    expect(payload.notes).toBe('Top holding')
  })

  it('converts blank numeric fields to null in the payload', () => {
    const onSave = vi.fn()
    render(<FundamentalTickerForm {...baseProps} initial={{ ...fullInitial, peRatio: null, beta: null }} onSave={onSave} />)

    fireEvent.click(screen.getByRole('button', { name: /guardar fundamentales/i }))

    const payload = onSave.mock.calls[0][0]
    expect(payload.peRatio).toBeNull()
    expect(payload.beta).toBeNull()
  })

  it('converts blank string text fields to null in the payload', () => {
    const onSave = vi.fn()
    // initial has companyName and sector set, then user clears them
    render(<FundamentalTickerForm {...baseProps} initial={fullInitial} onSave={onSave} />)

    const allInputs = screen.getAllByRole('textbox')
    // First input is Empresa (companyName)
    fireEvent.change(allInputs[0], { target: { value: '   ' } })
    // Second input is Sector
    fireEvent.change(allInputs[1], { target: { value: '' } })

    fireEvent.click(screen.getByRole('button', { name: /guardar fundamentales/i }))

    const payload = onSave.mock.calls[0][0]
    expect(payload.companyName).toBeNull()
    expect(payload.sector).toBeNull()
  })

  it('does not call onSave when form submit default is prevented correctly', () => {
    // Verifies e.preventDefault() is called (form does not navigate)
    const onSave = vi.fn()
    const { container } = render(<FundamentalTickerForm {...baseProps} onSave={onSave} />)
    const form = container.querySelector('form')

    const submitEvent = new Event('submit', { bubbles: true, cancelable: true })
    const preventDefaultSpy = vi.spyOn(submitEvent, 'preventDefault')
    form.dispatchEvent(submitEvent)

    expect(preventDefaultSpy).toHaveBeenCalled()
  })

  // ── Cancel ────────────────────────────────────────────────────────────────

  it('calls onCancel when Cancel button is clicked', () => {
    const onCancel = vi.fn()
    render(<FundamentalTickerForm {...baseProps} onCancel={onCancel} />)
    fireEvent.click(screen.getByRole('button', { name: /cancelar/i }))
    expect(onCancel).toHaveBeenCalledOnce()
  })

  it('does not call onSave when Cancel is clicked', () => {
    const onSave = vi.fn()
    render(<FundamentalTickerForm {...baseProps} onSave={onSave} />)
    fireEvent.click(screen.getByRole('button', { name: /cancelar/i }))
    expect(onSave).not.toHaveBeenCalled()
  })

  // ── Symbol change ─────────────────────────────────────────────────────────

  it('shows the new symbol in the title when symbol prop changes', () => {
    const { rerender } = render(<FundamentalTickerForm {...baseProps} symbol="AAPL" />)
    expect(screen.getByText(/Fundamentales · AAPL/i)).toBeInTheDocument()

    rerender(<FundamentalTickerForm {...baseProps} symbol="MSFT" />)
    expect(screen.getByText(/Fundamentales · MSFT/i)).toBeInTheDocument()
  })
})
