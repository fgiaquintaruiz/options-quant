import React from 'react'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { vi, describe, it, expect, beforeEach, afterEach } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import StrategiesPage, { sortStrategiesForTest } from './StrategiesPage'
import { strategyConfigApi } from '../api'

vi.mock('../api', () => ({
  strategyConfigApi: {
    findAll: vi.fn(),
    update:  vi.fn(),
  }
}))

const MOCK_STRATEGIES = [
  { name: 'p6_reversal', enabledLive: true,  enabledBacktest: true  },
  { name: 'p1_squeeze',  enabledLive: true,  enabledBacktest: false },
  { name: 'c6_reversal', enabledLive: false, enabledBacktest: true  },
  { name: 'm2_momentum', enabledLive: false, enabledBacktest: false },
]

const renderPage = () => render(<MemoryRouter><StrategiesPage /></MemoryRouter>)

// ── Setup / Teardown ──────────────────────────────────────────────────────────

beforeEach(() => {
  strategyConfigApi.findAll.mockResolvedValue([...MOCK_STRATEGIES])
  strategyConfigApi.update.mockResolvedValue({})
})

afterEach(() => {
  vi.clearAllMocks()
})

// ═════════════════════════════════════════════════════════════════════════════
// Loading and error states
// ═════════════════════════════════════════════════════════════════════════════

