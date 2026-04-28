import { renderHook, act } from '@testing-library/react'
import { vi, describe, it, expect, beforeEach } from 'vitest'

vi.mock('../api', () => ({
  liveApi: {
    deleteSignal: vi.fn().mockResolvedValue({ success: true }),
    batchDeleteSignals: vi.fn().mockResolvedValue({}),
    clearStaleSignals: vi.fn().mockResolvedValue({}),
    executeSignal: vi.fn().mockResolvedValue({ success: true }),
    closeTrade: vi.fn().mockResolvedValue({ success: true }),
    cancelTrade: vi.fn().mockResolvedValue({ success: true }),
  },
  externalPositionsApi: {
    closeExternalPosition: vi.fn().mockResolvedValue({}),
    scheduleClose1450: vi.fn().mockResolvedValue({}),
  },
}))

vi.mock('../utils/liveSignalUtils', () => ({
  isSignalStale: vi.fn(() => false),
  formatSignalTimestamp: vi.fn((v) => v ?? '-'),
  formatTodayWallClock: vi.fn((v) => v ?? '-'),
}))

import { liveApi } from '../api'
import { isSignalStale } from '../utils/liveSignalUtils'
import { useTradeActions } from './useTradeActions'

function makeSignal(overrides = {}) {
  return {
    ticker: 'AAPL',
    timestamp: Date.now(),
    signalFoundAt: Date.now(),
    tradeStatus: 'PENDING',
    direction: 'LONG',
    strategy: 'ORB',
    currentPrice: 150,
    candlestickPattern: 'hammer',
    tradePlan: { takeProfit: 155, stopLoss: 145 },
    executeTime: null,
    orderId: null,
    tpOrderId: null,
    slOrderId: null,
    ...overrides,
  }
}

function makeProps(overrides = {}) {
  return {
    signals: [],
    closedTrades: {},
    fetchSignals: vi.fn().mockResolvedValue(undefined),
    setErrorMsg: vi.fn(),
    externalPositions: [],
    refreshExternalPositions: vi.fn(),
    staleClock: 0,
    ...overrides,
  }
}

describe('useTradeActions', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    isSignalStale.mockReturnValue(false)
  })

  it('trades derivado: dado signals=[{ticker:AAPL}], trades incluye la señal mapeada', () => {
    const signal = makeSignal()
    const props = makeProps({ signals: [signal] })

    const { result } = renderHook(() => useTradeActions(props))

    expect(result.current.trades).toHaveLength(1)
    expect(result.current.trades[0].ticker).toBe('AAPL')
    expect(result.current.trades[0].direction).toBe('LONG')
    expect(result.current.trades[0].strategy).toBe('ORB')
  })

  it('staleSignalCount: señal con isSignalStale=true y sin exitedAt → staleSignalCount >= 1', () => {
    isSignalStale.mockReturnValue(true)
    const signal = makeSignal()
    const props = makeProps({ signals: [signal] })

    const { result } = renderHook(() => useTradeActions(props))

    expect(result.current.staleSignalCount).toBeGreaterThanOrEqual(1)
  })

  it('staleSignalCount: señal EXECUTED no cuenta como stale aunque isSignalStale=true', () => {
    isSignalStale.mockReturnValue(true)
    const signal = makeSignal({ tradeStatus: 'EXECUTED' })
    const props = makeProps({ signals: [signal] })

    const { result } = renderHook(() => useTradeActions(props))

    expect(result.current.staleSignalCount).toBe(0)
  })

  it('handleDeleteSignal: llama liveApi.deleteSignal(ticker) y luego fetchSignals', async () => {
    const props = makeProps()
    const { result } = renderHook(() => useTradeActions(props))

    await act(async () => {
      await result.current.handleDeleteSignal('AAPL')
    })

    expect(liveApi.deleteSignal).toHaveBeenCalledWith('AAPL')
    expect(props.fetchSignals).toHaveBeenCalled()
  })

  it('handleCloseTrade con signal (isOpenPos=true): llama liveApi.executeSignal con ticker/direction/price/strategy', async () => {
    const signal = makeSignal({ ticker: 'AAPL', currentPrice: 150, direction: 'LONG', strategy: 'ORB' })
    const props = makeProps({ signals: [signal] })

    const { result } = renderHook(() => useTradeActions(props))

    await act(async () => {
      await result.current.handleCloseTrade('AAPL', 150, true)
    })

    expect(liveApi.executeSignal).toHaveBeenCalledWith('AAPL', 'LONG', 150, 'ORB')
    expect(props.fetchSignals).toHaveBeenCalled()
  })

  it('handleCloseTrade (isOpenPos=true): pendingActions[ticker]=true durante la llamada, false después', async () => {
    let resolveExec
    liveApi.executeSignal.mockImplementationOnce(
      () => new Promise((res) => { resolveExec = () => res({ success: true }) })
    )

    const signal = makeSignal({ ticker: 'TSLA', currentPrice: 200 })
    const props = makeProps({ signals: [signal] })

    const { result } = renderHook(() => useTradeActions(props))

    let promise
    act(() => {
      promise = result.current.handleCloseTrade('TSLA', 200, true)
    })

    expect(result.current.pendingActions['TSLA']).toBe(true)

    await act(async () => {
      resolveExec()
      await promise
    })

    expect(result.current.pendingActions['TSLA']).toBe(false)
  })

  it('handleCancelTrade: llama liveApi.cancelTrade(ticker, orderId)', async () => {
    const props = makeProps()
    const { result } = renderHook(() => useTradeActions(props))

    await act(async () => {
      await result.current.handleCancelTrade('AAPL', 'ORDER-123')
    })

    expect(liveApi.cancelTrade).toHaveBeenCalledWith('AAPL', 'ORDER-123')
  })

  it('handleCancelTrade: no hace nada si orderId es falsy', async () => {
    const props = makeProps()
    const { result } = renderHook(() => useTradeActions(props))

    await act(async () => {
      await result.current.handleCancelTrade('AAPL', null)
    })

    expect(liveApi.cancelTrade).not.toHaveBeenCalled()
  })

  it('handleClearStaleBatch: llama liveApi.batchDeleteSignals con los tickers', async () => {
    const props = makeProps()
    const { result } = renderHook(() => useTradeActions(props))

    await act(async () => {
      await result.current.handleClearStaleBatch(['AAPL', 'TSLA'])
    })

    expect(liveApi.batchDeleteSignals).toHaveBeenCalledWith(['AAPL', 'TSLA'])
    expect(props.fetchSignals).toHaveBeenCalled()
  })

  it('handleClearAllStale: llama liveApi.clearStaleSignals', async () => {
    const props = makeProps()
    const { result } = renderHook(() => useTradeActions(props))

    await act(async () => {
      await result.current.handleClearAllStale()
    })

    expect(liveApi.clearStaleSignals).toHaveBeenCalled()
    expect(props.fetchSignals).toHaveBeenCalled()
  })

  it('error handling: si liveApi.executeSignal falla, llama setErrorMsg', async () => {
    liveApi.executeSignal.mockRejectedValueOnce(new Error('TWS timeout'))
    const signal = makeSignal({ ticker: 'NVDA', currentPrice: 500 })
    const props = makeProps({ signals: [signal] })

    const { result } = renderHook(() => useTradeActions(props))

    await act(async () => {
      await result.current.handleCloseTrade('NVDA', 500, true)
    })

    expect(props.setErrorMsg).toHaveBeenCalledWith(expect.stringContaining('TWS timeout'))
  })
})
