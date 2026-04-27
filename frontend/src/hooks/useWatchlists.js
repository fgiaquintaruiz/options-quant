import { useState, useEffect } from 'react'
import { LS } from '../utils/storage'
import { DEFAULT_WATCHLISTS } from '../constants/defaultWatchlists'

/**
 * Encapsulates read/write for the `ticker_groups` localStorage key.
 * Single source of truth for watchlist state — any consumer that calls this
 * hook gets the same persisted list and mutates it through a stable API.
 *
 * Exposes:
 *   groups        — current array of { id, label, tickers }
 *   addGroup      — (label: string, tickers: string) => void
 *   removeGroup   — (id: string) => void
 *   updateGroup   — (id: string, newTickers: string) => void
 */
export function useWatchlists() {
  const [groups, setGroups] = useState(() => LS.get('ticker_groups', DEFAULT_WATCHLISTS))

  // Persist every change
  useEffect(() => { LS.set('ticker_groups', groups) }, [groups])

  // Dispatch storage event so TickerSelector (listening via window) stays in sync
  const persist = (next) => {
    setGroups(next)
    // The useEffect above writes to LS asynchronously; fire storage event after a tick
    setTimeout(() => window.dispatchEvent(new Event('storage')), 0)
  }

  const addGroup = (label, tickers) => {
    if (!label || !tickers) return
    const id = label.toLowerCase().replace(/\s+/g, '-').replace(/[^a-z0-9-]/g, '')
    const existing = groups.findIndex((g) => g.id === id)
    const updated =
      existing >= 0
        ? groups.map((g) => (g.id === id ? { ...g, label, tickers } : g))
        : [...groups, { id, label, tickers }]
    persist(updated)
  }

  const removeGroup = (id) => {
    persist(groups.filter((g) => g.id !== id))
  }

  const updateGroup = (id, newTickers) => {
    persist(groups.map((g) => (g.id === id ? { ...g, tickers: newTickers } : g)))
  }

  const updateGroupFull = (id, newLabel, newTickers) => {
    if (!newLabel.trim()) return
    persist(groups.map((g) => (g.id === id ? { ...g, label: newLabel.trim(), tickers: newTickers } : g)))
  }

  const duplicateGroup = (id) => {
    const idx = groups.findIndex((g) => g.id === id)
    if (idx < 0) return
    const original = groups[idx]
    const copyPattern = new RegExp(
      `^${original.label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')} \\(copia (\\d+)\\)$`
    )
    const maxCopy = groups.reduce((max, g) => {
      const m = g.label.match(copyPattern)
      return m ? Math.max(max, parseInt(m[1], 10)) : max
    }, 0)
    const newLabel = `${original.label} (copia ${maxCopy + 1})`
    const newId = newLabel.toLowerCase().replace(/\s+/g, '-').replace(/[^a-z0-9-]/g, '')
    const newGroup = { id: newId, label: newLabel, tickers: original.tickers }
    const next = [...groups.slice(0, idx + 1), newGroup, ...groups.slice(idx + 1)]
    persist(next)
  }

  return { groups, addGroup, removeGroup, updateGroup, updateGroupFull, duplicateGroup }
}
