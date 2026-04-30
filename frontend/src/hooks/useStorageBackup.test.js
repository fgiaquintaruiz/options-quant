import { vi, describe, it, expect, beforeEach, afterEach } from 'vitest'
import { renderHook } from '@testing-library/react'
import { useStorageBackup } from './useStorageBackup'

vi.mock('../utils/storage', () => ({
  LS: { get: vi.fn().mockReturnValue(null) },
}))

describe('useStorageBackup', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true }))
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
    vi.clearAllMocks()
  })

  it('fires fetch to /api/backup after 2s when app-ls-change event fires', async () => {
    renderHook(() => useStorageBackup())

    window.dispatchEvent(new CustomEvent('app-ls-change'))
    await vi.advanceTimersByTimeAsync(2000)

    expect(fetch).toHaveBeenCalledWith(
      '/api/backup',
      expect.objectContaining({ method: 'POST' })
    )
  })

  it('fires fetch to /api/backup after 2s when storage event fires', async () => {
    renderHook(() => useStorageBackup())

    window.dispatchEvent(new Event('storage'))
    await vi.advanceTimersByTimeAsync(2000)

    expect(fetch).toHaveBeenCalledWith(
      '/api/backup',
      expect.objectContaining({ method: 'POST' })
    )
  })

  it('debounces: multiple rapid events result in single fetch call', async () => {
    renderHook(() => useStorageBackup())

    window.dispatchEvent(new CustomEvent('app-ls-change'))
    await vi.advanceTimersByTimeAsync(500)

    window.dispatchEvent(new CustomEvent('app-ls-change'))
    await vi.advanceTimersByTimeAsync(2000)

    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it('does NOT fire fetch if no event fires before 2s', async () => {
    renderHook(() => useStorageBackup())

    await vi.advanceTimersByTimeAsync(5000)

    expect(fetch).not.toHaveBeenCalled()
  })

  it('cleans up listeners on unmount', async () => {
    const { unmount } = renderHook(() => useStorageBackup())

    unmount()

    window.dispatchEvent(new CustomEvent('app-ls-change'))
    await vi.advanceTimersByTimeAsync(2000)

    expect(fetch).not.toHaveBeenCalled()
  })
})
