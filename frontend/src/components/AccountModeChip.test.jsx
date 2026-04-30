import React from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import AccountModeChip from './AccountModeChip'

vi.mock('../api', () => ({
  accountApi: {
    getMode: vi.fn(),
  },
}))

import { accountApi } from '../api'

describe('AccountModeChip', () => {
  afterEach(() => {
    vi.clearAllMocks()
  })

  it('renders nothing while loading (mode is null)', () => {
    accountApi.getMode.mockReturnValue(new Promise(() => {}))
    const { container } = render(<AccountModeChip />)
    expect(container.firstChild).toBeNull()
  })

  it('renders nothing when API returns ERROR mode', async () => {
    accountApi.getMode.mockRejectedValue(new Error('network'))
    const { container } = render(<AccountModeChip />)
    await waitFor(() => {
      expect(container.firstChild).toBeNull()
    })
  })

  it('renders PAPER chip with correct label and aria-label', async () => {
    accountApi.getMode.mockResolvedValue({ mode: 'PAPER', accountId: 'DU1234567' })
    render(<AccountModeChip />)
    const chip = await screen.findByRole('generic', { hidden: true })
    const span = await screen.findByLabelText('Trading mode: PAPER')
    expect(span).toBeInTheDocument()
    expect(span.textContent).toContain('PAPER')
  })

  it('renders LIVE chip with correct label and aria-label', async () => {
    accountApi.getMode.mockResolvedValue({ mode: 'LIVE', accountId: 'U9876543' })
    render(<AccountModeChip />)
    const span = await screen.findByLabelText('Trading mode: LIVE')
    expect(span).toBeInTheDocument()
    expect(span.textContent).toContain('LIVE')
  })

  it('sets title to Account: <accountId> when accountId is present', async () => {
    accountApi.getMode.mockResolvedValue({ mode: 'PAPER', accountId: 'DU111' })
    render(<AccountModeChip />)
    const span = await screen.findByLabelText('Trading mode: PAPER')
    expect(span.title).toBe('Account: DU111')
  })

  it('sets title to "Account unknown" when accountId is absent', async () => {
    accountApi.getMode.mockResolvedValue({ mode: 'LIVE', accountId: '' })
    render(<AccountModeChip />)
    const span = await screen.findByLabelText('Trading mode: LIVE')
    expect(span.title).toBe('Account unknown')
  })

  it('applies paper CSS class for PAPER mode', async () => {
    accountApi.getMode.mockResolvedValue({ mode: 'PAPER', accountId: 'DU1' })
    render(<AccountModeChip />)
    const span = await screen.findByLabelText('Trading mode: PAPER')
    expect(span.className).toContain('account-mode-chip--paper')
  })

  it('applies live CSS class for LIVE mode', async () => {
    accountApi.getMode.mockResolvedValue({ mode: 'LIVE', accountId: 'U1' })
    render(<AccountModeChip />)
    const span = await screen.findByLabelText('Trading mode: LIVE')
    expect(span.className).toContain('account-mode-chip--live')
  })
})
