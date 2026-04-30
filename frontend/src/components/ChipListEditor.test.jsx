import React from 'react'
import { render, screen, fireEvent } from '@testing-library/react'
import { describe, it, expect, vi, afterEach } from 'vitest'
import ChipListEditor from './ChipListEditor'

// Stub out TickerTooltip to avoid API calls and portal complexity
vi.mock('./TickerTooltip', () => ({
  default: ({ children }) => <span>{children}</span>,
}))

const EMPTY_SET = Object.freeze(new Set())

describe('ChipListEditor', () => {
  afterEach(() => {
    vi.clearAllMocks()
  })

  // ── Render ────────────────────────────────────────────────────────────────

  it('renders empty container with placeholder when value is empty', () => {
    render(<ChipListEditor value="" onChange={vi.fn()} hotSet={EMPTY_SET} placeholder="Ticker…" />)
    expect(screen.getByPlaceholderText('Ticker…')).toBeInTheDocument()
  })

  it('renders a chip for each ticker in value', () => {
    render(<ChipListEditor value="AAPL,MSFT,TSLA" onChange={vi.fn()} hotSet={EMPTY_SET} />)
    expect(screen.getByText('AAPL')).toBeInTheDocument()
    expect(screen.getByText('MSFT')).toBeInTheDocument()
    expect(screen.getByText('TSLA')).toBeInTheDocument()
  })

  it('normalises tickers to uppercase', () => {
    render(<ChipListEditor value="aapl,msft" onChange={vi.fn()} hotSet={EMPTY_SET} />)
    expect(screen.getByText('AAPL')).toBeInTheDocument()
    expect(screen.getByText('MSFT')).toBeInTheDocument()
  })

  it('hides placeholder when chips are present', () => {
    render(<ChipListEditor value="AAPL" onChange={vi.fn()} hotSet={EMPTY_SET} placeholder="Ticker…" />)
    const input = screen.getByRole('textbox')
    expect(input.placeholder).toBe('')
  })

  it('does not render input when disabled=true', () => {
    render(<ChipListEditor value="AAPL" onChange={vi.fn()} hotSet={EMPTY_SET} disabled={true} />)
    expect(screen.queryByRole('textbox')).toBeNull()
  })

  it('does not render remove buttons when disabled=true', () => {
    render(<ChipListEditor value="AAPL,MSFT" onChange={vi.fn()} hotSet={EMPTY_SET} disabled={true} />)
    expect(screen.queryByRole('button', { name: /Remove/i })).toBeNull()
  })

  it('applies disabled CSS modifier when disabled=true', () => {
    const { container } = render(
      <ChipListEditor value="AAPL" onChange={vi.fn()} hotSet={EMPTY_SET} disabled={true} />
    )
    expect(container.firstChild.className).toContain('cle-container--disabled')
  })

  // ── Add chip ──────────────────────────────────────────────────────────────

  it('calls onChange with new ticker appended when Enter is pressed', () => {
    const onChange = vi.fn()
    render(<ChipListEditor value="AAPL" onChange={onChange} hotSet={EMPTY_SET} />)
    const input = screen.getByRole('textbox')
    fireEvent.change(input, { target: { value: 'MSFT' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(onChange).toHaveBeenCalledWith('AAPL,MSFT')
  })

  it('calls onChange when comma is pressed after typing a ticker', () => {
    const onChange = vi.fn()
    render(<ChipListEditor value="" onChange={onChange} hotSet={EMPTY_SET} />)
    const input = screen.getByRole('textbox')
    fireEvent.change(input, { target: { value: 'NVDA' } })
    fireEvent.keyDown(input, { key: ',' })
    expect(onChange).toHaveBeenCalledWith('NVDA')
  })

  it('does not call onChange when trying to add a duplicate ticker', () => {
    const onChange = vi.fn()
    render(<ChipListEditor value="AAPL" onChange={onChange} hotSet={EMPTY_SET} />)
    const input = screen.getByRole('textbox')
    fireEvent.change(input, { target: { value: 'AAPL' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(onChange).not.toHaveBeenCalled()
  })

  it('clears input after adding a ticker', () => {
    render(<ChipListEditor value="AAPL" onChange={vi.fn()} hotSet={EMPTY_SET} />)
    const input = screen.getByRole('textbox')
    fireEvent.change(input, { target: { value: 'MSFT' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(input.value).toBe('')
  })

  // ── Remove chip ───────────────────────────────────────────────────────────

  it('calls onChange without the removed ticker when X is clicked', () => {
    const onChange = vi.fn()
    render(<ChipListEditor value="AAPL,MSFT,TSLA" onChange={onChange} hotSet={EMPTY_SET} />)
    const removeBtn = screen.getByRole('button', { name: 'Remove MSFT' })
    fireEvent.click(removeBtn)
    expect(onChange).toHaveBeenCalledWith('AAPL,TSLA')
  })

  it('removes last ticker via Backspace when input is empty', () => {
    const onChange = vi.fn()
    render(<ChipListEditor value="AAPL,MSFT" onChange={onChange} hotSet={EMPTY_SET} />)
    const input = screen.getByRole('textbox')
    fireEvent.keyDown(input, { key: 'Backspace' })
    expect(onChange).toHaveBeenCalledWith('AAPL')
  })

  it('does not call onChange on Backspace when input has text', () => {
    const onChange = vi.fn()
    render(<ChipListEditor value="AAPL" onChange={onChange} hotSet={EMPTY_SET} />)
    const input = screen.getByRole('textbox')
    fireEvent.change(input, { target: { value: 'M' } })
    fireEvent.keyDown(input, { key: 'Backspace' })
    expect(onChange).not.toHaveBeenCalled()
  })

  // ── Hot set ───────────────────────────────────────────────────────────────

  it('applies hot chip class when ticker is in hotSet', () => {
    const hotSet = new Set(['AAPL'])
    const { container } = render(
      <ChipListEditor value="AAPL,MSFT" onChange={vi.fn()} hotSet={hotSet} />
    )
    const chips = container.querySelectorAll('.ts-chip')
    const hotChip = [...chips].find((c) => c.textContent.includes('AAPL'))
    expect(hotChip.className).toContain('ts-chip--hot')
  })

  it('does not apply hot chip class when ticker is not in hotSet', () => {
    const hotSet = new Set(['AAPL'])
    const { container } = render(
      <ChipListEditor value="AAPL,MSFT" onChange={vi.fn()} hotSet={hotSet} />
    )
    const chips = container.querySelectorAll('.ts-chip')
    const normalChip = [...chips].find((c) => c.textContent.includes('MSFT'))
    expect(normalChip.className).not.toContain('ts-chip--hot')
  })
})
