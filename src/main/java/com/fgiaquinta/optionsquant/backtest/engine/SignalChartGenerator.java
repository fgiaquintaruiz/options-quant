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
    private static final DateTimeFormatter SPAIN_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", java.util.Locale.US)
            .withZone(java.time.ZoneId.of("Europe/Madrid"));

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
     * @param netPnl Net PnL of the trade (null if trade not yet closed)
     * @param exitReason Exit reason (TP, SL, Time Stop, EOS) - null if not yet closed
     * @param candlesHeld Number of candles the trade was held - null if not yet closed
     * @return Path to generated HTML file, or null if failed
     */
    public static Path generateChart(String ticker, String strategy, java.time.ZonedDateTime signalTime,
                                      double entryPrice, double takeProfit, double stopLoss,
                                      boolean isCall, List<Candle> allCandles, Path chartsDir,
                                      Double netPnl, String exitReason, Integer candlesHeld) {
        try {
            if (!Files.exists(chartsDir)) {
                Files.createDirectories(chartsDir);
            }

            // Find signal index and slice candles around it
            int signalIdx = -1;
            long signalEpoch = signalTime.toEpochSecond();
            for (int i = 0; i < allCandles.size(); i++) {
                if (Math.abs(allCandles.get(i).timestamp().toEpochSecond() - signalEpoch) < 900) {
                    signalIdx = i;
                    break;
                }
            }

            if (signalIdx < 0) return null;

            // Determine candle range based on whether we know the exit
            int startIdx, endIdx;
            if (candlesHeld != null && candlesHeld > 0) {
                // Exit-time chart: show from entry to exit with small context before
                startIdx = Math.max(0, signalIdx - 10);
                endIdx = Math.min(allCandles.size(), signalIdx + candlesHeld + 5);
            } else {
                // Entry-time chart: show 40 candles before + 20 after
                startIdx = Math.max(0, signalIdx - 40);
                endIdx = Math.min(allCandles.size(), signalIdx + 20);
            }
            List<Candle> window = allCandles.subList(startIdx, endIdx);

            // Build chart data with Spain timezone for correct tooltip display
            StringBuilder candleData = new StringBuilder();
            for (Candle c : window) {
                String timeStr = c.timestamp().withZoneSameInstant(java.time.ZoneId.of("Europe/Madrid")).format(TIME_FMT);
                candleData.append(String.format(Locale.US,
                        "{ time: '%s', open: %.4f, high: %.4f, low: %.4f, close: %.4f },%n",
                        timeStr, c.open(), c.high(), c.low(), c.close()));
            }

            String direction = isCall ? "CALL" : "PUT";
            String color = isCall ? "#26a69a" : "#ef5350";
            String safeTicker = ticker.replaceAll("[^a-zA-Z0-9]", "_");
            String safeStrategy = strategy.replaceAll("[^a-zA-Z0-9]", "_");
            String signalTimeStr = signalTime.withZoneSameInstant(java.time.ZoneId.of("Europe/Madrid"))
                    .format(TIME_FMT);
            String filename = String.format("%s_%s_%s_%s.html",
                    safeTicker, safeStrategy, direction, signalTimeStr.replace(" ", "_").replace(":", "-"));

            String html = buildHtml(ticker, strategy, direction, signalTimeStr,
                    entryPrice, takeProfit, stopLoss, color, candleData.toString(), window,
                    netPnl, exitReason, candlesHeld);

            Path outputPath = chartsDir.resolve(filename);
            Files.writeString(outputPath, html);
            return outputPath;

        } catch (IOException e) {
            return null;
        }
    }

    private static String buildHtml(String ticker, String strategy, String direction,
                                     String signalTime, double entryPrice, double takeProfit,
                                     double stopLoss, String color, String candleData, List<Candle> candles,
                                     Double netPnl, String exitReason, Integer candlesHeld) {
        // Calculate chart boundaries for price axis (used for future chart scaling enhancements)
        double maxPrice = candles.stream().mapToDouble(Candle::high).max().orElse(entryPrice * 1.05);
        double minPrice = candles.stream().mapToDouble(Candle::low).min().orElse(entryPrice * 0.95);
        @SuppressWarnings("unused")
        double padding = (maxPrice - minPrice) * 0.1;

        double tpPct = ((takeProfit - entryPrice) / entryPrice) * 100;
        double slPct = ((entryPrice - stopLoss) / entryPrice) * 100;
        double riskReward = Math.abs(takeProfit - entryPrice) / Math.abs(entryPrice - stopLoss);

        // Build trade result HTML if trade is closed
        String resultSection = "";
        String exitMarkerJs = "";
        if (netPnl != null && exitReason != null) {
            boolean isWin = netPnl >= 0;
            String resultColor = isWin ? "#26a69a" : "#ef5350";
            String resultIcon = isWin ? "\u2705 WIN" : "\u274c LOSS";
            String candlesHeldText = candlesHeld != null ? String.format(" | Held: %d candles", candlesHeld) : "";
            resultSection = """
                    <div class="result" style="background: %s22; border: 1px solid %s; padding: 12px; border-radius: 8px; margin: 12px 0; text-align: center;">
                        <div style="font-size: 20px; font-weight: bold; color: %s">%s: $%.2f</div>
                        <div style="font-size: 14px; color: #787b86; margin-top: 4px;">Exit: %s%s</div>
                    </div>
                    """.formatted(resultColor, resultColor, resultColor, resultIcon, netPnl, exitReason, candlesHeldText);

            // Add exit marker on chart if we have candle info
            if (candlesHeld != null && !candles.isEmpty()) {
                // Find the signal candle index, then the exit candle
                exitMarkerJs = """
                        // Exit marker
                        candleSeries.createPriceLine({
                            price: %.2f, color: '%s', lineWidth: 2, lineStyle: 0,
                            axisLabelVisible: true, title: 'EXIT (%s)'
                        });
                        """.formatted(
                        netPnl >= 0 ? takeProfit : stopLoss,
                        resultColor,
                        exitReason
                );
            }
        }

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
                    %s
                    <div id="chart"></div>
                    <script>
                        const chart = LightweightCharts.createChart(document.getElementById('chart'), {
                            width: document.getElementById('chart').clientWidth,
                            height: 500,
                            layout: { background: { type: 'solid', color: '#131722' }, textColor: '#d1d4dc' },
                            grid: { vertLines: { color: '#1e222d' }, horzLines: { color: '#1e222d' } },
                            crosshair: { mode: LightweightCharts.CrosshairMode.Normal },
                            timeScale: { timeVisible: true, secondsVisible: false, rightOffset: 5, barSpacing: 10 },
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
                        %s

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
                // 1-3: title
                ticker, strategy, direction,
                // 4-6: ticker badge
                ticker, color, direction,
                // 7-8: meta info
                ticker, signalTime,
                // 9-13: levels (entry, TP, SL, risk/reward)
                entryPrice, takeProfit, tpPct, stopLoss, slPct,
                // 14: risk/reward ratio
                riskReward,
                // 15: result section (trade outcome HTML or empty)
                resultSection,
                // 16: candle data
                candleData,
                // 17-19: price lines (entry, TP, SL)
                entryPrice, color,
                takeProfit,
                stopLoss,
                // 20: exit marker JS (or empty)
                exitMarkerJs
        );
    }
}
