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
  localStorage.setItem('replay_lastDate', JSON.stringify(FIXED_DATE))
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

// Helper: advance the 5s interval and let the async tick settle
async function advancePolling(ms = 5000) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms)
    await Promise.resolve()
    await Promise.resolve()
  })
}

describe('ReplayControls — polling transitions', () => {

  // ── Test 1 — orphan replay detected on mount ────────────────────────────

  it('detects active replay on mount and notifies onActiveChange once', async () => {
    api.replayApi.status.mockResolvedValue({ active: true, virtualNow: null, speed: 30, runId: null })
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
      .mockResolvedValueOnce({ active: true, virtualNow: null, speed: 30, runId: null })
      .mockResolvedValue({ active: false, virtualNow: null, speed: 0, runId: null })
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
    await advancePolling(5000)

    // onActiveChange: first true (mount), then false (stop click), no extra call from polling
    // (polling sees false, matches lastActiveRef=false after handleStop, so no extra transition)
    expect(onActiveChange).toHaveBeenCalledWith(false)
  })

  // ── Test 3 — Start click activates replay ───────────────────────────────

  it('Start click calls replayApi.start with date/speed and polling confirms active', async () => {
    api.replayApi.status
      .mockResolvedValueOnce({ active: false, virtualNow: null, speed: 0, runId: null }) // mount tick
      .mockResolvedValue({ active: true, virtualNow: '2026-04-27T10:00:00Z', speed: 30, runId: 'r1' }) // subsequent polls
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
    await advancePolling(5000)

    expect(onActiveChange).toHaveBeenCalledTimes(1)
    expect(onActiveChange).toHaveBeenLastCalledWith(true)
  })

  // ── Test 4 — onActiveChange fires only on state transitions ─────────────

  it('onActiveChange is called exactly once across repeated polls with same active state', async () => {
    // Always returns active:true — transition: false→true on first tick, then steady state
    api.replayApi.status.mockResolvedValue({ active: true, virtualNow: null, speed: 30, runId: null })
    const onActiveChange = vi.fn()

    render(<ReplayControls onActiveChange={onActiveChange} marketOpen={false} />)

    // First tick (mount): false → true → 1 call
    await flushMountTick()
    expect(onActiveChange).toHaveBeenCalledTimes(1)
    expect(onActiveChange).toHaveBeenCalledWith(true)

    // Second tick (5s): true → true → no new call
    await advancePolling(5000)
    expect(onActiveChange).toHaveBeenCalledTimes(1)

    // Third tick (5s): true → true → no new call
    await advancePolling(5000)
    expect(onActiveChange).toHaveBeenCalledTimes(1)
  })
})

