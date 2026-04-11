package com.fgiaquinta.optionsquant.backtest.engine;

import com.fgiaquinta.optionsquant.backtest.domain.TradeRecord;
import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.service.CandleCsvService;

import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

/**
 * Generates ASCII art candlestick charts for completed trades.
 *
 * Shows OHLC candles, SMA 20/40/100/200, Bollinger Bands, volume bars,
 * and entry/exit markers to help visually analyze why a trade succeeded or failed.
 */
public class TradeChartGenerator {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("MM/dd HH:mm");
    private static final DateTimeFormatter PRICE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // Chart dimensions
    private static final int CHART_WIDTH = 120;
    private static final int CHART_HEIGHT = 30;
    private static final int VOLUME_HEIGHT = 6;
    private static final int LEFT_MARGIN = 8;
    private static final int RIGHT_MARGIN = 20;

    /**
     * Generates an ASCII chart for a completed trade.
     *
     * @param trade   the trade to chart
     * @param dataDir directory where CSV candle data is stored
     * @return a multi-line string representation of the chart
     */
    public static String generateChart(TradeRecord trade, Path dataDir) {
        CandleCsvService csvService = new CandleCsvService();
        csvService.setDataDir(dataDir);

        // Load 15-minute candles for the trade period
        List<Candle> allCandles = csvService.loadFromCsv(trade.ticker(), TimeFrame.MIN_15);
        if (allCandles.isEmpty()) {
            return "No candle data found for ticker: " + trade.ticker();
        }

        // Filter to candles within the trade window (with some padding)
        ZonedDateTime windowStart = trade.entryTime().minusHours(2);
        ZonedDateTime windowEnd = trade.exitTime().plusHours(2);
        List<Candle> tradeCandles = allCandles.stream()
                .filter(c -> !c.timestamp().isBefore(windowStart) && !c.timestamp().isAfter(windowEnd))
                .sorted(Comparator.comparing(Candle::timestamp))
                .toList();

        if (tradeCandles.isEmpty()) {
            return "No candle data found in trade window: " + trade.entryTime() + " to " + trade.exitTime();
        }

        return buildChart(trade, tradeCandles);
    }

    /**
     * Overload that accepts a pre-loaded candle list (useful for testing).
     */
    public static String generateChart(TradeRecord trade, List<Candle> candles) {
        return buildChart(trade, candles);
    }

