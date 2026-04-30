import { renderHook, act } from '@testing-library/react'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { useGridSearch } from './useGridSearch'

vi.mock('../api', () => ({
  backtestApi: {
    gridSearch: vi.fn(),
    retestStrategy: vi.fn(),
    promoteRiskParams: vi.fn(),
  },
}))

vi.mock('../utils/backtestFormatters', () => ({
  lookbackMonthsToRange: vi.fn().mockReturnValue({ from: '2024-01-01', to: '2024-12-31' }),
  inferCallPutFromStrategyName: vi.fn().mockReturnValue(true),
}))

import { backtestApi } from '../api'

function makeProps(overrides = {}) {
  return {
    ticker: 'AAPL',
    strategyName: 'ORB',
    startParams: { capital: 50000, risk: 0.02 },
    maxConcurrent: 4,
    onMemoryApplied: vi.fn(),
    ...overrides,
  }
}

// Helper: seed a grid result with hasWinner=true via runGrid success
async function seedWinnerResult(result, mockData) {
  backtestApi.gridSearch.mockResolvedValueOnce(mockData)
  await act(async () => {
    await result.current.runGrid()
  })
}

const DEFAULT_WINNER = {
  optimization: {
    hasWinner: true,
    bestParameters: { tpMultiplierDelta: 0.1, slMultiplierDelta: 0.2 },
  },
}

// ─── Grupo A: Estado inicial ───────────────────────────────────────────────

describe('useGridSearch — initial state', () => {
  it('A1. renders with correct default state', () => {
    const { result } = renderHook(() => useGridSearch(makeProps()))

    expect(result.current.modalLookback).toBe('12')
    expect(result.current.modalTpAxis).toBe('0,0.1')
    expect(result.current.modalSlAxis).toBe('0,0.2')
    expect(result.current.gridMetric).toBe('TOTAL_PNL')
    expect(result.current.modalGridMinTrades).toBe('10')
    expect(result.current.modalWalkForward).toBe(false)
    expect(result.current.modalGridLoading).toBe(false)
    expect(result.current.modalGridError).toBeNull()
    expect(result.current.modalGridResult).toBeNull()
  })
})

// ─── Grupo B: runGrid validations ──────────────────────────────────────────

describe('useGridSearch — runGrid validations', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('B1. runGrid: sets modalGridError if ticker is empty', async () => {
    const { result } = renderHook(() => useGridSearch(makeProps({ ticker: '' })))

    await act(async () => {
      await result.current.runGrid()
    })

    expect(result.current.modalGridError).toMatch(/ticker/i)
    expect(backtestApi.gridSearch).not.toHaveBeenCalled()
  })

  it('B2. runGrid: sets modalGridError if strategyName is empty', async () => {
    const { result } = renderHook(() => useGridSearch(makeProps({ strategyName: '' })))

    await act(async () => {
      await result.current.runGrid()
    })

    expect(result.current.modalGridError).toMatch(/estrategia|strat/i)
    expect(backtestApi.gridSearch).not.toHaveBeenCalled()
  })

  it('B3. runGrid: sets modalGridError if axes are empty strings', async () => {
    const { result } = renderHook(() => useGridSearch(makeProps()))

    act(() => { result.current.setModalTpAxis('') })
    act(() => { result.current.setModalSlAxis('') })

    await act(async () => {
      await result.current.runGrid()
    })

    expect(result.current.modalGridError).toBeTruthy()
    expect(backtestApi.gridSearch).not.toHaveBeenCalled()
  })

  it('B4. runGrid: success path — calls backtestApi.gridSearch and sets modalGridResult', async () => {
    const { result } = renderHook(() => useGridSearch(makeProps()))

    backtestApi.gridSearch.mockResolvedValueOnce(DEFAULT_WINNER)

    await act(async () => {
      await result.current.runGrid()
    })

    expect(backtestApi.gridSearch).toHaveBeenCalledOnce()
    expect(backtestApi.gridSearch).toHaveBeenCalledWith(
      expect.objectContaining({
        tickers: ['AAPL'],
        fromDate: '2024-01-01',
        toDate: '2024-12-31',
        primaryMetric: 'TOTAL_PNL',
        axes: expect.arrayContaining([
          expect.objectContaining({ name: 'tpMultiplierDelta' }),
          expect.objectContaining({ name: 'slMultiplierDelta' }),
        ]),
      })
    )
    expect(result.current.modalGridResult).toEqual(DEFAULT_WINNER)
    expect(result.current.modalGridLoading).toBe(false)
    expect(result.current.modalGridError).toBeNull()
  })

  it('B5. runGrid: error path — API throws → sets modalGridError, clears loading', async () => {
    const { result } = renderHook(() => useGridSearch(makeProps()))

    backtestApi.gridSearch.mockRejectedValueOnce(new Error('timeout'))

    await act(async () => {
      await result.current.runGrid()
    })

    expect(result.current.modalGridError).toContain('timeout')
    expect(result.current.modalGridLoading).toBe(false)
  })
})

