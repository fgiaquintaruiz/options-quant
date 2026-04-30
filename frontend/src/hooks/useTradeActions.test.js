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

import { liveApi, externalPositionsApi } from '../api'
import { isSignalStale } from '../utils/liveSignalUtils'
import { useTradeActions } from './useTradeActions'

/**
 * Tests for useTradeActions — derives `trades` and `staleSignalCount`,
 * exposes handlers for close/cancel/delete/external/scheduleClose1450.
 * Mocks liveApi and externalPositionsApi to assert call signatures and side-effects.
 */

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

  it('derives trades from signals: includes the mapped signal', () => {
    const signal = makeSignal()
    const props = makeProps({ signals: [signal] })

    const { result } = renderHook(() => useTradeActions(props))

    expect(result.current.trades).toHaveLength(1)
    expect(result.current.trades[0].ticker).toBe('AAPL')
    expect(result.current.trades[0].direction).toBe('LONG')
    expect(result.current.trades[0].strategy).toBe('ORB')
  })

  it('staleSignalCount: counts a stale signal without exitedAt', () => {
    isSignalStale.mockReturnValue(true)
    const signal = makeSignal()
    const props = makeProps({ signals: [signal] })

    const { result } = renderHook(() => useTradeActions(props))

    expect(result.current.staleSignalCount).toBeGreaterThanOrEqual(1)
  })

  it('staleSignalCount: EXECUTED signal does not count as stale even if isSignalStale=true', () => {
    isSignalStale.mockReturnValue(true)
    const signal = makeSignal({ tradeStatus: 'EXECUTED' })
    const props = makeProps({ signals: [signal] })

    const { result } = renderHook(() => useTradeActions(props))

    expect(result.current.staleSignalCount).toBe(0)
  })

  it('handleDeleteSignal: calls liveApi.deleteSignal(ticker) and then fetchSignals', async () => {
    const props = makeProps()
    const { result } = renderHook(() => useTradeActions(props))

    await act(async () => {
      await result.current.handleDeleteSignal('AAPL')
    })

    expect(liveApi.deleteSignal).toHaveBeenCalledWith('AAPL')
    expect(props.fetchSignals).toHaveBeenCalled()
  })

  it('handleCloseTrade with isOpenPos=true: calls liveApi.executeSignal with ticker/direction/price/strategy', async () => {
    const signal = makeSignal({ ticker: 'AAPL', currentPrice: 150, direction: 'LONG', strategy: 'ORB' })
    const props = makeProps({ signals: [signal] })

    const { result } = renderHook(() => useTradeActions(props))

    await act(async () => {
      await result.current.handleCloseTrade('AAPL', 150, true)
    })

    expect(liveApi.executeSignal).toHaveBeenCalledWith('AAPL', 'LONG', 150, 'ORB')
    expect(props.fetchSignals).toHaveBeenCalled()
  })

  it('handleCloseTrade with isOpenPos=true: pendingActions[ticker]=true during the call, false after', async () => {
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

  it('handleCancelTrade: calls liveApi.cancelTrade(ticker, orderId)', async () => {
    const props = makeProps()
    const { result } = renderHook(() => useTradeActions(props))

    await act(async () => {
      await result.current.handleCancelTrade('AAPL', 'ORDER-123')
    })

    expect(liveApi.cancelTrade).toHaveBeenCalledWith('AAPL', 'ORDER-123')
  })

  it('handleCancelTrade: no-op if orderId is falsy', async () => {
    const props = makeProps()
    const { result } = renderHook(() => useTradeActions(props))

    await act(async () => {
      await result.current.handleCancelTrade('AAPL', null)
    })

    expect(liveApi.cancelTrade).not.toHaveBeenCalled()
  })

  it('handleClearStaleBatch: calls liveApi.batchDeleteSignals with the tickers', async () => {
    const props = makeProps()
    const { result } = renderHook(() => useTradeActions(props))

    await act(async () => {
      await result.current.handleClearStaleBatch(['AAPL', 'TSLA'])
    })

    expect(liveApi.batchDeleteSignals).toHaveBeenCalledWith(['AAPL', 'TSLA'])
    expect(props.fetchSignals).toHaveBeenCalled()
  })

  it('handleClearAllStale: calls liveApi.clearStaleSignals', async () => {
    const props = makeProps()
    const { result } = renderHook(() => useTradeActions(props))

    await act(async () => {
      await result.current.handleClearAllStale()
    })

    expect(liveApi.clearStaleSignals).toHaveBeenCalled()
    expect(props.fetchSignals).toHaveBeenCalled()
  })

  it('error handling: if liveApi.executeSignal rejects, calls setErrorMsg', async () => {
    liveApi.executeSignal.mockRejectedValueOnce(new Error('TWS timeout'))
    const signal = makeSignal({ ticker: 'NVDA', currentPrice: 500 })
    const props = makeProps({ signals: [signal] })

    const { result } = renderHook(() => useTradeActions(props))

    await act(async () => {
      await result.current.handleCloseTrade('NVDA', 500, true)
    })

    expect(props.setErrorMsg).toHaveBeenCalledWith(expect.stringContaining('TWS timeout'))
  })

  it('handleCloseTrade con isOpenPos=false: llama liveApi.closeTrade(ticker, price, tpOrderId, slOrderId) y fetchSignals', async () => {
    const props = makeProps()
    const { result } = renderHook(() => useTradeActions(props))

    await act(async () => {
      await result.current.handleCloseTrade('AAPL', 100, false, 'TP-1', 'SL-2')
    })

    expect(liveApi.closeTrade).toHaveBeenCalledWith('AAPL', 100, 'TP-1', 'SL-2')
    expect(props.fetchSignals).toHaveBeenCalled()
  })

  it('handleCloseTrade con isOpenPos=false y closeTrade success=false: NO llama fetchSignals', async () => {
    liveApi.closeTrade.mockResolvedValueOnce({ success: false, message: 'denied' })
    const props = makeProps()
    const { result } = renderHook(() => useTradeActions(props))

    await act(async () => {
      await result.current.handleCloseTrade('AAPL', 100, false, 'TP-1', 'SL-2')
    })

    expect(liveApi.closeTrade).toHaveBeenCalledWith('AAPL', 100, 'TP-1', 'SL-2')
    expect(props.fetchSignals).not.toHaveBeenCalled()
  })

  it('handleCloseExternal: llama externalPositionsApi.closeExternalPosition(ticker) y refreshExternalPositions', async () => {
    const props = makeProps()
    const { result } = renderHook(() => useTradeActions(props))

    await act(async () => {
      await result.current.handleCloseExternal('NVDA')
    })

    expect(externalPositionsApi.closeExternalPosition).toHaveBeenCalledWith('NVDA')
    expect(props.refreshExternalPositions).toHaveBeenCalled()
  })

  it('handleScheduleClose1450: llama externalPositionsApi.scheduleClose1450(ticker)', async () => {
    const props = makeProps()
    const { result } = renderHook(() => useTradeActions(props))

    await act(async () => {
      await result.current.handleScheduleClose1450('TSLA')
    })

    expect(externalPositionsApi.scheduleClose1450).toHaveBeenCalledWith('TSLA')
  })

  it('handleCancelTrade error path: si liveApi.cancelTrade rechaza, llama setErrorMsg y NO llama fetchSignals', async () => {
    liveApi.cancelTrade.mockRejectedValueOnce(new Error('TWS down'))
    const props = makeProps()
    const { result } = renderHook(() => useTradeActions(props))

    await act(async () => {
      await result.current.handleCancelTrade('AAPL', 'ORDER-1')
    })

    expect(props.setErrorMsg).toHaveBeenCalledWith(expect.stringContaining('TWS down'))
    expect(props.fetchSignals).not.toHaveBeenCalled()
  })
})
