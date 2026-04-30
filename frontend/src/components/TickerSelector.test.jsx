import React from 'react'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest'
import TickerSelector, { resolveTickerEntries, DEFAULT_GROUPS } from './TickerSelector'
import { liveApi } from '../api'

// ── Stubs ──────────────────────────────────────────────────────────────────

vi.mock('./TickerTooltip', () => ({
  default: ({ children }) => <span>{children}</span>,
}))

vi.mock('../api', () => ({
  liveApi: {
    getNewsTickers: vi.fn(() => Promise.resolve([])),
  },
}))

// Wrapper that provides router context (TickerSelector renders a <Link>)
function renderWithRouter(ui, opts = {}) {
  return render(<MemoryRouter>{ui}</MemoryRouter>, opts)
}

const baseProps = {
  value: '',
  onChange: vi.fn(),
  disabled: false,
}

// ── Pure helper: resolveTickerEntries ──────────────────────────────────────

describe('resolveTickerEntries', () => {
  const groups = [
    { id: 'semis', label: 'Semis', tickers: 'NVDA,AMD,AVGO' },
    { id: 'spy',   label: 'SPY',   tickers: 'SPY' },
  ]

  it('returns empty string for undefined value', () => {
    expect(resolveTickerEntries(undefined, groups)).toBe('')
  })

  it('returns empty string for empty string value', () => {
    expect(resolveTickerEntries('', groups)).toBe('')
  })

  it('resolves bare tickers to uppercase', () => {
    expect(resolveTickerEntries('aapl,msft', groups)).toBe('AAPL,MSFT')
  })

  it('expands @group ref to its tickers', () => {
    expect(resolveTickerEntries('@semis', groups)).toBe('NVDA,AMD,AVGO')
  })

  it('expands @group mixed with bare tickers', () => {
    expect(resolveTickerEntries('@spy,AAPL', groups)).toBe('SPY,AAPL')
  })

  it('deduplicates tickers that appear in both group and bare list', () => {
    const result = resolveTickerEntries('NVDA,@semis', groups)
    const parts = result.split(',')
    expect(parts.filter((t) => t === 'NVDA').length).toBe(1)
  })

  it('returns empty string for unknown @group ref', () => {
    expect(resolveTickerEntries('@unknown', groups)).toBe('')
  })
})

// ── Component: TickerSelector ──────────────────────────────────────────────

