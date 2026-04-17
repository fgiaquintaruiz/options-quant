import React, { useState, useEffect } from 'react'
import { ChevronDown, List } from 'lucide-react'
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

export default function TickerSelector({ value, onChange, disabled }) {
  const [isOpen, setIsOpen] = useState(false)
  const [groups, setGroups] = useState(() => LS.get('ticker_groups', DEFAULT_GROUPS))

  // Refresh groups if they change in Settings
  useEffect(() => {
    const handleStorage = () => setGroups(LS.get('ticker_groups', DEFAULT_GROUPS))
    window.addEventListener('storage', handleStorage)
    const interval = setInterval(handleStorage, 2000) // Poll for local tab changes
    return () => {
      window.removeEventListener('storage', handleStorage)
      clearInterval(interval)
    }
  }, [])

  const handleGroupSelect = (tickers) => {
    if (disabled) return
    onChange(tickers)
    setIsOpen(false)
  }

  return (
    <div style={{ position: 'relative', width: '100%' }}>
      <div className="flex-row gap-4 w-full">
        <input
          type="text"
          className="ticker-input"
          value={value}
          onChange={e => onChange(e.target.value.toUpperCase())}
          placeholder="Enter tickers (e.g. AAPL,MSFT,TSLA)..."
          disabled={disabled}
          style={{ flex: 1 }}
        />
        <button
          className="btn"
          onClick={() => setIsOpen(!isOpen)}
          disabled={disabled}
          style={{ padding: '8px 12px', background: '#21262d', border: '1px solid #30363d', borderRadius: 6 }}
        >
          <List size={16} className="color-muted" />
          <ChevronDown size={14} className="color-muted" style={{ marginLeft: 4, transform: isOpen ? 'rotate(180deg)' : 'none', transition: 'transform 0.2s' }} />
        </button>
      </div>

      {isOpen && (
        <>
          <div 
            style={{ position: 'fixed', top: 0, left: 0, right: 0, bottom: 0, zIndex: 998 }} 
            onClick={() => setIsOpen(false)} 
          />
          <div style={{
            position: 'absolute', top: '100%', right: 0, marginTop: 8,
            background: '#161b22', border: '1px solid #30363d', borderRadius: 8,
            boxShadow: '0 8px 24px rgba(0,0,0,0.5)', zIndex: 999,
            minWidth: 220, overflow: 'hidden'
          }}>
            <div style={{ padding: '8px 12px', fontSize: 10, fontWeight: 700, color: '#8b949e', textTransform: 'uppercase', background: '#0d1117', borderBottom: '1px solid #30363d' }}>
              Quick Select Lists
            </div>
            <div style={{ maxHeight: 300, overflowY: 'auto' }}>
              {groups.map(g => (
                <div
                  key={g.id}
                  onClick={() => handleGroupSelect(g.tickers)}
                  style={{
                    padding: '10px 15px', cursor: 'pointer', fontSize: 13, borderBottom: '1px solid #21262d',
                    transition: 'background 0.2s'
                  }}
                  onMouseOver={e => e.target.style.background = '#1f6feb22'}
                  onMouseOut={e => e.target.style.background = 'transparent'}
                >
                  <div className="font-bold color-text">{g.label}</div>
                  <div className="text-xs color-muted" style={{ whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{g.tickers}</div>
                </div>
              ))}
            </div>
            <Link 
              to="/settings"
              style={{ display: 'block', padding: '10px 15px', fontSize: 12, color: '#58a6ff', textDecoration: 'none', textAlign: 'center', background: '#0d1117' }}
              onClick={() => setIsOpen(false)}
            >
              Manage Lists in Config...
            </Link>
          </div>
        </>
      )}
    </div>
  )
}
