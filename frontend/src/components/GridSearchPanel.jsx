/**
 * GridSearchPanel — grid search form, walk-forward controls, and result display.
 *
 * Accepts a `gridSearch` object (the return value of the useGridSearch hook) to avoid
 * prop-drilling individual state variables and setters.
 *
 * Props:
 *   gridSearch       — return value of useGridSearch (form state + result state + actions)
 *   ticker           — ticker for display and request building
 *   strategyName     — strategy name for display
 *   startParams      — { capital, risk } from the active backtest config
 *   maxConcurrent    — engine concurrency setting
 *   running          — true while a full backtest is in progress (disables grid button)
 *   onApplyAndStartScan — saves the winning cell to memory and triggers a new full backtest
 *
 * Note: UI labels intentionally mix Spanish and English — consistent with the rest of the app.
 */
import React from 'react'
import { formatUsd, lookbackMonthsToRange } from '../utils/backtestFormatters'

function WalkForwardFoldsTable({ folds }) {
  if (!folds?.length) return null
  return (
    <div className="bt-wf-folds-wrap">
      <table className="bt-wf-folds-table">
        <thead>
          <tr className="color-muted">
            <th className="bt-wf-th">#</th>
            <th className="bt-wf-th">Train</th>
            <th className="bt-wf-th">Test</th>
            <th className="bt-wf-th bt-wf-th-right">OOS PnL</th>
            <th className="bt-wf-th bt-wf-th-right">N</th>
            <th className="bt-wf-th">Nota</th>
          </tr>
        </thead>
        <tbody>
          {folds.map((f) => (
            <tr key={f.foldIndex} className="bt-wf-fold-row">
              <td className="bt-wf-td">{f.foldIndex}</td>
              <td className="bt-wf-td bt-wf-td-nowrap">{f.trainFrom} → {f.trainTo}</td>
              <td className="bt-wf-td bt-wf-td-nowrap">{f.testFrom} → {f.testTo}</td>
              <td className="bt-wf-td bt-wf-td-right">{formatUsd(f.oosTotalPnl)}</td>
              <td className="bt-wf-td bt-wf-td-right">{f.oosTotalTrades}</td>
              <td className="bt-wf-td color-muted bt-wf-td-note">
                {f.skippedReason
                  ? f.skippedReason
                  : f.isOptimizationWinner
                    ? `tpΔ${Number(f.bestIsParameters?.tpMultiplierDelta ?? 0).toFixed(1)} slΔ${Number(f.bestIsParameters?.slMultiplierDelta ?? 0).toFixed(1)}`
                    : '—'}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function GridRowsTable({ rows }) {
  if (!rows?.length) return null
  return (
    <div className="bt-grid-rows-wrap">
      <table className="bt-grid-rows-table">
        <thead>
          <tr className="bt-grid-rows-thead-row">
            <th className="bt-grid-rows-th">#</th>
            <th className="bt-grid-rows-th">tpΔ</th>
            <th className="bt-grid-rows-th">slΔ</th>
            <th className="bt-grid-rows-th">P&L</th>
            <th className="bt-grid-rows-th">Trades</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row, ri) => (
            <tr key={`m-${row.cellIndex}-${ri}`} className="bt-grid-rows-row">
              <td className="bt-grid-rows-td">{row.cellIndex}</td>
              <td className="bt-grid-rows-td">{row.parameters?.tpMultiplierDelta ?? '—'}</td>
              <td className="bt-grid-rows-td">{row.parameters?.slMultiplierDelta ?? '—'}</td>
              <td className="bt-grid-rows-td">${formatUsd(row.totalPnl)}</td>
              <td className="bt-grid-rows-td">{row.totalTrades ?? '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

export default function GridSearchPanel({ gridSearch, ticker, strategyName, startParams, maxConcurrent, running, onApplyAndStartScan }) {
  const {
    modalLookback, setModalLookback,
    modalGridFrom, setModalGridFrom,
    modalGridTo, setModalGridTo,
    modalTpAxis, setModalTpAxis,
    modalSlAxis, setModalSlAxis,
    gridMetric, setGridMetric,
    modalGridMinTrades, setModalGridMinTrades,
    modalGridMaxDdPct, setModalGridMaxDdPct,
    modalWalkForward, setModalWalkForward,
    modalWfTrainDays, setModalWfTrainDays,
    modalWfTestDays, setModalWfTestDays,
    modalWfStepDays, setModalWfStepDays,
    modalGridLoading, modalGridElapsed, modalGridError, modalGridResult,
    retestLoading, retestError, retestData,
    applyMemoryLoading, applyMemoryMessage,
    runGrid, runRetest,
  } = gridSearch

  const handleLookbackChange = (v) => {
    setModalLookback(v)
    const m = Number(v)
    if (!Number.isNaN(m) && m > 0) {
      const rng = lookbackMonthsToRange(m)
      setModalGridFrom(rng.from)
      setModalGridTo(rng.to)
    }
  }

  return (
    <div className="bt-grid-section">
      <div className="text-sm font-bold bt-grid-section-title">Grid search (mismo motor que el panel)</div>
      <p className="text-xs color-muted bt-grid-desc">
        Un solo ticker <strong>{ticker || '—'}</strong>, rango de fechas y ejes TP/SL Δ.
        Capital y riesgo actuales: ${formatUsd(startParams.capital)} · {(Number(startParams.risk) * 100).toFixed(1)}%.
        {' '}La mejor celda respeta <strong>mín. trades</strong> y, si indicás, <strong>max drawdown</strong>.
      </p>

      <div className="flex-wrap flex-align-center gap-10 bt-grid-row">
        <label className="text-xs color-muted flex-align-center gap-6">
          Ventana
          <select value={modalLookback} onChange={(e) => handleLookbackChange(e.target.value)} className="bt-select">
            <option value="1">1 mes</option>
            <option value="3">3 meses</option>
            <option value="6">6 meses</option>
            <option value="12">1 año</option>
          </select>
        </label>
        <label className="text-xs color-muted flex-align-center gap-4">
          Desde <input type="date" value={modalGridFrom} onChange={(e) => setModalGridFrom(e.target.value)} className="bt-date-input" />
        </label>
        <label className="text-xs color-muted flex-align-center gap-4">
          Hasta <input type="date" value={modalGridTo} onChange={(e) => setModalGridTo(e.target.value)} className="bt-date-input" />
        </label>
      </div>

      <div className="flex-wrap flex-align-center gap-10 bt-grid-row">
        <label className="text-xs color-muted flex-col gap-4 bt-grid-axis-label">
          TP Δ (coma) <input value={modalTpAxis} onChange={(e) => setModalTpAxis(e.target.value)} className="bt-text-input w-full" />
        </label>
        <label className="text-xs color-muted flex-col gap-4 bt-grid-axis-label">
          SL Δ (coma) <input value={modalSlAxis} onChange={(e) => setModalSlAxis(e.target.value)} className="bt-text-input w-full" />
        </label>
        <label className="text-xs color-muted flex-align-center gap-6">
          Métrica
          <select value={gridMetric} onChange={(e) => setGridMetric(e.target.value)} className="bt-select">
            <option value="TOTAL_PNL">P&L total</option>
            <option value="PROFIT_FACTOR">Profit factor</option>
            <option value="WIN_RATE">Win rate</option>
          </select>
        </label>
      </div>

      <div className="flex-wrap flex-align-center gap-10 bt-grid-row">
        <label className="text-xs color-muted flex-col gap-4 bt-grid-constraint-label">
          Mín. trades (ganador)
          <input type="number" min={0} step={1} value={modalGridMinTrades} onChange={(e) => setModalGridMinTrades(e.target.value)} placeholder="vacío = sin filtro" className="bt-text-input w-full" />
        </label>
        <label className="text-xs color-muted flex-col gap-4 bt-grid-constraint-label">
          Max DD % (opcional)
          <input type="text" inputMode="decimal" value={modalGridMaxDdPct} onChange={(e) => setModalGridMaxDdPct(e.target.value)} placeholder="ej. 40" className="bt-text-input w-full" />
        </label>
      </div>

      <div className="bt-wf-box">
        <label className="text-xs flex-align-center gap-8 color-muted bt-wf-checkbox-label">
          <input type="checkbox" checked={modalWalkForward} onChange={(e) => setModalWalkForward(e.target.checked)} />
          Walk-forward (IS rejilla + OOS con el mejor IS por ventana)
        </label>
        {modalWalkForward && (
          <div className="flex-wrap flex-align-center gap-10 bt-wf-inputs">
            <label className="text-xs color-muted flex-col gap-4 bt-wf-day-label">
              Train (días) <input type="number" min={1} value={modalWfTrainDays} onChange={(e) => setModalWfTrainDays(e.target.value)} className="bt-text-input w-full" />
            </label>
            <label className="text-xs color-muted flex-col gap-4 bt-wf-day-label">
              Test (días) <input type="number" min={1} value={modalWfTestDays} onChange={(e) => setModalWfTestDays(e.target.value)} className="bt-text-input w-full" />
            </label>
            <label className="text-xs color-muted flex-col gap-4 bt-wf-day-label">
              Paso (días) <input type="number" min={1} value={modalWfStepDays} onChange={(e) => setModalWfStepDays(e.target.value)} placeholder={modalWfTestDays || 'test'} className="bt-text-input w-full" />
            </label>
            <span className="text-xs color-muted bt-wf-hint">Usa el rango Desde/Hasta del modal. Vacío en Paso = avanza igual que días de test.</span>
          </div>
        )}
      </div>

      <button
        type="button"
        className="btn btn-warning"
        disabled={modalGridLoading || running || !ticker}
        onClick={runGrid}
        data-testid="bt-modal-grid-search"
      >
        {modalGridLoading
          ? `${modalWalkForward ? 'Walk-forward' : 'Grid'}… ${modalGridElapsed}s`
          : modalWalkForward ? 'Ejecutar walk-forward' : 'Ejecutar grid search'}
      </button>

      {modalGridError && <p className="text-xs color-error bt-grid-error" role="alert">{modalGridError}</p>}

      {modalGridResult && (
        <div className="bt-grid-result">
          <div className="text-xs bt-grid-result-status">
            <span className={`font-bold ${modalGridResult.success ? 'color-success' : 'color-error'}`}>
              {modalGridResult.success ? 'Listo' : 'Falló'}
            </span>
            {modalGridResult.message && <span className="color-muted bt-grid-result-msg">{modalGridResult.message}</span>}
          </div>

          {modalGridResult.walkForwardSummary && (
            <div className="bt-wf-summary">
              <div className="font-bold bt-wf-summary-title">Walk-forward — OOS agregado</div>
              <div className="color-muted bt-wf-summary-body">
                Pliegues: {modalGridResult.walkForwardSummary.foldsCompleted ?? '—'} / {modalGridResult.walkForwardSummary.foldsPlanned ?? '—'}
                {' · '}con OOS: {modalGridResult.walkForwardSummary.foldsWithOosBacktest ?? '—'}
                {' · '}PnL OOS sumado: <strong>{formatUsd(modalGridResult.walkForwardSummary.aggregateOosPnl)}</strong>
                {' · '}trades OOS: {modalGridResult.walkForwardSummary.aggregateOosTrades ?? '—'}
              </div>
              {modalGridResult.walkForwardSummary.detail && (
                <p className="color-muted bt-wf-summary-detail">{modalGridResult.walkForwardSummary.detail}</p>
              )}
            </div>
          )}

          <WalkForwardFoldsTable folds={modalGridResult.walkForwardFolds} />

          {modalGridResult.optimization && !modalGridResult.optimization.hasWinner && modalGridResult.optimization.detailMessage && (
            <p className="text-xs color-muted bt-grid-no-winner" role="status">{modalGridResult.optimization.detailMessage}</p>
          )}

          {modalGridResult.optimization?.hasWinner && modalGridResult.optimization.bestParameters && !modalGridResult.walkForwardSummary && (
            <div className="flex-wrap flex-align-center gap-10 bt-grid-winner-actions">
              <button
                type="button"
                className="btn btn-secondary bt-grid-action-btn"
                disabled={retestLoading || running}
                onClick={runRetest}
                title={ticker?.trim() ? 'Backtest solo en este ticker con los Δ del ganador.' : 'Backtest con el universo del último run y los Δ del ganador.'}
              >
                {retestLoading ? 'Retest…' : 'Validar con retest (Δ ganador)'}
              </button>
              <button type="button" className="btn btn-primary bt-grid-action-btn" disabled={applyMemoryLoading} onClick={onApplyAndStartScan}>
                {applyMemoryLoading ? 'Guardando…' : 'Guardar mejor celda en memoria'}
              </button>
              {applyMemoryMessage && (
                <span className={`text-xs ${applyMemoryMessage.ok ? 'color-success' : 'color-error'}`}>{applyMemoryMessage.text}</span>
              )}
            </div>
          )}

          {retestError && <p className="text-xs color-error bt-grid-error" role="alert">{retestError}</p>}

          {retestData?.success && retestData.improvement && (
            <div className="bt-retest-result">
              <div className="font-bold bt-retest-title color-text">Retest (solo simulación; no guarda memoria)</div>
              <div className="color-muted">
                Universo:{' '}
                {retestData.singleTickerScoped && retestData.filterTicker ? <>solo <strong>{retestData.filterTicker}</strong> · </> : null}
                último run UI si existe; si no, HOT + fechas por defecto.
              </div>
              <div className="bt-retest-row">
                P&L baseline: <strong>${formatUsd(retestData.previous?.totalPnl)}</strong>
                {' · '}WR {retestData.previous?.winRate != null ? `${Number(retestData.previous.winRate).toFixed(1)}%` : '—'}
              </div>
              <div className="bt-retest-row">
                P&L simulado con Δ: <strong>${formatUsd(retestData.current?.totalPnl)}</strong>
                {' · '}WR {retestData.current?.winRate != null ? `${Number(retestData.current.winRate).toFixed(1)}%` : '—'}
              </div>
              <div className={`bt-retest-row ${(Number(retestData.improvement?.pnlDiff) || 0) >= 0 ? 'color-success' : 'color-error'}`}>
                Δ P&L: ${formatUsd(retestData.improvement?.pnlDiff)}
                {' · '}Δ WR: {retestData.improvement?.winRateDiff != null ? `${Number(retestData.improvement.winRateDiff).toFixed(1)} pp` : '—'}
              </div>
              {retestData.appliedTpMultiplierDelta != null && (
                <div className="color-muted bt-retest-row">
                  tpΔ {Number(retestData.appliedTpMultiplierDelta).toFixed(2)} · slΔ {Number(retestData.appliedSlMultiplierDelta).toFixed(2)}
                </div>
              )}
            </div>
          )}

          <GridRowsTable rows={modalGridResult.rows} />
        </div>
      )}

      <p className="text-xs color-muted bt-grid-footer">
        Al cerrar, si el grid terminó bien, la fila del trade log muestra un resumen del último grid.
      </p>
    </div>
  )
}
