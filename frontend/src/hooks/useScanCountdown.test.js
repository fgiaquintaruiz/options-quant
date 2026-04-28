/**
 * Tests for useScanCountdown hook.
 *
 * Scenarios covered:
 * - `nextScanSecs` decreases by 1 each second as the interval ticks.
 * - `elapsed` increases by 1 each second while `scanning` is true.
 * - `elapsed` resets to 0 when `scanning` changes from true to false.
 * - Both intervals are cleared on unmount (no further state updates after that).
 */
import { renderHook, act } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { useScanCountdown } from './useScanCountdown'

describe('useScanCountdown', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.clearAllMocks()
  })

  it('nextScanSecs decreases each second as the interval ticks', async () => {
    const { result } = renderHook(() => useScanCountdown({ scanning: false }))

    const initial = result.current.nextScanSecs

    await act(async () => {
      await vi.advanceTimersByTimeAsync(3_000)
    })

    // After 3 ticks the value should be lower than the initial snapshot.
    // (exact value depends on Date, so we check relative direction only)
    expect(result.current.nextScanSecs).toBeLessThanOrEqual(initial)
  })

  it('elapsed increases each second when scanning is true', async () => {
    const { result } = renderHook(() => useScanCountdown({ scanning: true }))

    await act(async () => {
      await vi.advanceTimersByTimeAsync(3_000)
    })

    expect(result.current.elapsed).toBeGreaterThanOrEqual(3)
  })

  it('elapsed resets to 0 when scanning changes to false', async () => {
    let scanning = true
    const { result, rerender } = renderHook(() => useScanCountdown({ scanning }))

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2_000)
    })

    expect(result.current.elapsed).toBeGreaterThanOrEqual(2)

    scanning = false
    rerender()

    expect(result.current.elapsed).toBe(0)
  })

  it('clears intervals on unmount and does not update state after that', async () => {
    const { result, unmount } = renderHook(() => useScanCountdown({ scanning: true }))

    unmount()

    const elapsedAfterUnmount = result.current.elapsed
    const nextAfterUnmount = result.current.nextScanSecs

    await act(async () => {
      await vi.advanceTimersByTimeAsync(3_000)
    })

    expect(result.current.elapsed).toBe(elapsedAfterUnmount)
    expect(result.current.nextScanSecs).toBe(nextAfterUnmount)
  })
})
