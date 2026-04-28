/** Formats ISO-8601 timestamp as locale date+time. Returns '—' for null/invalid. */
export function formatSignalTimestamp(ts) {
  if (ts == null || ts === '') return '—'
  const d = new Date(ts)
  if (Number.isNaN(d.getTime())) return typeof ts === 'string' ? ts : String(ts)
  return d.toLocaleString('es-ES', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

/** Backend sends HH:mm:ss for executions — combine with today's date for display. */
export function formatTodayWallClock(hms) {
  if (hms == null || hms === '' || hms === '-') return '—'
  const parts = String(hms).trim().split(':').map((x) => parseInt(x, 10))
  if (parts.some((n) => Number.isNaN(n))) return String(hms)
  const d = new Date()
  d.setHours(parts[0] ?? 0, parts[1] ?? 0, parts[2] ?? 0, 0)
  return d.toLocaleString('es-ES', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit' })
}

/** Signals older than 30 minutes are stale — Open/execute is blocked. */
export const SIGNAL_STALE_MS = 30 * 60 * 1000
export const isSignalStale = (ts) => {
  if (ts == null || ts === '') return true
  const t = new Date(ts).getTime()
  return Number.isNaN(t) || Date.now() - t > SIGNAL_STALE_MS
}

/** Clamps concurrent pool size to [1, 16]. */
export const normalizeLiveMaxConcurrent = (v) => {
  const n = Number(v)
  return Number.isFinite(n) ? Math.min(16, Math.max(1, Math.round(n))) : 4
}

export const formatMinSec = (s) => {
  if (s == null) return '--:--'
  const m = Math.floor(s / 60)
  return `${String(m).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`
}

export function checkMarketOpen() {
  const et = new Date(new Date().toLocaleString('en-US', { timeZone: 'America/New_York' }))
  const day = et.getDay()
  if (day === 0 || day === 6) return false
  const mins = et.getHours() * 60 + et.getMinutes()
  return mins >= 9 * 60 + 30 && mins < 16 * 60
}
