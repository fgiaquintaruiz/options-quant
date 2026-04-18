import React, { useState, useEffect, useMemo, useRef } from 'react'
import { ChevronDown, List, X, Globe, Flame } from 'lucide-react'
import { Link } from 'react-router-dom'
import { LS } from '../utils/storage'

const DEFAULT_GROUPS = [
  { id: 'mega-tech', label: 'Mega Tech', tickers: 'AAPL,MSFT,NVDA,GOOGL,AMZN,META,TSLA,AMD,AVGO,ORCL' },
  { id: 'spy-top-10', label: 'SPY Top 10', tickers: 'AAPL,MSFT,NVDA,AMZN,META,GOOGL,BRK B,GOOG,TSLA,AVGO' },
  { id: 'semis', label: 'Semis', tickers: 'NVDA,AMD,AVGO,INTC,TSM,ASML,QCOM,MU,AMAT,LRCX' },
  { id: 'finance', label: 'Finance', tickers: 'JPM,V,MA,BAC,MS,GS,HSBC,AXP,PYPL,COIN' },
  { id: 'crypto', label: 'Crypto Proxy', tickers: 'COIN,MARA,RIOT,MSTR,CLSK,MHT,WULF,BTBT' },
  { id: 'spy', label: 'SPY', tickers: 'SPY' },
  { id: 'qqq', label: 'QQQ', tickers: 'QQQ' }
]

