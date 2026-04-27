// Loads company names for the watchlist tickers into ticker-runtime.json via the API.
// Run with: node scripts/load-tickers.mjs

const BASE = 'http://localhost:9090'

const TICKERS = {
  ADBE:  { companyName: 'Adobe Inc.',                        sector: 'Technology' },
  AMD:   { companyName: 'Advanced Micro Devices',            sector: 'Technology' },
  APD:   { companyName: 'Air Products & Chemicals',          sector: 'Materials' },
  ABNB:  { companyName: 'Airbnb',                            sector: 'Consumer Discretionary' },
  GOOG:  { companyName: 'Alphabet Inc. (Class C)',           sector: 'Technology' },
  GOOGL: { companyName: 'Alphabet Inc. (Class A)',           sector: 'Technology' },
  AMZN:  { companyName: 'Amazon.com Inc.',                   sector: 'Consumer Discretionary' },
  EAT:   { companyName: 'AmRest Holdings',                   sector: 'Consumer Discretionary' },
  AAPL:  { companyName: 'Apple Inc.',                        sector: 'Technology' },
  T:     { companyName: 'AT&T Inc.',                         sector: 'Communication Services' },
  A3M:   { companyName: 'Atresmedia Corporación',            sector: 'Communication Services' },
  BABA:  { companyName: 'Alibaba Group Holding',             sector: 'Consumer Discretionary' },
  AVGO:  { companyName: 'Broadcom Inc.',                     sector: 'Technology' },
  CAPR:  { companyName: 'Capricor Therapeutics',             sector: 'Health Care' },
  SCHW:  { companyName: 'Charles Schwab Corporation',        sector: 'Financials' },
  CVX:   { companyName: 'Chevron Corporation',               sector: 'Energy' },
  CSCO:  { companyName: 'Cisco Systems',                     sector: 'Technology' },
  C:     { companyName: 'Citigroup Inc.',                    sector: 'Financials' },
  DELL:  { companyName: 'Dell Technologies',                 sector: 'Technology' },
  SOXL:  { companyName: 'Direxion Daily Semiconductor Bull 3X ETF', sector: 'ETF' },
  TNA:   { companyName: 'Direxion Daily Small Cap Bull 3X ETF',     sector: 'ETF' },
  XOM:   { companyName: 'Exxon Mobil Corporation',           sector: 'Energy' },
  GE:    { companyName: 'GE Aerospace',                      sector: 'Industrials' },
  URA:   { companyName: 'Global X Uranium ETF',              sector: 'ETF' },
  AMDL:  { companyName: 'GraniteShares 2x Long AMD Daily ETF', sector: 'ETF' },
  QQQ:   { companyName: 'Invesco QQQ Trust',                 sector: 'ETF' },
  TISG:  { companyName: 'Italian Sea Group SpA',             sector: 'Consumer Discretionary' },
  JNJ:   { companyName: 'Johnson & Johnson',                 sector: 'Health Care' },
  JPM:   { companyName: 'JPMorgan Chase & Co.',              sector: 'Financials' },
  LCID:  { companyName: 'Lucid Group',                       sector: 'Consumer Discretionary' },
  MA:    { companyName: 'Mastercard Incorporated',           sector: 'Financials' },
  MBG:   { companyName: 'Mercedes-Benz Group AG',            sector: 'Consumer Discretionary' },
  MU:    { companyName: 'Micron Technology',                 sector: 'Technology' },
  MSFT:  { companyName: 'Microsoft Corporation',             sector: 'Technology' },
  NKE:   { companyName: 'Nike Inc.',                         sector: 'Consumer Discretionary' },
  NVDA:  { companyName: 'NVIDIA Corporation',                sector: 'Technology' },
  OKLO:  { companyName: 'Oklo Inc.',                         sector: 'Industrials' },
  PYPL:  { companyName: 'PayPal Holdings Inc.',              sector: 'Financials' },
  TQQQ:  { companyName: 'ProShares UltraPro QQQ',            sector: 'ETF' },
  PUIG:  { companyName: 'Puig Brands S.A.',                  sector: 'Consumer Staples' },
  QCOM:  { companyName: 'Qualcomm Incorporated',             sector: 'Technology' },
  REP:   { companyName: 'Repsol S.A.',                       sector: 'Energy' },
  SBUX:  { companyName: 'Starbucks Corporation',             sector: 'Consumer Discretionary' },
  SPY:   { companyName: 'SPDR S&P 500 ETF Trust',            sector: 'ETF' },
  TRE:   { companyName: 'Técnicas Reunidas S.A.',            sector: 'Industrials' },
  TSLA:  { companyName: 'Tesla Inc.',                        sector: 'Consumer Discretionary' },
  KO:    { companyName: 'The Coca-Cola Company',             sector: 'Consumer Staples' },
  THNC:  { companyName: 'Thinkific Labs Inc.',               sector: 'Technology' },
  UNH:   { companyName: 'UnitedHealth Group',                sector: 'Health Care' },
  NLR:   { companyName: 'VanEck Uranium and Nuclear ETF',    sector: 'ETF' },
  VZ:    { companyName: 'Verizon Communications',            sector: 'Communication Services' },
  V:     { companyName: 'Visa Inc.',                         sector: 'Financials' },
  WMT:   { companyName: 'Walmart Inc.',                      sector: 'Consumer Staples' },
  WFC:   { companyName: 'Wells Fargo & Company',             sector: 'Financials' },
}

async function run() {
  // 1. GET current config
  const res = await fetch(`${BASE}/api/ticker-config`)
  const current = await res.json()

  // 2. Merge new tickers — preserve existing data, only fill missing companyName/sector
  const fundamentals = { ...(current.fundamentals ?? {}) }
  let added = 0, updated = 0

  for (const [ticker, info] of Object.entries(TICKERS)) {
    const existing = fundamentals[ticker] ?? {}
    const wasEmpty = !existing.companyName
    fundamentals[ticker] = {
      ...existing,
      companyName: existing.companyName || info.companyName,
      sector:      existing.sector      || info.sector,
    }
    if (wasEmpty) added++
    else if (existing.companyName !== fundamentals[ticker].companyName) updated++
  }

  // 3. PUT back
  const body = {
    universe:   current.universe   ?? [],
    hotTickers: current.hotTickers ?? [],
    fundamentals,
  }
  const putRes = await fetch(`${BASE}/api/ticker-config`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })

  if (putRes.ok) {
    console.log(`✅ Done — ${added} added, ${updated} updated, ${Object.keys(fundamentals).length} total fundamentals`)
  } else {
    console.error('❌ PUT failed:', putRes.status, await putRes.text())
  }
}

run().catch(console.error)
