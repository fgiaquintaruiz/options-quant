package com.fgiaquinta.optionsquant.backtest.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Exports a completed backtest run to two CSV files:
 * <ul>
 *   <li>{@code backtest-trades-{runId}.csv} — one row per trade</li>
 *   <li>{@code backtest-summary-{runId}.csv} — aggregated analytics sections</li>
 * </ul>
 *
 * <p>Export is always silent on failure — never propagates exceptions to the caller.
 * BOM ({@code \uFEFF}) is written as the first character of every file so Excel
 * opens it correctly with UTF-8 encoding.
 */
@Service
@ConditionalOnProperty(name = "candles.store", havingValue = "sqlite", matchIfMissing = true)
public class BacktestExportService {

    private static final Logger log = LoggerFactory.getLogger(BacktestExportService.class);

    private static final String BOM = "\uFEFF";

    /** Overridable in tests via reflection. */
    private Path exportDir = Path.of("data/backtest-exports");

    private static final String TRADES_HEADER =
            "ticker,strategy,timeframe,signal_date,signal_type,entry_price,exit_price,pnl,win,pattern";

    private static final String SELECT_TRADES =
            "SELECT ticker, strategy, timeframe, signal_date, signal_type, " +
            "entry_price, exit_price, pnl, win, pattern " +
            "FROM backtest_trades WHERE run_id = ?";

    private final DataSource candlesReadDs;

