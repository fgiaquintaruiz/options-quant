import React from 'react'
import { render, screen, fireEvent, act } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

// Mock the api module so loadFundamentals never hits the network.
// The mock is defined at module scope, but individual tests can override
// tickerConfigApi.get via mockResolvedValue / mockRejectedValue.
vi.mock('../api', () => ({
  tickerConfigApi: {
    get: vi.fn(),
  },
}))

import { tickerConfigApi } from '../api'

// ── Shared fixture ────────────────────────────────────────────────────────────

const FUNDAMENTALS_RESPONSE = {
  fundamentals: {
    AAPL: {
      companyName: 'Apple Inc.',
      sector: 'Technology',
      marketCapBillion: 3200,
      peRatio: 28.5,
      beta: 1.2,
      epsGrowth: 10.5,
      revenueGrowth: 8.3,
      dividendYield: 0.5,
      debtToEquity: 1.7,
      roic: 45.2,
      notes: 'Top holding',
    },
    // NODATA has a companyName but no numeric fields → rows[] will be empty
    NODATA: {
      companyName: 'NoData Corp',
    },
  },
}

// ── Helpers ───────────────────────────────────────────────────────────────────

function hoverIn(element) {
  fireEvent.mouseEnter(element)
}

function hoverOut(element) {
  fireEvent.mouseLeave(element)
}

// Dynamically import TickerTooltip so each describe block gets a fresh module
// (including a clean fundamentalsCache / loadPromise).
async function importFresh() {
  // Reset the module registry so the next import re-executes the module.
  vi.resetModules()
  // Re-apply the api mock after reset so the fresh module still uses the mock.
  vi.mock('../api', () => ({ tickerConfigApi: { get: vi.fn() } }))
  const mod = await import('./TickerTooltip')
  return mod.default
}

// ── Main suite (warm-cache scenarios) ────────────────────────────────────────

