/**
 * Tests for useMarketOpen hook.
 *
 * Scenarios covered:
 * - Initial state reflects the return value of checkMarketOpen (true / false).
 * - After 30 seconds the hook re-polls checkMarketOpen via its interval.
 * - The interval is cleared on unmount so no further calls happen after that.
 */
import { renderHook, act } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { useMarketOpen } from './useMarketOpen'

vi.mock('../utils/liveSignalUtils', () => ({
  checkMarketOpen: vi.fn(),
}))

import { checkMarketOpen } from '../utils/liveSignalUtils'

describe('useMarketOpen', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.clearAllMocks()
  })

  it('returns true when checkMarketOpen returns true', () => {
    checkMarketOpen.mockReturnValue(true)
    const { result } = renderHook(() => useMarketOpen())
    expect(result.current).toBe(true)
  })

  it('returns false when checkMarketOpen returns false', () => {
    checkMarketOpen.mockReturnValue(false)
    const { result } = renderHook(() => useMarketOpen())
    expect(result.current).toBe(false)
  })

  it('calls checkMarketOpen again after 30s interval', async () => {
    checkMarketOpen.mockReturnValue(false)
    renderHook(() => useMarketOpen())

    const callsBefore = checkMarketOpen.mock.calls.length

    await act(async () => {
      await vi.advanceTimersByTimeAsync(30_000)
    })

    expect(checkMarketOpen.mock.calls.length).toBeGreaterThan(callsBefore)
  })

  it('clears the interval on unmount and does not call checkMarketOpen after that', async () => {
    checkMarketOpen.mockReturnValue(true)
    const { unmount } = renderHook(() => useMarketOpen())

    unmount()

    const callsAfterUnmount = checkMarketOpen.mock.calls.length

    await act(async () => {
      await vi.advanceTimersByTimeAsync(30_000)
    })

    expect(checkMarketOpen.mock.calls.length).toBe(callsAfterUnmount)
  })
})
