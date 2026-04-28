// Formatters and helpers shared across Backtest UI components.
// API contract: ratio fields (winRate, totalReturnPct, maxDrawdownPct) are 0–1;
// dollar amounts are plain numbers; timestamps may use TWS underscore format.

// Returns locale-aware thousands-separated USD string, or '—'.
export function formatUsd(v) {
  if (v == null) return '—'
  const n = Number(v)
  return Number.isFinite(n) ? n.toLocaleString() : '—'
}

// Formats a signed percentage from a 0–1 API ratio (e.g. 0.052 → "+5.20%").
export function formatSignedPct(v) {
  const n = Number(v)
  if (!Number.isFinite(n)) return '—'
  const pct = n * 100
  return `${pct > 0 ? '+' : ''}${pct.toFixed(2)}%`
}

// Formats a drawdown percentage from a 0–1 ratio (e.g. 0.15 → "15.00%").
export function formatDrawdownPctPeak(v) {
  const n = Number(v)
  if (!Number.isFinite(n)) return '—'
  return `${(n * 100).toFixed(2)}%`
}

// Formats a win-rate percentage from a 0–1 ratio (e.g. 0.6 → "60.0%").
export function formatWinRatePct(v) {
  const n = Number(v)
  if (!Number.isFinite(n)) return '—'
  return `${(n * 100).toFixed(1)}%`
}

// Truncates a TWS timestamp to "YYYY-MM-DD HH:mm:ss" for compact display.
export function formatTradeTimeDisplay(t) {
  if (!t || t === '-') return '—'
  const s = String(t).trim()
  if (s.length >= 16 && s.includes(' ')) return s.length > 19 ? s.slice(0, 19) : s
  return s
}

// Duration between HH:mm:ss strings (same-day only; adds 86400 s for midnight rollover).
export function computeScanDurationHms(start, end) {
  if (!start || !end || end === '-') return '—'
  try {
    const parse = (t) => {
      const p = String(t).split(':').map(Number)
      return (p[0] || 0) * 3600 + (p[1] || 0) * 60 + (p[2] || 0)
    }
    let sec = parse(end) - parse(start)
    if (sec < 0) sec += 86400
    return `${sec.toFixed(1)}s`
  } catch {
    return '—'
  }
}

// Duration between two date strings; TWS uses underscores ("2024-01-15_09:30") as separators.
export function computeTradeDuration(entry, exit) {
  if (!entry || !exit || exit === '-') return '—'
  const d1 = Date.parse(String(entry).replace(/_/g, ' '))
  const d2 = Date.parse(String(exit).replace(/_/g, ' '))
  if (Number.isNaN(d1) || Number.isNaN(d2)) return '—'
  const sec = Math.round((d2 - d1) / 1000)
  if (sec < 0) return '—'
  if (sec < 60) return `${sec.toFixed(1)}s`
  const m = Math.floor(sec / 60)
  const s = sec % 60
  if (m < 60) return `${m}m ${s}s`
  const h = Math.floor(m / 60)
  return `${h}h ${m % 60}m`
}

// Downsamples equity curve to maxPoints, keeping first and last, to reduce chart clutter.
export function downsampleEquityForChart(points, maxPoints = 160) {
  if (!points.length || points.length <= maxPoints) return points
  const n = points.length
  const out = []
  const step = (n - 1) / (maxPoints - 1)
  for (let i = 0; i < maxPoints; i++) {
    const idx = Math.min(n - 1, Math.round(i * step))
    out.push(points[idx])
  }
  return out
}

export function isoDate(d) {
  return d.toISOString().slice(0, 10)
}

// Date range spanning the last N months from today.
export function lookbackMonthsToRange(months) {
  const to = new Date()
  const from = new Date(to)
  from.setMonth(from.getMonth() - months)
  return { from: isoDate(from), to: isoDate(to) }
}

// Infers isCall from strategy name convention: "c1 squeeze" → call, "p5 continuation" → put.
// Required because PromoteRiskRequest.isCall has no equivalent field in the trade log API.
export function inferCallPutFromStrategyName(name) {
  if (!name || typeof name !== 'string') return false
  const lower = name.trim().toLowerCase()
  if (/^c\d/.test(lower)) return true
  if (/^p\d/.test(lower)) return false
  if (/\bput\b/.test(lower) || lower.includes(' put')) return false
  if (/\bcall\b/.test(lower) || lower.includes(' call')) return true
  return false
}