    public BacktestExportService(@Qualifier("candlesReadDs") DataSource candlesReadDs) {
        this.candlesReadDs = candlesReadDs;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Exports the given run to two CSV files under {@code data/backtest-exports/}.
     * Any failure is caught and logged — never propagated to the caller.
     *
     * @param runId the run identifier (e.g. {@code "2024-01-15_10-00"})
     */
    public void exportRunToCSV(String runId) {
        try {
            Files.createDirectories(exportDir);
            List<TradeRow> trades = loadTrades(runId);
            exportTrades(runId, trades);
            exportSummary(runId, trades);
            log.info("[backtest-export] Exported run {} to {} ({} trades)", runId, exportDir, trades.size());
        } catch (Exception e) {
            log.error("[backtest-export] Export failed for run {}: {}", runId, e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Trade CSV
    // -------------------------------------------------------------------------

    private void exportTrades(String runId, List<TradeRow> trades) throws IOException {
        Path file = exportDir.resolve("backtest-trades-" + runId + ".csv");
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write(BOM);
            writer.write(TRADES_HEADER);
            writer.newLine();
            for (TradeRow t : trades) {
                writer.write(buildTradeRow(t));
                writer.newLine();
            }
        }
    }

    private String buildTradeRow(TradeRow t) {
        return csvField(t.ticker) + "," +
               csvField(t.strategy) + "," +
               csvField(t.timeframe) + "," +
               csvField(t.signalDate) + "," +
               csvField(t.signalType) + "," +
               fmt(t.entryPrice) + "," +
               fmt(t.exitPrice) + "," +
               fmt(t.pnl) + "," +
               t.win + "," +
               csvField(t.pattern);
    }

    // -------------------------------------------------------------------------
    // Summary CSV
    // -------------------------------------------------------------------------

    private void exportSummary(String runId, List<TradeRow> trades) throws IOException {
        Path file = exportDir.resolve("backtest-summary-" + runId + ".csv");
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write(BOM);
            writeSummarySection(writer, trades);
            writeByStrategySection(writer, trades);
            writeCallVsPutSection(writer, trades);
            writeLossesByPatternSection(writer, trades);
            writeLossesByTickerSection(writer, trades);
            writeWorst20Section(writer, trades);
        }
    }

    private void writeSummarySection(BufferedWriter w, List<TradeRow> trades) throws IOException {
        w.write("=== SUMMARY ===");
        w.newLine();
        w.write("total_trades,wins,losses,win_rate,total_pnl,avg_pnl");
        w.newLine();

        int total = trades.size();
        int wins = (int) trades.stream().filter(t -> t.win == 1).count();
        int losses = total - wins;
        double winRate = total == 0 ? 0.0 : (double) wins / total;
        double totalPnl = trades.stream().mapToDouble(t -> t.pnl).sum();
        double avgPnl = total == 0 ? 0.0 : totalPnl / total;

        w.write(total + "," + wins + "," + losses + "," + fmt(winRate) + "," + fmt(totalPnl) + "," + fmt(avgPnl));
        w.newLine();
        w.newLine();
    }

    private void writeByStrategySection(BufferedWriter w, List<TradeRow> trades) throws IOException {
        w.write("=== BY STRATEGY ===");
        w.newLine();
        w.write("strategy,trades,wins,losses,win_rate,total_pnl,avg_pnl");
        w.newLine();

        Map<String, List<TradeRow>> byStrategy = groupBy(trades, t -> t.strategy);
        for (Map.Entry<String, List<TradeRow>> e : byStrategy.entrySet()) {
            w.write(buildGroupRow(e.getKey(), e.getValue()));
            w.newLine();
        }
        w.newLine();
    }

    private void writeCallVsPutSection(BufferedWriter w, List<TradeRow> trades) throws IOException {
        w.write("=== CALL VS PUT ===");
        w.newLine();
        w.write("signal_type,trades,wins,losses,win_rate,total_pnl,avg_pnl");
        w.newLine();

        Map<String, List<TradeRow>> byType = groupBy(trades, t -> t.signalType);
        for (Map.Entry<String, List<TradeRow>> e : byType.entrySet()) {
            w.write(buildGroupRow(e.getKey(), e.getValue()));
            w.newLine();
        }
        w.newLine();
    }

    private void writeLossesByPatternSection(BufferedWriter w, List<TradeRow> trades) throws IOException {
        w.write("=== LOSSES BY PATTERN ===");
        w.newLine();
        w.write("pattern,count,total_loss");
        w.newLine();

        List<TradeRow> losses = trades.stream().filter(t -> t.pnl < 0).toList();
        Map<String, List<TradeRow>> byPattern = groupBy(losses, t -> t.pattern != null ? t.pattern : "unknown");
        for (Map.Entry<String, List<TradeRow>> e : byPattern.entrySet()) {
            double totalLoss = e.getValue().stream().mapToDouble(t -> t.pnl).sum();
            w.write(csvField(e.getKey()) + "," + e.getValue().size() + "," + fmt(totalLoss));
            w.newLine();
        }
        w.newLine();
    }

    private void writeLossesByTickerSection(BufferedWriter w, List<TradeRow> trades) throws IOException {
        w.write("=== LOSSES BY TICKER ===");
        w.newLine();
        w.write("ticker,count,total_loss");
        w.newLine();

        List<TradeRow> losses = trades.stream().filter(t -> t.pnl < 0).toList();
        Map<String, List<TradeRow>> byTicker = groupBy(losses, t -> t.ticker);
        for (Map.Entry<String, List<TradeRow>> e : byTicker.entrySet()) {
            double totalLoss = e.getValue().stream().mapToDouble(t -> t.pnl).sum();
            w.write(csvField(e.getKey()) + "," + e.getValue().size() + "," + fmt(totalLoss));
            w.newLine();
        }
        w.newLine();
    }

    private void writeWorst20Section(BufferedWriter w, List<TradeRow> trades) throws IOException {
        w.write("=== WORST 20 TRADES ===");
        w.newLine();
        w.write("ticker,strategy,signal_type,pnl,signal_date");
        w.newLine();

        trades.stream()
              .sorted((a, b) -> Double.compare(a.pnl, b.pnl))
              .limit(20)
              .forEach(t -> {
                  try {
                      w.write(csvField(t.ticker) + "," + csvField(t.strategy) + "," +
                              csvField(t.signalType) + "," + fmt(t.pnl) + "," + csvField(t.signalDate));
                      w.newLine();
                  } catch (IOException ex) {
                      throw new RuntimeException(ex);
                  }
              });
        w.newLine();
    }

    // -------------------------------------------------------------------------
    // Data loading
    // -------------------------------------------------------------------------

    private List<TradeRow> loadTrades(String runId) throws Exception {
        List<TradeRow> rows = new ArrayList<>();
        try (Connection conn = candlesReadDs.getConnection();
             PreparedStatement ps = conn.prepareStatement(SELECT_TRADES)) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new TradeRow(
                            rs.getString("ticker"),
                            rs.getString("strategy"),
                            rs.getString("timeframe"),
                            rs.getString("signal_date"),
                            rs.getString("signal_type"),
                            rs.getDouble("entry_price"),
                            rs.getDouble("exit_price"),
                            rs.getDouble("pnl"),
                            rs.getInt("win"),
                            rs.getString("pattern")
                    ));
                }
            }
        }
        return rows;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static String buildGroupRow(String key, List<TradeRow> group) {
        int total = group.size();
        int wins = (int) group.stream().filter(t -> t.win == 1).count();
        int losses = total - wins;
        double winRate = total == 0 ? 0.0 : (double) wins / total;
        double totalPnl = group.stream().mapToDouble(t -> t.pnl).sum();
        double avgPnl = total == 0 ? 0.0 : totalPnl / total;
        return csvField(key) + "," + total + "," + wins + "," + losses + "," +
               fmt(winRate) + "," + fmt(totalPnl) + "," + fmt(avgPnl);
    }

    private static Map<String, List<TradeRow>> groupBy(List<TradeRow> trades,
                                                        java.util.function.Function<TradeRow, String> keyFn) {
        Map<String, List<TradeRow>> map = new LinkedHashMap<>();
        for (TradeRow t : trades) {
            map.computeIfAbsent(keyFn.apply(t), k -> new ArrayList<>()).add(t);
        }
        return map;
    }

    private static String fmt(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    /**
     * Wraps a CSV field value in quotes if it contains a comma, newline, or quote.
     * Internal quotes are escaped by doubling them.
     */
    private static String csvField(String value) {
        if (value == null) return "";
        String escaped = value.replace("\"", "\"\"");
        if (escaped.contains(",") || escaped.contains("\n") || escaped.contains("\"")) {
            return "\"" + escaped + "\"";
        }
        return escaped;
    }

    // -------------------------------------------------------------------------
    // Internal value object
    // -------------------------------------------------------------------------

    private record TradeRow(
            String ticker,
            String strategy,
            String timeframe,
            String signalDate,
            String signalType,
            double entryPrice,
            double exitPrice,
            double pnl,
            int win,
            String pattern
    ) {}
}
