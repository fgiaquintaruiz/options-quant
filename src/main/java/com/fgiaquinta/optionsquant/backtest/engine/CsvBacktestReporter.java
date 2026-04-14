package com.fgiaquinta.optionsquant.backtest.engine;

import com.fgiaquinta.optionsquant.backtest.domain.BacktestReport;
import com.fgiaquinta.optionsquant.backtest.domain.TradeRecord;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;

/**
 * Records backtest results to CSV files and computes stats on-demand.
 *
 * CSV is the single source of truth for trade data. Trades are NOT stored
 * in-memory; they are read from CSV only when onFinish() is called.
 * This eliminates duplicate storage (in-memory list + CSV + BacktestReport.trades).
 */
@Slf4j
public class CsvBacktestReporter implements BacktestReporter {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path outputDir;
    private final List<BacktestReport.EquityPoint> equityCurve = Collections.synchronizedList(new ArrayList<>());
    private double initialCapital;
    private ZonedDateTime startTime;
    private ZonedDateTime lastEquityTimestamp = null;

    public CsvBacktestReporter(Path outputDir) {
        this.outputDir = outputDir;
    }

    @Override
    public void onStart(ZonedDateTime startTime, double initialCapital) {
        this.startTime = startTime;
        this.initialCapital = initialCapital;
        try {
            Files.deleteIfExists(outputDir.resolve("trades.csv"));
            Files.createDirectories(outputDir); } catch (Exception e) { log.warn("Could not create output dir: {}", outputDir); }
    }

    @Override
    public void onTrade(TradeRecord trade) {
        // CSV is the single source of truth - no in-memory duplication
        appendTradeToCsv(trade);
    }

    @Override
    public void onEquityUpdate(ZonedDateTime timestamp, double equity) {
        // Only record one equity value per unique timestamp
        // (Multiple tickers may report equity at the same candle time)
        if (lastEquityTimestamp != null && lastEquityTimestamp.isEqual(timestamp)) {
            return;
        }
        lastEquityTimestamp = timestamp;
        equityCurve.add(new BacktestReport.EquityPoint(timestamp, equity));
    }

    @Override
    public BacktestReport onFinish(long elapsedMs) {
        // Read trades from CSV (single source of truth) instead of in-memory list
        List<TradeRecord> trades = loadTradesFromCsv();
        double finalCapital = initialCapital + trades.stream().mapToDouble(TradeRecord::netPnl).sum();
        int wins = (int) trades.stream().filter(t -> t.netPnl() > 0).count();
        int losses = (int) trades.stream().filter(t -> t.netPnl() <= 0).count();
        double winRate = trades.isEmpty() ? 0 : (double) wins / trades.size();

        double totalProfit = trades.stream().filter(t -> t.netPnl() > 0).mapToDouble(TradeRecord::netPnl).sum();
        double totalLoss = Math.abs(trades.stream().filter(t -> t.netPnl() < 0).mapToDouble(TradeRecord::netPnl).sum());
        double profitFactor = totalLoss == 0 ? (totalProfit > 0 ? Double.POSITIVE_INFINITY : 0) : totalProfit / totalLoss;

        double maxDrawdown = 0, maxDrawdownPct = 0, peak = initialCapital;
        for (BacktestReport.EquityPoint p : equityCurve) {
            if (p.equity() > peak) peak = p.equity();
            double dd = peak - p.equity();
            if (dd > maxDrawdown) { maxDrawdown = dd; maxDrawdownPct = dd / peak; }
        }

        double avgWin = wins > 0 ? trades.stream().filter(t -> t.netPnl() > 0).mapToDouble(TradeRecord::netPnl).average().orElse(0) : 0;
        double avgLoss = losses > 0 ? trades.stream().filter(t -> t.netPnl() < 0).mapToDouble(TradeRecord::netPnl).average().orElse(0) : 0;
        double avgDuration = trades.stream().mapToDouble(t -> java.time.Duration.between(t.entryTime(), t.exitTime()).toMinutes() / 60.0).average().orElse(0);
        double sharpe = calculateSharpe(trades, initialCapital);

        Map<String, BacktestReport.StrategyStats> byStrategy = computePerGroupStats(trades, TradeRecord::strategy);
        Map<String, BacktestReport.StrategyStats> byTicker = computePerGroupStats(trades, TradeRecord::ticker);

        BacktestReport report = new BacktestReport(
                initialCapital, finalCapital, finalCapital - initialCapital,
                (finalCapital - initialCapital) / initialCapital,
                trades.size(), wins, losses, winRate, profitFactor,
                maxDrawdown, maxDrawdownPct, sharpe, avgWin, avgLoss, avgDuration,
                byStrategy, byTicker, List.copyOf(equityCurve), List.copyOf(trades), elapsedMs);

        writeSummary(report);
        writeEquityCsv();
        return report;
    }

