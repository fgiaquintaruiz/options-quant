import { renderHook, act } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

vi.mock('../api', () => ({
  liveApi: {
    getStatus: vi.fn(),
    getSignals: vi.fn(),
    getScanActivity: vi.fn(),
    getScanScores: vi.fn(),
    startScan: vi.fn(),
    stopScan: vi.fn(),
    forceStop: vi.fn(),
    toggleScheduler: vi.fn(),
  },
}))

import { liveApi } from '../api'
import { useLiveScanner } from './useLiveScanner'

function makeStatus(overrides = {}) {
  return {
    isScanning: false,
    stopScanRequested: false,
    hotTickersList: [],
    macroRegime: null,
    macroMomentum: null,
    macroSummary: null,
    exclusiveScanLockHeld: false,
    ...overrides,
  }
}

function setupApi() {
  liveApi.getStatus.mockResolvedValue(makeStatus())
  liveApi.getSignals.mockResolvedValue({ signals: [], closedTrades: {} })
  liveApi.getScanActivity.mockResolvedValue({ activity: [] })
  liveApi.getScanScores.mockResolvedValue({ scoresByTicker: {}, scanStartedAt: null })
  liveApi.startScan.mockResolvedValue({ success: true })
  liveApi.stopScan.mockResolvedValue(undefined)
  liveApi.forceStop.mockResolvedValue(undefined)
  liveApi.toggleScheduler.mockResolvedValue(undefined)
}

// Drain pending microtasks after advancing timers
async function flush() {
  await Promise.resolve()
  await Promise.resolve()
  await Promise.resolve()
}

