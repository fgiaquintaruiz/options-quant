package com.fgiaquinta.optionsquant.backtest.engine;

import com.fgiaquinta.optionsquant.backtest.domain.BacktestReport;
import com.fgiaquinta.optionsquant.backtest.domain.TradeRecord;
import lombok.extern.slf4j.Slf4j;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Records backtest results to CSV files and in-memory stats.
 */
@Slf4j
public class CsvBacktestReporter implements BacktestReporter {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path outputDir;
    private final List<TradeRecord> trades = Collections.synchronizedList(new ArrayList<>());
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
        trades.add(trade);
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

    private Map<String, BacktestReport.StrategyStats> computePerGroupStats(List<TradeRecord> trades, Function<TradeRecord, String> grouper) {
        return trades.stream().collect(Collectors.groupingBy(grouper, Collectors.collectingAndThen(
                Collectors.toList(), group -> {
                    int gWins = (int) group.stream().filter(t -> t.netPnl() > 0).count();
                    double gProfit = group.stream().filter(t -> t.netPnl() > 0).mapToDouble(TradeRecord::netPnl).sum();
                    double gLoss = Math.abs(group.stream().filter(t -> t.netPnl() < 0).mapToDouble(TradeRecord::netPnl).sum());
                    double gPf = gLoss == 0 ? (gProfit > 0 ? Double.POSITIVE_INFINITY : 0) : gProfit / gLoss;
                    return new BacktestReport.StrategyStats(group.size(), gWins,
                            group.isEmpty() ? 0 : (double) gWins / group.size(),
                            group.stream().mapToDouble(TradeRecord::netPnl).sum(), gPf,
                            group.stream().mapToDouble(TradeRecord::maxDrawdown).max().orElse(0));
                })));
    }
}
