/**
 * ImproveModal — modal that shows strategy analysis and hosts the grid-search workflow.
 *
 * Grid state and API logic are fully delegated to the useGridSearch hook.
 * This component only owns the improve-analysis lifecycle and modal presentation.
 *
 * Props:
 *   open         — controls visibility
 *   ticker       — uppercase ticker symbol from the selected trade row
 *   strategyName — strategy name from the selected trade row
 *   entryTime    — trade entry timestamp (used to patch the trade log on close)
 *   startParams  — { capital, risk } from the active backtest config
 *   maxConcurrent — engine concurrency setting
 *   running      — true while a full backtest is in progress
 *   onClose      — called with { gridResult, ticker, strategyName, entryTime }
 *   onMemoryApplied — called after a successful promote (parent reloads MemoryPanel if open)
 *   onStartScan  — triggers a new full backtest run
 *
 * Note: UI labels intentionally mix Spanish and English — consistent with the rest of the app.
 */
import React, { useState, useEffect, useCallback } from 'react'
import { X } from 'lucide-react'
import { backtestApi } from '../api'
import { LS } from '../utils/storage'
import { formatUsd } from '../utils/backtestFormatters'
import { useGridSearch } from '../hooks/useGridSearch'
import GridSearchPanel from './GridSearchPanel'

export default function ImproveModal({
  open, ticker, strategyName, entryTime,
  startParams, maxConcurrent, running,
  onClose, onMemoryApplied, onStartScan,
}) {
  const [improveLoading, setImproveLoading] = useState(false)
  const [improveData, setImproveData] = useState(null)
  const [improveError, setImproveError] = useState(null)

  const gridSearch = useGridSearch({ ticker, strategyName, startParams, maxConcurrent, onMemoryApplied })

  // Fetch strategy analysis each time the modal opens for a new ticker/strategy pair.
  useEffect(() => {
    if (!open || !strategyName) return
    const strat = String(strategyName).trim()
    setImproveLoading(true)
    setImproveData(null)
    setImproveError(null)
    LS.set('bt_lastImproveStrategy', strat)
    backtestApi.improveStrategy(strat, ticker || undefined)
      .then((d) => {
        if (!d.success) setImproveError(d.message || d.error || 'Analysis failed.')
        else setImproveData(d)
      })
      .catch((e) => setImproveError(e.message || 'Request failed'))
      .finally(() => setImproveLoading(false))
  }, [open, ticker, strategyName])

  const handleClose = useCallback(() => {
    onClose({ gridResult: gridSearch.modalGridResult, ticker, strategyName, entryTime })
  }, [gridSearch.modalGridResult, ticker, strategyName, entryTime, onClose])

  const handleApplyAndStartScan = useCallback(async () => {
    const result = await gridSearch.applyToMemory()
    if (result?.ok) { handleClose(); onStartScan() }
  }, [gridSearch, handleClose, onStartScan])

  if (!open) return null

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="bt-improve-title"
      className="bt-modal-backdrop"
      onClick={(e) => { if (e.target === e.currentTarget) handleClose() }}
    >
      <div className="card bt-modal-card" onClick={(e) => e.stopPropagation()}>
        <button type="button" aria-label="Close" className="btn btn-secondary bt-modal-close" onClick={handleClose}>
          <X size={18} />
        </button>
        <h3 id="bt-improve-title" className="m-0 bt-modal-title">
          Info
          {(ticker || strategyName) && (
            <span className="color-muted bt-modal-subtitle">
              {ticker ? <strong className="color-text">{ticker}</strong> : null}
              {ticker && strategyName ? ' · ' : null}
              {strategyName ? <code className="bt-modal-code">{strategyName}</code> : null}
            </span>
          )}
        </h3>

        {improveLoading && <p className="text-sm color-muted bt-modal-loading">Loading analysis…</p>}
        {improveError && !improveLoading && <p className="text-sm color-error bt-modal-loading">{improveError}</p>}

        {improveData && !improveLoading && improveData.success && (
          <>
            {improveData.analysisScope === 'TICKER_STRATEGY' && improveData.filterTicker && (
              <p className="text-xs color-muted bt-modal-scope">
                Alcance: solo <strong className="color-text">{improveData.filterTicker}</strong> · esta estrategia.
              </p>
            )}
            {improveData.analysisScope === 'STRATEGY_ALL_TICKERS' && (
              <p className="text-xs color-muted bt-modal-scope">Alcance: todos los trades de esta estrategia (todos los tickers).</p>
            )}
            {improveData.lowSampleWarning && (
              <p className="text-xs bt-modal-warn">Muestra pequeña (&lt;3 trades). Compará también la vista por estrategia completa.</p>
            )}

            <div className="flex-wrap bt-improve-kpi-row">
              <div><div className="text-xs color-muted">Trades (baseline)</div><div className="font-bold">{improveData.totalTrades ?? '—'}</div></div>
              <div><div className="text-xs color-muted">W / L</div><div className="font-bold">{improveData.wins ?? '—'} / {improveData.losses ?? '—'}</div></div>
              <div><div className="text-xs color-muted">Win rate</div><div className="font-bold">{improveData.winRate != null ? `${Number(improveData.winRate).toFixed(1)}%` : '—'}</div></div>
              <div>
                <div className="text-xs color-muted">Total P&L</div>
                <div className={`font-bold ${(Number(improveData.totalPnl) || 0) >= 0 ? 'color-success' : 'color-error'}`}>
                  ${formatUsd(improveData.totalPnl)}
                </div>
              </div>
            </div>

            {improveData.exitReasons && Object.keys(improveData.exitReasons).length > 0 && (
              <div className="bt-improve-section">
                <div className="text-xs color-muted bt-improve-section-label">Exit reasons</div>
                <ul className="m-0 bt-improve-list">
                  {Object.entries(improveData.exitReasons).map(([reason, count]) => (
                    <li key={reason} className="bt-improve-list-item"><strong>{reason}</strong>: {String(count)}</li>
                  ))}
                </ul>
              </div>
            )}
            {Array.isArray(improveData.recommendations) && improveData.recommendations.length > 0 && (
              <div className="bt-improve-section">
                <div className="text-xs color-muted bt-improve-section-label">Recommendations</div>
                <ul className="m-0 bt-improve-list">
                  {improveData.recommendations.map((rec, i) => <li key={i} className="bt-improve-list-item">{rec}</li>)}
                </ul>
              </div>
            )}
            {Array.isArray(improveData.suggestedParams) && improveData.suggestedParams.length > 0 && (
              <div className="bt-improve-section">
                <div className="text-xs color-muted bt-improve-section-label">Suggested parameter tweaks</div>
                <ul className="m-0 bt-improve-list">
                  {improveData.suggestedParams.map((p, i) => <li key={i} className="bt-improve-list-item">{p}</li>)}
                </ul>
              </div>
            )}

            <GridSearchPanel
              gridSearch={gridSearch}
              ticker={ticker}
              strategyName={strategyName}
              startParams={startParams}
              maxConcurrent={maxConcurrent}
              running={running}
              onApplyAndStartScan={handleApplyAndStartScan}
            />
          </>
        )}
      </div>
    </div>
  )
}
