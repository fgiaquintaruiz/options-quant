import React, { useState, useEffect, useMemo, useRef, useCallback } from 'react'
import { X, Flame, List, Layers } from 'lucide-react'
import TickerTooltip from './TickerTooltip'
import { Link } from 'react-router-dom'
import { LS } from '../utils/storage'
import { liveApi } from '../api'

export const DEFAULT_GROUPS = [
  { id: 'mega-tech',  label: 'Mega Tech',   tickers: 'AAPL,MSFT,NVDA,GOOGL,AMZN,META,TSLA,AMD,AVGO,ORCL' },
  { id: 'spy-top-10', label: 'SPY Top 10',  tickers: 'AAPL,MSFT,NVDA,AMZN,META,GOOGL,BRK B,GOOG,TSLA,AVGO' },
  { id: 'semis',      label: 'Semis',       tickers: 'NVDA,AMD,AVGO,INTC,TSM,ASML,QCOM,MU,AMAT,LRCX' },
  { id: 'finance',    label: 'Finance',     tickers: 'JPM,V,MA,BAC,MS,GS,HSBC,AXP,PYPL,COIN' },
  { id: 'crypto',     label: 'Crypto Proxy',tickers: 'COIN,MARA,RIOT,MSTR,CLSK,MHT,WULF,BTBT' },
  { id: 'spy',        label: 'SPY',         tickers: 'SPY' },
  { id: 'qqq',        label: 'QQQ',         tickers: 'QQQ' },
  { id: 'all',        label: 'All (Rest of World)', tickers: 'A,AAL,ABBV,ABNB,ABT,ACGL,ACN,ADBE,ADI,ADM,ADP,ADSK,AEE,AEP,AES,AFG,AFL,AFRM,AIG,AIZ,AJG,AKAM,ALB,ALGN,ALL,ALLE,AMAT,AMCR,AMDL,AME,AMGN,AMP,AMT,ANET,AON,AOS,APA,APD,APH,APTV,ARE,ARKK,ARM,ATO,AVB,AVGO,AVY,AWK,AXON,AXP,AYI,AZO,BA,BABA,BAC,BALL,BAX,BBWI,BBY,BDX,BEN,BG,BIIB,BIO,BITX,BK,BKNG,BKR,BLDR,BLK,BMY,BR,BSX,BWA,BX,BYND,C,CAG,CAH,CARR,CAT,CB,CBOE,CBRE,CCI,CCL,CDNS,CDW,CE,CEG,CF,CFG,CHD,CHRW,CHTR,CI,CINF,CL,CLX,CMCSA,CME,CMG,CMI,CMS,CNC,CNP,COF,COIN,COO,COP,COR,COST,CPAY,CPB,CPRT,CPT,CRL,CRM,CRWD,CSGP,CSX,CTAS,CTRA,CTSH,CTVA,CVNA,CVS,CVX,CZR,D,DAL,DD,DDOG,DE,DECK,DELL,DG,DGX,DHI,DHR,DIA,DIS,DLR,DLTR,DOC,DOV,DOW,DPZ,DRI,DTE,DUK,DVA,DVN,DXCM,EA,EBAY,ECL,ED,EFX,EG,EIX,EL,ELV,EMN,EMR,ENPH,EOG,EPAM,EQIX,EQT,ERIE,ES,ESS,ETN,ETR,ETSY,EVRG,EW,EXC,EXPD,EXPE,EXR,F,FANG,FAST,FCX,FDS,FDX,FE,FFIV,FICO,FIS,FITB,FMC,FOX,FOXA,FRT,FSLR,FTNT,FTV,GD,GE,GEHC,GEN,GEV,GILD,GIS,GL,GLW,GM,GNRC,GOOG,GPN,GRMN,GS,GWW,HAL,HAS,HBAN,HCA,HD,HIG,HII,HLT,HOLX,HON,HOOD,HPE,HPQ,HRL,HSIC,HST,HSY,HUBB,HUM,HWM,IBM,ICE,IDXX,IEX,IFF,ILMN,INCY,INTC,INTU,INVH,IP,IQV,IR,IRM,ISRG,IT,ITW,IVZ,IWM,J,JBHT,JBL,JCI,JD,JKHY,KDP,KEY,KEYS,KHC,KIM,KLAC,KMB,KMI,KMX,KO,KR,KVUE,L,LDOS,LEN,LH,LHX,LIN,LKQ,LLY,LMT,LNC,LOW,LRCX,LULU,LUV,LVS,LW,LYB,LYV,MA,MAA,MAR,MAS,MCD,MCHP,MCK,MCO,MDB,MDLZ,MDT,MET,METP,MGM,MHK,MKC,MKTX,MLM,MMM,MNST,MO,MOH,MOS,MPC,MPWR,MRK,MRNA,MS,MSI,MSTR,MTB,MTCH,MTD,MU,NCLH,NDAQ,NDSN,NEE,NEM,NFLX,NI,NIO,NKE,NORW,NSC,NTAP,NTRS,NUE,NVO,NVR,NWS,NWSA,NXPI,O,ODFL,OKE,OKLO,OMC,ON,ORCL,ORLY,OTIS,OXY,PANW,PAYC,PAYX,PCAR,PCG,PDD,PEG,PEP,PFE,PFG,PG,PGR,PH,PHM,PKG,PLD,PLTR,PM,PNC,PNR,PNW,PODD,POOL,PPG,PPL,PRU,PSA,PSX,PTC,PWR,PYPL,QCOM,QQQ,QRVO,RCL,REG,REGN,RF,RIOT,RIVN,RJF,RL,RMD,ROL,ROP,ROST,RSG,RTX,RVTY,SBAC,SBUX,SCHW,SHOP,SHW,SJM,SLB,SMCI,SNA,SNPS,SO,SOXL,SPG,SPGI,SPY,SRE,STE,STLD,STT,STX,STZ,SWK,SWKS,SYK,SYY,T,TAP,TDG,TDY,TECH,TEL,TER,TFC,TFX,TGT,TJX,TMO,TMUS,TNA,TPR,TQQQ,TRGP,TRMB,TROW,TRV,TSCO,TSM,TSN,TT,TTWO,TXN,TXT,TYL,UAL,UDR,UHS,ULTA,UNH,UNP,UPS,UPST,URA,URI,USB,VICI,VLO,VMC,VRSK,VRSN,VRTX,VST,VTR,VZ,WAB,WAT,WBD,WDC,WEC,WELL,WFC,WHR,WM,WMB,WMT,WRB,WST,WTW,WY,WYNN,XEL,XPEV,XYL,XYZ,YUM,ZBH,ZBRA,ZTS' },
]

