/**
 * HotTickerCountEditor — inline editor for the hot-ticker-count runtime override.
 *
 * Loads the current effective count on mount (GET /api/ticker-config/hot-ticker-count).
 * On save, PUTs the new value (PUT /api/ticker-config/hot-ticker-count?count=N).
 * Valid range: 1–100. Out-of-range shows an inline error and disables save.
 */
import React, { useState, useEffect } from 'react'
import { tickerConfigApi } from '../api'

const MIN = 1
const MAX = 100

function validate(value) {
  const n = Number(value)
  if (!value && value !== 0) return 'Ingresá un valor'
  if (!Number.isInteger(n) || isNaN(n)) return 'Debe ser un número entero'
  if (n < MIN || n > MAX) return `Debe estar entre ${MIN} y ${MAX}`
  return null
}

export default function HotTickerCountEditor() {
  const [value, setValue]         = useState('')
  const [ymlDefault, setYmlDefault] = useState(null)
  const [error, setError]         = useState(null)
  const [saving, setSaving]       = useState(false)
  const [saveStatus, setSaveStatus] = useState(null) // 'ok' | 'error'
  const [saveMsg, setSaveMsg]     = useState('')
  const [loading, setLoading]     = useState(true)
  const [loadError, setLoadError] = useState(null)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setLoadError(null)
    tickerConfigApi.getHotTickerCount()
      .then((data) => {
        if (cancelled) return
        setValue(String(data.effectiveCount ?? data.ymlDefault ?? 20))
        setYmlDefault(data.ymlDefault ?? null)
      })
      .catch((e) => {
        if (cancelled) return
        setLoadError(e.message || 'Error al cargar hot-ticker-count')
      })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [])

  const handleChange = (e) => {
    const v = e.target.value
    setValue(v)
    setError(validate(v))
    setSaveStatus(null)
  }

  const handleSave = async () => {
    const validationError = validate(value)
    if (validationError) {
      setError(validationError)
      return
    }
    setSaving(true)
    setSaveStatus(null)
    setSaveMsg('')
    try {
      await tickerConfigApi.setHotTickerCount(Number(value))
      setSaveStatus('ok')
      setSaveMsg('Guardado')
      setTimeout(() => setSaveStatus(null), 3000)
    } catch (e) {
      setSaveStatus('error')
      setSaveMsg(e.message || 'Error al guardar')
    } finally {
      setSaving(false)
    }
  }

  if (loading) {
    return <p className="text-sm color-muted">Cargando hot-ticker-count…</p>
  }

  if (loadError) {
    return <p className="text-sm color-error">{loadError}</p>
  }

  const validationError = validate(value)
  const canSave = !validationError && !saving

  return (
    <div className="flex-col gap-6">
      <label className="text-sm" style={{ fontWeight: 600 }}>
        Tickers market-cap fallback (límite)
        {ymlDefault != null && (
          <span className="text-xs color-muted" style={{ marginLeft: 6 }}>
            (default YAML: {ymlDefault})
          </span>
        )}
      </label>
      <div className="flex-align-center gap-8">
        <input
          type="number"
          className="ticker-input"
          style={{ width: 80 }}
          min={MIN}
          max={MAX}
          value={value}
          onChange={handleChange}
          disabled={saving}
          aria-label="Hot ticker count"
        />
        <button
          className="btn btn-primary"
          onClick={handleSave}
          disabled={!canSave}
        >
          {saving ? 'Guardando…' : 'Guardar'}
        </button>
        {saveStatus === 'ok' && (
          <span className="text-xs badge badge-success">{saveMsg}</span>
        )}
        {saveStatus === 'error' && (
          <span className="text-xs color-error">{saveMsg}</span>
        )}
      </div>
      {validationError && (
        <span className="text-xs color-error">{validationError}</span>
      )}
      <p className="text-xs color-muted" style={{ marginTop: 2 }}>
        Cuántos tickers de mayor capitalización bursátil se promueven como HOT
        cuando no hay lista explícita en el runtime. Rango: {MIN}–{MAX}.
      </p>
    </div>
  )
}
