import React from 'react'
import { render, screen, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, it, expect, vi } from 'vitest'
import AccountMenuDropdown from './AccountMenuDropdown'

const baseProps = {
  twsStatus: { connected: true, accountId: 'DU1234567', balance: 25000 },
  accountMode: 'PAPER',
  now: new Date('2026-01-01T10:30:00'),
}

describe('AccountMenuDropdown', () => {
  it('renders the trigger button', () => {
    render(<AccountMenuDropdown {...baseProps} />)
    expect(screen.getByTestId('account-menu-trigger')).toBeInTheDocument()
  })

  it('dropdown is hidden initially', () => {
    render(<AccountMenuDropdown {...baseProps} />)
    expect(screen.queryByTestId('account-menu-dropdown')).not.toBeInTheDocument()
  })

  it('opens dropdown on trigger click', async () => {
    render(<AccountMenuDropdown {...baseProps} />)
    await userEvent.click(screen.getByTestId('account-menu-trigger'))
    expect(screen.getByTestId('account-menu-dropdown')).toBeInTheDocument()
  })

  it('shows account ID in dropdown', async () => {
    render(<AccountMenuDropdown {...baseProps} />)
    await userEvent.click(screen.getByTestId('account-menu-trigger'))
    expect(screen.getByTestId('account-menu-dropdown').textContent).toContain('DU1234567')
  })

  it('shows account mode in dropdown', async () => {
    render(<AccountMenuDropdown {...baseProps} />)
    await userEvent.click(screen.getByTestId('account-menu-trigger'))
    expect(screen.getByTestId('account-menu-dropdown').textContent).toContain('PAPER')
  })

  it('shows balance in dropdown when balance > 0', async () => {
    render(<AccountMenuDropdown {...baseProps} />)
    await userEvent.click(screen.getByTestId('account-menu-trigger'))
    expect(screen.getByTestId('account-menu-dropdown').textContent).toContain('25,000')
  })

  it('shows TWS connected badge in dropdown', async () => {
    render(<AccountMenuDropdown {...baseProps} />)
    await userEvent.click(screen.getByTestId('account-menu-trigger'))
    expect(screen.getByTestId('account-menu-dropdown').textContent).toContain('TWS')
  })

  it('closes dropdown on second trigger click', async () => {
    render(<AccountMenuDropdown {...baseProps} />)
    await userEvent.click(screen.getByTestId('account-menu-trigger'))
    await userEvent.click(screen.getByTestId('account-menu-trigger'))
    expect(screen.queryByTestId('account-menu-dropdown')).not.toBeInTheDocument()
  })

  it('renders without crashing when twsStatus is null', () => {
    render(<AccountMenuDropdown {...baseProps} twsStatus={null} />)
    expect(screen.getByTestId('account-menu-trigger')).toBeInTheDocument()
  })

  it('does not show balance when balance is 0', async () => {
    render(<AccountMenuDropdown {...baseProps} twsStatus={{ connected: true, accountId: 'DU1234567', balance: 0 }} />)
    await userEvent.click(screen.getByTestId('account-menu-trigger'))
    expect(screen.queryByTestId('account-balance')).not.toBeInTheDocument()
  })
})
