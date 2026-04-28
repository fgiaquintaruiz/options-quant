import { useState, useEffect } from 'react'
import { liveApi } from '../api'
import { LS } from '../utils/storage'
import { normalizeLiveMaxConcurrent } from '../utils/liveSignalUtils'

export function useScanSettings({ status, setErrorMsg }) {
  const [maxConcurrent, setMaxConcurrent] = useState(() => normalizeLiveMaxConcurrent(LS.get('live_maxConcurrent', 4)))
  const [riskInput, setRiskInput]         = useState(() => LS.get('live_riskPct', '2.0'))
  const [mockMarketOpen, setMockMarketOpen] = useState(() => LS.get('live_mockMarketOpen', false))

  // Restore pool size from localStorage and align JVM on mount
  useEffect(() => {
    const n = normalizeLiveMaxConcurrent(LS.get('live_maxConcurrent', 4))
    setMaxConcurrent(n)
    liveApi.setMaxConcurrent(n).catch((e) => console.warn('[live] concurrent init:', e.message))
  }, [])

  useEffect(() => { LS.set('live_maxConcurrent', maxConcurrent) }, [maxConcurrent])
  useEffect(() => { LS.set('live_mockMarketOpen', mockMarketOpen) }, [mockMarketOpen])

  // Sync riskInput from backend on first status load
  useEffect(() => {
    if (status?.riskPct != null) setRiskInput(String(status.riskPct))
  }, [status?.riskPct])

  const handleMaxConcurrentChange = async (newVal) => {
    const val = parseInt(newVal, 10)
    if (isNaN(val) || val < 1 || val > 16) return
    const prev = maxConcurrent
    setMaxConcurrent(val)
    await liveApi.setMaxConcurrent(val).catch((e) => {
      setMaxConcurrent(prev)
      setErrorMsg(`Concurrent update failed: ${e.message}`)
    })
  }

  const handleRiskAdjust = async (delta) => {
    const current = parseFloat(riskInput)
    const newVal  = Math.max(0.1, Math.min(10, current + delta))
    const formatted = newVal.toFixed(1)
    const prevFormatted = riskInput
    setRiskInput(formatted)
    LS.set('live_riskPct', formatted)
    await liveApi.setRisk(newVal).catch((e) => {
      setRiskInput(prevFormatted)
      LS.set('live_riskPct', prevFormatted)
      setErrorMsg(`Risk update failed: ${e.message}`)
    })
  }

  const handleToggleAutoExecute = async () => {
    await liveApi.toggleAutoExecute().catch((e) => setErrorMsg(`Auto execute toggle failed: ${e.message}`))
  }

  const handleToggleMacroFilter = async () => {
    await liveApi.toggleMacroFilter().catch((e) => setErrorMsg(`Macro filter toggle failed: ${e.message}`))
  }

  const handleToggleMockMarket = async () => {
    const prev = mockMarketOpen
    setMockMarketOpen(!prev)
    await liveApi.toggleMockMarket().catch((e) => {
      setMockMarketOpen(prev)
      setErrorMsg('Failed to toggle Mock Market')
    })
  }

  return {
    maxConcurrent,
    riskInput,
    mockMarketOpen,
    handleMaxConcurrentChange,
    handleRiskAdjust,
    handleToggleAutoExecute,
    handleToggleMacroFilter,
    handleToggleMockMarket,
  }
}