    private static String buildChart(TradeRecord trade, List<Candle> candles) {
        StringBuilder sb = new StringBuilder();

        // Header
        sb.append(formatHeader(trade));
        sb.append("\n");

        int numCandles = candles.size();
        int chartableWidth = CHART_WIDTH - LEFT_MARGIN - RIGHT_MARGIN;
        int displayCount = Math.min(numCandles, chartableWidth);
        int startIndex = Math.max(0, numCandles - displayCount);
        List<Candle> displayCandles = candles.subList(startIndex, numCandles);

        if (displayCandles.isEmpty()) {
            sb.append("Not enough candles to display.");
            return sb.toString();
        }

        // Calculate indicators
        int[] sma20 = calculateSMA(displayCandles, 20);
        int[] sma40 = calculateSMA(displayCandles, 40);
        int[] sma100 = calculateSMA(displayCandles, 100);
        int[] sma200 = calculateSMA(displayCandles, 200);
        double[] bbUpper = calculateBBUpper(displayCandles, 20);
        double[] bbMiddle = calculateBBMiddle(displayCandles, 20);
        double[] bbLower = calculateBBLower(displayCandles, 20);

        // Determine price range
        double minPrice = Double.MAX_VALUE;
        double maxPrice = Double.MIN_VALUE;

        for (int i = 0; i < displayCandles.size(); i++) {
            Candle c = displayCandles.get(i);
            minPrice = Math.min(minPrice, c.low());
            maxPrice = Math.max(maxPrice, c.high());

            if (sma20[i] >= 0) {
                minPrice = Math.min(minPrice, smaValue(sma20[i]));
                maxPrice = Math.max(maxPrice, smaValue(sma20[i]));
            }
            if (sma40[i] >= 0) {
                minPrice = Math.min(minPrice, smaValue(sma40[i]));
                maxPrice = Math.max(maxPrice, smaValue(sma40[i]));
            }
            if (sma100[i] >= 0) {
                minPrice = Math.min(minPrice, smaValue(sma100[i]));
                maxPrice = Math.max(maxPrice, smaValue(sma100[i]));
            }
            if (sma200[i] >= 0) {
                minPrice = Math.min(minPrice, smaValue(sma200[i]));
                maxPrice = Math.max(maxPrice, smaValue(sma200[i]));
            }

            if (!Double.isNaN(bbLower[i])) {
                minPrice = Math.min(minPrice, bbLower[i]);
            }
            if (!Double.isNaN(bbUpper[i])) {
                maxPrice = Math.max(maxPrice, bbUpper[i]);
            }
        }

        // Include entry and exit prices in range
        minPrice = Math.min(minPrice, trade.entryPrice());
        minPrice = Math.min(minPrice, trade.exitPrice());
        maxPrice = Math.max(maxPrice, trade.entryPrice());
        maxPrice = Math.max(maxPrice, trade.exitPrice());

        // Add padding
        double priceRange = maxPrice - minPrice;
        if (priceRange == 0) priceRange = 1.0; // avoid division by zero
        double padding = priceRange * 0.05;
        minPrice -= padding;
        maxPrice += padding;

        int chartRows = CHART_HEIGHT;
        int chartCols = displayCandles.size();

        // Build the chart as a 2D character array
        char[][] grid = new char[chartRows][chartCols];
        for (char[] row : grid) {
            for (int j = 0; j < row.length; j++) {
                row[j] = ' ';
            }
        }

        // Track which cells are used by candles for layering
        boolean[][] candleBody = new boolean[chartRows][chartCols];

        // Draw Bollinger Bands first (background layer)
        drawBBBands(grid, bbUpper, bbMiddle, bbLower, minPrice, maxPrice, chartRows);

        // Draw SMA lines
        drawSMALine(grid, sma20, minPrice, maxPrice, chartRows, '~');
        drawSMALine(grid, sma40, minPrice, maxPrice, chartRows, '.');
        drawSMALine(grid, sma100, minPrice, maxPrice, chartRows, '+');
        drawSMALine(grid, sma200, minPrice, maxPrice, chartRows, '*');

        // Draw candles
        for (int i = 0; i < chartCols; i++) {
            Candle c = displayCandles.get(i);
            drawCandle(grid, candleBody, i, c, minPrice, maxPrice, chartRows);
        }

        // Mark entry and exit points on the grid
        int entryCol = findColumnForTime(displayCandles, trade.entryTime());
        int exitCol = findColumnForTime(displayCandles, trade.exitTime());
        int entryRow = priceToRow(trade.entryPrice(), minPrice, maxPrice, chartRows);
        int exitRow = priceToRow(trade.exitPrice(), minPrice, maxPrice, chartRows);

        // Draw horizontal entry/exit lines across the chart
        drawHorizontalLine(grid, entryRow, '>');
        drawHorizontalLine(grid, exitRow, '<');

        // Now build the output
        double priceStep = (maxPrice - minPrice) / chartRows;

        // Price labels on the left (show every Nth row)
        int labelInterval = Math.max(1, chartRows / 10);

        sb.append("Price\n");

        // Top to bottom
        for (int r = 0; r < chartRows; r++) {
            // Price label
            double priceAtRow = maxPrice - (r * priceStep);
            String priceLabel;
            if (r % labelInterval == 0) {
                priceLabel = String.format("%7.2f ", priceAtRow);
            } else {
                priceLabel = "        ";
            }
            sb.append(priceLabel);

            // Chart row
            sb.append("|");
            for (int c = 0; c < chartCols; c++) {
                sb.append(grid[r][c]);
            }
            sb.append("|");

            // Annotations on the right
            String annotation = "";
            if (r == Math.max(0, Math.min(chartRows - 1, entryRow))) {
                annotation = String.format(" <-- Entry $%.2f", trade.entryPrice());
            } else if (r == Math.max(0, Math.min(chartRows - 1, exitRow))) {
                String reason = trade.exitReason() != null ? trade.exitReason() : "";
                annotation = String.format(" <-- Exit $%.2f (%s)", trade.exitPrice(), reason);
            }
            sb.append(annotation);
            sb.append("\n");
        }

        // Time axis
        sb.append("        +");
        for (int i = 0; i < chartCols; i++) {
            sb.append("-");
        }
        sb.append("+\n");

        sb.append("         ");
        // Show timestamps at intervals
        int timeLabelInterval = Math.max(1, chartCols / 6);
        for (int i = 0; i < chartCols; i++) {
            if (i % timeLabelInterval == 0) {
                String label = displayCandles.get(i).timestamp().format(TIME_FMT);
                sb.append(label);
                int pad = Math.max(0, timeLabelInterval - label.length() + 1);
                for (int p = 0; p < pad; p++) sb.append(" ");
            } else {
                sb.append(" ");
            }
        }
        sb.append("\n");

        // Volume bars
        sb.append("\nVolume\n");
        sb.append(buildVolumeBars(displayCandles, entryCol, exitCol));

        // Legend
        sb.append("\n");
        sb.append(formatLegend());

        // Trade summary
        sb.append("\n");
        sb.append(formatTradeSummary(trade));

        return sb.toString();
    }

