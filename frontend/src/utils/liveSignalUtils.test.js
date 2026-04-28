import { describe, it, expect, vi, afterEach } from 'vitest'
import {
  formatMinSec,
  normalizeLiveMaxConcurrent,
  isSignalStale,
  SIGNAL_STALE_MS,
  checkMarketOpen,
  formatSignalTimestamp,
  formatTodayWallClock,
} from './liveSignalUtils'

afterEach(() => {
  vi.useRealTimers()
})

// ── formatMinSec ─────────────────────────────────────────────────────────────

describe('formatMinSec', () => {
  it('returns --:-- for null', () => {
    expect(formatMinSec(null)).toBe('--:--')
  })

  it('formats 0 seconds as 00:00', () => {
    expect(formatMinSec(0)).toBe('00:00')
  })

  it('formats 65 seconds as 01:05', () => {
    expect(formatMinSec(65)).toBe('01:05')
  })

  it('formats 3600 seconds as 60:00', () => {
    expect(formatMinSec(3600)).toBe('60:00')
  })
})

// ── normalizeLiveMaxConcurrent ────────────────────────────────────────────────

describe('normalizeLiveMaxConcurrent', () => {
  it('returns the value unchanged when within [1, 16]', () => {
    expect(normalizeLiveMaxConcurrent(8)).toBe(8)
  })

  it('clamps values below 1 to 1', () => {
    expect(normalizeLiveMaxConcurrent(0)).toBe(1)
    expect(normalizeLiveMaxConcurrent(-5)).toBe(1)
  })

  it('clamps values above 16 to 16', () => {
    expect(normalizeLiveMaxConcurrent(100)).toBe(16)
    expect(normalizeLiveMaxConcurrent(17)).toBe(16)
  })

  it('returns 4 for non-finite values', () => {
    expect(normalizeLiveMaxConcurrent(NaN)).toBe(4)
    expect(normalizeLiveMaxConcurrent('abc')).toBe(4)
    expect(normalizeLiveMaxConcurrent(undefined)).toBe(4)
  })

  it('rounds fractional values', () => {
    expect(normalizeLiveMaxConcurrent(2.7)).toBe(3)
    expect(normalizeLiveMaxConcurrent(2.3)).toBe(2)
  })
})

// ── isSignalStale ─────────────────────────────────────────────────────────────

describe('isSignalStale', () => {
  it('returns false for a recent timestamp', () => {
    vi.useFakeTimers()
    const now = new Date('2026-04-28T14:00:00Z')
    vi.setSystemTime(now)
    const recentTs = new Date(Date.now() - 5 * 60 * 1000).toISOString()
    expect(isSignalStale(recentTs)).toBe(false)
  })

  it('returns true for a timestamp older than SIGNAL_STALE_MS', () => {
    vi.useFakeTimers()
    const now = new Date('2026-04-28T14:00:00Z')
    vi.setSystemTime(now)
    const staleTs = new Date(Date.now() - SIGNAL_STALE_MS - 1000).toISOString()
    expect(isSignalStale(staleTs)).toBe(true)
  })

  it('returns true for null', () => {
    expect(isSignalStale(null)).toBe(true)
  })

  it('returns true for empty string', () => {
    expect(isSignalStale('')).toBe(true)
  })

  it('returns true for invalid date string', () => {
    expect(isSignalStale('not-a-date')).toBe(true)
  })
})

// ── checkMarketOpen ───────────────────────────────────────────────────────────

describe('checkMarketOpen', () => {
  it('returns true during market hours on a weekday (ET)', () => {
    // Monday 2026-04-27 at 10:00 ET = 14:00 UTC
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-04-27T14:00:00Z'))
    expect(checkMarketOpen()).toBe(true)
  })

  it('returns false before market open on a weekday (ET)', () => {
    // Monday 2026-04-27 at 08:00 ET = 12:00 UTC
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-04-27T12:00:00Z'))
    expect(checkMarketOpen()).toBe(false)
  })

  it('returns false on a Saturday (ET)', () => {
    // Saturday 2026-04-25 at 12:00 ET = 16:00 UTC
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-04-25T16:00:00Z'))
    expect(checkMarketOpen()).toBe(false)
  })

  it('returns false on a Sunday (ET)', () => {
    // Sunday 2026-04-26 at 12:00 ET = 16:00 UTC
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-04-26T16:00:00Z'))
    expect(checkMarketOpen()).toBe(false)
  })
})

// ── formatSignalTimestamp ─────────────────────────────────────────────────────

describe('formatSignalTimestamp', () => {
  it('returns — for null', () => {
    expect(formatSignalTimestamp(null)).toBe('—')
  })

  it('returns — for empty string', () => {
    expect(formatSignalTimestamp('')).toBe('—')
  })

  it('returns the original string for an unparseable value', () => {
    expect(formatSignalTimestamp('garbage')).toBe('garbage')
  })

  it('returns a formatted date string for a valid ISO timestamp', () => {
    const result = formatSignalTimestamp('2026-04-28T14:30:00Z')
    expect(typeof result).toBe('string')
    expect(result).not.toBe('—')
    expect(result.length).toBeGreaterThan(0)
  })
})

// ── formatTodayWallClock ──────────────────────────────────────────────────────

describe('formatTodayWallClock', () => {
  it('returns — for null', () => {
    expect(formatTodayWallClock(null)).toBe('—')
  })

  it('returns — for empty string', () => {
    expect(formatTodayWallClock('')).toBe('—')
  })

  it('returns — for the sentinel dash string', () => {
    expect(formatTodayWallClock('-')).toBe('—')
  })

  it('returns the original value for unparseable HH:mm:ss', () => {
    expect(formatTodayWallClock('ab:cd:ef')).toBe('ab:cd:ef')
  })

  it('returns a formatted string for a valid HH:mm:ss input', () => {
    const result = formatTodayWallClock('10:30:00')
    expect(typeof result).toBe('string')
    expect(result).not.toBe('—')
    expect(result.length).toBeGreaterThan(0)
  })
})
