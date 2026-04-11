package com.fgiaquinta.optionsquant.backtest.engine;

import com.fgiaquinta.optionsquant.domain.Candle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Generates interactive HTML candlestick charts for backtest signal analysis.
 * Uses TradingView's lightweight-charts library (loaded from CDN, no build dependencies).
 *
 * For each signal, creates an HTML file showing:
 * - 40 candles before the signal
 * - 20 candles after the signal
 * - Signal entry point marked with a vertical line
 * - TP and SL levels marked
 */
public class SignalChartGenerator {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * Generates an HTML chart for a signal and saves it to the charts directory.
     *
     * @param ticker Ticker symbol
     * @param strategy Strategy name
     * @param signalTime Signal timestamp
     * @param entryPrice Entry price
     * @param takeProfit Take profit level
     * @param stopLoss Stop loss level
     * @param isCall true for CALL, false for PUT
     * @param allCandles Full candle data for this ticker (all timeframes merged or primary timeframe)
     * @param chartsDir Directory to save HTML charts
     * @return Path to generated HTML file, or null if failed
     */
    public static Path generateChart(String ticker, String strategy, java.time.ZonedDateTime signalTime,
                                      double entryPrice, double takeProfit, double stopLoss,
                                      boolean isCall, List<Candle> allCandles, Path chartsDir) {
        try {
            if (!Files.exists(chartsDir)) {
                Files.createDirectories(chartsDir);
            }

            // Find signal index and slice candles around it
            int signalIdx = -1;
            long signalEpoch = signalTime.toEpochSecond();
            for (int i = 0; i < allCandles.size(); i++) {
                if (Math.abs(allCandles.get(i).timestamp().toEpochSecond() - signalEpoch) < 60) {
                    signalIdx = i;
                    break;
                }
            }

            if (signalIdx < 0) return null;

            int startIdx = Math.max(0, signalIdx - 40);
            int endIdx = Math.min(allCandles.size(), signalIdx + 20);
            List<Candle> window = allCandles.subList(startIdx, endIdx);

            // Build chart data
            StringBuilder candleData = new StringBuilder();
            for (Candle c : window) {
                String timeStr = c.timestamp().toLocalDateTime().format(TIME_FMT);
                candleData.append(String.format(Locale.US,
                        "{ time: '%s', open: %.4f, high: %.4f, low: %.4f, close: %.4f },%n",
                        timeStr, c.open(), c.high(), c.low(), c.close()));
            }

            String direction = isCall ? "CALL" : "PUT";
            String color = isCall ? "#26a69a" : "#ef5350";
            String safeTicker = ticker.replaceAll("[^a-zA-Z0-9]", "_");
            String safeStrategy = strategy.replaceAll("[^a-zA-Z0-9]", "_");
            String signalTimeStr = signalTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            String filename = String.format("%s_%s_%s_%s.html",
                    safeTicker, safeStrategy, direction, signalTimeStr.replace(" ", "_").replace(":", "-"));

            String html = buildHtml(ticker, strategy, direction, signalTimeStr,
                    entryPrice, takeProfit, stopLoss, color, candleData.toString(), window);

            Path outputPath = chartsDir.resolve(filename);
            Files.writeString(outputPath, html);
            return outputPath;

        } catch (IOException e) {
            return null;
        }
    }

