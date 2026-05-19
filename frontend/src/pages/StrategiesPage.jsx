import React, { useEffect, useState } from 'react'
import SwapButton from '../components/SwapButton'
import { strategyConfigApi } from '../api'
import './StrategiesPage.css'

// ── Sorting ───────────────────────────────────────────────────────────────────

function sortStrategies(list) {
  const live    = list.filter(s => s.enabledLive).sort((a, b) => (a.name ?? '').localeCompare(b.name ?? ''))
  const notLive = list.filter(s => !s.enabledLive).sort((a, b) => (a.name ?? '').localeCompare(b.name ?? ''))
  return [...live, ...notLive]
}

export { sortStrategies as sortStrategiesForTest }

// ── ConfirmDialog ─────────────────────────────────────────────────────────────

function ConfirmDialog({ strategyName, onCancel, onConfirm }) {
  return (
    <div className="confirm-dialog-overlay">
      <div className="confirm-dialog" role="dialog" aria-modal="true">
        <p>
          ¿Activar <strong>{strategyName}</strong> en LIVE TRADING?
          Esto va a generar operaciones reales en paper trading.
        </p>
        <div className="confirm-dialog-actions">
          <button className="btn btn-secondary" onClick={onCancel}>Cancelar</button>
          <button className="btn btn-primary"   onClick={onConfirm}>Confirmar</button>
        </div>
      </div>
    </div>
  )
}

// ── StrategiesPage ────────────────────────────────────────────────────────────

/** Página de configuración de estrategias: habilitar/deshabilitar live y backtest por estrategia. */
function StrategiesPage() {
  const [strategies, setStrategies] = useState([])
  const [loading,    setLoading]    = useState(true)
  const [error,      setError]      = useState(null)
  // dialog state: { name } | null
  const [dialog, setDialog] = useState(null)

  // ── Fetch on mount ──────────────────────────────────────────────────────────
  useEffect(() => {
    strategyConfigApi.findAll()
      .then(data => {
        setStrategies(sortStrategies(data))
        setLoading(false)
      })
      .catch(err => {
        setError(err.message || 'Error al cargar estrategias')
        setLoading(false)
      })
  }, [])

  // ── Live toggle handler ─────────────────────────────────────────────────────
  function handleLiveToggle(strategy) {
    if (strategy.enabledLive) {
      // ON → OFF: direct API call, no dialog, optimistic update
      const prev = [...strategies]
      setStrategies(sortStrategies(
        strategies.map(s => s.name === strategy.name ? { ...s, enabledLive: false } : s)
      ))
      strategyConfigApi.update(strategy.name, { enabledLive: false })
        .catch(() => setStrategies(sortStrategies(prev)))
    } else {
      // OFF → ON: show confirm dialog
      setDialog({ name: strategy.name })
    }
  }

  // ── Dialog confirm ──────────────────────────────────────────────────────────
  function handleDialogConfirm() {
    const name = dialog.name
    setDialog(null)
    const prev = [...strategies]
    setStrategies(sortStrategies(
      strategies.map(s => s.name === name ? { ...s, enabledLive: true } : s)
    ))
    strategyConfigApi.update(name, { enabledLive: true })
      .catch(() => setStrategies(sortStrategies(prev)))
  }

  function handleDialogCancel() {
    setDialog(null)
  }

  // ── Backtest toggle handler ─────────────────────────────────────────────────
  function handleBacktestToggle(strategy) {
    const newValue = !strategy.enabledBacktest
    const prev = [...strategies]
    setStrategies(sortStrategies(
      strategies.map(s => s.name === strategy.name ? { ...s, enabledBacktest: newValue } : s)
    ))
    strategyConfigApi.update(strategy.name, { enabledBacktest: newValue })
      .catch(() => setStrategies(sortStrategies(prev)))
  }

  // ── Derived counts ──────────────────────────────────────────────────────────
  const liveActiveCount = strategies.filter(s => s.enabledLive).length

  // ── Render ──────────────────────────────────────────────────────────────────
  if (loading) return <div className="strategies-page"><p>Cargando...</p></div>
  if (error)   return <div className="strategies-page"><p role="alert">{error}</p></div>

  return (
    <div className="strategies-page">
      {dialog && (
        <ConfirmDialog
          strategyName={dialog.name}
          onCancel={handleDialogCancel}
          onConfirm={handleDialogConfirm}
        />
      )}

      <div className="strategies-section-header">
        <h2>Estrategias — <span>{liveActiveCount} activas en live</span></h2>
      </div>

      <table className="strategies-table">
        <thead>
          <tr>
            <th>Estrategia</th>
            <th>Live</th>
            <th>Backtest</th>
          </tr>
        </thead>
        <tbody>
          {strategies.map(s => (
            <tr key={s.name} className={s.enabledLive ? 'strategies-section-live' : ''}>
              <td>{s.name}</td>
              <td>
                <SwapButton
                  active={s.enabledLive}
                  onText="Desactivar Live"
                  offText="Activar Live"
                  onClick={() => handleLiveToggle(s)}
                  activeColor="#f0883e"
                  offColor="#238636"
                  testId={`strategy-live-${s.name}`}
                />
              </td>
              <td>
                <input
                  type="checkbox"
                  data-testid={`strategy-backtest-${s.name}`}
                  checked={s.enabledBacktest}
                  onChange={() => handleBacktestToggle(s)}
                />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

export default StrategiesPage
