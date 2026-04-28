import React from 'react'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import SaveListPopover from './SaveListPopover'

const TICKERS = ['AAPL', 'NVDA', 'TSLA', 'SPY', 'QQQ', 'MSFT', 'AMZN', 'META']

function renderPopover(overrides = {}) {
  const props = {
    disabled: false,
    resolvedTickers: TICKERS,
    onSave: vi.fn(),
    ...overrides,
  }
  return { ...render(<SaveListPopover {...props} />), onSave: props.onSave }
}

describe('SaveListPopover', () => {
  it('renders closed — trigger visible, popover absent', () => {
    renderPopover()
    expect(screen.getByRole('button', { name: /guardar como watchlist/i })).toBeInTheDocument()
    expect(screen.queryByPlaceholderText(/nombre de la lista/i)).not.toBeInTheDocument()
  })

  it('opens on trigger click and focuses the input', async () => {
    renderPopover()
    fireEvent.click(screen.getByRole('button', { name: /guardar como watchlist/i }))
    const input = await screen.findByPlaceholderText(/nombre de la lista/i)
    expect(input).toBeInTheDocument()
    expect(document.activeElement).toBe(input)
  })

  it('closes and resets on Cancelar click', async () => {
    renderPopover()
    fireEvent.click(screen.getByRole('button', { name: /guardar como watchlist/i }))
    await screen.findByPlaceholderText(/nombre de la lista/i)
    fireEvent.click(screen.getByRole('button', { name: /cancelar/i }))
    expect(screen.queryByPlaceholderText(/nombre de la lista/i)).not.toBeInTheDocument()
  })

  it('closes on click outside the wrapper', async () => {
    renderPopover()
    fireEvent.click(screen.getByRole('button', { name: /guardar como watchlist/i }))
    await screen.findByPlaceholderText(/nombre de la lista/i)
    fireEvent.mouseDown(document.body)
    await waitFor(() =>
      expect(screen.queryByPlaceholderText(/nombre de la lista/i)).not.toBeInTheDocument()
    )
  })

  it('keeps Guardar disabled when input is empty', async () => {
    renderPopover()
    fireEvent.click(screen.getByRole('button', { name: /guardar como watchlist/i }))
    await screen.findByPlaceholderText(/nombre de la lista/i)
    expect(screen.getByRole('button', { name: /^guardar$/i })).toBeDisabled()
  })

  it('calls onSave with (name, tickers) and closes on Guardar click', async () => {
    const { onSave } = renderPopover()
    fireEvent.click(screen.getByRole('button', { name: /guardar como watchlist/i }))
    const input = await screen.findByPlaceholderText(/nombre de la lista/i)
    fireEvent.change(input, { target: { value: 'Mi lista' } })
    fireEvent.click(screen.getByRole('button', { name: /^guardar$/i }))
    expect(onSave).toHaveBeenCalledWith('Mi lista', TICKERS)
    expect(screen.queryByPlaceholderText(/nombre de la lista/i)).not.toBeInTheDocument()
  })

  it('disables trigger button when disabled prop is true', () => {
    renderPopover({ disabled: true })
    expect(screen.getByRole('button', { name: /guardar como watchlist/i })).toBeDisabled()
  })
})
