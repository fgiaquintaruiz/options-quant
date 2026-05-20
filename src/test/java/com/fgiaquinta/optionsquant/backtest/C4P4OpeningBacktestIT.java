package com.fgiaquinta.optionsquant.backtest;

import com.fgiaquinta.optionsquant.OptionsQuantApplication;
import com.fgiaquinta.optionsquant.backtest.domain.BacktestConfig;
import com.fgiaquinta.optionsquant.backtest.domain.BacktestReport;
import com.fgiaquinta.optionsquant.backtest.domain.TradeRecord;
import com.fgiaquinta.optionsquant.backtest.engine.BacktestEngine;
import com.fgiaquinta.optionsquant.infrastructure.MetricsService;
import com.fgiaquinta.optionsquant.service.AccountManager;
import com.fgiaquinta.optionsquant.service.IbkrService;
import com.fgiaquinta.optionsquant.service.OrderExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IT — C4 (Opening Call) and P4 (Opening Put) strategy backtest.
 *
 * <p>Both strategies fire in the 9:30–9:35 AM ET sniper window, which is BLOCKED
 * by the default global entry gate (9:45–10:30 AM). This test overrides that gate
 * to 9:30–9:36 AM so C4 and P4 can actually fire.
 *
 * <p>Uses the {@code onTickerComplete} callback to collect ONLY C4/P4 trades per
 * ticker, avoiding loading all 12 strategies' trades into memory simultaneously.
 *
 * <p>Tickers: 16-ticker universe (HOT + Tactical)
 * Date range: 2018-01-01 → today
 * Output: analysis/backtest_c4_p4_opening_&lt;today&gt;/
 *
 * Run: ./gradlew slowTest --tests "*C4P4Opening*"
 */
@Tag("slow")
@SpringBootTest(
    classes = OptionsQuantApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = {
    "candles.store=sqlite",
    "candles.sqlite.path=data/candles.db"
})
class C4P4OpeningBacktestIT {

    private static final Logger log = LoggerFactory.getLogger(C4P4OpeningBacktestIT.class);

    private static final List<String> TICKERS = List.of(
        // HOT tickers
        "NVDA", "AMD", "AMDL", "TSLA", "META", "AVGO", "COIN", "MSTR", "AMZN", "SPY",
        // Tactical tickers
        "AAPL", "URA", "MU", "SMH", "OXY", "GLD"
    );

    private static final Path OUTPUT_DIR =
        Path.of("analysis/backtest_c4_p4_opening_" + LocalDate.now());

    @Autowired
    private BacktestEngine backtestEngine;

    // Mock all beans that open real sockets / external connections
    @MockitoBean
    private IbkrService ibkrService;

    @MockitoBean
    private AccountManager accountManager;

    @MockitoBean
    private OrderExecutionService orderExecutionService;

    @MockitoBean
    private MetricsService metricsService;