export default function TickerSelector({ value, onChange, disabled, scope, onScopeChange }) {
  const [isOpen, setIsOpen] = useState(false)
  const [inputValue, setInputValue] = useState('')
  const [groups, setGroups] = useState(() => LS.get('ticker_groups', DEFAULT_GROUPS))
  const dropdownRef = useRef(null)

  // Sync groups if changed in other tabs/settings
  useEffect(() => {
    const handleStorage = () => setGroups(LS.get('ticker_groups', DEFAULT_GROUPS))
    window.addEventListener('storage', handleStorage)
    const interval = setInterval(handleStorage, 3000)
    return () => {
      window.removeEventListener('storage', handleStorage)
      clearInterval(interval)
    }
  }, [])

  // Close dropdown on click outside
  useEffect(() => {
    const handleClickOutside = (e) => {
      if (dropdownRef.current && !dropdownRef.current.contains(e.target)) setIsOpen(false)
    }
    document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [])

  // Parse value (comma separated string) into ticker chips
  const tickers = useMemo(() => {
    if (!value) return []
    return value.split(',')
      .map(t => t.trim().toUpperCase())
      .filter(t => t.length > 0)
  }, [value])

  const handleRemoveTicker = (ticker) => {
    if (disabled) return
    const newList = tickers.filter(t => t !== ticker)
    onChange(newList.join(','))
  }

  const handleAddTicker = (e) => {
    if (e.key === 'Enter' || e.key === ',') {
      e.preventDefault()
      const newTicker = inputValue.trim().toUpperCase().replace(/[^A-Z0-9 .]/g, '')
      if (newTicker && !tickers.includes(newTicker)) {
        onChange(value ? `${value},${newTicker}` : newTicker)
      }
      setInputValue('')
    }
  }

  const handleGroupSelect = (groupTickers) => {
    if (disabled) return
    onChange(groupTickers)
    setIsOpen(false)
  }

  return (
    <div style={{ position: 'relative', width: '100%' }} ref={dropdownRef}>
      <div className="flex-row flex-wrap gap-6 border-main rounded-md" style={{ 
        padding: '6px 10px', 
        background: '#0d1117', 
        minHeight: 40,
        alignItems: 'center',
        opacity: disabled ? 0.6 : 1
      }}>
        
        {/* Chips */}
        {tickers.map(t => (
          <div key={t} className="flex-align-center" style={{
            background: '#21262d',
            border: '1px solid #30363d',
            borderRadius: 4,
            padding: '2px 8px',
            fontSize: 12,
            fontWeight: 600,
            color: '#c9d1d9',
            position: 'relative'
          }}>
            {t}
            {!disabled && (
              <div 
                onClick={() => handleRemoveTicker(t)}
                style={{ 
                  cursor: 'pointer', 
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  width: 14,
                  height: 14,
                  borderRadius: '50%',
                  background: '#30363d',
                  marginLeft: 6,
                  color: '#8b949e',
                  fontSize: 10,
                  transition: 'all 0.2s'
                }}
                onMouseOver={e => e.currentTarget.style.color = '#f85149'}
                onMouseOut={e => e.currentTarget.style.color = '#8b949e'}
              >
                <X size={10} />
              </div>
            )}
          </div>
        ))}

        {/* Input */}
        <input
          type="text"
          value={inputValue}
          onChange={e => setInputValue(e.target.value.toUpperCase())}
          onKeyDown={handleAddTicker}
          placeholder={tickers.length === 0 ? "Type ticker and press Enter..." : ""}
          disabled={disabled}
          style={{
            flex: 1,
            minWidth: 100,
            border: 'none',
            background: 'transparent',
            color: '#c9d1d9',
            fontSize: 13,
            outline: 'none',
            height: 24
          }}
        />

        <div className="divider-v" style={{ height: 20, margin: '0 4px' }} />

        {/* Dropdown Toggle */}
        <button
          className="btn"
          onClick={() => !disabled && setIsOpen(!isOpen)}
          disabled={disabled}
          style={{ padding: '4px 8px', background: 'none', border: 'none', height: '100%' }}
        >
          <List size={16} className="color-muted" />
          <ChevronDown size={14} className="color-muted" style={{ marginLeft: 2, transform: isOpen ? 'rotate(180deg)' : 'none', transition: 'transform 0.2s' }} />
        </button>
      </div>

      {isOpen && (
        <div style={{
          position: 'absolute', top: '100%', right: 0, marginTop: 8,
          background: '#161b22', border: '1px solid #30363d', borderRadius: 8,
          boxShadow: '0 8px 24px rgba(0,0,0,0.5)', zIndex: 1000,
          minWidth: 260, overflow: 'hidden'
        }}>
          
          {/* Universe Section (Integrated) */}
          {onScopeChange && (
            <div style={{ padding: '12px', background: '#0d1117', borderBottom: '1px solid #30363d' }}>
              <div className="stat-label-sm color-muted mb-8" style={{ fontSize: 9 }}>SCAN UNIVERSE</div>
              <div className="flex-row gap-4">
                <button
                  className="btn w-full"
                  onClick={() => { onScopeChange('ALL'); setIsOpen(false); }}
                  style={{ 
                    padding: '6px', fontSize: 11, gap: 4,
                    background: scope === 'ALL' ? '#1f6feb22' : 'transparent',
                    border: `1px solid ${scope === 'ALL' ? '#1f6feb88' : '#30363d'}`,
                    color: scope === 'ALL' ? '#58a6ff' : '#8b949e'
                  }}
                >
                  <Globe size={12} /> All Tickers
                </button>
                <button
                  className="btn w-full"
                  onClick={() => { onScopeChange('HOT'); setIsOpen(false); }}
                  style={{ 
                    padding: '6px', fontSize: 11, gap: 4,
                    background: scope === 'HOT' ? '#9e6a0322' : 'transparent',
                    border: `1px solid ${scope === 'HOT' ? '#9e6a0388' : '#30363d'}`,
                    color: scope === 'HOT' ? '#f0883e' : '#8b949e'
                  }}
                >
                  <Flame size={12} /> Hot Only
                </button>
              </div>
            </div>
          )}

          <div style={{ padding: '8px 12px', fontSize: 10, fontWeight: 700, color: '#8b949e', textTransform: 'uppercase', background: '#0d1117', borderBottom: '1px solid #30363d' }}>
            Quick Select Lists
          </div>
          <div style={{ maxHeight: 250, overflowY: 'auto' }}>
            {groups.map(g => (
              <div
                key={g.id}
                onClick={() => handleGroupSelect(g.tickers)}
                style={{
                  padding: '10px 15px', cursor: 'pointer', fontSize: 13, borderBottom: '1px solid #21262d',
                  transition: 'background 0.2s'
                }}
                onMouseOver={e => e.currentTarget.style.background = '#1f6feb15'}
                onMouseOut={e => e.currentTarget.style.background = 'transparent'}
              >
                <div className="font-bold color-text">{g.label}</div>
                <div className="text-xs color-muted" style={{ whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{g.tickers}</div>
              </div>
            ))}
          </div>
          <Link 
            to="/settings"
            style={{ display: 'block', padding: '10px 15px', fontSize: 12, color: '#58a6ff', textDecoration: 'none', textAlign: 'center', background: '#0d1117', borderTop: '1px solid #30363d' }}
            onClick={() => setIsOpen(false)}
          >
            Manage Lists in Config...
          </Link>
        </div>
      )}
    </div>
  )
}
