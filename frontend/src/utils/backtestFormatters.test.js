import { describe, it, expect, vi, afterEach } from 'vitest'
import {
  formatUsd,
  formatSignedPct,
  formatDrawdownPctPeak,
  formatWinRatePct,
  formatTradeTimeDisplay,
  computeScanDurationHms,
  computeTradeDuration,
  downsampleEquityForChart,
  isoDate,
  lookbackMonthsToRange,
  inferCallPutFromStrategyName,
} from './backtestFormatters'

afterEach(() => {
  vi.useRealTimers()
})

// ── formatUsd ─────────────────────────────────────────────────────────────────

describe('formatUsd', () => {
  it('returns a locale string for a positive finite number', () => {
    const result = formatUsd(1234.56)
    expect(typeof result).toBe('string')
    expect(result).toMatch(/\d/)
  })

  it('returns a locale string for 0', () => {
    const result = formatUsd(0)
    expect(typeof result).toBe('string')
    expect(result).toMatch(/0/)
  })

  it('returns a locale string for a negative number', () => {
    const result = formatUsd(-500)
    expect(typeof result).toBe('string')
    expect(result).toMatch(/\d/)
  })

  it('returns — for NaN', () => {
    expect(formatUsd(NaN)).toBe('—')
  })

  it('returns — for null', () => {
    expect(formatUsd(null)).toBe('—')
  })

  it('returns — for undefined', () => {
    expect(formatUsd(undefined)).toBe('—')
  })

  it('returns — for a non-numeric string', () => {
    expect(formatUsd('abc')).toBe('—')
  })
})

// ── formatSignedPct ───────────────────────────────────────────────────────────

describe('formatSignedPct', () => {
  it('formats a positive ratio with a leading plus sign', () => {
    expect(formatSignedPct(0.052)).toBe('+5.20%')
  })

  it('formats a negative ratio without a plus sign', () => {
    expect(formatSignedPct(-0.12)).toBe('-12.00%')
  })

  it('formats zero without a plus sign', () => {
    expect(formatSignedPct(0)).toBe('0.00%')
  })

  it('returns — for NaN', () => {
    expect(formatSignedPct(NaN)).toBe('—')
  })

  it('returns — for undefined', () => {
    expect(formatSignedPct(undefined)).toBe('—')
  })

  it('returns — for a non-numeric string', () => {
    expect(formatSignedPct('abc')).toBe('—')
  })
})

// ── formatDrawdownPctPeak ─────────────────────────────────────────────────────

describe('formatDrawdownPctPeak', () => {
  it('formats 0.15 as 15.00%', () => {
    expect(formatDrawdownPctPeak(0.15)).toBe('15.00%')
  })

  it('formats 0 as 0.00%', () => {
    expect(formatDrawdownPctPeak(0)).toBe('0.00%')
  })

  it('formats 1 as 100.00%', () => {
    expect(formatDrawdownPctPeak(1)).toBe('100.00%')
  })

  it('returns — for NaN', () => {
    expect(formatDrawdownPctPeak(NaN)).toBe('—')
  })

  it('returns — for undefined', () => {
    expect(formatDrawdownPctPeak(undefined)).toBe('—')
  })
})

// ── formatWinRatePct ──────────────────────────────────────────────────────────

describe('formatWinRatePct', () => {
  it('formats 0.6 as 60.0%', () => {
    expect(formatWinRatePct(0.6)).toBe('60.0%')
  })

  it('formats 0 as 0.0%', () => {
    expect(formatWinRatePct(0)).toBe('0.0%')
  })

  it('formats 1 as 100.0%', () => {
    expect(formatWinRatePct(1)).toBe('100.0%')
  })

  it('returns — for NaN', () => {
    expect(formatWinRatePct(NaN)).toBe('—')
  })

  it('returns — for undefined', () => {
    expect(formatWinRatePct(undefined)).toBe('—')
  })
})

// ── formatTradeTimeDisplay ────────────────────────────────────────────────────

