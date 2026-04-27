import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import HotTickerCountEditor from './HotTickerCountEditor'
import { tickerConfigApi } from '../api'

vi.mock('../api', () => ({
  tickerConfigApi: {
    getHotTickerCount: vi.fn(),
    setHotTickerCount: vi.fn(),
  },
}))

describe('HotTickerCountEditor', () => {
  beforeEach(() => {
    tickerConfigApi.getHotTickerCount.mockResolvedValue({
      effectiveCount: 20,
      ymlDefault: 20,
      runtimeOverride: null,
    })
    tickerConfigApi.setHotTickerCount.mockResolvedValue({ success: true, effectiveCount: 20 })
  })

  afterEach(() => {
    vi.clearAllMocks()
  })

  // ── Load ──────────────────────────────────────────────────────────────────

  it('loads and displays the effective count on mount', async () => {
    render(<HotTickerCountEditor />)
    await waitFor(() => {
      const input = screen.getByRole('spinbutton', { name: /hot ticker count/i })
      expect(input.value).toBe('20')
    })
  })

  it('shows yml default in label when set', async () => {
    render(<HotTickerCountEditor />)
    await waitFor(() => {
      expect(screen.getByText(/default YAML: 20/i)).toBeTruthy()
    })
  })

  it('shows load error when API fails', async () => {
    tickerConfigApi.getHotTickerCount.mockRejectedValue(new Error('network error'))
    render(<HotTickerCountEditor />)
    await waitFor(() => {
      expect(screen.getByText(/network error/i)).toBeTruthy()
    })
  })

  // ── Validation ────────────────────────────────────────────────────────────

  it('shows error and disables save when value is 0', async () => {
    render(<HotTickerCountEditor />)
    await waitFor(() => screen.getByRole('spinbutton'))

    fireEvent.change(screen.getByRole('spinbutton'), { target: { value: '0' } })

    const saveBtn = screen.getByRole('button', { name: /guardar/i })
    expect(saveBtn).toBeDisabled()
    expect(screen.getByText(/entre 1 y 100/i)).toBeTruthy()
  })

  it('shows error and disables save when value is 101', async () => {
    render(<HotTickerCountEditor />)
    await waitFor(() => screen.getByRole('spinbutton'))

    fireEvent.change(screen.getByRole('spinbutton'), { target: { value: '101' } })

    expect(screen.getByRole('button', { name: /guardar/i })).toBeDisabled()
    expect(screen.getByText(/entre 1 y 100/i)).toBeTruthy()
  })

  it('does NOT call API when value is invalid', async () => {
    render(<HotTickerCountEditor />)
    await waitFor(() => screen.getByRole('spinbutton'))

    fireEvent.change(screen.getByRole('spinbutton'), { target: { value: '0' } })
    fireEvent.click(screen.getByRole('button', { name: /guardar/i }))

    expect(tickerConfigApi.setHotTickerCount).not.toHaveBeenCalled()
  })

  it('valid value 1 enables save button', async () => {
    render(<HotTickerCountEditor />)
    await waitFor(() => screen.getByRole('spinbutton'))

    fireEvent.change(screen.getByRole('spinbutton'), { target: { value: '1' } })

    expect(screen.getByRole('button', { name: /guardar/i })).not.toBeDisabled()
  })

  it('valid value 100 enables save button', async () => {
    render(<HotTickerCountEditor />)
    await waitFor(() => screen.getByRole('spinbutton'))

    fireEvent.change(screen.getByRole('spinbutton'), { target: { value: '100' } })

    expect(screen.getByRole('button', { name: /guardar/i })).not.toBeDisabled()
  })

  // ── Save success ──────────────────────────────────────────────────────────

  it('calls setHotTickerCount with numeric value on save', async () => {
    render(<HotTickerCountEditor />)
    await waitFor(() => screen.getByRole('spinbutton'))

    fireEvent.change(screen.getByRole('spinbutton'), { target: { value: '25' } })
    fireEvent.click(screen.getByRole('button', { name: /guardar/i }))

    await waitFor(() => {
      expect(tickerConfigApi.setHotTickerCount).toHaveBeenCalledWith(25)
    })
  })

  it('shows success badge after successful save', async () => {
    render(<HotTickerCountEditor />)
    await waitFor(() => screen.getByRole('spinbutton'))

    fireEvent.change(screen.getByRole('spinbutton'), { target: { value: '15' } })
    fireEvent.click(screen.getByRole('button', { name: /guardar/i }))

    await waitFor(() => {
      expect(screen.getByText(/guardado/i)).toBeTruthy()
    })
  })

  // ── Save error ────────────────────────────────────────────────────────────

  it('shows error message when API save fails', async () => {
    tickerConfigApi.setHotTickerCount.mockRejectedValue(new Error('HTTP 500: server error'))
    render(<HotTickerCountEditor />)
    await waitFor(() => screen.getByRole('spinbutton'))

    fireEvent.change(screen.getByRole('spinbutton'), { target: { value: '10' } })
    fireEvent.click(screen.getByRole('button', { name: /guardar/i }))

    await waitFor(() => {
      expect(screen.getByText(/HTTP 500: server error/i)).toBeTruthy()
    })
  })
})
