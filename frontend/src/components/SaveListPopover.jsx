import React, { useState, useEffect, useRef } from 'react'
import { BookmarkPlus } from 'lucide-react'

/**
 * Self-contained save-as-watchlist popover.
 * Props:
 *   disabled        — boolean: disables the trigger button (e.g. no filter active or scan running)
 *   resolvedTickers — string[]: tickers to preview and pass to onSave
 *   onSave          — (name: string, tickers: string[]) => void
 */
export default function SaveListPopover({ disabled, resolvedTickers, onSave }) {
  const [open, setOpen]   = useState(false)
  const [name, setName]   = useState('')
  const inputRef          = useRef(null)
  const wrapRef           = useRef(null)

  useEffect(() => {
    if (!open) return
    const close = (e) => {
      if (wrapRef.current && !wrapRef.current.contains(e.target)) {
        setOpen(false)
        setName('')
      }
    }
    document.addEventListener('mousedown', close)
    return () => document.removeEventListener('mousedown', close)
  }, [open])

  useEffect(() => {
    if (open) inputRef.current?.focus()
  }, [open])

  const handleSave = () => {
    const trimmed = name.trim()
    if (!trimmed || !resolvedTickers.length) return
    onSave(trimmed, resolvedTickers)
    setOpen(false)
    setName('')
  }

  return (
    <div className="ld-save-list-wrap" ref={wrapRef}>
      <button
        type="button"
        className="btn ld-save-list-btn"
        title="Guardar como Watchlist"
        disabled={disabled}
        onClick={() => setOpen((v) => !v)}
      >
        <BookmarkPlus size={15} />
      </button>
      {open && (
        <div className="ld-save-list-popover">
          <div className="text-xs color-muted mb-6">Guardar como Watchlist</div>
          <input
            ref={inputRef}
            type="text"
            className="ticker-input ld-save-list-input"
            placeholder="Nombre de la lista…"
            value={name}
            onChange={(e) => setName(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') { e.preventDefault(); handleSave() }
              if (e.key === 'Escape') { setOpen(false); setName('') }
            }}
          />
          <div className="text-xs color-muted ld-save-list-preview">
            {resolvedTickers.slice(0, 6).join(', ')}
            {resolvedTickers.length > 6 ? ` +${resolvedTickers.length - 6} más` : ''}
          </div>
          <div className="flex-align-center gap-8 mt-8">
            <button type="button" className="btn btn-primary btn-sm" onClick={handleSave} disabled={!name.trim()}>Guardar</button>
            <button type="button" className="btn btn-secondary btn-sm" onClick={() => { setOpen(false); setName('') }}>Cancelar</button>
          </div>
        </div>
      )}
    </div>
  )
}