    /**
     * Loads all trades from the CSV file. CSV is the single source of truth.
     */
    private List<TradeRecord> loadTradesFromCsv() {
        List<TradeRecord> result = new ArrayList<>();
        Path filePath = outputDir.resolve("trades.csv");
        if (!Files.exists(filePath)) {
            return result;
        }
        try (BufferedReader reader = Files.newBufferedReader(filePath)) {
            String headerLine = reader.readLine(); // skip header
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] parts = line.split(",", -1);
                if (parts.length < 20) continue;
                try {
                    TradeRecord trade = new TradeRecord(
                            parts[0],   // ticker
                            parts[1],   // strategy
                            parts[2],   // direction
                            Integer.parseInt(parts[3]),  // quantity
                            Double.parseDouble(parts[4]), // entryPrice
                            ZonedDateTime.parse(parts[5], TS_FMT),  // entryTime
                            Double.parseDouble(parts[6]), // exitPrice
                            ZonedDateTime.parse(parts[7], TS_FMT),  // exitTime
                            parts[8],   // exitReason
                            Double.parseDouble(parts[9]),  // grossPnl
                            Double.parseDouble(parts[10]), // commission
                            Double.parseDouble(parts[11]), // slippage
                            Double.parseDouble(parts[12]), // netPnl
                            Double.parseDouble(parts[13]), // maxDrawdown
                            Double.parseDouble(parts[14]), // maxRunup
                            parts[15],  // candlestickPattern
                            Double.parseDouble(parts[16]), // atrAtEntry
                            Double.parseDouble(parts[17]), // vixAtEntry
                            Integer.parseInt(parts[18]),   // entryHour
                            parts[19],  // marketTrend
                            new HashMap<>() // entryContext (not stored in CSV)
                    );
                    result.add(trade);
                } catch (Exception e) {
                    // Skip malformed lines
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load trades from CSV: {}", e.getMessage());
        }
        return result;
    }

    private void appendTradeToCsv(TradeRecord trade) {
        Path filePath = outputDir.resolve("trades.csv");
        boolean exists = Files.exists(filePath);
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(filePath, StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
            if (!exists) {
                pw.println("Ticker,Strategy,Direction,Qty,EntryPrice,EntryTime,ExitPrice,ExitTime,ExitReason,GrossPnl,Commission,Slippage,NetPnl,MaxDD,MaxRunup,Pattern,ATR,VIX,EntryHour,MarketTrend");
            }
            pw.printf(Locale.US, "%s,%s,%s,%d,%.2f,%s,%.2f,%s,%s,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%s,%.2f,%.2f,%d,%s%n",
                    trade.ticker(), trade.strategy(), trade.direction(), trade.quantity(),
                    trade.entryPrice(), trade.entryTime().format(TS_FMT),
                    trade.exitPrice(), trade.exitTime().format(TS_FMT),
                    trade.exitReason(), trade.grossPnl(), trade.commission(),
                    trade.slippage(), trade.netPnl(), trade.maxDrawdown(), trade.maxRunup(),
                    trade.candlestickPattern(), trade.atrAtEntry(), trade.vixAtEntry(),
                    trade.entryHour(), trade.marketTrend());
        } catch (Exception e) { log.warn("Failed to append trade CSV: {}", e.getMessage()); }
    }