describe('TickerTooltip', () => {
  // We load a fresh TickerTooltip once per describe block so the internal
  // module-level cache starts empty for this group.
  let TickerTooltip

  beforeEach(async () => {
    vi.useFakeTimers()
    tickerConfigApi.get.mockResolvedValue(FUNDAMENTALS_RESPONSE)
    TickerTooltip = await importFresh()
  })

  afterEach(() => {
    vi.clearAllMocks()
    vi.useRealTimers()
  })

  // ── No tooltip initially ──────────────────────────────────────────────────

  it('renders children without showing tooltip initially', () => {
    render(
      <TickerTooltip ticker="AAPL">
        <strong>AAPL</strong>
      </TickerTooltip>
    )
    expect(screen.getByText('AAPL')).toBeInTheDocument()
    expect(screen.queryByText('Apple Inc.')).not.toBeInTheDocument()
  })

  // ── Tooltip visible after hover ───────────────────────────────────────────

  it('shows the tooltip with fundamental data after mouseenter', async () => {
    const { container } = render(
      <TickerTooltip ticker="AAPL">
        <strong>AAPL</strong>
      </TickerTooltip>
    )
    const wrapper = container.firstChild

    await act(async () => {
      hoverIn(wrapper)
      await Promise.resolve()
    })

    expect(screen.getByText('Apple Inc.')).toBeInTheDocument()
    expect(screen.getByText('Technology')).toBeInTheDocument()
  })

  it('shows Sector, Mkt Cap, P/E, Beta rows when data is present', async () => {
    const { container } = render(
      <TickerTooltip ticker="AAPL">
        <strong>AAPL</strong>
      </TickerTooltip>
    )
    const wrapper = container.firstChild

    await act(async () => {
      hoverIn(wrapper)
      await Promise.resolve()
    })

    expect(screen.getByText('Sector')).toBeInTheDocument()
    expect(screen.getByText('Mkt Cap')).toBeInTheDocument()
    expect(screen.getByText('P/E')).toBeInTheDocument()
    expect(screen.getByText('Beta')).toBeInTheDocument()
  })

  it('uppercases the ticker symbol in the tooltip header', async () => {
    const { container } = render(
      <TickerTooltip ticker="aapl">
        <strong>aapl</strong>
      </TickerTooltip>
    )
    const wrapper = container.firstChild

    await act(async () => {
      hoverIn(wrapper)
      await Promise.resolve()
    })

    // AAPL appears at least once in the tooltip header
    const tickerSpans = screen.getAllByText('AAPL')
    expect(tickerSpans.length).toBeGreaterThanOrEqual(1)
  })

  it('shows company name when it differs from the ticker symbol', async () => {
    const { container } = render(
      <TickerTooltip ticker="AAPL">
        <span>AAPL</span>
      </TickerTooltip>
    )
    const wrapper = container.firstChild

    await act(async () => {
      hoverIn(wrapper)
      await Promise.resolve()
    })

    expect(screen.getByText('Apple Inc.')).toBeInTheDocument()
  })

  it('shows notes when present in fundamental data', async () => {
    const { container } = render(
      <TickerTooltip ticker="AAPL">
        <span>AAPL</span>
      </TickerTooltip>
    )
    const wrapper = container.firstChild

    await act(async () => {
      hoverIn(wrapper)
      await Promise.resolve()
    })

    expect(screen.getByText('Top holding')).toBeInTheDocument()
  })

  // ── No-data cases ─────────────────────────────────────────────────────────

  it('shows "Sin datos fundamentales" when ticker has no numeric rows', async () => {
    const { container } = render(
      <TickerTooltip ticker="NODATA">
        <span>NODATA</span>
      </TickerTooltip>
    )
    const wrapper = container.firstChild

    await act(async () => {
      hoverIn(wrapper)
      await Promise.resolve()
    })

    expect(screen.getByText('Sin datos fundamentales')).toBeInTheDocument()
  })

  it('shows "Sin datos fundamentales" for an unknown ticker', async () => {
    const { container } = render(
      <TickerTooltip ticker="XYZ">
        <span>XYZ</span>
      </TickerTooltip>
    )
    const wrapper = container.firstChild

    await act(async () => {
      hoverIn(wrapper)
      await Promise.resolve()
    })

    expect(screen.getByText('Sin datos fundamentales')).toBeInTheDocument()
  })

  // ── Hide on mouse leave ───────────────────────────────────────────────────

  it('hides the tooltip after mouseleave + 80 ms timer fires', async () => {
    const { container } = render(
      <TickerTooltip ticker="AAPL">
        <span>AAPL</span>
      </TickerTooltip>
    )
    const wrapper = container.firstChild

    // Show
    await act(async () => {
      hoverIn(wrapper)
      await Promise.resolve()
    })
    expect(screen.getByText('Apple Inc.')).toBeInTheDocument()

    // Hide
    await act(async () => {
      hoverOut(wrapper)
      vi.advanceTimersByTime(200) // beyond the 80 ms hide timeout
    })

    expect(screen.queryByText('Apple Inc.')).not.toBeInTheDocument()
  })
})

// ── API error suite — isolated module so cache starts empty ───────────────────

describe('TickerTooltip — API error', () => {
  let TickerTooltip

  beforeEach(async () => {
    vi.useFakeTimers()
    // Reject BEFORE importing so the fresh module-level loadPromise rejects
    tickerConfigApi.get.mockRejectedValue(new Error('network error'))
    TickerTooltip = await importFresh()
  })

  afterEach(() => {
    vi.clearAllMocks()
    vi.useRealTimers()
  })

  it('shows "Sin datos fundamentales" when API call fails', async () => {
    const { container } = render(
      <TickerTooltip ticker="AAPL">
        <span>AAPL</span>
      </TickerTooltip>
    )
    const wrapper = container.firstChild

    await act(async () => {
      hoverIn(wrapper)
      // Two ticks: one for the promise being created, one for its rejection
      await Promise.resolve()
      await Promise.resolve()
    })

    expect(screen.getByText('Sin datos fundamentales')).toBeInTheDocument()
  })
})
