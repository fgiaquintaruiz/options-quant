import React, { useState, useCallback, useEffect, useRef } from 'react'
import { createPortal } from 'react-dom'
import { tickerConfigApi } from '../api'

// Module-level cache — shared across all instances, loaded once
let fundamentalsCache = null
let loadPromise = null

function loadFundamentals() {
  if (fundamentalsCache) return Promise.resolve(fundamentalsCache)
  if (loadPromise) return loadPromise
  loadPromise = tickerConfigApi.get()
    .then((data) => { fundamentalsCache = data?.fundamentals ?? {}; return fundamentalsCache })
    .catch(() => { fundamentalsCache = {}; return fundamentalsCache })
  return loadPromise
}

function fmt(val, suffix = '', decimals = 1) {
  if (val == null) return null
  return `${Number(val).toFixed(decimals)}${suffix}`
}

function TooltipContent({ ticker, data, pos }) {
  const style = {
    position: 'fixed',
    top: pos.y + 12,
    left: pos.x,
    zIndex: 9999,
    background: '#161b22',
    border: '1px solid #30363d',
    borderRadius: 8,
    padding: '10px 12px',
    boxShadow: '0 8px 24px rgba(0,0,0,0.6)',
    minWidth: 220,
    maxWidth: 300,
    pointerEvents: 'none',
  }

  const name = data?.companyName || ticker
  const rows = [
    data?.sector          && ['Sector',    data.sector],
    data?.marketCapBillion && ['Mkt Cap',  `$${data.marketCapBillion}B`],
    data?.peRatio          && ['P/E',       fmt(data.peRatio)],
    data?.beta             && ['Beta',      fmt(data.beta)],
    data?.epsGrowth        && ['EPS Growth',fmt(data.epsGrowth, '%')],
    data?.revenueGrowth    && ['Rev Growth',fmt(data.revenueGrowth, '%')],
    data?.dividendYield    && ['Div Yield', fmt(data.dividendYield, '%')],
    data?.debtToEquity     && ['D/E',       fmt(data.debtToEquity)],
    data?.roic             && ['ROIC',      fmt(data.roic, '%')],
  ].filter(Boolean)

  return createPortal(
    <div style={style}>
      <div style={{ fontWeight: 700, fontSize: 13, color: '#c9d1d9', marginBottom: 6 }}>
        <span style={{ color: '#58a6ff', marginRight: 6 }}>{ticker}</span>
        {name !== ticker && <span style={{ color: '#8b949e', fontWeight: 400 }}>{name}</span>}
      </div>
      {rows.length > 0 ? (
        <table style={{ borderCollapse: 'collapse', width: '100%' }}>
          <tbody>
            {rows.map(([label, value]) => (
              <tr key={label}>
                <td style={{ fontSize: 11, color: '#6e7681', paddingRight: 12, paddingBottom: 2, whiteSpace: 'nowrap' }}>{label}</td>
                <td style={{ fontSize: 11, color: '#c9d1d9', fontWeight: 600, paddingBottom: 2 }}>{value}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <span style={{ fontSize: 11, color: '#6e7681' }}>Sin datos fundamentales</span>
      )}
      {data?.notes && (
        <div style={{ fontSize: 10, color: '#8b949e', marginTop: 6, borderTop: '1px solid #21262d', paddingTop: 5 }}>
          {data.notes}
        </div>
      )}
    </div>,
    document.body
  )
}

/**
 * Wraps any ticker display with a hover tooltip showing fundamental data.
 * Usage: <TickerTooltip ticker="AAPL"><strong>AAPL</strong></TickerTooltip>
 */
export default function TickerTooltip({ ticker, children }) {
  const [visible, setVisible]   = useState(false)
  const [pos, setPos]           = useState({ x: 0, y: 0 })
  const [data, setData]         = useState(null)
  const hideTimer               = useRef(null)

  const show = useCallback((e) => {
    clearTimeout(hideTimer.current)
    const rect = e.currentTarget.getBoundingClientRect()
    setPos({ x: rect.left, y: rect.bottom })
    loadFundamentals().then((fund) => {
      setData(fund[ticker?.toUpperCase()] ?? null)
      setVisible(true)
    })
  }, [ticker])

  const hide = useCallback(() => {
    hideTimer.current = setTimeout(() => setVisible(false), 80)
  }, [])

  useEffect(() => () => clearTimeout(hideTimer.current), [])

  return (
    <span
      onMouseEnter={show}
      onMouseLeave={hide}
      style={{ cursor: 'default' }}
    >
      {children}
      {visible && <TooltipContent ticker={ticker?.toUpperCase()} data={data} pos={pos} />}
    </span>
  )
}