// ─── Grupo C: runRetest ────────────────────────────────────────────────────

describe('useGridSearch — runRetest', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('C1. runRetest: no-op if modalGridResult has no winner', async () => {
    const { result } = renderHook(() => useGridSearch(makeProps()))

    await act(async () => {
      await result.current.runRetest()
    })

    expect(backtestApi.retestStrategy).not.toHaveBeenCalled()
  })

  it('C2. runRetest: calls retestStrategy with bestParameters when winner exists', async () => {
    const { result } = renderHook(() => useGridSearch(makeProps()))

    await seedWinnerResult(result, DEFAULT_WINNER)

    backtestApi.retestStrategy.mockResolvedValueOnce({ success: true, backtest: {} })

    await act(async () => {
      await result.current.runRetest()
    })

    expect(backtestApi.retestStrategy).toHaveBeenCalledOnce()
    expect(backtestApi.retestStrategy).toHaveBeenCalledWith(
      'ORB',
      50000,
      0.02,
      true,
      0.1,
      0.2,
      'AAPL',
    )
  })
})

// ─── Grupo D: applyToMemory ────────────────────────────────────────────────

describe('useGridSearch — applyToMemory', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('D1. applyToMemory: returns {ok:false} and sets message if no winner', async () => {
    const { result } = renderHook(() => useGridSearch(makeProps()))

    let returnVal
    await act(async () => {
      returnVal = await result.current.applyToMemory()
    })

    expect(returnVal.ok).toBe(false)
    expect(result.current.applyMemoryMessage.ok).toBe(false)
    expect(backtestApi.promoteRiskParams).not.toHaveBeenCalled()
  })

  it('D2. applyToMemory: success path — calls promoteRiskParams, sets message.ok=true, calls onMemoryApplied', async () => {
    const onMemoryApplied = vi.fn()
    const props = makeProps({ onMemoryApplied })
    const { result } = renderHook(() => useGridSearch(props))

    await seedWinnerResult(result, DEFAULT_WINNER)

    backtestApi.promoteRiskParams.mockResolvedValueOnce({ persisted: true, message: 'Saved' })

    await act(async () => {
      await result.current.applyToMemory()
    })

    expect(backtestApi.promoteRiskParams).toHaveBeenCalledOnce()
    expect(result.current.applyMemoryMessage.ok).toBe(true)
    expect(result.current.applyMemoryMessage.text).toContain('Saved')
    expect(onMemoryApplied).toHaveBeenCalled()
  })

  it('D3. applyToMemory: error path — API throws → sets message.ok=false', async () => {
    const { result } = renderHook(() => useGridSearch(makeProps()))

    await seedWinnerResult(result, {
      optimization: {
        hasWinner: true,
        bestParameters: { tpMultiplierDelta: 0, slMultiplierDelta: 0 },
      },
    })

    backtestApi.promoteRiskParams.mockRejectedValueOnce(new Error('network error'))

    await act(async () => {
      await result.current.applyToMemory()
    })

    expect(result.current.applyMemoryMessage.ok).toBe(false)
    expect(result.current.applyMemoryMessage.text).toContain('network error')
  })
})

// ─── Grupo E: useEffects ───────────────────────────────────────────────────

describe('useGridSearch — useEffects', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('E1. strategy sync effect: resets grid state when strategyName changes', async () => {
    const props = makeProps({ ticker: 'AAPL', strategyName: 'ORB' })
    const { result, rerender } = renderHook((p) => useGridSearch(p), { initialProps: props })

    await seedWinnerResult(result, DEFAULT_WINNER)
    expect(result.current.modalGridResult).not.toBeNull()

    await act(async () => {
      rerender({ ...props, strategyName: 'HAMMER' })
    })

    expect(result.current.modalGridResult).toBeNull()
    expect(result.current.modalGridError).toBeNull()
  })

  it('E2. elapsed timer effect: increments modalGridElapsed every second while loading', async () => {
    vi.useFakeTimers()

    const { result, unmount } = renderHook(() => useGridSearch(makeProps()))

    backtestApi.gridSearch.mockImplementationOnce(() => new Promise(() => {})) // never resolves

    act(() => {
      result.current.runGrid() // intentionally not awaited — keeps loading=true
    })

    expect(result.current.modalGridLoading).toBe(true)

    await act(async () => {
      vi.advanceTimersByTime(3000)
    })

    expect(result.current.modalGridElapsed).toBeGreaterThanOrEqual(3)

    unmount()
    vi.useRealTimers()
  })
})