    private static String formatHeader(TradeRecord trade) {
        StringBuilder sb = new StringBuilder();
        sb.append("=".repeat(120)).append("\n");
        sb.append(String.format(" %s - %s - %s%n",
                trade.ticker(), trade.strategy(), trade.direction()));
        sb.append(String.format(" Entry: $%,.2f | Exit: $%,.2f | PnL: $%,.2f%n",
                trade.entryPrice(), trade.exitPrice(), trade.netPnl()));
        sb.append(String.format(" %s to %s | Qty: %d | Exit: %s%n",
                trade.entryTime().format(PRICE_FMT),
                trade.exitTime().format(PRICE_FMT),
                trade.quantity(),
                trade.exitReason() != null ? trade.exitReason() : "N/A"));
        sb.append(String.format(" Max Drawdown: $%,.2f | Max Runup: $%,.2f%n",
                trade.maxDrawdown(), trade.maxRunup()));
        sb.append("=".repeat(120));
        return sb.toString();
    }

    private static String formatTradeSummary(TradeRecord trade) {
        StringBuilder sb = new StringBuilder();
        sb.append("=".repeat(120)).append("\n");

        double pnlPct = trade.entryPrice() > 0
                ? ((trade.exitPrice() - trade.entryPrice()) / trade.entryPrice()) * 100
                : 0;
        sb.append(String.format(" Trade Result: %s $%,.2f (%.2f%%)%n",
                trade.netPnl() >= 0 ? "PROFIT" : "LOSS",
                trade.netPnl(), pnlPct));

        if (trade.exitReason() != null) {
            sb.append(String.format(" Exit Reason: %s%n", trade.exitReason()));
        }

        // Analysis hints
        boolean hitSL = "SL".equals(trade.exitReason());
        boolean hitTP = "TP".equals(trade.exitReason());

        if (hitSL) {
            sb.append(" NOTE: Trade hit Stop Loss. Check if entry was at a valid signal point.\n");
        } else if (hitTP) {
            sb.append(" NOTE: Trade hit Take Profit.\n");
        }

        double positionValue = trade.entryPrice() * trade.quantity() * 100;
        double ddPct = positionValue > 0 ? (trade.maxDrawdown() / positionValue) * 100 : 0;
        sb.append(String.format(" Max adverse excursion: %.2f%% of position value%n", ddPct));

        sb.append("=".repeat(120));
        return sb.toString();
    }