/**
 * Expands a mixed filter value (watchlist refs like @mega-tech + bare tickers)
 * into a flat deduplicated comma-separated ticker string for the backend.
 */
export function resolveTickerEntries(value, groups) {
  const entries = value ? value.split(',').map((e) => e.trim()).filter(Boolean) : []
  const seen    = new Set()
  const result  = []
  for (const entry of entries) {
    const list = entry.startsWith('@')
      ? (groups.find((g) => g.id === entry.slice(1))?.tickers.split(',').map((t) => t.trim().toUpperCase()) ?? [])
      : [entry.toUpperCase()]
    for (const t of list) { if (t && !seen.has(t)) { seen.add(t); result.push(t) } }
  }
  return result.join(',')
}

const navigateDown = (prev, max) => (prev < max - 1 ? prev + 1 : 0)
const navigateUp   = (prev, max) => (prev > 0 ? prev - 1 : max - 1)

export default function TickerSelector({ value, onChange, disabled, scope, onScopeChange, hotTickers = [] }) {
  const hotSet = useMemo(() => new Set(hotTickers.map((t) => t.toUpperCase())), [hotTickers])

  const [inputValue, setInputValue]    = useState('')
  const [showSuggestions, setShow]     = useState(false)
  const [highlightedIdx, setHighlight] = useState(-1)
  const [groups, setGroups]            = useState(() => {
    const stored = LS.get('ticker_groups', null)
    if (!stored) return DEFAULT_GROUPS
    const storedIds = new Set(stored.map((g) => g.id))
    const missing = DEFAULT_GROUPS.filter((g) => !storedIds.has(g.id))
    return [...missing, ...stored]
  })
  const [newsTickers, setNewsTickers]  = useState([])
  const containerRef = useRef(null)
  const inputRef     = useRef(null)

  useEffect(() => {
    const sync = () => {
      const stored = LS.get('ticker_groups', null)
      if (!stored) { setGroups(DEFAULT_GROUPS); return }
      const storedIds = new Set(stored.map((g) => g.id))
      const missing = DEFAULT_GROUPS.filter((g) => !storedIds.has(g.id))
      setGroups([...missing, ...stored])
    }
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
        setShow(false); setHighlight(-1)
      }
    }
    document.addEventListener('mousedown', onClickOutside)
    return () => document.removeEventListener('mousedown', onClickOutside)
  }, [])

  // Raw entries in the field — either @groupId refs or individual tickers
  const entries = useMemo(
    () => (value ? value.split(',').map((e) => e.trim()).filter(Boolean) : []),
    [value]
  )

  // Expand @groupId refs to flat ticker list — derived from entries to avoid re-parsing value
  const resolvedTickers = useMemo(() => {
    const seen = new Set()
    const result = []
    for (const entry of entries) {
      const list = entry.startsWith('@')
        ? (groups.find((g) => g.id === entry.slice(1))?.tickers.split(',').map((t) => t.trim().toUpperCase()) ?? [])
        : [entry.toUpperCase()]
      for (const t of list) { if (t && !seen.has(t)) { seen.add(t); result.push(t) } }
    }
    return result
  }, [entries, groups])

  const { hotCount, restCount } = useMemo(() => {
    const hot = resolvedTickers.filter((t) => hotSet.has(t)).length
    return { hotCount: hot, restCount: resolvedTickers.length - hot }
  }, [resolvedTickers, hotSet])

  const scopeLabel = useMemo(() => {
    if (!onScopeChange || hotCount === 0) return null
    if (scope === 'HOT') return { text: `HOT only · ${hotCount} tickers`, hint: 'Clic para escanear todo', hot: true }
    if (restCount === 0) return { text: `All ${hotCount} HOT`, hint: null, hot: true }
    return { text: `${hotCount} HOT first · then ${restCount} more`, hint: 'Clic para escanear solo HOT', hot: false }
  }, [hotCount, restCount, scope, onScopeChange])

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

  // Add watchlist as a @ref — no expansion
  const applyGroup = useCallback((groupId) => {
    if (disabled) return
    const ref = `@${groupId}`
    if (!entries.includes(ref)) onChange(value ? `${value},${ref}` : ref)
    setInputValue('')
    closeDropdown()
  }, [disabled, onChange, value, entries, closeDropdown])

  const applyNewsTicker = useCallback((ticker) => {
    if (disabled) return
    if (!resolvedTickers.includes(ticker)) onChange(value ? `${value},${ticker}` : ticker)
    setInputValue('')
    closeDropdown()
  }, [disabled, onChange, value, resolvedTickers, closeDropdown])

  const confirmTyped = useCallback(() => {
    const t = inputValue.trim().toUpperCase().replace(/[^A-Z0-9 .]/g, '')
    if (t && !resolvedTickers.includes(t)) onChange(value ? `${value},${t}` : t)
    setInputValue('')
  }, [inputValue, resolvedTickers, onChange, value])

  const handleRemoveEntry = useCallback((entry) => {
    if (disabled) return
    onChange(entries.filter((e) => e !== entry).join(','))
  }, [disabled, entries, onChange])

  const handleKeyDown = useCallback((e) => {
    if (!showSuggestions && e.key === 'ArrowDown') { setShow(true); return }
    if (e.key === 'ArrowDown') { e.preventDefault(); setHighlight((p) => navigateDown(p, allSuggestions.length)); return }
    if (e.key === 'ArrowUp')   { e.preventDefault(); setHighlight((p) => navigateUp(p, allSuggestions.length));   return }
    if (e.key === 'Escape')    { closeDropdown(); return }
    if (e.key === 'Enter') {
      e.preventDefault()
      if (showSuggestions && highlightedIdx >= 0 && highlightedIdx < allSuggestions.length) {
        const s = allSuggestions[highlightedIdx]
        s.type === 'group' ? applyGroup(s.id) : applyNewsTicker(s.ticker)
      } else {
        confirmTyped()
      }
      return
    }
    if (e.key === ',') { e.preventDefault(); confirmTyped() }
  }, [showSuggestions, highlightedIdx, allSuggestions, applyGroup, applyNewsTicker, confirmTyped, closeDropdown])

  return (
    <div className="ts-container" ref={containerRef}>
      <div className={`ts-input-row ${disabled ? 'ts-input-row--disabled' : ''}`}>

        {/* Chips: watchlist refs (@id) or individual tickers */}
        {entries.map((entry) => {
          if (entry.startsWith('@')) {
            const group = groups.find((g) => g.id === entry.slice(1))
            const label = group?.label ?? entry.slice(1)
            const count = group ? group.tickers.split(',').length : '?'
            return (
              <div key={entry} className="ts-chip ts-chip--group" title={group?.tickers ?? ''}>
                <Layers size={10} className="ts-chip-group-icon" />
                {label}
                <span className="ts-chip-meta">{count}</span>
                {!disabled && (
                  <span className="ts-chip-remove" onClick={() => handleRemoveEntry(entry)} role="button" aria-label={`Remove ${label}`}>
                    <X size={10} />
                  </span>
                )}
              </div>
            )
          }
          const isHot = hotSet.has(entry.toUpperCase())
          return (
            <div key={entry} className={`ts-chip ${isHot ? 'ts-chip--hot' : ''}`}>
              {isHot && <Flame size={10} className="ts-chip-flame" />}
              <TickerTooltip ticker={entry}>{entry}</TickerTooltip>
              {!disabled && (
                <span className="ts-chip-remove" onClick={() => handleRemoveEntry(entry)} role="button" aria-label={`Remove ${entry}`}>
                  <X size={10} />
                </span>
              )}
            </div>
          )
        })}

        <input
          ref={inputRef}
          type="text"
          className="ts-input"
          value={inputValue}
          onChange={(e) => { setInputValue(e.target.value.toUpperCase()); setShow(true); setHighlight(-1) }}
          onFocus={() => setShow(true)}
          onKeyDown={handleKeyDown}
          placeholder={entries.length === 0 ? 'Ticker, lista o watchlist…' : ''}
          disabled={disabled}
        />

        <List size={15} className="color-muted ts-icon-hint" />
      </div>

      {/* Scope status bar — always clickable; scope is a setting, not a scan action */}
      {scopeLabel && (
        <div
          className={`ts-scope-bar ${scopeLabel.hot ? 'ts-scope-bar--hot' : 'ts-scope-bar--mixed'} ${onScopeChange ? 'ts-scope-bar--clickable' : ''}`}
          onClick={() => onScopeChange && onScopeChange(scope === 'HOT' ? 'ALL' : 'HOT')}
          title={scopeLabel.hint || undefined}
          role="button"
          aria-label={scopeLabel.hint || 'Toggle scan scope'}
        >
          <Flame size={10} className="ts-scope-bar-flame" />
          {scopeLabel.text}
        </div>
      )}

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
                    onClick={() => applyGroup(g.id)}
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
                    {n.headline && <div className="text-xs color-muted ts-dropdown-headline">{n.headline}</div>}
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
