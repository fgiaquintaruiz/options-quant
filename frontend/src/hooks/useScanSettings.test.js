import { renderHook, act } from '@testing-library/react'
import { vi, describe, it, expect, beforeEach } from 'vitest'
import { useScanSettings } from './useScanSettings'

vi.mock('../api', () => ({
  liveApi: {
    setMaxConcurrent: vi.fn().mockResolvedValue({}),
    setRisk: vi.fn().mockResolvedValue({}),
    toggleAutoExecute: vi.fn().mockResolvedValue({}),
    toggleMacroFilter: vi.fn().mockResolvedValue({}),
    toggleMockMarket: vi.fn().mockResolvedValue({}),
  },
}))

vi.mock('../utils/storage', () => ({
  LS: {
    get: vi.fn(),
    set: vi.fn(),
  },
}))

vi.mock('../utils/liveSignalUtils', () => ({
  normalizeLiveMaxConcurrent: vi.fn((v) => (typeof v === 'number' && v >= 1 && v <= 16 ? v : 4)),
}))

import { liveApi } from '../api'
import { LS } from '../utils/storage'

function makeSetErrorMsg() {
  return vi.fn()
}

describe('useScanSettings', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    LS.get.mockImplementation((key, def) => def)
  })

  it('estado inicial desde LS: si LS tiene live_maxConcurrent=3, inicia con 3', () => {
    LS.get.mockImplementation((key, def) => {
      if (key === 'live_maxConcurrent') return 3
      return def
    })

    const { result } = renderHook(() =>
      useScanSettings({ status: null, setErrorMsg: makeSetErrorMsg() })
    )

    expect(result.current.maxConcurrent).toBe(3)
  })

  it('estado inicial fallback: sin LS, maxConcurrent inicia con 4', () => {
    LS.get.mockImplementation((key, def) => def)

    const { result } = renderHook(() =>
      useScanSettings({ status: null, setErrorMsg: makeSetErrorMsg() })
    )

    expect(result.current.maxConcurrent).toBe(4)
  })

  it('handleMaxConcurrentChange válido: llama setMaxConcurrent y actualiza estado', async () => {
    const { result } = renderHook(() =>
      useScanSettings({ status: null, setErrorMsg: makeSetErrorMsg() })
    )

    await act(async () => {
      await result.current.handleMaxConcurrentChange('5')
    })

    expect(liveApi.setMaxConcurrent).toHaveBeenCalledWith(5)
    expect(result.current.maxConcurrent).toBe(5)
  })

  it('handleMaxConcurrentChange inválido (NaN): no llama API', async () => {
    const setErrorMsg = makeSetErrorMsg()
    const { result } = renderHook(() =>
      useScanSettings({ status: null, setErrorMsg })
    )

    await act(async () => {
      await result.current.handleMaxConcurrentChange('abc')
    })

    expect(liveApi.setMaxConcurrent).not.toHaveBeenCalledWith(NaN)
  })

  it('handleMaxConcurrentChange inválido (0): no llama API', async () => {
    const setErrorMsg = makeSetErrorMsg()
    const { result } = renderHook(() =>
      useScanSettings({ status: null, setErrorMsg })
    )

    const callsBefore = liveApi.setMaxConcurrent.mock.calls.length

    await act(async () => {
      await result.current.handleMaxConcurrentChange('0')
    })

    expect(liveApi.setMaxConcurrent.mock.calls.length).toBe(callsBefore)
  })

  it('handleMaxConcurrentChange inválido (>16): no llama API con valor fuera de rango', async () => {
    const setErrorMsg = makeSetErrorMsg()
    const { result } = renderHook(() =>
      useScanSettings({ status: null, setErrorMsg })
    )

    const callsBefore = liveApi.setMaxConcurrent.mock.calls.length

    await act(async () => {
      await result.current.handleMaxConcurrentChange('99')
    })

    expect(liveApi.setMaxConcurrent.mock.calls.length).toBe(callsBefore)
  })

  it('handleRiskAdjust: llama setRisk con valor clampeado y actualiza LS', async () => {
    LS.get.mockImplementation((key, def) => {
      if (key === 'live_riskPct') return '2.0'
      return def
    })

    const { result } = renderHook(() =>
      useScanSettings({ status: null, setErrorMsg: makeSetErrorMsg() })
    )

    await act(async () => {
      await result.current.handleRiskAdjust(1.0)
    })

    expect(liveApi.setRisk).toHaveBeenCalledWith(3.0)
    expect(LS.set).toHaveBeenCalledWith('live_riskPct', '3.0')
    expect(result.current.riskInput).toBe('3.0')
  })

  it('handleToggleMockMarket: optimistic update, rollback si la API falla', async () => {
    liveApi.toggleMockMarket.mockRejectedValueOnce(new Error('server error'))
    const setErrorMsg = makeSetErrorMsg()

    const { result } = renderHook(() =>
      useScanSettings({ status: null, setErrorMsg })
    )

    const initialValue = result.current.mockMarketOpen

    await act(async () => {
      await result.current.handleToggleMockMarket()
    })

    expect(result.current.mockMarketOpen).toBe(initialValue)
    expect(setErrorMsg).toHaveBeenCalledWith('Failed to toggle Mock Market')
  })

  it('handleToggleAutoExecute: llama toggleAutoExecute', async () => {
    const { result } = renderHook(() =>
      useScanSettings({ status: null, setErrorMsg: makeSetErrorMsg() })
    )

    await act(async () => {
      await result.current.handleToggleAutoExecute()
    })

    expect(liveApi.toggleAutoExecute).toHaveBeenCalled()
  })

  it('handleToggleMacroFilter: llama toggleMacroFilter', async () => {
    const { result } = renderHook(() =>
      useScanSettings({ status: null, setErrorMsg: makeSetErrorMsg() })
    )

    await act(async () => {
      await result.current.handleToggleMacroFilter()
    })

    expect(liveApi.toggleMacroFilter).toHaveBeenCalled()
  })

  it('sync con status: cuando status.riskPct cambia, riskInput se actualiza', () => {
    const { result, rerender } = renderHook(
      ({ status }) => useScanSettings({ status, setErrorMsg: makeSetErrorMsg() }),
      { initialProps: { status: null } }
    )

    rerender({ status: { riskPct: 3.5 } })

    expect(result.current.riskInput).toBe('3.5')
  })
})