    private static String formatLegend() {
        return """
                Legend:
                  [ ] = Bullish candle (close > open)
                  | | = Bearish candle (close < open)
                  ~~~ = SMA 20
                  ... = SMA 40
                  +++ = SMA 100
                  *** = SMA 200
                  === = Bollinger Band Upper/Lower
                  --- = Bollinger Band Middle
                  >>> = Entry price level
                  <<< = Exit price level
                  #   = Volume (bullish candle)
                  :   = Volume (bearish candle)
                """;
    }

    private static String buildVolumeBars(List<Candle> candles, int entryCol, int exitCol) {
        if (candles.isEmpty()) return "";

        long maxVol = 0;
        for (Candle c : candles) {
            maxVol = Math.max(maxVol, c.volume());
        }
        if (maxVol == 0) return "No volume data";

        int height = VOLUME_HEIGHT;
        int width = candles.size();
        StringBuilder sb = new StringBuilder();

        for (int row = 0; row < height; row++) {
            double threshold = (1.0 - (double) row / height) * maxVol;
            sb.append("  ");
            for (int col = 0; col < width; col++) {
                Candle c = candles.get(col);
                if (c.volume() >= threshold) {
                    boolean isBullish = c.close() >= c.open();
                    sb.append(isBullish ? "#" : ":");
                } else {
                    sb.append(" ");
                }
            }
            sb.append("\n");
        }

        // Entry/exit markers under volume
        sb.append("  ");
        for (int i = 0; i < width; i++) {
            if (i == entryCol) {
                sb.append("^");
            } else if (i == exitCol) {
                sb.append("X");
            } else {
                sb.append(" ");
            }
        }
        sb.append("\n");
        sb.append("  ^ = Entry   X = Exit   # = Bullish vol   : = Bearish vol\n");

        return sb.toString();
    }

    // ---- Drawing helpers ----

    private static int priceToRow(double price, double minPrice, double maxPrice, int rows) {
        double ratio = (price - minPrice) / (maxPrice - minPrice);
        int row = (int) Math.round((1.0 - ratio) * (rows - 1));
        return Math.max(0, Math.min(rows - 1, row));
    }

    private static int findColumnForTime(List<Candle> candles, ZonedDateTime time) {
        for (int i = 0; i < candles.size(); i++) {
            if (!candles.get(i).timestamp().isBefore(time)) {
                return i;
            }
        }
        return candles.size() - 1;
    }

    private static void drawCandle(char[][] grid, boolean[][] candleBody, int col, Candle candle,
                                   double minPrice, double maxPrice, int rows) {
        int openRow = priceToRow(candle.open(), minPrice, maxPrice, rows);
        int closeRow = priceToRow(candle.close(), minPrice, maxPrice, rows);
        int highRow = priceToRow(candle.high(), minPrice, maxPrice, rows);
        int lowRow = priceToRow(candle.low(), minPrice, maxPrice, rows);

        boolean isBullish = candle.close() >= candle.open();

        // Draw wick (high to low)
        for (int r = Math.min(highRow, lowRow); r <= Math.max(highRow, lowRow); r++) {
            if (r >= 0 && r < rows && col >= 0 && col < grid[0].length) {
                if (grid[r][col] == ' ') {
                    grid[r][col] = '|';
                }
            }
        }

        // Draw body (open to close)
        int bodyTop = Math.min(openRow, closeRow);
        int bodyBottom = Math.max(openRow, closeRow);
        for (int r = bodyTop; r <= bodyBottom; r++) {
            if (r >= 0 && r < rows && col >= 0 && col < grid[0].length) {
                grid[r][col] = isBullish ? '[' : ']';
                candleBody[r][col] = true;
            }
        }
    }