    @Test
    @DisplayName("C4/P4 Opening strategies — full backtest 2018–today with 9:30 entry gate")
    void c4P4OpeningBacktest_producesTradesAndSavesReport() throws IOException {
        // Thread-safe lists to collect C4/P4 trades via callback (avoids loading all trades into memory)
        List<TradeRecord> c4Trades = new CopyOnWriteArrayList<>();
        List<TradeRecord> p4Trades = new CopyOnWriteArrayList<>();

        // --- Config: override entry window to 9:30–9:36 AM ET so C4/P4 can fire ---
        BacktestConfig config = new BacktestConfig(
            TICKERS,
            LocalDate.of(2018, 1, 1),
            LocalDate.now(),
            50_000.0,            // initialCapital
            0.02,                // riskPerTradePct
            0.0008,              // slippagePct
            0.65,                // commissionPerContract
            3,                   // maxConcurrentTrades
            com.fgiaquinta.optionsquant.domain.TimeFrame.MIN_15, // executionTimeframe
            false,               // includeTradePlans (false: saves memory, no chart generation)
            false,               // deterministicMode
            0.0,                 // tpMultiplierDelta
            0.0,                 // slMultiplierDelta
            // Per-ticker callback: collect only C4/P4 trades, discard the rest immediately
            (ticker, trades) -> {
                for (TradeRecord t : trades) {
                    if ("C4OpeningCallStrategy".equals(t.strategy())) {
                        c4Trades.add(t);
                    } else if ("P4OpeningPutStrategy".equals(t.strategy())) {
                        p4Trades.add(t);
                    }
                    // All other strategies' trades are discarded — saves memory
                }
            },
            LocalTime.of(9, 30), // entryWindowStart — override to allow C4/P4
            LocalTime.of(9, 36), // entryWindowEnd   — exclusive upper bound
            LocalTime.of(13, 0)  // forcedCloseTime
        );

        // --- Run ---
        long startMs = System.currentTimeMillis();
        BacktestReport report = backtestEngine.run(config, false, null);
        long elapsedMs = System.currentTimeMillis() - startMs;

        assertThat(report).isNotNull();

        // Sort trades chronologically
        c4Trades.sort(Comparator.comparing(TradeRecord::entryTime));
        p4Trades.sort(Comparator.comparing(TradeRecord::entryTime));

        // --- Compute stats ---
        StrategyStats c4Stats = computeStats(c4Trades);
        StrategyStats p4Stats = computeStats(p4Trades);

        // --- Print to console ---
        log.info("\n========== C4/P4 OPENING STRATEGIES BACKTEST ==========");
        log.info(String.format("Date range : 2018-01-01 → %s", LocalDate.now()));
        log.info(String.format("Tickers    : %d", TICKERS.size()));
        log.info(String.format("Elapsed    : %d ms", elapsedMs));

        log.info("--- C4 (Opening Call) ---");
        printStats("C4", c4Stats);

        log.info("\n--- P4 (Opening Put) ---");
        printStats("P4", p4Stats);

        log.info("=======================================================\n");

        // --- Save output files ---
        Files.createDirectories(OUTPUT_DIR);
        saveSummary(c4Stats, p4Stats, elapsedMs);
        saveTrades("C4", c4Trades);
        saveTrades("P4", p4Trades);
        saveYearlyBreakdown("C4", c4Trades);
        saveYearlyBreakdown("P4", p4Trades);
        saveTickerBreakdown("C4", c4Trades);
        saveTickerBreakdown("P4", p4Trades);

        log.info("Results saved to: {}", OUTPUT_DIR.toAbsolutePath());

        // Soft assertion: the test passes even with 0 trades (useful diagnostic)
        if (c4Trades.isEmpty() && p4Trades.isEmpty()) {
            log.info("WARNING: Both C4 and P4 produced 0 trades. " +
                "Check that SQLite has candles at 9:30 ET for the 16 tickers.");
        }
    }

    // =========================================================================
    // Stats helpers
    // =========================================================================

    private record StrategyStats(
        int totalTrades,
        int wins,
        int losses,
        double winRate,
        double totalPnl,
        double avgPnl,
        double avgWin,
        double avgLoss,
        LocalDate firstDate,
        LocalDate lastDate
    ) {}

    private StrategyStats computeStats(List<TradeRecord> trades) {
        if (trades.isEmpty()) {
            return new StrategyStats(0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, null, null);
        }

        int wins = (int) trades.stream().filter(TradeRecord::isWin).count();
        int losses = trades.size() - wins;
        double winRate = (double) wins / trades.size();
        double totalPnl = trades.stream().mapToDouble(TradeRecord::netPnl).sum();
        double avgPnl = totalPnl / trades.size();
        double avgWin = trades.stream()
            .filter(TradeRecord::isWin)
            .mapToDouble(TradeRecord::netPnl)
            .average()
            .orElse(0.0);
        double avgLoss = trades.stream()
            .filter(t -> !t.isWin())
            .mapToDouble(TradeRecord::netPnl)
            .average()
            .orElse(0.0);

        LocalDate firstDate = trades.stream()
            .filter(t -> t.entryTime() != null)
            .map(t -> t.entryTime().withZoneSameInstant(ZoneId.of("America/New_York")).toLocalDate())
            .min(Comparator.naturalOrder())
            .orElse(null);

        LocalDate lastDate = trades.stream()
            .filter(t -> t.entryTime() != null)
            .map(t -> t.entryTime().withZoneSameInstant(ZoneId.of("America/New_York")).toLocalDate())
            .max(Comparator.naturalOrder())
            .orElse(null);

        return new StrategyStats(trades.size(), wins, losses, winRate,
            totalPnl, avgPnl, avgWin, avgLoss, firstDate, lastDate);
    }

    private void printStats(String label, StrategyStats s) {
        log.info(String.format("  Trades    : %d (wins=%d, losses=%d)", s.totalTrades(), s.wins(), s.losses()));
        log.info(String.format("  Win rate  : %.1f%%", s.winRate() * 100));
        log.info(String.format("  Total PnL : $%.2f", s.totalPnl()));
        log.info(String.format("  Avg PnL   : $%.2f per trade", s.avgPnl()));
        log.info(String.format("  Avg Win   : $%.2f / Avg Loss: $%.2f", s.avgWin(), s.avgLoss()));
        log.info(String.format("  Date range: %s → %s", s.firstDate(), s.lastDate()));
    }

