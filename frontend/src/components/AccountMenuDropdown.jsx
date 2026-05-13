import React, { useState, useEffect, useRef } from 'react'

/**
 * Compact dropdown that surfaces account info from the header status bar.
 * Shows: account ID, PAPER/LIVE mode, balance, TWS connection badge.
 * The clock stays in the main header — only account-related info lives here.
 */
export default function AccountMenuDropdown({ twsStatus, accountMode, now }) {
  const [open, setOpen] = useState(false)
  const ref = useRef(null)

  useEffect(() => {
    if (!open) return
    const handler = (e) => {
      if (ref.current && !ref.current.contains(e.target)) setOpen(false)
    }
    document.addEventListener('mousedown', handler)
    return () => document.removeEventListener('mousedown', handler)
  }, [open])

  const connected = twsStatus?.connected ?? false
  const accountId = twsStatus?.accountId ?? null
  const balance   = twsStatus?.balance   ?? 0
  const isPaper   = accountMode === 'PAPER'

  return (
    <div className="amd-wrap" ref={ref} style={{ position: 'relative' }}>
      <button
        type="button"
        data-testid="account-menu-trigger"
        className="amd-trigger"
        onClick={() => setOpen((v) => !v)}
        aria-expanded={open}
        aria-haspopup="true"
      >
        <span className={`badge ${connected ? 'badge-success' : 'badge-error'} amd-tws-mini`}>
          {connected ? '🔌' : '🔐'} TWS
        </span>
        {accountId && <span className="amd-trigger-account">{accountId}</span>}
        <span className="amd-chevron">{open ? '▲' : '▼'}</span>
      </button>

      {open && (
        <div
          className="amd-dropdown"
          data-testid="account-menu-dropdown"
          role="menu"
        >
          <div className="amd-section">
            <span className="amd-label">Connection</span>
            <span className={`badge ${connected ? 'badge-success' : 'badge-error'}`}>
              {connected ? '🔌 TWS connected' : '🔐 TWS disconnected'}
            </span>
          </div>

          {accountId && (
            <div className="amd-section">
              <span className="amd-label">Account</span>
              <strong className="pill pill-info amd-account-id">{accountId}</strong>
            </div>
          )}

          {accountMode && (
            <div className="amd-section">
              <span className="amd-label">Mode</span>
              <span className={`account-mode-chip ${isPaper ? 'account-mode-chip--paper' : 'account-mode-chip--live'}`}>
                {accountMode}
              </span>
            </div>
          )}

          {balance > 0 && (
            <div className="amd-section" data-testid="account-balance">
              <span className="amd-label">Balance</span>
              <strong className="color-success">${Number(balance).toLocaleString()}</strong>
            </div>
          )}
        </div>
      )}
    </div>
  )
}
