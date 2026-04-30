import { renderHook, act } from '@testing-library/react'
import { vi, describe, it, expect, beforeEach } from 'vitest'
import { useWatchlists } from './useWatchlists'

vi.mock('../utils/storage', () => ({
  LS: {
    get: vi.fn(),
    set: vi.fn(),
  },
}))

vi.mock('../constants/defaultWatchlists', () => ({
  DEFAULT_WATCHLISTS: [
    { id: 'test-group', label: 'Test Group', tickers: 'AAPL,MSFT' },
  ],
}))

import { LS } from '../utils/storage'

describe('useWatchlists', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    // Simulate LS.get(key, fallback) returning the fallback when no data is stored
    LS.get.mockImplementation((_key, fallback) => fallback)
  })

  it('initial state: falls back to DEFAULT_WATCHLISTS when LS has no data', () => {
    // beforeEach already sets mockImplementation to return the fallback
    const { result } = renderHook(() => useWatchlists())

    expect(result.current.groups).toEqual([
      { id: 'test-group', label: 'Test Group', tickers: 'AAPL,MSFT' },
    ])
  })

  it('initial state: loads from LS when data exists', () => {
    const stored = [{ id: 'my-list', label: 'My List', tickers: 'TSLA' }]
    LS.get.mockImplementation((_key, _fallback) => stored)

    const { result } = renderHook(() => useWatchlists())

    expect(result.current.groups[0].id).toBe('my-list')
  })

  it('addGroup: adds a new group with computed id from label', async () => {
    const { result } = renderHook(() => useWatchlists())

    await act(async () => {
      result.current.addGroup('My Picks', 'NVDA,AMD')
    })

    expect(result.current.groups).toContainEqual({
      id: 'my-picks',
      label: 'My Picks',
      tickers: 'NVDA,AMD',
    })
  })

  it('addGroup: updates existing group if id collision', async () => {
    const { result } = renderHook(() => useWatchlists())

    const initialLength = result.current.groups.length

    await act(async () => {
      result.current.addGroup('Test Group', 'NEW,TICKERS')
    })

    const updated = result.current.groups.find((g) => g.id === 'test-group')
    expect(updated.tickers).toBe('NEW,TICKERS')
    expect(result.current.groups.length).toBe(initialLength)
  })

  it('addGroup: no-op if label is empty', async () => {
    const { result } = renderHook(() => useWatchlists())

    const lengthBefore = result.current.groups.length
    const lsSetCallsBefore = LS.set.mock.calls.length

    await act(async () => {
      result.current.addGroup('', 'AAPL')
    })

    expect(result.current.groups.length).toBe(lengthBefore)
    // LS.set must NOT have been called again (no new persist)
    expect(LS.set.mock.calls.length).toBe(lsSetCallsBefore)
  })

  it('removeGroup: removes the group with matching id', async () => {
    const { result } = renderHook(() => useWatchlists())

    await act(async () => {
      result.current.removeGroup('test-group')
    })

    expect(result.current.groups).toEqual([])
  })

  it('updateGroup: updates tickers for matching id', async () => {
    const { result } = renderHook(() => useWatchlists())

    await act(async () => {
      result.current.updateGroup('test-group', 'GOOGL,META')
    })

    const updated = result.current.groups.find((g) => g.id === 'test-group')
    expect(updated.tickers).toBe('GOOGL,META')
  })

  it('updateGroupFull: updates label and tickers', async () => {
    const { result } = renderHook(() => useWatchlists())

    await act(async () => {
      result.current.updateGroupFull('test-group', 'Renamed Group', 'SPY,QQQ')
    })

    const updated = result.current.groups.find((g) => g.id === 'test-group')
    expect(updated.label).toBe('Renamed Group')
    expect(updated.tickers).toBe('SPY,QQQ')
  })

  it('updateGroupFull: no-op if newLabel is empty after trim', async () => {
    const { result } = renderHook(() => useWatchlists())

    const groupsBefore = [...result.current.groups]

    await act(async () => {
      result.current.updateGroupFull('test-group', '   ', 'AAPL')
    })

    expect(result.current.groups).toEqual(groupsBefore)
  })

  it('duplicateGroup: creates a copy with "(copia 1)" suffix', async () => {
    const { result } = renderHook(() => useWatchlists())

    await act(async () => {
      result.current.duplicateGroup('test-group')
    })

    const copy = result.current.groups.find((g) => g.label === 'Test Group (copia 1)')
    expect(copy).toBeDefined()
    // id derived from: "test group (copia 1)" -> replace spaces -> "test-group-(copia-1)" -> strip non [a-z0-9-] -> "test-group-copia-1"
    expect(copy.id).toBe('test-group-copia-1')
  })

  it('persist: calls LS.set with updated groups after mutation', async () => {
    const { result } = renderHook(() => useWatchlists())

    // Clear calls from initial mount effect
    vi.clearAllMocks()

    await act(async () => {
      result.current.removeGroup('test-group')
    })

    expect(LS.set).toHaveBeenCalledWith('ticker_groups', [])
  })
})