describe('formatTradeTimeDisplay', () => {
  it('returns — for null', () => {
    expect(formatTradeTimeDisplay(null)).toBe('—')
  })

  it('returns — for the sentinel dash string', () => {
    expect(formatTradeTimeDisplay('-')).toBe('—')
  })

  it('returns a short string as-is', () => {
    expect(formatTradeTimeDisplay('09:30')).toBe('09:30')
  })

  it('returns a string exactly 16 chars with space as-is', () => {
    const s = '2024-01-15 09:30'
    expect(s.length).toBe(16)
    expect(formatTradeTimeDisplay(s)).toBe(s)
  })

  it('returns a string of 19 chars with space as-is', () => {
    const s = '2024-01-15 09:30:00'
    expect(s.length).toBe(19)
    expect(formatTradeTimeDisplay(s)).toBe(s)
  })

  it('slices to 19 chars when string is longer than 19 and contains a space', () => {
    const s = '2024-01-15 09:30:00.000'
    expect(formatTradeTimeDisplay(s)).toBe('2024-01-15 09:30:00')
  })

  it('returns a string without space as-is regardless of length', () => {
    const s = '2024-01-15T09:30:00'
    expect(formatTradeTimeDisplay(s)).toBe(s)
  })
})

// ── computeScanDurationHms ────────────────────────────────────────────────────

describe('computeScanDurationHms', () => {
  it('returns — for null start', () => {
    expect(computeScanDurationHms(null, '10:00:00')).toBe('—')
  })

  it('returns — for null end', () => {
    expect(computeScanDurationHms('10:00:00', null)).toBe('—')
  })

  it('returns — when end is the sentinel dash string', () => {
    expect(computeScanDurationHms('10:00:00', '-')).toBe('—')
  })

  it('computes duration in seconds for a normal case', () => {
    expect(computeScanDurationHms('10:00:00', '10:00:30')).toBe('30.0s')
  })

  it('handles sub-second precision with fractional seconds', () => {
    expect(computeScanDurationHms('09:00:00', '09:01:30')).toBe('90.0s')
  })

  it('adds 86400 s when end is earlier than start (midnight rollover)', () => {
    const result = computeScanDurationHms('23:59:50', '00:00:10')
    expect(result).toBe('20.0s')
  })
})

// ── computeTradeDuration ──────────────────────────────────────────────────────

describe('computeTradeDuration', () => {
  it('returns — for null entry', () => {
    expect(computeTradeDuration(null, '2024-01-15 09:30:30')).toBe('—')
  })

  it('returns — for null exit', () => {
    expect(computeTradeDuration('2024-01-15 09:30:00', null)).toBe('—')
  })

  it('returns — when exit is the sentinel dash string', () => {
    expect(computeTradeDuration('2024-01-15 09:30:00', '-')).toBe('—')
  })

  it('returns — for invalid date strings', () => {
    expect(computeTradeDuration('not-a-date', 'also-not')).toBe('—')
  })

  it('formats duration under 60 seconds', () => {
    expect(computeTradeDuration('2024-01-15 09:30:00', '2024-01-15 09:30:30')).toBe('30.0s')
  })

  it('formats duration in minutes and seconds', () => {
    expect(computeTradeDuration('2024-01-15 09:30:00', '2024-01-15 09:32:45')).toBe('2m 45s')
  })

  it('formats duration in hours and minutes', () => {
    expect(computeTradeDuration('2024-01-15 09:00:00', '2024-01-15 10:30:00')).toBe('1h 30m')
  })

  it('returns — for negative duration (exit before entry)', () => {
    expect(computeTradeDuration('2024-01-15 09:30:30', '2024-01-15 09:30:00')).toBe('—')
  })

  it('handles TWS underscore format correctly', () => {
    expect(computeTradeDuration('2024-01-15_09:30:00', '2024-01-15_09:30:45')).toBe('45.0s')
  })
})

// ── downsampleEquityForChart ──────────────────────────────────────────────────