    private static void drawBBBands(char[][] grid, double[] upper, double[] middle, double[] lower,
                                    double minPrice, double maxPrice, int rows) {
        for (int i = 0; i < upper.length; i++) {
            // Upper band
            if (!Double.isNaN(upper[i])) {
                int row = priceToRow(upper[i], minPrice, maxPrice, rows);
                if (row >= 0 && row < rows && i >= 0 && i < grid[0].length && grid[row][i] == ' ') {
                    grid[row][i] = '=';
                }
            }
            // Middle band
            if (!Double.isNaN(middle[i])) {
                int row = priceToRow(middle[i], minPrice, maxPrice, rows);
                if (row >= 0 && row < rows && i >= 0 && i < grid[0].length && grid[row][i] == ' ') {
                    grid[row][i] = '-';
                }
            }
            // Lower band
            if (!Double.isNaN(lower[i])) {
                int row = priceToRow(lower[i], minPrice, maxPrice, rows);
                if (row >= 0 && row < rows && i >= 0 && i < grid[0].length && grid[row][i] == ' ') {
                    grid[row][i] = '=';
                }
            }
        }
    }

    private static void drawSMALine(char[][] grid, int[] values, double minPrice, double maxPrice, int rows, char ch) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] < 0) continue;
            double price = smaValue(values[i]);
            int row = priceToRow(price, minPrice, maxPrice, rows);
            if (row >= 0 && row < rows && i >= 0 && i < grid[0].length && grid[row][i] == ' ') {
                grid[row][i] = ch;
            }
        }
    }

    private static void drawHorizontalLine(char[][] grid, int row, char ch) {
        if (row >= 0 && row < grid.length) {
            for (int c = 0; c < grid[row].length; c++) {
                if (grid[row][c] == ' ') {
                    grid[row][c] = ch;
                }
            }
        }
    }

    // ---- Indicator calculations ----

    /**
     * Calculates SMA for the given period. Returns int[] where values are price*100,
     * and -1 means insufficient data.
     */
    private static int[] calculateSMA(List<Candle> candles, int period) {
        int[] result = new int[candles.size()];

        for (int i = 0; i < candles.size(); i++) {
            if (i < period - 1) {
                result[i] = -1;
            } else {
                double sum = 0;
                for (int j = i - period + 1; j <= i; j++) {
                    sum += candles.get(j).close();
                }
                result[i] = (int) Math.round((sum / period) * 100);
            }
        }

        return result;
    }

    private static double[] calculateBBUpper(List<Candle> candles, int period) {
        double[] result = new double[candles.size()];
        for (int i = 0; i < candles.size(); i++) {
            if (i < period - 1) {
                result[i] = Double.NaN;
            } else {
                double sma = calculateSimpleAverage(candles, i, period);
                double stdDev = calculateStdDev(candles, i, period);
                result[i] = sma + 2 * stdDev;
            }
        }
        return result;
    }

    private static double[] calculateBBMiddle(List<Candle> candles, int period) {
        double[] result = new double[candles.size()];
        for (int i = 0; i < candles.size(); i++) {
            if (i < period - 1) {
                result[i] = Double.NaN;
            } else {
                result[i] = calculateSimpleAverage(candles, i, period);
            }
        }
        return result;
    }

    private static double[] calculateBBLower(List<Candle> candles, int period) {
        double[] result = new double[candles.size()];
        for (int i = 0; i < candles.size(); i++) {
            if (i < period - 1) {
                result[i] = Double.NaN;
            } else {
                double sma = calculateSimpleAverage(candles, i, period);
                double stdDev = calculateStdDev(candles, i, period);
                result[i] = sma - 2 * stdDev;
            }
        }
        return result;
    }

    private static double calculateSimpleAverage(List<Candle> candles, int index, int period) {
        double sum = 0;
        for (int j = index - period + 1; j <= index; j++) {
            sum += candles.get(j).close();
        }
        return sum / period;
    }

    private static double calculateStdDev(List<Candle> candles, int index, int period) {
        double mean = calculateSimpleAverage(candles, index, period);
        double sumSq = 0;
        for (int j = index - period + 1; j <= index; j++) {
            double diff = candles.get(j).close() - mean;
            sumSq += diff * diff;
        }
        return Math.sqrt(sumSq / period);
    }

    private static double smaValue(int encoded) {
        if (encoded < 0) return Double.NaN;
        return encoded / 100.0;
    }
}
