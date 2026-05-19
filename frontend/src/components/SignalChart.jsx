/**
 * Mini candlestick chart con Bollinger Bands para el panel de señal.
 * Fetcha candles históricos de 15m y renderiza con lightweight-charts.
 */
import { useEffect, useRef, useState } from 'react'
import { createChart } from 'lightweight-charts'
import './SignalChart.css'

// Calcular BB (SMA20 + 2σ) sobre array de closes
function calcBollingerBands(candles, period = 20) {
  return candles.map((c, i) => {
    if (i < period - 1) return null
    const slice = candles.slice(i - period + 1, i + 1).map(x => x.close)
    const mean = slice.reduce((a, b) => a + b, 0) / period
    const variance = slice.reduce((a, b) => a + (b - mean) ** 2, 0) / period
    const stdDev = Math.sqrt(variance)
    return {
      time: c.time,
      upper: mean + 2 * stdDev,
      middle: mean,
      lower: mean - 2 * stdDev,
    }
  }).filter(Boolean)
}

export default function SignalChart({ ticker, signalTimestamp, entryPrice, tp, sl }) {
  const chartContainerRef = useRef(null)
  const chartRef = useRef(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)

  useEffect(() => {
    const from = new Date(signalTimestamp - 2 * 24 * 60 * 60 * 1000).toISOString()
    const to = new Date(signalTimestamp + 4 * 60 * 60 * 1000).toISOString()
    const url = `/api/v1/historical/${ticker}?interval=15m&from=${from}&to=${to}`

    setLoading(true)
    setError(null)

    fetch(url)
      .then(res => {
        if (!res.ok) throw new Error(`HTTP ${res.status}`)
        return res.json()
      })
      .then(candles => {
        setLoading(false)

        const container = chartContainerRef.current
        const chart = createChart(container || document.createElement('div'), {
          layout: { background: { color: '#1a1a2e' }, textColor: '#d1d5db' },
          grid: { vertLines: { color: '#2d2d44' }, horzLines: { color: '#2d2d44' } },
          height: 200,
        })
        chartRef.current = chart

        // Candles
        const candleSeries = chart.addCandlestickSeries()
        const chartData = candles.map(c => ({
          time: Math.floor(new Date(c.time).getTime() / 1000),
          open: c.open, high: c.high, low: c.low, close: c.close,
        }))
        candleSeries.setData(chartData)

        // Marker en signalTimestamp
        candleSeries.addMarkers([{
          time: Math.floor(signalTimestamp / 1000),
          position: 'aboveBar',
          color: '#f59e0b',
          shape: 'arrowDown',
          text: 'Señal',
        }])

        // Bollinger Bands
        const bb = calcBollingerBands(candles)
        const bbUpper = chart.addLineSeries({ color: '#60a5fa', lineWidth: 1, title: 'BB+' })
        const bbMiddle = chart.addLineSeries({ color: '#94a3b8', lineWidth: 1, title: 'SMA20' })
        const bbLower = chart.addLineSeries({ color: '#60a5fa', lineWidth: 1, title: 'BB-' })

        bbUpper.setData(bb.map(d => ({ time: Math.floor(new Date(d.time).getTime() / 1000), value: d.upper })))
        bbMiddle.setData(bb.map(d => ({ time: Math.floor(new Date(d.time).getTime() / 1000), value: d.middle })))
        bbLower.setData(bb.map(d => ({ time: Math.floor(new Date(d.time).getTime() / 1000), value: d.lower })))

        // TP y SL como price lines en la serie media (siempre visible)
        bbMiddle.createPriceLine({ price: tp, color: '#22c55e', lineWidth: 1, lineStyle: 2, title: 'TP' })
        bbMiddle.createPriceLine({ price: sl, color: '#ef4444', lineWidth: 1, lineStyle: 2, title: 'SL' })

        chart.timeScale().fitContent()
      })
      .catch(err => {
        setLoading(false)
        setError(err.message)
      })

    return () => {
      chartRef.current?.remove()
      chartRef.current = null
    }
  }, [ticker, signalTimestamp, tp, sl])

  if (loading) return <div className="signal-chart-loading">Cargando gráfico...</div>
  if (error) return <div className="signal-chart-error">Error al cargar gráfico: {error}</div>
  return <div ref={chartContainerRef} className="signal-chart-container" data-testid="signal-chart" />
}
