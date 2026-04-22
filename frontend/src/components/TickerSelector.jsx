import React, { useState, useEffect, useMemo, useRef, useCallback } from 'react'
import { X, Globe, Flame, List } from 'lucide-react'
import { Link } from 'react-router-dom'
import { LS } from '../utils/storage'
import { liveApi } from '../api'

const DEFAULT_GROUPS = [
  { id: 'mega-tech',  label: 'Mega Tech',  tickers: 'AAPL,MSFT,NVDA,GOOGL,AMZN,META,TSLA,AMD,AVGO,ORCL' },
  { id: 'spy-top-10', label: 'SPY Top 10', tickers: 'AAPL,MSFT,NVDA,AMZN,META,GOOGL,BRK B,GOOG,TSLA,AVGO' },
  { id: 'semis',     label: 'Semis',      tickers: 'NVDA,AMD,AVGO,INTC,TSM,ASML,QCOM,MU,AMAT,LRCX' },
  { id: 'finance',   label: 'Finance',    tickers: 'JPM,V,MA,BAC,MS,GS,HSBC,AXP,PYPL,COIN' },
  { id: 'crypto',    label: 'Crypto Proxy',tickers: 'COIN,MARA,RIOT,MSTR,CLSK,MHT,WULF,BTBT' },
  { id: 'spy',       label: 'SPY',        tickers: 'SPY' },
  { id: 'qqq',       label: 'QQQ',        tickers: 'QQQ' },
]

/** Merges incoming tickers into existing list, deduplicating while preserving order. */
function mergeTickers(existing, incoming) {
  const current = existing ? existing.split(',').map((t) => t.trim().toUpperCase()).filter(Boolean) : []
  const added   = incoming.split(',').map((t) => t.trim().toUpperCase()).filter(Boolean)
  const seen    = new Set(current)
  const merged  = [...current]
  for (const t of added) { if (!seen.has(t)) { seen.add(t); merged.push(t) } }
  return merged.join(',')
}

// ── Keyboard navigation helpers ─────────────────────────────────────────────

const navigateDown = (prev, max) => (prev < max - 1 ? prev + 1 : 0)
const navigateUp   = (prev, max) => (prev > 0 ? prev - 1 : max - 1)

// ── Main component ─────────────────────────────────────────────────────────

/**
 * Multi-ticker selector with autocomplete dropdown.
 * Supports HOT/ALL scope toggles, group watchlists, and TWS news ticker suggestions.
 * Props: value (comma-separated tickers), onChange, disabled, scope, onScopeChange.
 */
