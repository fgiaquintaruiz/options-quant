import React from 'react'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi } from 'vitest'
import { MemoryRouter } from 'react-router-dom'
import App from './App'

// ── API mocks ─────────────────────────────────────────────────────────────────

vi.mock('./api', () => ({
  liveApi: {
    getTwsStatus: vi.fn().mockResolvedValue(null),
    getStatus:    vi.fn().mockResolvedValue(null),
    getMarketStatus: vi.fn().mockResolvedValue(null),
  },
  accountApi: {
    getMode: vi.fn().mockResolvedValue(null),
  },
}))

// ── Hook mocks ────────────────────────────────────────────────────────────────

vi.mock('./hooks/useStorageBackup', () => ({
  useStorageBackup: vi.fn(),
}))

// ── Page mocks (avoid real fetch / complex rendering) ─────────────────────────

vi.mock('./pages/LiveDashboard', () => ({
  default: () => <div data-testid="live-dashboard-mock">Live</div>,
}))

vi.mock('./pages/BacktestDashboard', () => ({
  default: () => <div data-testid="backtest-dashboard-mock">Backtest</div>,
}))

vi.mock('./pages/BacktestHistoryPage', () => ({
  default: () => <div data-testid="backtest-history-mock">History</div>,
}))

vi.mock('./pages/SettingsPage', () => ({
  default: () => <div data-testid="settings-page-mock">Settings</div>,
}))

vi.mock('./pages/HealthPage', () => ({
  default: () => <div data-testid="health-page-mock">Health</div>,
}))

vi.mock('./pages/StrategiesPage', () => ({
  default: () => <div data-testid="strategies-page-mock">Strategies</div>,
}))

// ── Helpers ───────────────────────────────────────────────────────────────────

const renderApp = (initialPath = '/live') =>
  render(
    <MemoryRouter initialEntries={[initialPath]}>
      <App />
    </MemoryRouter>
  )

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('Strategies route', () => {
  it('strategies route exists and renders StrategiesPage', () => {
    renderApp('/strategies')
    expect(screen.getByTestId('strategies-page-mock')).toBeInTheDocument()
  })

  it('strategies NavLink exists in navbar', () => {
    renderApp('/live')
    expect(screen.getByTestId('navlink-strategies')).toBeInTheDocument()
  })

  it('strategies NavLink has correct href', () => {
    renderApp('/live')
    expect(screen.getByTestId('navlink-strategies')).toHaveAttribute('href', '/strategies')
  })

  it('strategies NavLink navigates correctly on click', async () => {
    const user = userEvent.setup()
    renderApp('/live')
    await user.click(screen.getByTestId('navlink-strategies'))
    expect(screen.getByTestId('strategies-page-mock')).toBeInTheDocument()
  })

  it('strategies NavLink gets active class when on /strategies', () => {
    renderApp('/strategies')
    expect(screen.getByTestId('navlink-strategies')).toHaveClass('active')
  })
})