describe('downsampleEquityForChart', () => {
  it('returns the same array for an empty input', () => {
    const arr = []
    expect(downsampleEquityForChart(arr)).toBe(arr)
  })

  it('returns the same array when length is exactly maxPoints', () => {
    const arr = Array.from({ length: 160 }, (_, i) => i)
    expect(downsampleEquityForChart(arr)).toBe(arr)
  })

  it('returns the same array when length is below maxPoints', () => {
    const arr = Array.from({ length: 10 }, (_, i) => i)
    expect(downsampleEquityForChart(arr)).toBe(arr)
  })

  it('downsamples to maxPoints when the array is larger', () => {
    const arr = Array.from({ length: 500 }, (_, i) => i)
    const result = downsampleEquityForChart(arr)
    expect(result.length).toBe(160)
  })

  it('always includes the first point', () => {
    const arr = Array.from({ length: 500 }, (_, i) => i)
    const result = downsampleEquityForChart(arr)
    expect(result[0]).toBe(0)
  })

  it('always includes the last point', () => {
    const arr = Array.from({ length: 500 }, (_, i) => i)
    const result = downsampleEquityForChart(arr)
    expect(result[result.length - 1]).toBe(499)
  })

  it('respects a custom maxPoints parameter', () => {
    const arr = Array.from({ length: 500 }, (_, i) => i)
    const result = downsampleEquityForChart(arr, 50)
    expect(result.length).toBe(50)
  })
})

// ── isoDate ───────────────────────────────────────────────────────────────────

describe('isoDate', () => {
  it('returns a 10-character string', () => {
    const result = isoDate(new Date('2026-04-28T12:00:00Z'))
    expect(result.length).toBe(10)
  })

  it('returns the correct YYYY-MM-DD string', () => {
    expect(isoDate(new Date('2026-04-28T12:00:00Z'))).toBe('2026-04-28')
  })

  it('returns the correct date for beginning of year', () => {
    expect(isoDate(new Date('2024-01-01T00:00:00Z'))).toBe('2024-01-01')
  })
})

// ── lookbackMonthsToRange ─────────────────────────────────────────────────────

describe('lookbackMonthsToRange', () => {
  it('returns the correct range for 3 months back from 2026-04-28', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-04-28T12:00:00Z'))
    expect(lookbackMonthsToRange(3)).toEqual({ from: '2026-01-28', to: '2026-04-28' })
  })

  it('returns the same date for both from and to when months is 0', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-04-28T12:00:00Z'))
    expect(lookbackMonthsToRange(0)).toEqual({ from: '2026-04-28', to: '2026-04-28' })
  })

  it('returns a 12-month range correctly', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-04-28T12:00:00Z'))
    expect(lookbackMonthsToRange(12)).toEqual({ from: '2025-04-28', to: '2026-04-28' })
  })
})

// ── inferCallPutFromStrategyName ──────────────────────────────────────────────

describe('inferCallPutFromStrategyName', () => {
  it('returns true for a name starting with c followed by a digit', () => {
    expect(inferCallPutFromStrategyName('c1 squeeze')).toBe(true)
  })

  it('returns false for a name starting with p followed by a digit', () => {
    expect(inferCallPutFromStrategyName('p5 continuation')).toBe(false)
  })

  it('returns true for a name containing the word call', () => {
    expect(inferCallPutFromStrategyName('iron condor call')).toBe(true)
  })

  it('returns false for a name containing the word put', () => {
    expect(inferCallPutFromStrategyName('iron condor put')).toBe(false)
  })

  it('returns false for a name that matches neither call nor put keyword', () => {
    expect(inferCallPutFromStrategyName('iron condor')).toBe(false)
  })

  it('returns false for null', () => {
    expect(inferCallPutFromStrategyName(null)).toBe(false)
  })

  it('returns false for a number', () => {
    expect(inferCallPutFromStrategyName(42)).toBe(false)
  })

  it('returns false for an empty string', () => {
    expect(inferCallPutFromStrategyName('')).toBe(false)
  })

  it('is case-insensitive', () => {
    expect(inferCallPutFromStrategyName('C1 SQUEEZE')).toBe(true)
    expect(inferCallPutFromStrategyName('Iron Condor PUT')).toBe(false)
  })
})
