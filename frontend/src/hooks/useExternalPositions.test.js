import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'
import { useExternalPositions } from './useExternalPositions'
import * as api from '../api'

vi.mock('../api', () => ({
  externalPositionsApi: {
    getExternalPositions: vi.fn(),
  },
}))

const POSITIONS = [
  { ticker: 'AAPL', secType: 'STK', quantity: 100, avgCost: 170.5, snapshotAt: '10:00:00' },
  { ticker: 'NVDA', secType: 'STK', quantity: 50,  avgCost: 800.0, snapshotAt: '10:01:00' },
]

describe('useExternalPositions', () => {
  beforeEach(() => {
    api.externalPositionsApi.getExternalPositions.mockResolvedValue({ positions: POSITIONS })
  })

  afterEach(() => {
    vi.clearAllMocks()
  })

  // ── Initial state ────────────────────────────────────────────────────────

  it('starts with loading=true, positions=[], error=null', () => {
    const { result } = renderHook(() => useExternalPositions())
    expect(result.current.loading).toBe(true)
    expect(result.current.positions).toEqual([])
    expect(result.current.error).toBeNull()
  })

  // ── Load on mount ────────────────────────────────────────────────────────

  it('fetches on mount and populates positions', async () => {
    const { result } = renderHook(() => useExternalPositions())

    await waitFor(() => expect(result.current.loading).toBe(false))

    expect(result.current.positions).toEqual(POSITIONS)
    expect(result.current.error).toBeNull()
    expect(api.externalPositionsApi.getExternalPositions).toHaveBeenCalledTimes(1)
  })

  // ── setInterval polling ──────────────────────────────────────────────────

  it('re-fetches every 10 seconds via setInterval', async () => {
    vi.useFakeTimers()
    try {
      const { result } = renderHook(() => useExternalPositions())

      // Flush the initial mount fetch
      await act(async () => { await vi.runAllTicks() })
      await act(async () => { await Promise.resolve() })

      const callsAfterMount = api.externalPositionsApi.getExternalPositions.mock.calls.length

      // Advance 10s → triggers interval
      await act(async () => {
        vi.advanceTimersByTime(10_000)
        await vi.runAllTicks()
        await Promise.resolve()
      })

      expect(api.externalPositionsApi.getExternalPositions.mock.calls.length).toBeGreaterThan(callsAfterMount)
    } finally {
      vi.useRealTimers()
    }
  })

  // ── Cleanup: no fetch after unmount ─────────────────────────────────────

  it('clears interval on unmount — no additional fetches after unmount', async () => {
    vi.useFakeTimers()
    try {
      const { unmount } = renderHook(() => useExternalPositions())

      // Let mount fetch settle
      await act(async () => { await vi.runAllTicks(); await Promise.resolve() })

      const callsBeforeUnmount = api.externalPositionsApi.getExternalPositions.mock.calls.length

      unmount()

      // Advance 30s — should NOT trigger any more calls
      await act(async () => {
        vi.advanceTimersByTime(30_000)
        await vi.runAllTicks()
        await Promise.resolve()
      })

      expect(api.externalPositionsApi.getExternalPositions.mock.calls.length).toBe(callsBeforeUnmount)
    } finally {
      vi.useRealTimers()
    }
  })

  // ── Error path — keeps previous positions ────────────────────────────────

  it('on error surfaces error state, keeps last successful positions', async () => {
    const { result } = renderHook(() => useExternalPositions())

    // First fetch succeeds — populate positions
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.positions).toEqual(POSITIONS)

    // Make the next fetch fail
    api.externalPositionsApi.getExternalPositions.mockRejectedValueOnce(
      new Error('HTTP 503: TWS not connected')
    )

    // Trigger refresh to fire the failing fetch
    await act(async () => { await result.current.refresh() })

    expect(result.current.error).not.toBeNull()
    // Positions MUST be kept (stale data better than blank)
    expect(result.current.positions).toEqual(POSITIONS)
    expect(result.current.error.message).toContain('503')
    // loading stays false once we have data
    expect(result.current.loading).toBe(false)
  })

  // ── refresh() triggers immediate fetch ──────────────────────────────────

  it('refresh() triggers an immediate fetch outside the interval', async () => {
    const { result } = renderHook(() => useExternalPositions())

    // Wait for mount fetch
    await waitFor(() =>
      expect(api.externalPositionsApi.getExternalPositions).toHaveBeenCalledTimes(1)
    )

    // Call refresh — should increment call count by 1
    await act(async () => { await result.current.refresh() })

    expect(api.externalPositionsApi.getExternalPositions).toHaveBeenCalledTimes(2)
  })
})