describe('StrategiesPage', () => {

  describe('Loading and error states', () => {
    it('renders loading state initially', () => {
      strategyConfigApi.findAll.mockReturnValue(new Promise(() => {}))
      renderPage()
      expect(screen.getByText(/Cargando\.\.\./i)).toBeInTheDocument()
    })

    it('renders error state on fetch failure', async () => {
      strategyConfigApi.findAll.mockRejectedValue(new Error('Network error'))
      renderPage()
      await waitFor(() => {
        expect(screen.getByText(/Network error/i)).toBeInTheDocument()
      })
    })
  })

  // ═══════════════════════════════════════════════════════════════════════════
  // Data rendering
  // ═══════════════════════════════════════════════════════════════════════════

  describe('Data rendering', () => {
    it('renders strategy table after fetch', async () => {
      renderPage()
      await waitFor(() => {
        expect(screen.getByText('p6_reversal')).toBeInTheDocument()
        expect(screen.getByText('p1_squeeze')).toBeInTheDocument()
        expect(screen.getByText('c6_reversal')).toBeInTheDocument()
        expect(screen.getByText('m2_momentum')).toBeInTheDocument()
      })
    })

    it('groups live-active strategies first', async () => {
      renderPage()
      await waitFor(() => {
        const rows = screen.getAllByRole('row')
        // find data rows (skip header rows)
        const dataRows = rows.filter(r => r.querySelector('[data-testid^="strategy-live-"]'))
        const names = dataRows.map(r => r.querySelector('[data-testid^="strategy-live-"]').getAttribute('data-testid').replace('strategy-live-', ''))
        // live-active (p6_reversal, p1_squeeze) must come before live-disabled (c6_reversal, m2_momentum)
        const p6idx = names.indexOf('p6_reversal')
        const p1idx = names.indexOf('p1_squeeze')
        const c6idx = names.indexOf('c6_reversal')
        const m2idx = names.indexOf('m2_momentum')
        expect(p6idx).toBeLessThan(c6idx)
        expect(p1idx).toBeLessThan(c6idx)
        expect(p6idx).toBeLessThan(m2idx)
        expect(p1idx).toBeLessThan(m2idx)
      })
    })

    it('renders correct count in header', async () => {
      renderPage()
      await waitFor(() => {
        expect(screen.getByText(/2 activas/i)).toBeInTheDocument()
      })
    })
  })

  // ═══════════════════════════════════════════════════════════════════════════
  // Live toggle behavior
  // ═══════════════════════════════════════════════════════════════════════════

  describe('Live toggle behavior', () => {
    it('toggle live OFF→ON opens confirm dialog', async () => {
      renderPage()
      await waitFor(() => screen.getByTestId('strategy-live-c6_reversal'))
      fireEvent.click(screen.getByTestId('strategy-live-c6_reversal'))
      expect(screen.getByRole('dialog')).toBeInTheDocument()
    })

    it('toggle live ON→OFF calls api directly, no dialog', async () => {
      strategyConfigApi.update.mockResolvedValue({ name: 'p6_reversal', enabledLive: false, enabledBacktest: true })
      renderPage()
      await waitFor(() => screen.getByTestId('strategy-live-p6_reversal'))
      fireEvent.click(screen.getByTestId('strategy-live-p6_reversal'))
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
      await waitFor(() => {
        expect(strategyConfigApi.update).toHaveBeenCalledWith('p6_reversal', { enabledLive: false })
      })
    })

    it('confirm dialog Cancel does not call api', async () => {
      renderPage()
      await waitFor(() => screen.getByTestId('strategy-live-c6_reversal'))
      fireEvent.click(screen.getByTestId('strategy-live-c6_reversal'))
      fireEvent.click(screen.getByRole('button', { name: /Cancelar/i }))
      expect(strategyConfigApi.update).not.toHaveBeenCalled()
    })

    it('confirm dialog Confirm calls api with correct body', async () => {
      strategyConfigApi.update.mockResolvedValue({ name: 'c6_reversal', enabledLive: true, enabledBacktest: true })
      renderPage()
      await waitFor(() => screen.getByTestId('strategy-live-c6_reversal'))
      fireEvent.click(screen.getByTestId('strategy-live-c6_reversal'))
      fireEvent.click(screen.getByRole('button', { name: /Confirmar/i }))
      await waitFor(() => {
        expect(strategyConfigApi.update).toHaveBeenCalledWith('c6_reversal', { enabledLive: true })
      })
    })

    it('optimistic update reflects immediately', async () => {
      let resolveUpdate
      strategyConfigApi.update.mockReturnValue(new Promise(res => { resolveUpdate = res }))
      renderPage()
      await waitFor(() => screen.getByTestId('strategy-live-p6_reversal'))
      // p6_reversal is ON — toggling OFF should show change before API resolves
      fireEvent.click(screen.getByTestId('strategy-live-p6_reversal'))
      // The button text should reflect the new state immediately (before API resolves)
      // SwapButton: active=true shows onText, active=false shows offText
      // After optimistic: p6_reversal enabledLive becomes false → button shows "OFF" state text
      const btn = screen.getByTestId('strategy-live-p6_reversal')
      expect(btn.textContent).toMatch(/Activar Live|OFF|Activate/i)
      resolveUpdate({ name: 'p6_reversal', enabledLive: false, enabledBacktest: true })
    })

    it('reverts optimistic update on api error', async () => {
      strategyConfigApi.update.mockRejectedValue(new Error('API error'))
      renderPage()
      await waitFor(() => screen.getByTestId('strategy-live-p6_reversal'))
      // p6_reversal is ON — toggle OFF → optimistic → error → revert to ON
      fireEvent.click(screen.getByTestId('strategy-live-p6_reversal'))
      // After revert the button should show active (ON) state again
      await waitFor(() => {
        const btn = screen.getByTestId('strategy-live-p6_reversal')
        expect(btn.textContent).toMatch(/Desactivar Live|ON|Active/i)
      })
    })
  })

  // ═══════════════════════════════════════════════════════════════════════════
  // Backtest toggle behavior
  // ═══════════════════════════════════════════════════════════════════════════

  describe('Backtest toggle behavior', () => {
    it('toggle backtest calls api directly, no dialog', async () => {
      strategyConfigApi.update.mockResolvedValue({ name: 'p6_reversal', enabledLive: true, enabledBacktest: false })
      renderPage()
      await waitFor(() => screen.getByTestId('strategy-backtest-p6_reversal'))
      fireEvent.click(screen.getByTestId('strategy-backtest-p6_reversal'))
      await waitFor(() => {
        expect(strategyConfigApi.update).toHaveBeenCalledWith('p6_reversal', { enabledBacktest: false })
      })
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })

    it('toggle backtest optimistic update', async () => {
      let resolveUpdate
      strategyConfigApi.update.mockReturnValue(new Promise(res => { resolveUpdate = res }))
      renderPage()
      await waitFor(() => screen.getByTestId('strategy-backtest-p1_squeeze'))
      // p1_squeeze has enabledBacktest: false — clicking should optimistically check it
      const checkbox = screen.getByTestId('strategy-backtest-p1_squeeze')
      expect(checkbox.checked).toBe(false)
      fireEvent.click(checkbox)
      expect(screen.getByTestId('strategy-backtest-p1_squeeze').checked).toBe(true)
      resolveUpdate({ name: 'p1_squeeze', enabledLive: true, enabledBacktest: true })
    })
  })

  // ═══════════════════════════════════════════════════════════════════════════
  // Defensive sort — undefined name
  // ═══════════════════════════════════════════════════════════════════════════

  describe('Defensive sort — undefined name', () => {
    it('sortStrategiesForTest does not throw when a strategy has name undefined', () => {
      const list = [
        { name: undefined,     enabledLive: false, enabledBacktest: false },
        { name: 'p6_reversal', enabledLive: true,  enabledBacktest: true  },
      ]
      expect(() => sortStrategiesForTest(list)).not.toThrow()
    })
  })

  // ═══════════════════════════════════════════════════════════════════════════
  // Accessibility and testIds
  // ═══════════════════════════════════════════════════════════════════════════

  describe('Accessibility and testIds', () => {
    it('live toggles have correct testIds', async () => {
      renderPage()
      await waitFor(() => {
        MOCK_STRATEGIES.forEach(s => {
          expect(screen.getByTestId(`strategy-live-${s.name}`)).toBeInTheDocument()
        })
      })
    })

    it('dialog has accessible role', async () => {
      renderPage()
      await waitFor(() => screen.getByTestId('strategy-live-c6_reversal'))
      fireEvent.click(screen.getByTestId('strategy-live-c6_reversal'))
      const dialog = screen.getByRole('dialog')
      expect(dialog).toBeInTheDocument()
    })
  })
})
