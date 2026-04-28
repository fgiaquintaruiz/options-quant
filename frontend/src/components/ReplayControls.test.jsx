import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, act, fireEvent } from '@testing-library/react'
import ReplayControls from './ReplayControls'
import * as api from '../api'

vi.mock('../api', () => ({
  replayApi: {
    status: vi.fn(),
    start:  vi.fn(),
    stop:   vi.fn(),
  },
}))

// LS.get reads localStorage — provide a predictable date so tests aren't date-sensitive
const FIXED_DATE = '2026-04-27'

beforeEach(() => {
  localStorage.setItem('replay_lastDate', FIXED_DATE)
  vi.useFakeTimers()
})

afterEach(() => {
  vi.useRealTimers()
  vi.clearAllMocks()
  localStorage.clear()
})

// Helper: flush the initial tick() call that fires synchronously on mount.
// advanceTimersByTimeAsync(0) advances no timers but resolves queued microtasks
// created by the async tick(); two extra Promise.resolve() passes drain React's
// batched setState microtasks that settle AFTER the api mock resolves.
async function flushMountTick() {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(0)
    await Promise.resolve()
    await Promise.resolve()
  })
}

// Helper: advance the 2s interval and let the async tick settle
async function advancePolling(ms = 2000) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms)
    await Promise.resolve()
    await Promise.resolve()
  })
}

describe('ReplayControls — polling transitions', () => {

  // ── Test 1 — orphan replay detected on mount ────────────────────────────

  it('detects active replay on mount and notifies onActiveChange once', async () => {
    api.replayApi.status.mockResolvedValue({ active: true })
    const onActiveChange = vi.fn()

    render(<ReplayControls onActiveChange={onActiveChange} marketOpen={false} />)

    // The component calls tick() immediately on mount — flush it
    await flushMountTick()

    const startBtn = screen.getByTestId('replay-start-btn')
    // Active state → button should render the Stop icon (btn-secondary class)
    expect(startBtn.className).toContain('btn-secondary')
    expect(onActiveChange).toHaveBeenCalledTimes(1)
    expect(onActiveChange).toHaveBeenCalledWith(true)
  })

  // ── Test 2 — Stop click syncs with backend ──────────────────────────────

  it('Stop click calls replayApi.stop and polling confirms inactive', async () => {
    // First status → active (orphan), subsequent → inactive after stop
    api.replayApi.status
      .mockResolvedValueOnce({ active: true })
      .mockResolvedValue({ active: false })
    api.replayApi.stop.mockResolvedValue({ ok: true })
    const onActiveChange = vi.fn()

    render(<ReplayControls onActiveChange={onActiveChange} marketOpen={false} />)

    // Flush mount tick — component detects active state
    await flushMountTick()
    expect(onActiveChange).toHaveBeenCalledWith(true)

    // fireEvent.click is synchronous — safe with fake timers; act drains async state updates
    await act(async () => {
      fireEvent.click(screen.getByTestId('replay-start-btn'))
      await Promise.resolve()
      await Promise.resolve()
    })

    expect(api.replayApi.stop).toHaveBeenCalledTimes(1)
    // Optimistic update: button should immediately show Play (btn-primary)
    expect(screen.getByTestId('replay-start-btn').className).toContain('btn-primary')

    // Polling fires and confirms inactive
    await advancePolling(2000)

    // onActiveChange: first true (mount), then false (stop click), no extra call from polling
    // (polling sees false, matches lastActiveRef=false after handleStop, so no extra transition)
    expect(onActiveChange).toHaveBeenCalledWith(false)
  })

  // ── Test 3 — Start click activates replay ───────────────────────────────

  it('Start click calls replayApi.start with date/speed and polling confirms active', async () => {
    api.replayApi.status
      .mockResolvedValueOnce({ active: false }) // mount tick
      .mockResolvedValue({ active: true })      // subsequent polls
    api.replayApi.start.mockResolvedValue({ ok: true })
    const onActiveChange = vi.fn()

    render(<ReplayControls onActiveChange={onActiveChange} marketOpen={false} />)

    // Flush mount tick — component sees inactive, no transition
    await flushMountTick()
    expect(onActiveChange).not.toHaveBeenCalled()

    // fireEvent.click is synchronous — safe with fake timers; act drains async state updates
    await act(async () => {
      fireEvent.click(screen.getByTestId('replay-start-btn'))
      await Promise.resolve()
      await Promise.resolve()
    })

    expect(api.replayApi.start).toHaveBeenCalledTimes(1)
    expect(api.replayApi.start).toHaveBeenCalledWith(FIXED_DATE, 30)
    // Optimistic update → button shows Stop
    expect(screen.getByTestId('replay-start-btn').className).toContain('btn-secondary')
    expect(onActiveChange).toHaveBeenCalledWith(true)

    // Polling fires — backend returns active:true; handleStart already synced lastActiveRef=true,
    // so tick() sees true→true and does NOT fire onActiveChange again.
    await advancePolling(2000)

    expect(onActiveChange).toHaveBeenCalledTimes(1)
    expect(onActiveChange).toHaveBeenLastCalledWith(true)
  })

  // ── Test 4 — onActiveChange fires only on state transitions ─────────────

  it('onActiveChange is called exactly once across repeated polls with same active state', async () => {
    // Always returns active:true — transition: false→true on first tick, then steady state
    api.replayApi.status.mockResolvedValue({ active: true })
    const onActiveChange = vi.fn()

    render(<ReplayControls onActiveChange={onActiveChange} marketOpen={false} />)

    // First tick (mount): false → true → 1 call
    await flushMountTick()
    expect(onActiveChange).toHaveBeenCalledTimes(1)
    expect(onActiveChange).toHaveBeenCalledWith(true)

    // Second tick (2s): true → true → no new call
    await advancePolling(2000)
    expect(onActiveChange).toHaveBeenCalledTimes(1)

    // Third tick (2s): true → true → no new call
    await advancePolling(2000)
    expect(onActiveChange).toHaveBeenCalledTimes(1)
  })
})
