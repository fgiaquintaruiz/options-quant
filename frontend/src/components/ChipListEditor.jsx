import React, { useState, useRef, useCallback, useMemo } from 'react'
import { X, Flame } from 'lucide-react'
import TickerTooltip from './TickerTooltip'

/**
 * Chip-based editor for a comma-separated ticker list.
 * Props:
 *   value       — comma-separated string of tickers
 *   onChange    — (newValue: string) => void
 *   hotSet      — Set<string> — tickers to mark with flame
 *   onFetchInfo — async (ticker) => fundamentalPayload | null
 *   disabled    — bool
 *   placeholder — string
 *   maxHeight   — css string (default '160px')
 */
export default function ChipListEditor({
  value = '',
  onChange,
  hotSet = new Set(),
  prioritySet = null,
  disabled = false,
  placeholder = 'Ticker…',
  maxHeight = '160px',
}) {
  const [input, setInput]       = useState('')
  const inputRef                = useRef(null)

  const rawTickers = useMemo(
    () => (value ? value.split(',').map((t) => t.trim().toUpperCase()).filter(Boolean) : []),
    [value]
  )

  const tickers = useMemo(
    () => prioritySet
      ? [...rawTickers.filter((t) => prioritySet.has(t)), ...rawTickers.filter((t) => !prioritySet.has(t))]
      : rawTickers,
    [rawTickers, prioritySet]
  )

  const add = useCallback(() => {
    const sym = input.trim().toUpperCase().replace(/[^A-Z0-9 .]/g, '')
    if (!sym || rawTickers.includes(sym)) { setInput(''); return }
    const next = [...rawTickers, sym]
    const sorted = prioritySet
      ? [...next.filter((t) => prioritySet.has(t)), ...next.filter((t) => !prioritySet.has(t))]
      : next
    onChange(sorted.join(','))
    setInput('')
  }, [input, rawTickers, onChange, prioritySet])

  const remove = useCallback((sym) => {
    if (disabled) return
    onChange(rawTickers.filter((t) => t !== sym).join(','))
  }, [disabled, rawTickers, onChange])

  const onKeyDown = (e) => {
    if (e.key === 'Enter' || e.key === ',') { e.preventDefault(); add() }
    if (e.key === 'Backspace' && !input && rawTickers.length) {
      remove(rawTickers[rawTickers.length - 1])
    }
  }

  return (
    <div
      className={`cle-container ${disabled ? 'cle-container--disabled' : ''}`}
      style={{ maxHeight, overflowY: 'auto' }}
      onClick={() => inputRef.current?.focus()}
    >
      {tickers.map((sym) => {
        const isHot = hotSet.has(sym)
        return (
          <div key={sym} className={`ts-chip ${isHot ? 'ts-chip--hot' : ''}`}>
            {isHot && <Flame size={10} className="ts-chip-flame" />}
            <TickerTooltip ticker={sym}>{sym}</TickerTooltip>
            {!disabled && (
              <span className="ts-chip-remove" role="button" aria-label={`Remove ${sym}`} onClick={() => remove(sym)}>
                <X size={10} />
              </span>
            )}
          </div>
        )
      })}
      {!disabled && (
        <input
          ref={inputRef}
          type="text"
          className="ts-input"
          value={input}
          placeholder={tickers.length === 0 ? placeholder : ''}
          onChange={(e) => setInput(e.target.value.toUpperCase())}
          onKeyDown={onKeyDown}
          style={{ minWidth: 80, height: 24 }}
        />
      )}
    </div>
  )
}