    // =========================================================================
    // File writers
    // =========================================================================

    private void saveSummary(StrategyStats c4, StrategyStats p4, long elapsedMs) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# C4/P4 Opening Strategies Backtest\n\n");
        sb.append("Date range : 2018-01-01 → ").append(LocalDate.now()).append("\n");
        sb.append("Tickers    : ").append(TICKERS.size()).append(" (").append(String.join(", ", TICKERS)).append(")\n");
        sb.append("Entry gate : 9:30–9:36 AM ET (overridden for C4/P4 sniper window)\n");
        sb.append("Elapsed    : ").append(elapsedMs).append(" ms\n\n");

        sb.append("## C4 — Opening Call\n");
        appendStats(sb, c4);

        sb.append("\n## P4 — Opening Put\n");
        appendStats(sb, p4);

        Files.writeString(OUTPUT_DIR.resolve("summary.md"), sb.toString());
    }

    private void appendStats(StringBuilder sb, StrategyStats s) {
        sb.append("- Trades    : ").append(s.totalTrades())
          .append(" (wins=").append(s.wins()).append(", losses=").append(s.losses()).append(")\n");
        sb.append(String.format("- Win rate  : %.1f%%\n", s.winRate() * 100));
        sb.append(String.format("- Total PnL : $%.2f\n", s.totalPnl()));
        sb.append(String.format("- Avg PnL   : $%.2f per trade\n", s.avgPnl()));
        sb.append(String.format("- Avg Win   : $%.2f / Avg Loss: $%.2f\n", s.avgWin(), s.avgLoss()));
        sb.append("- First trade: ").append(s.firstDate()).append("\n");
        sb.append("- Last trade : ").append(s.lastDate()).append("\n");
    }

    private void saveTrades(String label, List<TradeRecord> trades) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("ticker\tstrategy\tdirection\tentryTime\texitTime\texitReason\tnetPnl\tgrossPnl\tisWin\n");
        for (TradeRecord t : trades) {
            sb.append(t.ticker()).append('\t')
              .append(t.strategy()).append('\t')
              .append(t.direction()).append('\t')
              .append(t.entryTime()).append('\t')
              .append(t.exitTime()).append('\t')
              .append(t.exitReason()).append('\t')
              .append(String.format("%.2f", t.netPnl())).append('\t')
              .append(String.format("%.2f", t.grossPnl())).append('\t')
              .append(t.isWin()).append('\n');
        }
        Files.writeString(OUTPUT_DIR.resolve(label + "_trades.tsv"), sb.toString());
    }

    private void saveYearlyBreakdown(String label, List<TradeRecord> trades) throws IOException {
        // Group by year
        Map<Integer, List<TradeRecord>> byYear = new TreeMap<>(
            trades.stream()
                .filter(t -> t.entryTime() != null)
                .collect(Collectors.groupingBy(t ->
                    t.entryTime().withZoneSameInstant(ZoneId.of("America/New_York")).getYear()))
        );

        StringBuilder sb = new StringBuilder();
        sb.append("year\ttrades\twins\twin_rate\ttotal_pnl\n");
        for (Map.Entry<Integer, List<TradeRecord>> entry : byYear.entrySet()) {
            StrategyStats s = computeStats(entry.getValue());
            sb.append(entry.getKey()).append('\t')
              .append(s.totalTrades()).append('\t')
              .append(s.wins()).append('\t')
              .append(String.format("%.1f%%", s.winRate() * 100)).append('\t')
              .append(String.format("%.2f", s.totalPnl())).append('\n');
        }
        Files.writeString(OUTPUT_DIR.resolve(label + "_by_year.tsv"), sb.toString());
    }

    private void saveTickerBreakdown(String label, List<TradeRecord> trades) throws IOException {
        Map<String, List<TradeRecord>> byTicker = new TreeMap<>(
            trades.stream().collect(Collectors.groupingBy(TradeRecord::ticker))
        );

        StringBuilder sb = new StringBuilder();
        sb.append("ticker\ttrades\twins\twin_rate\ttotal_pnl\n");
        for (Map.Entry<String, List<TradeRecord>> entry : byTicker.entrySet()) {
            StrategyStats s = computeStats(entry.getValue());
            sb.append(entry.getKey()).append('\t')
              .append(s.totalTrades()).append('\t')
              .append(s.wins()).append('\t')
              .append(String.format("%.1f%%", s.winRate() * 100)).append('\t')
              .append(String.format("%.2f", s.totalPnl())).append('\n');
        }
        Files.writeString(OUTPUT_DIR.resolve(label + "_by_ticker.tsv"), sb.toString());
    }
}
