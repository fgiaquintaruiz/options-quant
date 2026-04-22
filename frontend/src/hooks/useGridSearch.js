// useGridSearch — manages grid search, walk-forward, retest, and apply-to-memory state.
// Separates transient grid workflow logic from the ImproveModal presentation layer.
//
// Accepts: { ticker, strategyName, startParams, maxConcurrent, onMemoryApplied }
// Returns: all form state, result state, and action handlers.

import { useState, useCallback, useEffect } from 'react'
import { backtestApi } from '../api'
import { lookbackMonthsToRange, inferCallPutFromStrategyName } from '../utils/backtestFormatters'

export function useGridSearch({ ticker, strategyName, startParams, maxConcurrent, onMemoryApplied }) {
  // Date range and axes
  const [modalLookback, setModalLookback] = useState('12')
  const [modalGridFrom, setModalGridFrom] = useState(() => lookbackMonthsToRange(12).from)
  const [modalGridTo, setModalGridTo] = useState(() => lookbackMonthsToRange(12).to)
  const [modalTpAxis, setModalTpAxis] = useState('0,0.1')
  const [modalSlAxis, setModalSlAxis] = useState('0,0.2')
  const [gridMetric, setGridMetric] = useState('TOTAL_PNL')

  // Constraints
  const [modalGridMinTrades, setModalGridMinTrades] = useState('10')
  const [modalGridMaxDdPct, setModalGridMaxDdPct] = useState('')

  // Walk-forward
  const [modalWalkForward, setModalWalkForward] = useState(false)
  const [modalWfTrainDays, setModalWfTrainDays] = useState('90')
  const [modalWfTestDays, setModalWfTestDays] = useState('30')
  const [modalWfStepDays, setModalWfStepDays] = useState('')

  // Grid result
  const [modalGridLoading, setModalGridLoading] = useState(false)
  const [modalGridError, setModalGridError] = useState(null)
  const [modalGridResult, setModalGridResult] = useState(null)
  const [modalGridElapsed, setModalGridElapsed] = useState(0)
  const [gridFocusTicker, setGridFocusTicker] = useState('')
  const [gridFocusStrategy, setGridFocusStrategy] = useState('')
  const [manualApplyStrategy, setManualApplyStrategy] = useState('p5 continuation')

  // Retest
  const [retestLoading, setRetestLoading] = useState(false)
  const [retestError, setRetestError] = useState(null)
  const [retestData, setRetestData] = useState(null)

  // Apply to memory
  const [applyMemoryLoading, setApplyMemoryLoading] = useState(false)
  const [applyMemoryMessage, setApplyMemoryMessage] = useState(null)

  // Elapsed timer for the grid search
  useEffect(() => {
    if (!modalGridLoading) return
    setModalGridElapsed(0)
    const id = setInterval(() => setModalGridElapsed((s) => s + 1), 1000)
    return () => clearInterval(id)
  }, [modalGridLoading])

  // Resets grid state and syncs focus ticker/strategy whenever the target strategy changes.
  useEffect(() => {
    if (!strategyName) return
    const strat = String(strategyName).trim()
    setGridFocusStrategy(strat)
    setManualApplyStrategy(strat)
    if (ticker) setGridFocusTicker(ticker)
    setModalGridError(null)
    setModalGridResult(null)
    setApplyMemoryMessage(null)
    setRetestError(null)
    setRetestData(null)
  }, [ticker, strategyName])

  const runGrid = useCallback(async () => {
    const t = (ticker && String(ticker).trim().toUpperCase()) || ''
    if (!t || !strategyName?.trim()) {
      setModalGridError(!t ? 'Abrí Info desde una fila del trade log (hace falta el ticker).' : 'Falta la estrategia.')
      return
    }
    const parseNumberList = (str) =>
      String(str).split(/[\s,]+/).map((s) => parseFloat(s.trim())).filter((n) => !Number.isNaN(n))
    const tpVals = parseNumberList(modalTpAxis)
    const slVals = parseNumberList(modalSlAxis)
    if (!tpVals.length || !slVals.length) {
      setModalGridError('Los ejes TP Δ y SL Δ necesitan números separados por coma (ej. 0,0.1)')
      return
    }
    const minTradesRaw = String(modalGridMinTrades ?? '').trim()
    const minTradesParsed = minTradesRaw === '' ? null : parseInt(minTradesRaw, 10)
    if (minTradesParsed !== null && (Number.isNaN(minTradesParsed) || minTradesParsed < 0)) {
      setModalGridError('Mín. trades: número entero ≥ 0 o vacío.')
      return
    }
    const ddRaw = String(modalGridMaxDdPct ?? '').trim()
    let constraintMaxDd = null
    if (ddRaw !== '') {
      const ddNum = parseFloat(ddRaw.replace(',', '.'))
      if (Number.isNaN(ddNum) || ddNum < 0) { setModalGridError('Max drawdown: número ≥ 0 (ej. 35) o vacío.'); return }
      constraintMaxDd = ddNum > 1 ? ddNum / 100 : ddNum
    }
    let wfTrain = null, wfTest = null, wfStep = null
    if (modalWalkForward) {
      const td = parseInt(String(modalWfTrainDays).trim(), 10)
      const ted = parseInt(String(modalWfTestDays).trim(), 10)
      if (Number.isNaN(td) || td < 1 || Number.isNaN(ted) || ted < 1) {
        setModalGridError('Walk-forward: días de entrenamiento y de test deben ser enteros ≥ 1.'); return
      }
      wfTrain = td; wfTest = ted
      const st = String(modalWfStepDays ?? '').trim()
      if (st !== '') {
        const sd = parseInt(st, 10)
        if (Number.isNaN(sd) || sd < 1) { setModalGridError('Walk-forward: paso entero ≥ 1 o vacío.'); return }
        wfStep = sd
      }
    }
    setModalGridLoading(true)
    setModalGridError(null)
    setModalGridResult(null)
    try {
      const data = await backtestApi.gridSearch({
        tickers: [t],
        fromDate: modalGridFrom, toDate: modalGridTo,
        initialCapital: startParams.capital, riskPerTradePct: startParams.risk,
        slippagePct: 0.005, commissionPerContract: 0.65,
        maxConcurrentTrades: maxConcurrent, executionTimeframe: 'MIN_15',
        includeTradePlans: true, deterministicMode: false,
        axes: [
          { name: 'tpMultiplierDelta', values: tpVals },
          { name: 'slMultiplierDelta', values: slVals },
        ],
        searchMode: 'EXHAUSTIVE', randomSampleCount: null, randomSeed: null,
        constraintMinTrades: minTradesParsed, constraintMaxDrawdownPct: constraintMaxDd,
        primaryMetric: gridMetric,
        walkForwardTrainDays: wfTrain, walkForwardTestDays: wfTest, walkForwardStepDays: wfStep,
      })
      setModalGridResult(data)
      setGridFocusTicker(t)
      setGridFocusStrategy(String(strategyName).trim())
      setManualApplyStrategy(String(strategyName).trim())
    } catch (e) {
      setModalGridError(e.message || 'Grid search failed')
    } finally {
      setModalGridLoading(false)
    }
  }, [
    ticker, strategyName, modalGridFrom, modalGridTo, modalTpAxis, modalSlAxis,
    startParams.capital, startParams.risk, maxConcurrent, gridMetric,
    modalGridMinTrades, modalGridMaxDdPct, modalWalkForward,
    modalWfTrainDays, modalWfTestDays, modalWfStepDays,
  ])

  const runRetest = useCallback(async () => {
    const opt = modalGridResult?.optimization
    if (!opt?.hasWinner || !opt.bestParameters || !strategyName?.trim()) return
    setRetestLoading(true); setRetestError(null); setRetestData(null)
    try {
      const data = await backtestApi.retestStrategy(
        strategyName.trim(),
        Number(startParams.capital) || 50000, Number(startParams.risk) || 0.02,
        true,
        Number(opt.bestParameters.tpMultiplierDelta) || 0,
        Number(opt.bestParameters.slMultiplierDelta) || 0,
        ticker?.trim() || null,
      )
      if (!data.success) setRetestError(data.error || data.message || 'Retest failed')
      setRetestData(data)
    } catch (e) {
      setRetestError(e.message || 'Retest failed')
    } finally {
      setRetestLoading(false)
    }
  }, [modalGridResult, strategyName, ticker, startParams.capital, startParams.risk])

  // Returns { ok } so the caller decides whether to close the modal and restart the scan.
  const applyToMemory = useCallback(async () => {
    const opt = modalGridResult?.optimization
    if (!opt?.hasWinner || !opt.bestParameters) {
      setApplyMemoryMessage({ ok: false, text: 'No hay celda ganadora. Ejecutá el grid primero.' })
      return { ok: false }
    }
    const t = (gridFocusTicker && gridFocusTicker.trim()) || ''
    const strategy = (gridFocusStrategy && gridFocusStrategy.trim()) || manualApplyStrategy.trim()
    if (!t || !strategy) {
      setApplyMemoryMessage({ ok: false, text: !t ? 'Falta el ticker.' : 'Falta la estrategia.' })
      return { ok: false }
    }
    setApplyMemoryLoading(true); setApplyMemoryMessage(null)
    try {
      const data = await backtestApi.promoteRiskParams({
        ticker: t, strategyName: strategy,
        isCall: inferCallPutFromStrategyName(strategy),
        tpMultiplierDelta: Number(opt.bestParameters.tpMultiplierDelta) || 0,
        slMultiplierDelta: Number(opt.bestParameters.slMultiplierDelta) || 0,
        dryRun: false,
      })
      const persisted = !!data.persisted
      setApplyMemoryMessage({ ok: persisted, text: data.message || (persisted ? 'Guardado en ticker-memory.json' : 'No guardado') })
      if (persisted) onMemoryApplied()
      return { ok: persisted }
    } catch (e) {
      setApplyMemoryMessage({ ok: false, text: e.message || 'Error al guardar' })
      return { ok: false }
    } finally {
      setApplyMemoryLoading(false)
    }
  }, [modalGridResult, gridFocusTicker, gridFocusStrategy, manualApplyStrategy, onMemoryApplied])

  return {
    // Date range
    modalLookback, setModalLookback,
    modalGridFrom, setModalGridFrom,
    modalGridTo, setModalGridTo,
    // Axes and metric
    modalTpAxis, setModalTpAxis,
    modalSlAxis, setModalSlAxis,
    gridMetric, setGridMetric,
    // Constraints
    modalGridMinTrades, setModalGridMinTrades,
    modalGridMaxDdPct, setModalGridMaxDdPct,
    // Walk-forward
    modalWalkForward, setModalWalkForward,
    modalWfTrainDays, setModalWfTrainDays,
    modalWfTestDays, setModalWfTestDays,
    modalWfStepDays, setModalWfStepDays,
    // Grid result
    modalGridLoading, modalGridElapsed, modalGridError, modalGridResult,
    // Retest
    retestLoading, retestError, retestData,
    // Apply to memory
    applyMemoryLoading, applyMemoryMessage,
    // Actions
    runGrid, runRetest, applyToMemory,
  }
}
