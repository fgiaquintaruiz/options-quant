import { render, screen, waitFor } from '@testing-library/react'
import { vi, describe, it, expect, beforeEach } from 'vitest'
import SignalChart from './SignalChart'

// vi.mock se hoist al top del archivo, por lo que las variables deben
// declararse con vi.hoisted() para que estén disponibles en el factory.
const {
  mockSetData,
  mockCreatePriceLine,
  mockAddMarkers,
  mockFitContent,
  mockRemove,
  mockAddCandlestickSeries,
  mockAddLineSeries,
  mockCreateChart,
} = vi.hoisted(() => {
  const mockSetData = vi.fn()
  const mockCreatePriceLine = vi.fn()
  const mockAddMarkers = vi.fn()
  const mockFitContent = vi.fn()
  const mockRemove = vi.fn()
  const mockAddCandlestickSeries = vi.fn(() => ({ setData: mockSetData, addMarkers: mockAddMarkers }))
  const mockAddLineSeries = vi.fn(() => ({ setData: mockSetData, createPriceLine: mockCreatePriceLine }))
  const mockCreateChart = vi.fn(() => ({
    addCandlestickSeries: mockAddCandlestickSeries,
    addLineSeries: mockAddLineSeries,
    timeScale: vi.fn(() => ({ fitContent: mockFitContent })),
    resize: vi.fn(),
    remove: mockRemove,
  }))
  return {
    mockSetData,
    mockCreatePriceLine,
    mockAddMarkers,
    mockFitContent,
    mockRemove,
    mockAddCandlestickSeries,
    mockAddLineSeries,
    mockCreateChart,
  }
})

vi.mock('lightweight-charts', () => ({ createChart: mockCreateChart }))

// Mock del fetch
global.fetch = vi.fn()

const SIGNAL_TS = new Date('2026-05-19T14:00:00Z').getTime()
const defaultProps = {
  ticker: 'NVDA',
  signalTimestamp: SIGNAL_TS,
  entryPrice: 100,
  tp: 108,
  sl: 95,
}

const mockCandles = [
  { time: '2026-05-18T10:00:00Z', open: 98, high: 101, low: 97, close: 100, volume: 1000 },
  { time: '2026-05-18T10:15:00Z', open: 100, high: 103, low: 99, close: 102, volume: 1200 },
]

describe('SignalChart', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('llama al endpoint con ticker y rango correcto', async () => {
    global.fetch.mockResolvedValueOnce({
      ok: true,
      json: async () => mockCandles,
    })
    render(<SignalChart {...defaultProps} />)
    await waitFor(() => {
      expect(global.fetch).toHaveBeenCalledWith(
        expect.stringContaining('/api/v1/historical/NVDA')
      )
      const url = global.fetch.mock.calls[0][0]
      expect(url).toContain('interval=15m')
      // from = signalTimestamp - 2 days, to = signalTimestamp + 4h
      expect(url).toContain('from=')
      expect(url).toContain('to=')
    })
  })

  it('muestra estado Cargando... mientras fetcha', () => {
    global.fetch.mockReturnValueOnce(new Promise(() => {})) // never resolves
    render(<SignalChart {...defaultProps} />)
    expect(screen.getByText(/Cargando/i)).toBeInTheDocument()
  })

  it('muestra error si el fetch falla', async () => {
    global.fetch.mockRejectedValueOnce(new Error('Network error'))
    render(<SignalChart {...defaultProps} />)
    await waitFor(() => {
      expect(screen.getByText(/Error/i)).toBeInTheDocument()
    })
  })

  it('crea el chart y agrega CandlestickSeries cuando fetch OK', async () => {
    global.fetch.mockResolvedValueOnce({ ok: true, json: async () => mockCandles })
    render(<SignalChart {...defaultProps} />)
    await waitFor(() => {
      expect(mockCreateChart).toHaveBeenCalled()
      expect(mockAddCandlestickSeries).toHaveBeenCalled()
      expect(mockSetData).toHaveBeenCalledWith(expect.arrayContaining([
        expect.objectContaining({ open: expect.any(Number), close: expect.any(Number) })
      ]))
    })
  })

  it('agrega 3 LineSeries para BB superior, media e inferior', async () => {
    global.fetch.mockResolvedValueOnce({ ok: true, json: async () => mockCandles })
    render(<SignalChart {...defaultProps} />)
    await waitFor(() => {
      // 3 llamadas a addLineSeries: bb_upper, bb_middle, bb_lower
      expect(mockAddLineSeries).toHaveBeenCalledTimes(3)
    })
  })

  it('agrega marker o línea vertical en signalTimestamp', async () => {
    global.fetch.mockResolvedValueOnce({ ok: true, json: async () => mockCandles })
    render(<SignalChart {...defaultProps} />)
    await waitFor(() => {
      expect(mockAddMarkers).toHaveBeenCalledWith(
        expect.arrayContaining([
          expect.objectContaining({ position: expect.any(String) })
        ])
      )
    })
  })

  it('agrega líneas horizontales de TP y SL', async () => {
    global.fetch.mockResolvedValueOnce({ ok: true, json: async () => mockCandles })
    render(<SignalChart {...defaultProps} />)
    await waitFor(() => {
      // TP (verde) y SL (rojo) como price lines
      expect(mockCreatePriceLine).toHaveBeenCalledWith(
        expect.objectContaining({ price: 108, color: expect.stringMatching(/green|#[0-9a-fA-F]{6}/i) })
      )
      expect(mockCreatePriceLine).toHaveBeenCalledWith(
        expect.objectContaining({ price: 95, color: expect.stringMatching(/red|#[0-9a-fA-F]{6}/i) })
      )
    })
  })

  it('destruye el chart al desmontarse (cleanup)', async () => {
    global.fetch.mockResolvedValueOnce({ ok: true, json: async () => mockCandles })
    const { unmount } = render(<SignalChart {...defaultProps} />)
    await waitFor(() => expect(mockCreateChart).toHaveBeenCalled())
    unmount()
    expect(mockRemove).toHaveBeenCalled()
  })
})