    private static String buildHtml(String ticker, String strategy, String direction,
                                     String signalTime, double entryPrice, double takeProfit,
                                     double stopLoss, String color, String candleData, List<Candle> candles) {
        // Calculate chart boundaries for price axis
        double maxPrice = candles.stream().mapToDouble(Candle::high).max().orElse(entryPrice * 1.05);
        double minPrice = candles.stream().mapToDouble(Candle::low).min().orElse(entryPrice * 0.95);
        double padding = (maxPrice - minPrice) * 0.1;

        return """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <title>%s %s %s Signal</title>
                    <script src="https://unpkg.com/lightweight-charts@4.1.0/dist/lightweight-charts.standalone.production.js"></script>
                    <style>
                        body { margin: 0; padding: 20px; background: #131722; color: #d1d4dc; font-family: -apple-system, sans-serif; }
                        .header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
                        .ticker { font-size: 28px; font-weight: bold; }
                        .meta { font-size: 14px; color: #787b86; }
                        .levels { display: flex; gap: 24px; margin: 12px 0; }
                        .level { padding: 8px 16px; border-radius: 6px; font-size: 14px; }
                        .entry { background: #2962ff22; border: 1px solid #2962ff; }
                        .tp { background: #26a69a22; border: 1px solid #26a69a; }
                        .sl { background: #ef535022; border: 1px solid #ef5350; }
                        #chart { width: 100%%; height: 500px; }
                    </style>
                </head>
                <body>
                    <div class="header">
                        <div>
                            <div class="ticker">%s <span style="color:%s">(%s)</span></div>
                            <div class="meta">%s | Signal: %s</div>
                        </div>
                        <div class="meta">
                            <div>Generated by Options Quant Backtest</div>
                        </div>
                    </div>
                    <div class="levels">
                        <div class="level entry">Entry: $%.2f</div>
                        <div class="level tp">TP: $%.2f (+%.1f%%)</div>
                        <div class="level sl">SL: $%.2f (-%.1f%%)</div>
                        <div class="level" style="background:#ffffff11; border:1px solid #555">Risk/Reward: 1:%.2f</div>
                    </div>
                    <div id="chart"></div>
                    <script>
                        const chart = LightweightCharts.createChart(document.getElementById('chart'), {
                            width: document.getElementById('chart').clientWidth,
                            height: 500,
                            layout: { background: { type: 'solid', color: '#131722' }, textColor: '#d1d4dc' },
                            grid: { vertLines: { color: '#1e222d' }, horzLines: { color: '#1e222d' } },
                            crosshair: { mode: LightweightCharts.CrosshairMode.Normal },
                            timeScale: { timeVisible: true, secondsVisible: false },
                            rightPriceScale: { scaleMargins: { top: 0.1, bottom: 0.1 } },
                        });

                        const candleSeries = chart.addCandlestickSeries({
                            upColor: '#26a69a', downColor: '#ef5350',
                            borderDownColor: '#ef5350', borderUpColor: '#26a69a',
                            wickDownColor: '#ef535080', wickUpColor: '#26a69a80',
                        });

                        candleSeries.setData([%s]);

                        // Signal line
                        candleSeries.createPriceLine({
                            price: %.2f, color: '%s', lineWidth: 2, lineStyle: 2,
                            axisLabelVisible: true, title: 'ENTRY'
                        });
                        candleSeries.createPriceLine({
                            price: %.2f, color: '#26a69a', lineWidth: 1, lineStyle: 1,
                            axisLabelVisible: true, title: 'TP'
                        });
                        candleSeries.createPriceLine({
                            price: %.2f, color: '#ef5350', lineWidth: 1, lineStyle: 1,
                            axisLabelVisible: true, title: 'SL'
                        });

                        chart.timeScale().fitContent();

                        // Auto-resize
                        new ResizeObserver(entries => {
                            if (entries.length === 0) return;
                            const { width } = entries[0].contentRect;
                            chart.applyOptions({ width });
                        }).observe(document.getElementById('chart'));
                    </script>
                </body>
                </html>
                """.formatted(
                ticker, strategy, direction,
                ticker, direction, strategy, signalTime,
                entryPrice, takeProfit, ((takeProfit - entryPrice) / entryPrice) * 100,
                stopLoss, ((entryPrice - stopLoss) / entryPrice) * 100,
                Math.abs(takeProfit - entryPrice) / Math.abs(entryPrice - stopLoss),
                ticker, color, direction,
                candleData,
                entryPrice, color,
                takeProfit,
                stopLoss
        );
    }
}
