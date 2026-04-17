import React from 'react'

const TICKER_GROUPS = [
  { label: 'Mega Tech', tickers: 'AAPL,MSFT,NVDA,GOOGL,AMZN,META,TSLA,AMD,AVGO,ORCL' },
  { label: 'SPY Top 10', tickers: 'AAPL,MSFT,NVDA,AMZN,META,GOOGL,BRK.B,GOOG,TSLA,AVGO' },
  { label: 'Semis', tickers: 'NVDA,AMD,AVGO,INTC,TSM,ASML,QCOM,MU,AMAT,LRCX' },
  { label: 'Finance', tickers: 'JPM,V,MA,BAC,MS,GS,HSBC,AXP,PYPL,COIN' },
  { label: 'Crypto Proxy', tickers: 'COIN,MARA,RIOT,MSTR,CLSK,MHT,WULF,BTBT' },
  { label: 'SPY', tickers: 'SPY' },
  { label: 'QQQ', tickers: 'QQQ' }
]

export default function TickerSelector({ value, onChange, disabled }) {
  const handleGroupClick = (tickers) => {
    if (disabled) return
    onChange(tickers)
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 6, width: '100%' }}>
      <input
        type="text"
        className="ticker-input"
        value={value}
        onChange={e => onChange(e.target.value.toUpperCase())}
        placeholder="Enter tickers (e.g. AAPL,MSFT,TSLA)..."
        disabled={disabled}
      />
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
        {TICKER_GROUPS.map(g => (
          <button
            key={g.label}
            className="ticker-group-btn"
            onClick={() => handleGroupClick(g.tickers)}
            disabled={disabled}
          >
            {g.label}
          </button>
        ))}
      </div>
    </div>
  )
}
