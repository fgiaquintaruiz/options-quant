package com.fgiaquinta.optionsquant.backtest.engine;

import com.fgiaquinta.optionsquant.domain.Candle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

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
                                      Double netPnl, String exitReason, Integer candlesHeld,
                                      String pattern) {
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

            // Build a same-session window: only regular US market hours (09:00–17:00 ET) on the same date,
            // so pre-market / overnight candles with huge gaps don't distort the price axis.
            ZoneId ET = ZoneId.of("America/New_York");
            ZonedDateTime entryET = signalTime.withZoneSameInstant(ET);
            LocalDate sessionDate = entryET.toLocalDate();
            LocalTime sessionOpen  = LocalTime.of(9, 0);
            LocalTime sessionClose = LocalTime.of(17, 0);

            List<Candle> window = allCandles.stream()
                    .filter(c -> {
                        ZonedDateTime cET = c.timestamp().withZoneSameInstant(ET);
                        LocalDate cd = cET.toLocalDate();
                        LocalTime ct = cET.toLocalTime();
                        return cd.equals(sessionDate)
                                && !ct.isBefore(sessionOpen)
                                && !ct.isAfter(sessionClose);
                    })
                    .collect(Collectors.toList());

            // If session filter produced nothing (holiday / data gap), fall back to index-based window
            if (window.isEmpty()) {
                int contextBefore = 15;
                int contextAfter  = 10;
                int tradeLength   = (candlesHeld != null && candlesHeld > 0) ? candlesHeld : 20;
                int startIdx = Math.max(0, signalIdx - contextBefore);
                int endIdx   = Math.min(allCandles.size(), signalIdx + tradeLength + contextAfter);
                window = new java.util.ArrayList<>(allCandles.subList(startIdx, endIdx));
            }

            // Use Unix epoch seconds — lightweight-charts v4 requires numeric timestamps for intraday data.
            // String formats like 'YYYY-MM-DD HH:mm' are treated as daily bars and collapse intraday candles.
            StringBuilder candleData = new StringBuilder();
            for (Candle c : window) {
                long epochSec = c.timestamp().toEpochSecond();
                candleData.append(String.format(Locale.US,
                        "{ time: %d, open: %.4f, high: %.4f, low: %.4f, close: %.4f },%n",
                        epochSec, c.open(), c.high(), c.low(), c.close()));
            }

            String direction = isCall ? "CALL" : "PUT";
            String color = isCall ? "#26a69a" : "#ef5350";
            String safeTicker = ticker.replaceAll("[^a-zA-Z0-9]", "_");
            String safeStrategy = strategy.replaceAll("[^a-zA-Z0-9]", "_");
            String signalTimeStr = signalTime.withZoneSameInstant(java.time.ZoneId.of("Europe/Madrid"))
                    .format(TIME_FMT);
            long entryEpoch = signalTime.toEpochSecond();
            ZoneId madrid = ZoneId.of("Europe/Madrid");
            long exitEpoch = (candlesHeld != null && candlesHeld > 0)
                    ? signalTime.plusMinutes(15L * candlesHeld).toEpochSecond()
                    : signalTime.plusMinutes(300).toEpochSecond();
            String exitTimeStr = java.time.Instant.ofEpochSecond(exitEpoch)
                    .atZone(madrid).format(TIME_FMT);
            String safePattern = (pattern != null && !pattern.isBlank()) ? pattern : "-";

            String filename = String.format("%s_%s_%s_%s.html",
                    safeTicker, safeStrategy, direction, signalTimeStr.replace(" ", "_").replace(":", "-"));

            String html = buildHtml(ticker, strategy, direction, signalTimeStr, exitTimeStr,
                    entryPrice, takeProfit, stopLoss, color, candleData.toString(), window,
                    netPnl, exitReason, candlesHeld, entryEpoch, exitEpoch, safePattern);

            Path outputPath = chartsDir.resolve(filename);
            Files.writeString(outputPath, html);
            return outputPath;

        } catch (IOException e) {
            return null;
        }
    }

    private static String buildHtml(String ticker, String strategy, String direction,
                                     String entryTime, String exitTime,
                                     double entryPrice, double takeProfit,
                                     double stopLoss, String color, String candleData, List<Candle> candles,
                                     Double netPnl, String exitReason, Integer candlesHeld,
                                     long entryEpoch, long exitEpoch, String pattern) {
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
                        html, body { margin: 0; padding: 0; background: #131722; color: #d1d4dc; font-family: -apple-system, sans-serif; height: 100%%; overflow: hidden; }
                        .wrapper { display: flex; flex-direction: column; height: 100%%; padding: 8px; box-sizing: border-box; gap: 6px; }
                        .result { flex-shrink: 0; padding: 8px 12px; border-radius: 6px; text-align: center; }
                        #chart { width: 100%%; flex: 1; min-height: 0; }
                    </style>
                </head>
                <body>
                    <div class="wrapper">
                    %s
                    <div id="chart"></div>
                    </div>
                    <script>
                        const chartEl = document.getElementById('chart');
                        const chart = LightweightCharts.createChart(chartEl, {
                            width: chartEl.clientWidth,
                            height: chartEl.clientHeight,
                            layout: { background: { type: 'solid', color: '#131722' }, textColor: '#d1d4dc' },
                            grid: { vertLines: { color: '#1e222d' }, horzLines: { color: '#1e222d' } },
                            crosshair: { mode: LightweightCharts.CrosshairMode.Normal },
                            timeScale: { timeVisible: true, secondsVisible: false, rightOffset: 8, barSpacing: 12, fixLeftEdge: false, fixRightEdge: false },
                            rightPriceScale: { scaleMargins: { top: 0.15, bottom: 0.15 } },
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

                        // Zoom to show the full trade: from a few bars before entry to a few bars after exit
                        const CANDLE_SECS = 15 * 60;
                        const viewFrom = %d - CANDLE_SECS * 5;
                        const viewTo   = %d + CANDLE_SECS * 5;
                        chart.timeScale().setVisibleRange({ from: viewFrom, to: viewTo });

                        // Auto-resize to fill the flex container
                        new ResizeObserver(entries => {
                            if (entries.length === 0) return;
                            const { width, height } = entries[0].contentRect;
                            chart.applyOptions({ width, height });
                        }).observe(chartEl);
                    </script>
                </body>
                </html>
                """.formatted(
                // 1-3: page title
                ticker, strategy, direction,
                // 4: result section (WIN/LOSS box or empty)
                resultSection,
                // 5: candle data
                candleData,
                // 6-8: price lines (entry, TP, SL)
                entryPrice, color,
                takeProfit,
                stopLoss,
                // 9: exit marker JS
                exitMarkerJs,
                // 10-11: visible range epoch bounds
                entryEpoch,
                exitEpoch
        );
    }
}