export default function TickerSelector({ value, onChange, disabled, scope, onScopeChange }) {
  const [inputValue, setInputValue]     = useState('')
  const [showSuggestions, setShow]      = useState(false)
  const [highlightedIdx, setHighlight]  = useState(-1)
  const [groups, setGroups]             = useState(() => LS.get('ticker_groups', DEFAULT_GROUPS))
  const [newsTickers, setNewsTickers]   = useState([])
  const containerRef = useRef(null)
  const inputRef     = useRef(null)

  // Sync groups when localStorage changes (cross-tab); same-tab changes dispatch storage event via LS utility
  useEffect(() => {
    const sync = () => setGroups(LS.get('ticker_groups', DEFAULT_GROUPS))
    window.addEventListener('storage', sync)
    return () => window.removeEventListener('storage', sync)
  }, [])

  useEffect(() => {
    liveApi.getNewsTickers().then((data) => {
      if (Array.isArray(data)) setNewsTickers(data)
    }).catch(() => {})
  }, [])

  useEffect(() => {
    const onClickOutside = (e) => {
      if (containerRef.current && !containerRef.current.contains(e.target)) {
        setShow(false)
        setHighlight(-1)
      }
    }
    document.addEventListener('mousedown', onClickOutside)
    return () => document.removeEventListener('mousedown', onClickOutside)
  }, [])

  const tickers = useMemo(
    () => (value ? value.split(',').map((t) => t.trim().toUpperCase()).filter(Boolean) : []),
    [value]
  )

  const filteredGroups = useMemo(() => {
    const q = inputValue.trim().toLowerCase()
    if (!q) return groups
    return groups.filter((g) => g.label.toLowerCase().includes(q) || g.tickers.toLowerCase().includes(q))
  }, [inputValue, groups])

  const filteredNews = useMemo(() => {
    const q = inputValue.trim().toLowerCase()
    if (!newsTickers.length) return []
    const results = q ? newsTickers.filter((n) => n.ticker.toLowerCase().includes(q)) : newsTickers
    return results.slice(0, 5)
  }, [inputValue, newsTickers])

  const allSuggestions = useMemo(
    () => [...filteredGroups.map((g) => ({ type: 'group', ...g })), ...filteredNews.map((n) => ({ type: 'news', ...n }))],
    [filteredGroups, filteredNews]
  )

  const closeDropdown = useCallback(() => { setShow(false); setHighlight(-1) }, [])

  const applyGroup = useCallback((groupTickers) => {
    if (disabled) return
    onChange(mergeTickers(value, groupTickers))
    setInputValue('')
    closeDropdown()
  }, [disabled, onChange, value, closeDropdown])

  const applyNewsTicker = useCallback((ticker) => {
    if (disabled) return
    if (!tickers.includes(ticker)) onChange(value ? `${value},${ticker}` : ticker)
    setInputValue('')
    closeDropdown()
  }, [disabled, onChange, value, tickers, closeDropdown])

  const confirmTyped = useCallback(() => {
    const t = inputValue.trim().toUpperCase().replace(/[^A-Z0-9 .]/g, '')
    if (t && !tickers.includes(t)) onChange(value ? `${value},${t}` : t)
    setInputValue('')
  }, [inputValue, tickers, onChange, value])

  const handleKeyDown = useCallback((e) => {
    if (!showSuggestions && e.key === 'ArrowDown') { setShow(true); return }

    if (e.key === 'ArrowDown') { e.preventDefault(); setHighlight((p) => navigateDown(p, allSuggestions.length)); return }
    if (e.key === 'ArrowUp')   { e.preventDefault(); setHighlight((p) => navigateUp(p, allSuggestions.length));   return }
    if (e.key === 'Escape')    { closeDropdown(); return }

    if (e.key === 'Enter') {
      e.preventDefault()
      if (showSuggestions && highlightedIdx >= 0 && highlightedIdx < allSuggestions.length) {
        const s = allSuggestions[highlightedIdx]
        s.type === 'group' ? applyGroup(s.tickers) : applyNewsTicker(s.ticker)
      } else {
        confirmTyped()
      }
      return
    }
    if (e.key === ',') { e.preventDefault(); confirmTyped() }
  }, [showSuggestions, highlightedIdx, allSuggestions, applyGroup, applyNewsTicker, confirmTyped, closeDropdown])

  const handleRemoveTicker = (ticker) => {
    if (disabled) return
    onChange(tickers.filter((t) => t !== ticker).join(','))
  }

  return (
    <div className="ts-container" ref={containerRef}>
      <div className={`ts-input-row ${disabled ? 'ts-input-row--disabled' : ''}`}>

        {/* Scope pills */}
        {onScopeChange && (
          <div className="flex-align-center gap-2" style={{ flexShrink: 0 }}>
            <button type="button" className={`btn ts-scope-btn ts-scope-btn-all ${scope === 'ALL' ? 'ts-scope-btn-all--active' : ''}`}
              onClick={() => !disabled && onScopeChange('ALL')} disabled={disabled}>
              <Globe size={11} /> ALL
            </button>
            <button type="button" className={`btn ts-scope-btn ts-scope-btn-hot ${scope === 'HOT' ? 'ts-scope-btn-hot--active' : ''}`}
              onClick={() => !disabled && onScopeChange('HOT')} disabled={disabled}>
              <Flame size={11} /> HOT
            </button>
            <div className="divider-v ts-divider" />
          </div>
        )}

        {/* Ticker chips */}
        {tickers.map((t) => (
          <div key={t} className="ts-chip">
            {t}
            {!disabled && (
              <span className="ts-chip-remove" onClick={() => handleRemoveTicker(t)} role="button" aria-label={`Remove ${t}`}>
                <X size={10} />
              </span>
            )}
          </div>
        ))}

        {/* Text input */}
        <input
          ref={inputRef}
          type="text"
          className="ts-input"
          value={inputValue}
          onChange={(e) => { setInputValue(e.target.value.toUpperCase()); setShow(true); setHighlight(-1) }}
          onFocus={() => setShow(true)}
          onKeyDown={handleKeyDown}
          placeholder={tickers.length === 0 ? 'Ticker, lista o watchlist…' : ''}
          disabled={disabled}
        />

        <List size={15} className="color-muted ts-icon-hint" />
      </div>

      {/* Autocomplete dropdown */}
      {showSuggestions && !disabled && allSuggestions.length > 0 && (
        <div className="ts-dropdown">

          {filteredGroups.length > 0 && (
            <>
              <div className="ts-dropdown-header">Watchlists / Listas</div>
              <div className="ts-dropdown-scroll">
                {filteredGroups.map((g, i) => (
                  <div
                    key={g.id}
                    className={`ts-dropdown-item ${highlightedIdx === i ? 'ts-dropdown-item--hl' : ''}`}
                    onClick={() => applyGroup(g.tickers)}
                    onMouseEnter={() => setHighlight(i)}
                  >
                    <div className="ts-dropdown-group-name font-bold color-text">
                      {g.label}
                      <span className="ts-dropdown-group-meta">{g.tickers.split(',').length} tickers</span>
                    </div>
                    <div className="text-xs color-muted ts-dropdown-ticker-list">{g.tickers}</div>
                  </div>
                ))}
              </div>
            </>
          )}

          {filteredNews.length > 0 && (
            <>
              <div className={`ts-dropdown-header ${filteredGroups.length ? 'ts-dropdown-header--news' : ''}`} style={{ color: '#f0883e' }}>
                Noticias del día (TWS)
              </div>
              {filteredNews.map((n, i) => {
                const idx = filteredGroups.length + i
                return (
                  <div
                    key={n.ticker}
                    className={`ts-dropdown-item ${highlightedIdx === idx ? 'ts-dropdown-item-news--hl' : ''}`}
                    onClick={() => applyNewsTicker(n.ticker)}
                    onMouseEnter={() => setHighlight(idx)}
                    title={n.headline || ''}
                  >
                    <div className="flex-align-center gap-6">
                      <strong className="color-text">{n.ticker}</strong>
                      {n.source && <span className="badge badge-hot ts-news-badge">{n.source}</span>}
                    </div>
                    {n.headline && (
                      <div className="text-xs color-muted ts-dropdown-headline">{n.headline}</div>
                    )}
                  </div>
                )
              })}
            </>
          )}

          <Link to="/settings" className="ts-dropdown-footer" onClick={closeDropdown}>
            Configurar universo HOT + listas personalizadas…
          </Link>
        </div>
      )}
    </div>
  )
}