describe('useLiveScanner', () => {
  let setErrorMsg

  beforeEach(() => {
    vi.useFakeTimers()
    setErrorMsg = vi.fn()
    setupApi()
  })

  afterEach(() => {
    vi.clearAllMocks()
    vi.useRealTimers()
  })

  // ── Section 1 — Initial state and mount fetch ────────────────────────────

  it('initial state: status=null, signals=[], closedTrades={}, scanning=false, stopRequested=false', () => {
    liveApi.getStatus.mockReturnValue(new Promise(() => {}))
    liveApi.getSignals.mockReturnValue(new Promise(() => {}))
    liveApi.getScanActivity.mockReturnValue(new Promise(() => {}))
    liveApi.getScanScores.mockReturnValue(new Promise(() => {}))

    const { result } = renderHook(() => useLiveScanner(setErrorMsg))

    expect(result.current.status).toBeNull()
    expect(result.current.signals).toEqual([])
    expect(result.current.closedTrades).toEqual({})
    expect(result.current.scanActivity).toEqual([])
    expect(result.current.scanScores).toEqual({})
    expect(result.current.scanning).toBe(false)
    expect(result.current.stopRequested).toBe(false)
  })

  it('on mount, calls liveApi.getStatus exactly once and sets status', async () => {
    const statusPayload = makeStatus({ isScanning: true })
    liveApi.getStatus.mockResolvedValue(statusPayload)

    const { result } = renderHook(() => useLiveScanner(setErrorMsg))

    await act(async () => { await flush() })

    // Only the initial single-fetch effect; the 1s interval hasn't fired yet
    expect(liveApi.getStatus).toHaveBeenCalledTimes(1)
    expect(result.current.status).toEqual(statusPayload)
  })

  it('on mount, if getStatus rejects, calls setErrorMsg with connection error message', async () => {
    liveApi.getStatus.mockRejectedValue(new Error('ECONNREFUSED'))

    renderHook(() => useLiveScanner(setErrorMsg))

    await act(async () => { await flush() })

    expect(setErrorMsg).toHaveBeenCalledWith('Failed to connect to backend: ECONNREFUSED')
  })

  it('on mount, kicks off fetchSignals (getSignals called on mount)', async () => {
    renderHook(() => useLiveScanner(setErrorMsg))

    await act(async () => { await flush() })

    expect(liveApi.getSignals).toHaveBeenCalled()
  })

  // ── Section 2 — Polling intervals ───────────────────────────────────────

  it('after 1000ms, getStatus, getScanActivity, getScanScores are each called again', async () => {
    const { unmount } = renderHook(() => useLiveScanner(setErrorMsg))
    await act(async () => { await flush() })

    const statusBefore = liveApi.getStatus.mock.calls.length
    const activityBefore = liveApi.getScanActivity.mock.calls.length
    const scoresBefore = liveApi.getScanScores.mock.calls.length

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_000)
      await flush()
    })

    expect(liveApi.getStatus.mock.calls.length).toBeGreaterThan(statusBefore)
    expect(liveApi.getScanActivity.mock.calls.length).toBeGreaterThan(activityBefore)
    expect(liveApi.getScanScores.mock.calls.length).toBeGreaterThan(scoresBefore)
    unmount()
  })

  it('after 1500ms, getSignals is called again', async () => {
    const { unmount } = renderHook(() => useLiveScanner(setErrorMsg))
    await act(async () => { await flush() })

    const signalsBefore = liveApi.getSignals.mock.calls.length

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_500)
      await flush()
    })

    expect(liveApi.getSignals.mock.calls.length).toBeGreaterThan(signalsBefore)
    unmount()
  })

  it('polling errors are swallowed — hook continues without throwing', async () => {
    liveApi.getStatus.mockRejectedValue(new Error('network error'))
    liveApi.getScanActivity.mockRejectedValue(new Error('network error'))
    liveApi.getScanScores.mockRejectedValue(new Error('network error'))
    liveApi.getSignals.mockRejectedValue(new Error('network error'))

    const { result, unmount } = renderHook(() => useLiveScanner(setErrorMsg))

    // Drain the initial mount errors — should not throw at the test level
    await act(async () => {
      await Promise.resolve()
      await Promise.resolve()
      await Promise.resolve()
    })

    expect(result.current.signals).toEqual([])

    // Unmount before advancing timers to prevent pending rejected promises from bleeding
    unmount()

    // Re-resolve any remaining rejected promises so they don't pollute the next test
    await Promise.resolve()
    await Promise.resolve()
  })

  it('on unmount, intervals are cleared — no further api calls after unmount', async () => {
    const { unmount } = renderHook(() => useLiveScanner(setErrorMsg))

    // Drain mount tick
    await act(async () => {
      await Promise.resolve()
      await Promise.resolve()
      await Promise.resolve()
    })

    // Capture calls after initial mount (before unmount)
    unmount()
    // Drain any final cleanup effects
    await act(async () => {
      await Promise.resolve()
      await Promise.resolve()
    })

    const callsAfterUnmount = liveApi.getStatus.mock.calls.length

    // Advance 3s — if intervals were properly cleared, getStatus should not be called again
    await act(async () => {
      await vi.advanceTimersByTimeAsync(3_000)
      await Promise.resolve()
      await Promise.resolve()
    })

    expect(liveApi.getStatus.mock.calls.length).toBe(callsAfterUnmount)
  })

  // ── Section 3 — Action handlers ─────────────────────────────────────────

  it('handleStartScan: clears errorMsg first, then calls liveApi.startScan', async () => {
    const { result } = renderHook(() => useLiveScanner(setErrorMsg))
    await act(async () => { await flush() })

    await act(async () => { await result.current.handleStartScan() })

    expect(setErrorMsg).toHaveBeenCalledWith(null)
    expect(liveApi.startScan).toHaveBeenCalledTimes(1)
  })

  it('handleStartScan when api throws: setErrorMsg called with "Scan failed: <msg>"', async () => {
    liveApi.startScan.mockRejectedValue(new Error('TWS down'))
    const { result } = renderHook(() => useLiveScanner(setErrorMsg))
    await act(async () => { await flush() })

    await act(async () => { await result.current.handleStartScan() })

    expect(setErrorMsg).toHaveBeenCalledWith('Scan failed: TWS down')
  })

  it('handleStartScan when api returns {success:false, message}: setErrorMsg called with message', async () => {
    liveApi.startScan.mockResolvedValue({ success: false, message: 'blocked by lock' })
    const { result } = renderHook(() => useLiveScanner(setErrorMsg))
    await act(async () => { await flush() })

    await act(async () => { await result.current.handleStartScan() })

    expect(setErrorMsg).toHaveBeenCalledWith('blocked by lock')
  })

  it('handleStopScan: calls liveApi.stopScan', async () => {
    const { result } = renderHook(() => useLiveScanner(setErrorMsg))
    await act(async () => { await flush() })

    await act(async () => { await result.current.handleStopScan() })

    expect(liveApi.stopScan).toHaveBeenCalledTimes(1)
  })

  it('handleStopScan when api throws: setErrorMsg called with "Stop scan failed: <msg>"', async () => {
    liveApi.stopScan.mockRejectedValue(new Error('timeout'))
    const { result } = renderHook(() => useLiveScanner(setErrorMsg))
    await act(async () => { await flush() })

    await act(async () => { await result.current.handleStopScan() })

    expect(setErrorMsg).toHaveBeenCalledWith('Stop scan failed: timeout')
  })

  it('handleToggleScheduler: calls liveApi.toggleScheduler', async () => {
    const { result } = renderHook(() => useLiveScanner(setErrorMsg))
    await act(async () => { await flush() })

    await act(async () => { await result.current.handleToggleScheduler() })

    expect(liveApi.toggleScheduler).toHaveBeenCalledTimes(1)
  })

  it('handleToggleScheduler when api throws: setErrorMsg called with "Scheduler toggle failed: <msg>"', async () => {
    liveApi.toggleScheduler.mockRejectedValue(new Error('sched error'))
    const { result } = renderHook(() => useLiveScanner(setErrorMsg))
    await act(async () => { await flush() })

    await act(async () => { await result.current.handleToggleScheduler() })

    expect(setErrorMsg).toHaveBeenCalledWith('Scheduler toggle failed: sched error')
  })

  // ── Section 4 — Exclusive lock state machine ─────────────────────────────

  it('when status.exclusiveScanLockHeld transitions false→true, exclusiveLockSinceMs is set', async () => {
    vi.setSystemTime(new Date('2026-04-30T15:00:00Z'))

    liveApi.getStatus.mockResolvedValue(makeStatus({ exclusiveScanLockHeld: false }))
    const { result } = renderHook(() => useLiveScanner(setErrorMsg))
    await act(async () => { await flush() })

    expect(result.current.exclusiveLockSinceMs).toBeNull()

    liveApi.getStatus.mockResolvedValue(makeStatus({ exclusiveScanLockHeld: true }))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_000)
      await flush()
    })

    expect(result.current.exclusiveLockSinceMs).not.toBeNull()
    expect(typeof result.current.exclusiveLockSinceMs).toBe('number')
  })

  it('when status.exclusiveScanLockHeld transitions true→false, exclusiveLockSinceMs resets to null', async () => {
    liveApi.getStatus.mockResolvedValue(makeStatus({ exclusiveScanLockHeld: true }))
    const { result } = renderHook(() => useLiveScanner(setErrorMsg))
    await act(async () => { await flush() })

    expect(result.current.exclusiveLockSinceMs).not.toBeNull()

    liveApi.getStatus.mockResolvedValue(makeStatus({ exclusiveScanLockHeld: false }))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_000)
      await flush()
    })

    expect(result.current.exclusiveLockSinceMs).toBeNull()
  })

  it('exclusiveLockMinutes reflects elapsed minutes from Date.now', async () => {
    vi.setSystemTime(new Date('2026-04-30T15:00:00Z'))

    liveApi.getStatus.mockResolvedValue(makeStatus({ exclusiveScanLockHeld: true }))
    const { result } = renderHook(() => useLiveScanner(setErrorMsg))
    await act(async () => { await flush() })

    // The lock was acquired at 15:00:00 — advance clock to 15:03:00 then tick the interval
    vi.setSystemTime(new Date('2026-04-30T15:03:00Z'))
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5_000)
      await flush()
    })

    expect(result.current.exclusiveLockMinutes).toBe(3)
  })

  // ── Section 5 — handleForceStop (the complex one) ────────────────────────

  it('handleForceStop: calls liveApi.forceStop then liveApi.getStatus', async () => {
    const { result } = renderHook(() => useLiveScanner(setErrorMsg))
    await act(async () => { await flush() })

    const statusCallsBefore = liveApi.getStatus.mock.calls.length

    await act(async () => { await result.current.handleForceStop() })

    expect(liveApi.forceStop).toHaveBeenCalledTimes(1)
    expect(liveApi.getStatus.mock.calls.length).toBeGreaterThan(statusCallsBefore)
  })

  it('if post-forceStop status shows lock released, exclusiveLockSinceMs is null', async () => {
    liveApi.forceStop.mockResolvedValue(undefined)
    liveApi.getStatus.mockResolvedValue(makeStatus({ exclusiveScanLockHeld: false }))

    const { result } = renderHook(() => useLiveScanner(setErrorMsg))
    await act(async () => { await flush() })

    await act(async () => { await result.current.handleForceStop() })

    expect(result.current.exclusiveLockSinceMs).toBeNull()
  })

  it('if post-forceStop status still shows lock held, starts a polling interval', async () => {
    let callCount = 0
    liveApi.forceStop.mockResolvedValue(undefined)
    liveApi.getStatus.mockImplementation(() => {
      callCount++
      // Call 1: mount effect; Call 2: post-forceStop check → lock still held; Call 3+: poll ticks
      const held = callCount >= 2
      return Promise.resolve(makeStatus({ exclusiveScanLockHeld: held }))
    })

    const { result } = renderHook(() => useLiveScanner(setErrorMsg))
    await act(async () => { await flush() })

    await act(async () => {
      await result.current.handleForceStop()
      await flush()
    })

    const callsAfterForceStop = liveApi.getStatus.mock.calls.length

    await act(async () => {
      await vi.advanceTimersByTimeAsync(400)
      await flush()
    })

    expect(liveApi.getStatus.mock.calls.length).toBeGreaterThan(callsAfterForceStop)
  })

  it('the forceStop polling interval clears itself when lock releases', async () => {
    let callCount = 0
    liveApi.forceStop.mockResolvedValue(undefined)
    liveApi.getStatus.mockImplementation(() => {
      callCount++
      // Call 1: mount; Call 2: post-forceStop (held); Call 3: first poll tick → released
      if (callCount <= 2) return Promise.resolve(makeStatus({ exclusiveScanLockHeld: true }))
      return Promise.resolve(makeStatus({ exclusiveScanLockHeld: false }))
    })

    const { result } = renderHook(() => useLiveScanner(setErrorMsg))
    await act(async () => { await flush() })

    await act(async () => {
      await result.current.handleForceStop()
      await flush()
    })

    // First poll tick — lock releases, interval should clear itself
    await act(async () => {
      await vi.advanceTimersByTimeAsync(400)
      await flush()
    })

    expect(result.current.exclusiveLockSinceMs).toBeNull()

    const callsAfterRelease = liveApi.getStatus.mock.calls.length

    // Another 400ms — the force-stop poll interval should NOT fire again
    await act(async () => {
      await vi.advanceTimersByTimeAsync(400)
      await flush()
    })

    expect(liveApi.getStatus.mock.calls.length).toBe(callsAfterRelease)
  })

  it('the forceStop polling interval clears itself after 25s timeout', async () => {
    let callCount = 0
    liveApi.forceStop.mockResolvedValue(undefined)
    // Lock is always held — never releases; force-stop poll must time out at 25s
    liveApi.getStatus.mockImplementation(() => {
      callCount++
      return Promise.resolve(makeStatus({ exclusiveScanLockHeld: true }))
    })

    const { result } = renderHook(() => useLiveScanner(setErrorMsg))
    await act(async () => { await flush() })

    await act(async () => {
      await result.current.handleForceStop()
      await flush()
    })

    // Advance past 25s timeout — the force-stop poll should have stopped itself
    await act(async () => {
      await vi.advanceTimersByTimeAsync(26_000)
      await flush()
    })

    const callsAt26s = liveApi.getStatus.mock.calls.length

    // Further time — only the 1s regular interval can fire, not the 400ms force-stop poll
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_200)
      await flush()
    })

    const callsAt27s = liveApi.getStatus.mock.calls.length
    // If force-stop poll were still running at 400ms, 1.2s would add ~3 more calls
    // With only the regular 1s poll: ≤ 2 more calls
    expect(callsAt27s - callsAt26s).toBeLessThanOrEqual(2)
  })

  it('handleForceStop when forceStop throws: setErrorMsg called with "Force stop failed: <msg>"', async () => {
    liveApi.forceStop.mockRejectedValue(new Error('IBKR locked'))
    const { result } = renderHook(() => useLiveScanner(setErrorMsg))
    await act(async () => { await flush() })

    await act(async () => { await result.current.handleForceStop() })

    expect(setErrorMsg).toHaveBeenCalledWith('Force stop failed: IBKR locked')
  })

  it('forceStopReleasing is false initially, true during the call, false after', async () => {
    let resolveForceStop
    liveApi.forceStop.mockImplementationOnce(
      () => new Promise((res) => { resolveForceStop = () => res() })
    )

    const { result } = renderHook(() => useLiveScanner(setErrorMsg))
    await act(async () => { await flush() })

    expect(result.current.forceStopReleasing).toBe(false)

    let promise
    act(() => { promise = result.current.handleForceStop() })

    expect(result.current.forceStopReleasing).toBe(true)

    await act(async () => {
      resolveForceStop()
      await promise
    })

    expect(result.current.forceStopReleasing).toBe(false)
  })
})