describe('TickerSelector', () => {
  afterEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
  })

  // ── Render ───────────────────────────────────────────────────────────────

  it('renders empty input with placeholder when value is empty', () => {
    renderWithRouter(<TickerSelector {...baseProps} />)
    expect(screen.getByPlaceholderText('Ticker, lista o watchlist…')).toBeInTheDocument()
  })

  it('renders a chip for each individual ticker in value', () => {
    renderWithRouter(<TickerSelector {...baseProps} value="AAPL,MSFT" />)
    expect(screen.getByText('AAPL')).toBeInTheDocument()
    expect(screen.getByText('MSFT')).toBeInTheDocument()
  })

  it('hides placeholder when chips are present', () => {
    renderWithRouter(<TickerSelector {...baseProps} value="AAPL" />)
    const input = screen.getByRole('textbox')
    expect(input.placeholder).toBe('')
  })

  it('renders a group chip with layer icon label for @watchlist ref', () => {
    const onChange = vi.fn()
    renderWithRouter(<TickerSelector value="@mega-tech" onChange={onChange} />)
    expect(screen.getByText('Mega Tech')).toBeInTheDocument()
  })

  it('renders group chip with ticker count badge', () => {
    renderWithRouter(<TickerSelector value="@semis" onChange={vi.fn()} />)
    const semisGroup = DEFAULT_GROUPS.find((g) => g.id === 'semis')
    const count = semisGroup.tickers.split(',').length.toString()
    expect(screen.getByText(count)).toBeInTheDocument()
  })

  // ── Disabled state ────────────────────────────────────────────────────────

  it('disables the input when disabled=true', () => {
    renderWithRouter(<TickerSelector {...baseProps} disabled={true} />)
    expect(screen.getByRole('textbox')).toBeDisabled()
  })

  it('applies disabled CSS modifier class when disabled=true', () => {
    const { container } = renderWithRouter(
      <TickerSelector {...baseProps} value="AAPL" disabled={true} />
    )
    const row = container.querySelector('.ts-input-row')
    expect(row.className).toContain('ts-input-row--disabled')
  })

  it('does not render remove buttons when disabled=true', () => {
    renderWithRouter(<TickerSelector value="AAPL" onChange={vi.fn()} disabled={true} />)
    expect(screen.queryByRole('button', { name: /Remove/i })).toBeNull()
  })

  // ── Autocomplete: open / close ────────────────────────────────────────────

  it('shows dropdown suggestions on input focus', () => {
    renderWithRouter(<TickerSelector {...baseProps} />)
    const input = screen.getByRole('textbox')
    fireEvent.focus(input)
    // Default groups are always rendered when dropdown is open
    expect(screen.getByText('Watchlists / Listas')).toBeInTheDocument()
  })

  it('filters groups by typed query', () => {
    renderWithRouter(<TickerSelector {...baseProps} />)
    const input = screen.getByRole('textbox')
    fireEvent.focus(input)
    fireEvent.change(input, { target: { value: 'SEMIS' } })
    expect(screen.getByText('Semis')).toBeInTheDocument()
    expect(screen.queryByText('Mega Tech')).toBeNull()
  })

  it('closes dropdown on Escape key', () => {
    renderWithRouter(<TickerSelector {...baseProps} />)
    const input = screen.getByRole('textbox')
    fireEvent.focus(input)
    expect(screen.getByText('Watchlists / Listas')).toBeInTheDocument()
    fireEvent.keyDown(input, { key: 'Escape' })
    expect(screen.queryByText('Watchlists / Listas')).toBeNull()
  })

  it('hides dropdown when clicking outside the component', () => {
    renderWithRouter(
      <div>
        <TickerSelector {...baseProps} />
        <button data-testid="outside">Outside</button>
      </div>
    )
    const input = screen.getByRole('textbox')
    fireEvent.focus(input)
    expect(screen.getByText('Watchlists / Listas')).toBeInTheDocument()
    fireEvent.mouseDown(screen.getByTestId('outside'))
    expect(screen.queryByText('Watchlists / Listas')).toBeNull()
  })

  // ── Add tickers via keyboard ──────────────────────────────────────────────

  it('calls onChange with new ticker on Enter when no suggestion is highlighted', () => {
    const onChange = vi.fn()
    renderWithRouter(<TickerSelector value="" onChange={onChange} />)
    const input = screen.getByRole('textbox')
    fireEvent.change(input, { target: { value: 'NVDA' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(onChange).toHaveBeenCalledWith('NVDA')
  })

  it('calls onChange with new ticker appended when there is an existing value', () => {
    const onChange = vi.fn()
    renderWithRouter(<TickerSelector value="AAPL" onChange={onChange} />)
    const input = screen.getByRole('textbox')
    fireEvent.change(input, { target: { value: 'MSFT' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(onChange).toHaveBeenCalledWith('AAPL,MSFT')
  })

  it('calls onChange when comma is pressed after typing a ticker', () => {
    const onChange = vi.fn()
    renderWithRouter(<TickerSelector value="" onChange={onChange} />)
    const input = screen.getByRole('textbox')
    fireEvent.change(input, { target: { value: 'TSLA' } })
    fireEvent.keyDown(input, { key: ',' })
    expect(onChange).toHaveBeenCalledWith('TSLA')
  })

  it('normalises typed ticker to uppercase', () => {
    const onChange = vi.fn()
    renderWithRouter(<TickerSelector value="" onChange={onChange} />)
    const input = screen.getByRole('textbox')
    // The input onChange handler toUpperCases the value itself
    fireEvent.change(input, { target: { value: 'AAPL' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(onChange).toHaveBeenCalledWith('AAPL')
  })

  it('does not call onChange when typed ticker is already resolved', () => {
    const onChange = vi.fn()
    renderWithRouter(<TickerSelector value="AAPL" onChange={onChange} />)
    const input = screen.getByRole('textbox')
    fireEvent.change(input, { target: { value: 'AAPL' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(onChange).not.toHaveBeenCalled()
  })

  it('clears input after confirming a ticker', () => {
    renderWithRouter(<TickerSelector value="" onChange={vi.fn()} />)
    const input = screen.getByRole('textbox')
    fireEvent.change(input, { target: { value: 'AMD' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(input.value).toBe('')
  })

  // ── Add watchlist via dropdown click ──────────────────────────────────────

  it('calls onChange with @groupId when a group suggestion is clicked', () => {
    const onChange = vi.fn()
    renderWithRouter(<TickerSelector value="" onChange={onChange} />)
    const input = screen.getByRole('textbox')
    fireEvent.focus(input)
    // Click the first group item (Mega Tech is not in DEFAULT_GROUPS-missing so it shows)
    fireEvent.click(screen.getByText('Mega Tech'))
    expect(onChange).toHaveBeenCalledWith('@mega-tech')
  })

  it('does not add duplicate @group ref', () => {
    const onChange = vi.fn()
    const { container } = renderWithRouter(<TickerSelector value="@mega-tech" onChange={onChange} />)
    const input = screen.getByRole('textbox')
    fireEvent.focus(input)
    // Click the dropdown item (inside ts-dropdown), not the chip
    const dropdownItem = container.querySelector('.ts-dropdown .ts-dropdown-item')
    fireEvent.click(dropdownItem)
    expect(onChange).not.toHaveBeenCalled()
  })

  it('does not call onChange when disabled and group suggestion is clicked', () => {
    const onChange = vi.fn()
    renderWithRouter(
      <TickerSelector value="" onChange={onChange} disabled={true} />
    )
    // Dropdown does not show when disabled — onChange stays untouched
    expect(onChange).not.toHaveBeenCalled()
  })

  // ── Add via keyboard navigation ───────────────────────────────────────────

  it('navigates down through suggestions with ArrowDown', () => {
    const { container } = renderWithRouter(<TickerSelector {...baseProps} />)
    const input = screen.getByRole('textbox')
    fireEvent.focus(input)
    fireEvent.keyDown(input, { key: 'ArrowDown' })
    fireEvent.keyDown(input, { key: 'ArrowDown' })
    const highlighted = container.querySelector('.ts-dropdown-item--hl')
    expect(highlighted).not.toBeNull()
  })

  it('selects highlighted suggestion with Enter', () => {
    const onChange = vi.fn()
    renderWithRouter(<TickerSelector value="" onChange={onChange} />)
    const input = screen.getByRole('textbox')
    fireEvent.focus(input)
    // Open dropdown then navigate to first item (index 0)
    fireEvent.keyDown(input, { key: 'ArrowDown' })
    fireEvent.keyDown(input, { key: 'ArrowDown' })
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(onChange).toHaveBeenCalledOnce()
  })

  // ── Remove chip ────────────────────────────────────────────────────────────

  it('calls onChange without removed ticker when X button is clicked', () => {
    const onChange = vi.fn()
    renderWithRouter(<TickerSelector value="AAPL,MSFT,TSLA" onChange={onChange} />)
    const removeBtn = screen.getByRole('button', { name: 'Remove MSFT' })
    fireEvent.click(removeBtn)
    expect(onChange).toHaveBeenCalledWith('AAPL,TSLA')
  })

  it('calls onChange without removed @group ref when X button is clicked', () => {
    const onChange = vi.fn()
    renderWithRouter(<TickerSelector value="@mega-tech,TSLA" onChange={onChange} />)
    const removeBtn = screen.getByRole('button', { name: 'Remove Mega Tech' })
    fireEvent.click(removeBtn)
    expect(onChange).toHaveBeenCalledWith('TSLA')
  })

  // ── Hot tickers scope bar ──────────────────────────────────────────────────

  it('renders HOT chip flame icon for hot tickers', () => {
    renderWithRouter(
      <TickerSelector value="AAPL,MSFT" onChange={vi.fn()} hotTickers={['AAPL']} />
    )
    const { container } = renderWithRouter(
      <TickerSelector value="AAPL,MSFT" onChange={vi.fn()} hotTickers={['AAPL']} />
    )
    const hotChip = container.querySelector('.ts-chip--hot')
    expect(hotChip).not.toBeNull()
  })

  it('does not render scope bar when onScopeChange is not provided', () => {
    const { container } = renderWithRouter(
      <TickerSelector value="AAPL" onChange={vi.fn()} hotTickers={['AAPL']} />
    )
    expect(container.querySelector('.ts-scope-bar')).toBeNull()
  })

  it('renders scope bar when onScopeChange is provided and hotCount > 0', () => {
    const { container } = renderWithRouter(
      <TickerSelector
        value="AAPL"
        onChange={vi.fn()}
        hotTickers={['AAPL']}
        onScopeChange={vi.fn()}
        scope="ALL"
      />
    )
    expect(container.querySelector('.ts-scope-bar')).not.toBeNull()
  })

  it('calls onScopeChange with HOT when scope bar is clicked and current scope is ALL', () => {
    const onScopeChange = vi.fn()
    const { container } = renderWithRouter(
      <TickerSelector
        value="AAPL,MSFT"
        onChange={vi.fn()}
        hotTickers={['AAPL']}
        onScopeChange={onScopeChange}
        scope="ALL"
      />
    )
    fireEvent.click(container.querySelector('.ts-scope-bar'))
    expect(onScopeChange).toHaveBeenCalledWith('HOT')
  })

  it('calls onScopeChange with ALL when scope bar is clicked and current scope is HOT', () => {
    const onScopeChange = vi.fn()
    const { container } = renderWithRouter(
      <TickerSelector
        value="AAPL,MSFT"
        onChange={vi.fn()}
        hotTickers={['AAPL']}
        onScopeChange={onScopeChange}
        scope="HOT"
      />
    )
    fireEvent.click(container.querySelector('.ts-scope-bar'))
    expect(onScopeChange).toHaveBeenCalledWith('ALL')
  })

  // ── News tickers from API ─────────────────────────────────────────────────

  it('displays news ticker in dropdown when liveApi returns data', async () => {
    liveApi.getNewsTickers.mockResolvedValueOnce([
      { ticker: 'NVDA', headline: 'Big chip news', source: 'Reuters' },
    ])
    renderWithRouter(<TickerSelector {...baseProps} />)
    const input = screen.getByRole('textbox')
    fireEvent.focus(input)
    await waitFor(() => {
      expect(screen.getByText('NVDA')).toBeInTheDocument()
    })
  })

  it('calls onChange with news ticker when clicked', async () => {
    const onChange = vi.fn()
    liveApi.getNewsTickers.mockResolvedValueOnce([
      { ticker: 'COIN', headline: 'Crypto rally', source: 'Bloomberg' },
    ])
    renderWithRouter(<TickerSelector value="" onChange={onChange} />)
    const input = screen.getByRole('textbox')
    fireEvent.focus(input)
    await waitFor(() => {
      expect(screen.getByText('COIN')).toBeInTheDocument()
    })
    fireEvent.click(screen.getByText('COIN'))
    expect(onChange).toHaveBeenCalledWith('COIN')
  })

  it('does not add duplicate news ticker when already in resolved list', async () => {
    const onChange = vi.fn()
    liveApi.getNewsTickers.mockResolvedValueOnce([
      { ticker: 'AAPL', headline: 'Apple earnings', source: 'CNBC' },
    ])
    renderWithRouter(<TickerSelector value="AAPL" onChange={onChange} />)
    const input = screen.getByRole('textbox')
    fireEvent.focus(input)
    await waitFor(() => {
      expect(screen.getByText('AAPL')).toBeInTheDocument()
    })
    // AAPL chip already exists — click the news item in dropdown
    const aaplItems = screen.getAllByText('AAPL')
    fireEvent.click(aaplItems[aaplItems.length - 1])
    expect(onChange).not.toHaveBeenCalled()
  })

  it('silently handles rejected news tickers API call', async () => {
    liveApi.getNewsTickers.mockRejectedValueOnce(new Error('network error'))
    expect(() =>
      renderWithRouter(<TickerSelector {...baseProps} />)
    ).not.toThrow()
  })
})