    private void writeEquityCsv() {
        Path filePath = outputDir.resolve("equity.csv");
        try {
            // Ensure directory exists
            Files.createDirectories(outputDir);
            
            log.info("Writing equity curve: {} points to {}", equityCurve.size(), filePath.toAbsolutePath());
            
            try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(filePath))) {
                pw.println("Timestamp,Equity");
                for (BacktestReport.EquityPoint p : equityCurve) {
                    pw.printf(Locale.US, "%s,%.2f%n", p.timestamp().format(TS_FMT), p.equity());
                }
            }
        } catch (Exception e) { 
            log.error("Failed to write equity CSV to {}: {}", filePath.toAbsolutePath(), e.getMessage(), e); 
        }
    }

    private void writeSummary(BacktestReport r) {
        Path filePath = outputDir.resolve("summary.txt");
        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(filePath))) {
            pw.println("=== BACKTEST SUMMARY ===");
            pw.printf("Initial Capital: $%.2f%n", r.initialCapital());
            pw.printf("Final Capital: $%.2f%n", r.finalCapital());
            pw.printf("Total Return: $%.2f (%.2f%%)%n", r.totalReturn(), r.totalReturnPct() * 100);
            pw.printf("Trades: %d (Win: %d, Loss: %d, Rate: %.1f%%)%n", r.totalTrades(), r.winningTrades(), r.losingTrades(), r.winRate() * 100);
            pw.printf("Profit Factor: %.2f%n", r.profitFactor());
            pw.printf("Max Drawdown: $%.2f (%.2f%%)%n", r.maxDrawdown(), r.maxDrawdownPct() * 100);
            pw.printf("Avg Win: $%.2f | Avg Loss: $%.2f%n", r.avgWin(), r.avgLoss());
            pw.printf("Sharpe Ratio: %.2f%n", r.sharpeRatio());
            pw.println("\n=== BY STRATEGY ===");
            r.byStrategy().forEach((name, s) ->
                    pw.printf("%-30s | Trades: %4d | Win: %.1f%% | PnL: $%8.2f | PF: %.2f%n",
                            name, s.trades(), s.winRate() * 100, s.totalPnl(), s.profitFactor()));
            pw.println("\n=== BY TICKER ===");
            r.byTicker().forEach((t, s) ->
                    pw.printf("%-10s | Trades: %4d | Win: %.1f%% | PnL: $%8.2f | PF: %.2f%n",
                            t, s.trades(), s.winRate() * 100, s.totalPnl(), s.profitFactor()));
            pw.printf("\nElapsed: %d ms%n", r.elapsedMs());
        } catch (Exception e) { log.warn("Failed to write summary: {}", e.getMessage()); }
    }

    private double calculateSharpe(List<TradeRecord> trades, double initialCapital) {
        if (trades.size() < 2) return 0;
        double[] returns = trades.stream().mapToDouble(t -> t.netPnl() / initialCapital).toArray();
        double avg = Arrays.stream(returns).average().orElse(0);
        double variance = Arrays.stream(returns).map(r -> Math.pow(r - avg, 2)).average().orElse(0);
        double stdDev = Math.sqrt(variance);
        return stdDev == 0 ? 0 : avg / stdDev * Math.sqrt(252);
    }

    private Map<String, BacktestReport.StrategyStats> computePerGroupStats(
            List<TradeRecord> trades, java.util.function.Function<TradeRecord, String> grouper) {

        // Single-pass accumulation using primitive fields
        class GroupAccumulator {
            int count = 0;
            int wins = 0;
            double totalPnl = 0;
            double totalProfit = 0;
            double totalLoss = 0;
            double maxDrawdown = 0;
        }

        Map<String, GroupAccumulator> groups = new HashMap<>();
        for (TradeRecord t : trades) {
            String key = grouper.apply(t);
            GroupAccumulator acc = groups.computeIfAbsent(key, k -> new GroupAccumulator());
            acc.count++;
            acc.totalPnl += t.netPnl();
            if (t.netPnl() > 0) {
                acc.wins++;
                acc.totalProfit += t.netPnl();
            } else if (t.netPnl() < 0) {
                acc.totalLoss += Math.abs(t.netPnl());
            }
            acc.maxDrawdown = Math.max(acc.maxDrawdown, t.maxDrawdown());
        }

        Map<String, BacktestReport.StrategyStats> result = new LinkedHashMap<>();
        for (Map.Entry<String, GroupAccumulator> entry : groups.entrySet()) {
            GroupAccumulator acc = entry.getValue();
            double profitFactor = acc.totalLoss == 0 ?
                (acc.totalProfit > 0 ? Double.POSITIVE_INFINITY : 0) : acc.totalProfit / acc.totalLoss;
            result.put(entry.getKey(), new BacktestReport.StrategyStats(
                acc.count, acc.wins,
                acc.count == 0 ? 0 : (double) acc.wins / acc.count,
                acc.totalPnl, profitFactor, acc.maxDrawdown
            ));
        }
        return result;
    }
}