describe('ReplayControls — branch coverage', () => {

  // ── 1. cycleSpeed — full cycle ─────────────────────────────────────────────

  it('cycleSpeed cycles through all SPEEDS and wraps back to 30', async () => {
    api.replayApi.status.mockResolvedValue({ active: false, virtualNow: null, speed: 0, runId: null })

    render(<ReplayControls marketOpen={false} />)
    await flushMountTick()

    // 30 → 60
    await act(async () => {
      fireEvent.click(screen.getByTestId('replay-speed-btn-30'))
      await Promise.resolve()
    })
    expect(screen.getByTestId('replay-speed-btn-60')).toBeTruthy()

    // 60 → 180
    await act(async () => {
      fireEvent.click(screen.getByTestId('replay-speed-btn-60'))
      await Promise.resolve()
    })
    expect(screen.getByTestId('replay-speed-btn-180')).toBeTruthy()

    // 180 → 360
    await act(async () => {
      fireEvent.click(screen.getByTestId('replay-speed-btn-180'))
      await Promise.resolve()
    })
    expect(screen.getByTestId('replay-speed-btn-360')).toBeTruthy()

    // 360 → 30 (wrap)
    await act(async () => {
      fireEvent.click(screen.getByTestId('replay-speed-btn-360'))
      await Promise.resolve()
    })
    expect(screen.getByTestId('replay-speed-btn-30')).toBeTruthy()
  })

  // ── 2. handleStart error — with e.message ──────────────────────────────────

  it('handleStart calls onError with e.message when start rejects with an Error', async () => {
    api.replayApi.status.mockResolvedValue({ active: false, virtualNow: null, speed: 0, runId: null })
    api.replayApi.start.mockRejectedValue(new Error('market open'))
    const onError = vi.fn()

    render(<ReplayControls marketOpen={false} onError={onError} />)
    await flushMountTick()

    await act(async () => {
      fireEvent.click(screen.getByTestId('replay-start-btn'))
      await Promise.resolve()
      await Promise.resolve()
    })

    expect(onError).toHaveBeenCalledTimes(1)
    expect(onError).toHaveBeenCalledWith('market open')
  })

  // ── 3. handleStart error — without e.message (fallback string) ─────────────

  it('handleStart calls onError with fallback string when rejection has no message', async () => {
    api.replayApi.status.mockResolvedValue({ active: false, virtualNow: null, speed: 0, runId: null })
    api.replayApi.start.mockRejectedValue({})
    const onError = vi.fn()

    render(<ReplayControls marketOpen={false} onError={onError} />)
    await flushMountTick()

    await act(async () => {
      fireEvent.click(screen.getByTestId('replay-start-btn'))
      await Promise.resolve()
      await Promise.resolve()
    })

    expect(onError).toHaveBeenCalledTimes(1)
    expect(onError).toHaveBeenCalledWith('Replay bloqueado durante horario de mercado')
  })

  // ── 4. handleStart — no onError prop (no crash) ────────────────────────────

  it('handleStart does not throw when onError prop is omitted', async () => {
    api.replayApi.status.mockResolvedValue({ active: false, virtualNow: null, speed: 0, runId: null })
    api.replayApi.start.mockRejectedValue(new Error('oops'))

    render(<ReplayControls marketOpen={false} />)
    await flushMountTick()

    await act(async () => {
      fireEvent.click(screen.getByTestId('replay-start-btn'))
      await Promise.resolve()
      await Promise.resolve()
    })

    // If we reach here without throwing, the test passes
    expect(screen.getByTestId('replay-start-btn')).toBeTruthy()
  })

  // ── 5. marketOpen=true disables start button when inactive ─────────────────

  it('start button is disabled and titled when marketOpen=true and replay inactive', async () => {
    api.replayApi.status.mockResolvedValue({ active: false, virtualNow: null, speed: 0, runId: null })

    render(<ReplayControls marketOpen={true} />)
    await flushMountTick()

    const startBtn = screen.getByTestId('replay-start-btn')
    expect(startBtn).toBeDisabled()
    expect(startBtn.title).toBe('No disponible durante horario de mercado')
  })

  // ── 6. Date input change updates value ────────────────────────────────────

  it('changing the date input updates the displayed value', async () => {
    api.replayApi.status.mockResolvedValue({ active: false, virtualNow: null, speed: 0, runId: null })

    render(<ReplayControls marketOpen={false} />)
    await flushMountTick()

    fireEvent.change(screen.getByTestId('replay-date-input'), {
      target: { value: '2025-12-01' },
    })

    expect(screen.getByTestId('replay-date-input').value).toBe('2025-12-01')
  })

  // ── 7. Polling error is swallowed silently ─────────────────────────────────

  it('polling errors are swallowed and the component keeps rendering', async () => {
    api.replayApi.status.mockRejectedValue(new Error('network error'))

    render(<ReplayControls marketOpen={false} />)
    await flushMountTick()

    // Component must still be present despite the error
    expect(screen.getByTestId('replay-start-btn')).toBeTruthy()

    // Advance one polling cycle — still no crash
    await advancePolling(5000)

    expect(screen.getByTestId('replay-start-btn')).toBeTruthy()
  })

  // ── 8. handleStop without onActiveChange prop (no crash) ──────────────────

  it('handleStop does not throw when onActiveChange prop is omitted', async () => {
    api.replayApi.status
      .mockResolvedValueOnce({ active: true, virtualNow: null, speed: 30, runId: null })
      .mockResolvedValue({ active: false, virtualNow: null, speed: 0, runId: null })
    api.replayApi.stop.mockResolvedValue({ ok: true })

    render(<ReplayControls marketOpen={false} />)
    await flushMountTick()

    // Component should now show the stop button (active state)
    const startBtn = screen.getByTestId('replay-start-btn')
    expect(startBtn.className).toContain('btn-secondary')

    await act(async () => {
      fireEvent.click(startBtn)
      await Promise.resolve()
      await Promise.resolve()
    })

    // If we reach here without throwing, the test passes
    expect(screen.getByTestId('replay-start-btn')).toBeTruthy()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// NEW TESTS — Features 1–4
// ─────────────────────────────────────────────────────────────────────────────

describe('ReplayControls — Feature 1: virtual clock', () => {

  it('shows virtual clock when replay is active with virtualNow', async () => {
    api.replayApi.status.mockResolvedValue({
      active: true,
      virtualNow: '2026-04-27T14:30:00Z',
      speed: 30,
      runId: 'r1',
    })

    render(<ReplayControls marketOpen={false} />)
    await flushMountTick()

    // Should display the date in DD/MM/YYYY HH:mm format
    const clock = screen.getByTestId('replay-virtual-clock')
    expect(clock.textContent).toMatch(/\d{2}\/\d{2}\/\d{4}/)
  })

  it('does not show virtual clock when replay is inactive', async () => {
    api.replayApi.status.mockResolvedValue({
      active: false,
      virtualNow: null,
      speed: 0,
      runId: null,
    })

    render(<ReplayControls marketOpen={false} />)
    await flushMountTick()

    expect(screen.queryByTestId('replay-virtual-clock')).toBeNull()
  })

  it('updates virtual clock on next poll', async () => {
    api.replayApi.status
      .mockResolvedValueOnce({
        active: true,
        virtualNow: '2026-04-27T10:00:00Z',
        speed: 30,
        runId: 'r1',
      })
      .mockResolvedValue({
        active: true,
        virtualNow: '2026-04-27T11:00:00Z',
        speed: 30,
        runId: 'r1',
      })

    render(<ReplayControls marketOpen={false} />)
    await flushMountTick()

    const clock = screen.getByTestId('replay-virtual-clock')
    const firstText = clock.textContent

    await advancePolling(5000)

    expect(screen.getByTestId('replay-virtual-clock').textContent).not.toBe(firstText)
  })
})

describe('ReplayControls — Feature 2: progress bar', () => {

  it('shows progress bar when replay is active', async () => {
    api.replayApi.status.mockResolvedValue({
      active: true,
      virtualNow: '2026-04-27T12:00:00Z',
      speed: 30,
      runId: 'r1',
    })

    render(<ReplayControls marketOpen={false} />)
    await flushMountTick()

    expect(screen.getByTestId('replay-progress-bar')).toBeTruthy()
  })

  it('does not show progress bar when replay is inactive', async () => {
    api.replayApi.status.mockResolvedValue({
      active: false,
      virtualNow: null,
      speed: 0,
      runId: null,
    })

    render(<ReplayControls marketOpen={false} />)
    await flushMountTick()

    expect(screen.queryByTestId('replay-progress-bar')).toBeNull()
  })

  it('shows date range labels in the progress bar', async () => {
    api.replayApi.status.mockResolvedValue({
      active: true,
      virtualNow: '2026-04-27T12:00:00Z',
      speed: 30,
      runId: 'r1',
    })

    render(<ReplayControls marketOpen={false} />)
    await flushMountTick()

    const progress = screen.getByTestId('replay-progress-bar')
    // Should contain date info (DD/MM format)
    expect(progress.textContent).toMatch(/\d{2}\/\d{2}/)
  })
})

describe('ReplayControls — Feature 3: Play/Stop button', () => {

  it('renders Play button (▶) when replay is inactive', async () => {
    api.replayApi.status.mockResolvedValue({
      active: false,
      virtualNow: null,
      speed: 0,
      runId: null,
    })

    render(<ReplayControls marketOpen={false} />)
    await flushMountTick()

    const startBtn = screen.getByTestId('replay-start-btn')
    // btn-primary class = Play state
    expect(startBtn.className).toContain('btn-primary')
  })

  it('renders Stop button (⏹) when replay is active', async () => {
    api.replayApi.status.mockResolvedValue({
      active: true,
      virtualNow: '2026-04-27T10:00:00Z',
      speed: 30,
      runId: 'r1',
    })

    render(<ReplayControls marketOpen={false} />)
    await flushMountTick()

    const startBtn = screen.getByTestId('replay-start-btn')
    // btn-secondary class = Stop state
    expect(startBtn.className).toContain('btn-secondary')
  })
})

describe('ReplayControls — Feature 4: speed badge', () => {

  it('shows speed badge from status when replay is active', async () => {
    api.replayApi.status.mockResolvedValue({
      active: true,
      virtualNow: '2026-04-27T10:00:00Z',
      speed: 30,
      runId: 'r1',
    })

    render(<ReplayControls marketOpen={false} />)
    await flushMountTick()

    const badge = screen.getByTestId('replay-speed-badge')
    expect(badge.textContent).toBe('30x')
  })

  it('shows speed badge with different multiplier', async () => {
    api.replayApi.status.mockResolvedValue({
      active: true,
      virtualNow: '2026-04-27T10:00:00Z',
      speed: 180,
      runId: 'r1',
    })

    render(<ReplayControls marketOpen={false} />)
    await flushMountTick()

    const badge = screen.getByTestId('replay-speed-badge')
    expect(badge.textContent).toBe('180x')
  })

  it('does not show speed badge when replay is inactive', async () => {
    api.replayApi.status.mockResolvedValue({
      active: false,
      virtualNow: null,
      speed: 0,
      runId: null,
    })

    render(<ReplayControls marketOpen={false} />)
    await flushMountTick()

    expect(screen.queryByTestId('replay-speed-badge')).toBeNull()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// Task 1 — mockMarket prop bypasses marketOpen disable
// ─────────────────────────────────────────────────────────────────────────────

describe('ReplayControls — mockMarket bypass', () => {

  it('start button is ENABLED when marketOpen=true and mockMarket=true and replay inactive', async () => {
    api.replayApi.status.mockResolvedValue({ active: false, virtualNow: null, speed: 0, runId: null })

    render(<ReplayControls marketOpen={true} mockMarket={true} />)
    await flushMountTick()

    const startBtn = screen.getByTestId('replay-start-btn')
    expect(startBtn).not.toBeDisabled()
    expect(startBtn.title).toBeFalsy()
  })

  it('start button is DISABLED when marketOpen=true and mockMarket=false and replay inactive', async () => {
    api.replayApi.status.mockResolvedValue({ active: false, virtualNow: null, speed: 0, runId: null })

    render(<ReplayControls marketOpen={true} mockMarket={false} />)
    await flushMountTick()

    const startBtn = screen.getByTestId('replay-start-btn')
    expect(startBtn).toBeDisabled()
    expect(startBtn.title).toBe('No disponible durante horario de mercado')
  })

  it('start button is ENABLED when marketOpen=false regardless of mockMarket', async () => {
    api.replayApi.status.mockResolvedValue({ active: false, virtualNow: null, speed: 0, runId: null })

    render(<ReplayControls marketOpen={false} mockMarket={false} />)
    await flushMountTick()

    expect(screen.getByTestId('replay-start-btn')).not.toBeDisabled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// Task 3 — default date is 2 years back
// ─────────────────────────────────────────────────────────────────────────────

describe('ReplayControls — default date', () => {

  it('date input defaults to 2 years ago when localStorage has no stored date', async () => {
    localStorage.clear()
    api.replayApi.status.mockResolvedValue({ active: false, virtualNow: null, speed: 0, runId: null })

    render(<ReplayControls marketOpen={false} />)
    await flushMountTick()

    const input = screen.getByTestId('replay-date-input')
    const twoYearsAgo = new Date(Date.now() - 2 * 365 * 24 * 60 * 60 * 1000)
      .toISOString()
      .split('T')[0]

    expect(input.value).toBe(twoYearsAgo)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// virtualNow parsing — NaN regression tests
// ─────────────────────────────────────────────────────────────────────────────

describe('ReplayControls — virtualNow parsing', () => {

  // ── 1. null virtualNow → clock not rendered (no NaN) ──────────────────────

  it('does not render virtual clock when virtualNow is null', async () => {
    api.replayApi.status.mockResolvedValue({
      active: true,
      virtualNow: null,
      speed: 30,
      runId: 'r1',
    })

    render(<ReplayControls marketOpen={false} />)
    await flushMountTick()

    expect(screen.queryByTestId('replay-virtual-clock')).toBeNull()
  })

  // ── 2. ISO with zone offset only → displays valid date (no NaN) ───────────

  it('formats virtualNow with offset-only ISO string (2026-04-27T09:30:00-05:00)', async () => {
    api.replayApi.status.mockResolvedValue({
      active: true,
      virtualNow: '2026-04-27T09:30:00-05:00',
      speed: 30,
      runId: 'r1',
    })

    render(<ReplayControls marketOpen={false} />)
    await flushMountTick()

    const clock = screen.getByTestId('replay-virtual-clock')
    expect(clock.textContent).not.toContain('NaN')
    // offset -05:00 → UTC 14:30 → display as UTC: 27/04/2026 14:30
    expect(clock.textContent).toContain('27/04/2026 14:30')
  })

  // ── 3. ISO with Java ZonedDateTime.toString() suffix → no NaN ─────────────
  //
  //  Java's ZonedDateTime.toString() produces:
  //    "2026-04-27T09:30:00-05:00[America/New_York]"
  //  Browsers CANNOT parse the "[America/New_York]" zone-id suffix.
  //  This was the ROOT CAUSE of the "NaN/NaN/NaN NaN:NaN" bug.

  it('formats virtualNow with Java ZonedDateTime.toString() zone-id suffix (no NaN)', async () => {
    api.replayApi.status.mockResolvedValue({
      active: true,
      virtualNow: '2026-04-27T09:30:00-05:00[America/New_York]',
      speed: 30,
      runId: 'r1',
    })

    render(<ReplayControls marketOpen={false} />)
    await flushMountTick()

    const clock = screen.getByTestId('replay-virtual-clock')
    expect(clock.textContent).not.toContain('NaN')
    expect(clock.textContent).toContain('27/04/2026 14:30')
  })

  // ── 4. ISO with UTC Z suffix → displays valid date ────────────────────────

  it('formats virtualNow with UTC Z suffix (2026-04-27T14:30:00Z)', async () => {
    api.replayApi.status.mockResolvedValue({
      active: true,
      virtualNow: '2026-04-27T14:30:00Z',
      speed: 30,
      runId: 'r1',
    })

    render(<ReplayControls marketOpen={false} />)
    await flushMountTick()

    const clock = screen.getByTestId('replay-virtual-clock')
    expect(clock.textContent).not.toContain('NaN')
    expect(clock.textContent).toContain('27/04/2026 14:30')
  })

  // ── 5. progress bar label does not contain NaN when zone-id suffix present ──

  it('progress bar label has no NaN when virtualNow has zone-id suffix', async () => {
    api.replayApi.status.mockResolvedValue({
      active: true,
      virtualNow: '2026-04-27T09:30:00-05:00[America/New_York]',
      speed: 30,
      runId: 'r1',
    })

    render(<ReplayControls marketOpen={false} />)
    await flushMountTick()

    const bar = screen.getByTestId('replay-progress-bar')
    expect(bar.textContent).not.toContain('NaN')
  })
})
