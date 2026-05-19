import { useState, useCallback, useMemo } from 'react'
import { liveApi, externalPositionsApi } from '../api'
import { formatSignalTimestamp, formatTodayWallClock, isSignalStale } from '../utils/liveSignalUtils'

export function useTradeActions({ signals, closedTrades, fetchSignals, setErrorMsg, externalPositions, refreshExternalPositions, staleClock }) {
  const [pendingActions, setPendingActions] = useState({})

  const mapSignal = useCallback((s) => {
    const candleTs = s.timestamp
    const closed   = closedTrades[s.ticker]
    const signalStale = isSignalStale(candleTs)
    const executed    = s.tradeStatus === 'EXECUTED'
    const entryAt  = executed && s.executeTime ? formatTodayWallClock(s.executeTime) : formatSignalTimestamp(candleTs)
    return {
      ticker: s.ticker, pattern: s.candlestickPattern || 'signal',
      strategy: s.strategy, direction: s.direction, ep: s.currentPrice,
      signalFound: formatSignalTimestamp(s.signalFoundAt), entryAt,
      exitedAt: closed ? formatTodayWallClock(closed.closeTime) : '-',
      tp: s.tradePlan?.takeProfit, sl: s.tradePlan?.stopLoss,
      closePrice: closed ? closed.closePrice : null, xp: '-',
      exitReason: closed ? (closed.exitReason || 'MANUAL_CLOSE') : 'LIVE SIGNAL',
      netPnl: null, executeTime: s.executeTime, tradeStatus: s.tradeStatus,
      orderId: s.orderId, tpOrderId: s.tpOrderId, slOrderId: s.slOrderId, signalStale,
      signalTimestampMs: new Date(s.timestamp).getTime(),
      replay: s.replay ?? false,
      newsBias: s.newsBias ?? 'NEUTRAL',
      earningsAlert: s.earningsAlert ?? false,
    }
  }, [closedTrades])

  const trades = useMemo(
    () => signals.map(mapSignal),
    [signals, mapSignal, staleClock]
  )

  const staleSignalCount = useMemo(
    () => trades.filter((t) => t.signalStale && (!t.exitedAt || t.exitedAt === '-') && t.tradeStatus !== 'EXECUTED').length,
    [trades]
  )

  const handleDeleteSignal = async (ticker) => {
    const ok = await liveApi.deleteSignal(ticker).catch((e) => { setErrorMsg(`Delete failed: ${e.message}`); return null })
    if (ok && !ok.success) setErrorMsg(ok.message || 'No se pudo borrar la señal')
    await fetchSignals()
  }

  const handleClearStaleBatch = async (tickers) => {
    await liveApi.batchDeleteSignals(tickers)
      .then(fetchSignals)
      .catch((e) => setErrorMsg(`Batch delete failed: ${e.message}`))
  }

  const handleClearAllStale = async () => {
    await liveApi.clearStaleSignals()
      .then(fetchSignals)
      .catch((e) => setErrorMsg(`Clear stale failed: ${e.message}`))
  }

  const handleCloseTrade = async (ticker, price, isOpenPos = false, tpOrderId = null, slOrderId = null) => {
    setPendingActions((prev) => ({ ...prev, [ticker]: true }))
    const done = () => setPendingActions((prev) => ({ ...prev, [ticker]: false }))
    try {
      if (isOpenPos) {
        const signal = trades.find((t) => t.ticker === ticker && Number(t.ep) === Number(price))
        if (!signal) { setErrorMsg(`Could not find signal for ${ticker}`); done(); return }
        if (signal.signalStale) { setErrorMsg('Señal >30 min — ejecución bloqueada. Revisa velas / fuente.'); done(); return }
        const ok = await liveApi.executeSignal(ticker, signal.direction, price, signal.strategy)
        if (!ok.success) setErrorMsg(`Execution failed: ${ok.message}`)
        await fetchSignals()
        done()
        return
      }
      const ok = await liveApi.closeTrade(ticker, price, tpOrderId, slOrderId)
      if (ok.success) fetchSignals()
    } catch (e) {
      setErrorMsg(`Action failed: ${e.message}`)
    } finally {
      done()
    }
  }

  const handleCancelTrade = async (ticker, orderId) => {
    if (!orderId) return
    setPendingActions((prev) => ({ ...prev, [ticker]: true }))
    const ok = await liveApi.cancelTrade(ticker, orderId).catch((e) => { setErrorMsg(`Cancel failed: ${e.message}`); return null })
    if (ok?.success) fetchSignals()
    setPendingActions((prev) => ({ ...prev, [ticker]: false }))
  }

  const handleCloseExternal = async (ticker) => {
    await externalPositionsApi.closeExternalPosition(ticker)
    refreshExternalPositions()
  }

  const handleScheduleClose1450 = async (ticker) => {
    await externalPositionsApi.scheduleClose1450(ticker)
  }

  return {
    trades,
    staleSignalCount,
    pendingActions,
    handleCloseTrade,
    handleCancelTrade,
    handleDeleteSignal,
    handleClearStaleBatch,
    handleClearAllStale,
    handleCloseExternal,
    handleScheduleClose1450,
  }
}
