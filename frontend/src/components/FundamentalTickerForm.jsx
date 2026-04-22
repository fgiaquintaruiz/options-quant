import React, { useState, useEffect } from 'react'

const emptyPayload = () => ({
  companyName: '',
  sector: '',
  marketCapBillion: '',
  peRatio: '',
  dividendYield: '',
  beta: '',
  epsGrowth: '',
  revenueGrowth: '',
  debtToEquity: '',
  roic: '',
  notes: '',
})

const numOrNull = (s) => {
  const t = String(s || '').trim()
  if (t === '') return null
  const n = Number(t)
  return Number.isFinite(n) ? n : null
}

function FormField({ label, value, onChange, placeholder = '' }) {
  return (
    <label className="flex-col gap-4 text-sm">
      <span className="color-muted">{label}</span>
      <input
        type="text"
        className="ticker-input"
        value={value}
        onChange={onChange}
        placeholder={placeholder}
      />
    </label>
  )
}

/** Single-ticker fundamental editor (aligned with server TickerFundamentalPayload). */
export default function FundamentalTickerForm({ symbol, initial, onSave, onCancel }) {
  const [fields, setFields] = useState(emptyPayload)

  useEffect(() => {
    if (!initial) { setFields(emptyPayload()); return }
    setFields({
      companyName:    initial.companyName ?? '',
      sector:         initial.sector ?? '',
      marketCapBillion: initial.marketCapBillion != null ? String(initial.marketCapBillion) : '',
      peRatio:        initial.peRatio != null ? String(initial.peRatio) : '',
      dividendYield:  initial.dividendYield != null ? String(initial.dividendYield) : '',
      beta:           initial.beta != null ? String(initial.beta) : '',
      epsGrowth:      initial.epsGrowth != null ? String(initial.epsGrowth) : '',
      revenueGrowth:  initial.revenueGrowth != null ? String(initial.revenueGrowth) : '',
      debtToEquity:   initial.debtToEquity != null ? String(initial.debtToEquity) : '',
      roic:           initial.roic != null ? String(initial.roic) : '',
      notes:          initial.notes ?? '',
    })
  }, [initial, symbol])

  const set = (key) => (e) => setFields((f) => ({ ...f, [key]: e.target.value }))

  const handleSubmit = (e) => {
    e.preventDefault()
    onSave({
      companyName:      fields.companyName.trim() || null,
      sector:           fields.sector.trim() || null,
      marketCapBillion: numOrNull(fields.marketCapBillion),
      peRatio:          numOrNull(fields.peRatio),
      dividendYield:    numOrNull(fields.dividendYield),
      beta:             numOrNull(fields.beta),
      epsGrowth:        numOrNull(fields.epsGrowth),
      revenueGrowth:    numOrNull(fields.revenueGrowth),
      debtToEquity:     numOrNull(fields.debtToEquity),
      roic:             numOrNull(fields.roic),
      notes:            fields.notes.trim() || null,
    })
  }

  return (
    <form className="flex-col gap-12" onSubmit={handleSubmit}>
      <div className="text-sm font-bold color-text">Fundamentales · {symbol}</div>
      <p className="text-xs color-muted fund-form-hint">
        Estos datos alimentan el score híbrido del scanner (HYBRID) y la priorización junto a ticker-memory.
        Valores numéricos vacíos se envían como null.
      </p>
      <div className="fund-form-grid">
        <FormField label="Empresa"                            value={fields.companyName}    onChange={set('companyName')} />
        <FormField label="Sector"                             value={fields.sector}         onChange={set('sector')} />
        <FormField label="Market cap (miles de millones USD)" value={fields.marketCapBillion} onChange={set('marketCapBillion')} placeholder="ej. 3200" />
        <FormField label="P/E"                                value={fields.peRatio}        onChange={set('peRatio')} />
        <FormField label="Dividend yield %"                   value={fields.dividendYield}  onChange={set('dividendYield')} />
        <FormField label="Beta"                               value={fields.beta}           onChange={set('beta')} />
        <FormField label="EPS growth %"                       value={fields.epsGrowth}      onChange={set('epsGrowth')} />
        <FormField label="Revenue growth %"                   value={fields.revenueGrowth}  onChange={set('revenueGrowth')} />
        <FormField label="Debt / Equity"                      value={fields.debtToEquity}   onChange={set('debtToEquity')} />
        <FormField label="ROIC %"                             value={fields.roic}           onChange={set('roic')} />
      </div>
      <label className="flex-col gap-4 text-sm">
        <span className="color-muted">Notas</span>
        <textarea
          className="ticker-input textarea-resizable"
          rows={3}
          value={fields.notes}
          onChange={set('notes')}
        />
      </label>
      <div className="flex-align-center gap-10">
        <button type="submit" className="btn btn-primary">Guardar fundamentales</button>
        <button type="button" className="btn btn-secondary" onClick={onCancel}>Cancelar</button>
      </div>
    </form>
  )
}
