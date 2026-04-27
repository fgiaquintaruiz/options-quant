import React, { useEffect, useState } from 'react'
import { AlertTriangle, ShieldCheck } from 'lucide-react'
import { accountApi } from '../api'

/**
 * Read-only indicator that shows whether the backend is connected to a PAPER
 * (DU* account prefix) or LIVE TWS session. Refreshes on mount; no click
 * toggle in v1 — runtime swap is deferred to the tws-mode-toggle SDD.
 */
export default function AccountModeChip() {
  const [mode, setMode] = useState(null)
  const [accountId, setAccountId] = useState('')

  useEffect(() => {
    let cancelled = false
    accountApi.getMode()
      .then((data) => { if (!cancelled) { setMode(data.mode); setAccountId(data.accountId || '') } })
      .catch(() => { if (!cancelled) { setMode('ERROR'); setAccountId('') } })
    return () => { cancelled = true }
  }, [])

  if (!mode || mode === 'ERROR') return null

  const isPaper = mode === 'PAPER'
  const label = isPaper ? 'PAPER' : 'LIVE'
  const title = accountId ? `Account: ${accountId}` : 'Account unknown'
  const Icon = isPaper ? ShieldCheck : AlertTriangle
  const cls = isPaper ? 'account-mode-chip account-mode-chip--paper' : 'account-mode-chip account-mode-chip--live'

  return (
    <span className={cls} title={title} aria-label={`Trading mode: ${label}`}>
      <Icon size={13} /> {label}
    </span>
  )
}
