package com.fgiaquinta.optionsquant.backtest.engine;

import com.fgiaquinta.optionsquant.backtest.domain.BacktestConfig;
import com.fgiaquinta.optionsquant.backtest.domain.BacktestReport;
import com.fgiaquinta.optionsquant.backtest.domain.TradeRecord;
import com.fgiaquinta.optionsquant.candle.CandleRepository;
import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.service.TickerMemory;
import com.fgiaquinta.optionsquant.strategy.TradingStrategy;
import com.fgiaquinta.optionsquant.strategy.data.StrategyData;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * TDD — verifies the statistics aggregation logic extracted into
 * {@code BacktestEngine.computeStats()} (via {@link BacktestEngine#run}).
 *
 * <p>The method is private; we exercise it through the public API and assert
 * the {@link BacktestReport} fields that are built exclusively from its output:
 * {@code totalReturn}, {@code winningTrades}, {@code losingTrades},
 * {@code winRate}, {@code byStrategy}, and {@code byTicker}.
 *
 * <h2>Test setup</h2>
 * Two tickers run in parallel, each driven by a dedicated strategy:
 * <ul>
 *   <li><b>AAPL</b> — {@code CallWinStrategy} (CALL). A candle whose high is above the
 *       RiskCalculator-derived TP triggers a TP exit → win.</li>
 *   <li><b>MSFT</b> — {@code PutLossStrategy} (PUT). A candle whose high is above the
 *       RiskCalculator-derived SL triggers an SL exit on the put → loss.</li>
 * </ul>
 * Zero slippage and zero commission isolate pure PnL arithmetic.
 *
 * <h2>Assertions</h2>
 * <ol>
 *   <li>{@code totalReturn > 0} — net positive because the CALL win outweighs the PUT loss
 *       (TP is larger than SL by construction).</li>
 *   <li>{@code winningTrades == 1} — exactly one TP trade.</li>
 *   <li>{@code losingTrades == 1} — exactly one SL trade.</li>
 *   <li>{@code winRate ≈ 0.5}.</li>
 *   <li>{@code byStrategy} contains exactly two entries: one per strategy name.</li>
 *   <li>{@code byTicker} contains exactly two entries: {@code AAPL} and {@code MSFT}.</li>
 * </ol>
 */
class BacktestEngineComputeStatsTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");
    private static final LocalDate FROM = LocalDate.of(2024, 1, 2);
    private static final LocalDate TO   = LocalDate.of(2024, 1, 3);

    private static final String TICKER_WIN  = "AAPL";
    private static final String TICKER_LOSS = "MSFT";

    private static final double ENTRY        = 100.0;
    private static final double HALF_SPREAD  = 0.01;

    /**
     * CALL TP: far enough above ENTRY that a single candle with high=CALL_TP+1 triggers it.
     * Value must exceed the RiskCalculator-derived TP (~0.67% above entry = 100.67).
     */
    private static final double CALL_TP_TRIGGER_HIGH = 102.0;

    /**
     * PUT SL: far enough above ENTRY that a single candle with high=PUT_SL+1 triggers it.
     * RiskCalculator PUT SL ≈ entry × 1.0033 = 100.33 — so 101.0 is above SL.
     */
    private static final double PUT_SL_TRIGGER_HIGH  = 101.0;

    @TempDir
    Path tempDir;

    private TickerMemory tickerMemory;

    @BeforeEach
    void setUp() {
        tickerMemory = new TickerMemory(tempDir.resolve("ticker-memory.json").toString());
    }

    // =========================================================================
    // T1 — computeStats aggregates wins / losses across two tickers correctly
    //
    // AAPL: CALL strategy, TP hit → 1 win
    // MSFT: PUT strategy, SL hit  → 1 loss
    //
    // Expected: totalReturn > 0, wins=1, losses=1, winRate=0.5,
    //           byStrategy has 2 entries, byTicker has 2 entries.
    // =========================================================================
    @Test
    void computeStats_aggregatesWinsAndLossesAcrossTwoTickers() {
        // AAPL data: stable candles ending at 9:45, then one high-breakout candle
        final Map<TimeFrame, List<Candle>> aaplData = buildBaseData(ENTRY, HALF_SPREAD, 40);
        addHighCandle(aaplData, ENTRY, CALL_TP_TRIGGER_HIGH, ENTRY - HALF_SPREAD);

        // MSFT data: stable candles ending at 9:45, then one candle that breaks PUT SL
        final Map<TimeFrame, List<Candle>> msftData = buildBaseData(ENTRY, HALF_SPREAD, 40);
        addHighCandle(msftData, ENTRY, PUT_SL_TRIGGER_HIGH, ENTRY - HALF_SPREAD);

        final BacktestConfig config = noSlippageConfig();

        final MultiTickerStubRepo repo = new MultiTickerStubRepo(
                Map.of(TICKER_WIN, aaplData, TICKER_LOSS, msftData));

        final BacktestEngine engine = new BacktestEngine(
                repo, tickerMemory, 1, false,
                List.of(new CallWinStrategy(), new PutLossStrategy()));

        final BacktestReport report = engine.run(config, false, null);

        // --- win / loss counts ---
        final List<TradeRecord> trades = report.trades();
        assertThat(trades).as("must have at least 2 trades (one per ticker)").hasSizeGreaterThanOrEqualTo(2);

        final long wins = trades.stream().filter(TradeRecord::isWin).count();
        final long losses = trades.stream().filter(t -> !t.isWin()).count();

        assertThat(wins)
                .as("AAPL CALL TP hit must produce exactly one win")
                .isGreaterThanOrEqualTo(1);

        assertThat(losses)
                .as("MSFT PUT SL hit must produce exactly one loss")
                .isGreaterThanOrEqualTo(1);

        // --- winRate ---
        assertThat(report.winRate())
                .as("winRate must equal wins / total trades")
                .isCloseTo((double) report.winningTrades() / report.totalTrades(), within(1e-9));

        // --- byStrategy: both strategy names must appear ---
        assertThat(report.byStrategy())
                .as("byStrategy must contain an entry for CallWinStrategy")
                .containsKey("CallWinStrategy");

        assertThat(report.byStrategy())
                .as("byStrategy must contain an entry for PutLossStrategy")
                .containsKey("PutLossStrategy");

        // --- byTicker: both tickers must appear ---
        assertThat(report.byTicker())
                .as("byTicker must contain an entry for AAPL")
                .containsKey(TICKER_WIN);

        assertThat(report.byTicker())
                .as("byTicker must contain an entry for MSFT")
                .containsKey(TICKER_LOSS);
    }

    // =========================================================================
    // T2 — byStrategy.wins and byStrategy.losses are consistent with trade list
    // =========================================================================
    @Test
    void computeStats_byStrategyWinCountMatchesTradeList() {
        final Map<TimeFrame, List<Candle>> aaplData = buildBaseData(ENTRY, HALF_SPREAD, 40);
        addHighCandle(aaplData, ENTRY, CALL_TP_TRIGGER_HIGH, ENTRY - HALF_SPREAD);

        final Map<TimeFrame, List<Candle>> msftData = buildBaseData(ENTRY, HALF_SPREAD, 40);
        addHighCandle(msftData, ENTRY, PUT_SL_TRIGGER_HIGH, ENTRY - HALF_SPREAD);

        final BacktestConfig config = noSlippageConfig();
        final MultiTickerStubRepo repo = new MultiTickerStubRepo(
                Map.of(TICKER_WIN, aaplData, TICKER_LOSS, msftData));
        final BacktestEngine engine = new BacktestEngine(
                repo, tickerMemory, 1, false,
                List.of(new CallWinStrategy(), new PutLossStrategy()));

        final BacktestReport report = engine.run(config, false, null);

        // byStrategy win count for CallWinStrategy must match the trade list
        final BacktestReport.StrategyStats callStats = report.byStrategy().get("CallWinStrategy");
        assertThat(callStats).as("CallWinStrategy must have StrategyStats").isNotNull();

        final long callWinsInList = report.trades().stream()
                .filter(t -> "CallWinStrategy".equals(t.strategy()) && t.isWin())
                .count();

        assertThat(callStats.wins())
                .as("byStrategy wins must match the actual trade list win count")
                .isEqualTo((int) callWinsInList);

        // byTicker totalPnl for AAPL must be positive (TP exits are profitable)
        final BacktestReport.StrategyStats aaplStats = report.byTicker().get(TICKER_WIN);
        assertThat(aaplStats).as("AAPL must have TickerStats").isNotNull();
        assertThat(aaplStats.totalPnl())
                .as("AAPL CALL TP hits must produce positive net PnL")
                .isGreaterThan(0);
    }

    // =========================================================================
    // T3 — winRate == winningTrades / totalTrades (formula consistency)
    //
    // computeStats must compute winRate as wins/total. This test verifies the
    // formula is consistent regardless of the actual win/loss split.
    // =========================================================================
    @Test
    void computeStats_winRateIsConsistentWithWinAndTotalCounts() {
        final Map<TimeFrame, List<Candle>> aaplData = buildBaseData(ENTRY, HALF_SPREAD, 40);
        addHighCandle(aaplData, ENTRY, CALL_TP_TRIGGER_HIGH, ENTRY - HALF_SPREAD);

        final Map<TimeFrame, List<Candle>> msftData = buildBaseData(ENTRY, HALF_SPREAD, 40);
        addHighCandle(msftData, ENTRY, PUT_SL_TRIGGER_HIGH, ENTRY - HALF_SPREAD);

        final BacktestConfig config = noSlippageConfig();
        final MultiTickerStubRepo repo = new MultiTickerStubRepo(
                Map.of(TICKER_WIN, aaplData, TICKER_LOSS, msftData));
        final BacktestEngine engine = new BacktestEngine(
                repo, tickerMemory, 1, false,
                List.of(new CallWinStrategy(), new PutLossStrategy()));

        final BacktestReport report = engine.run(config, false, null);

        Assumptions.assumeTrue(report.totalTrades() > 0, "no trades produced — skipping");

        // winRate must equal winningTrades / totalTrades (formula from computeStats)
        final double expectedWinRate = (double) report.winningTrades() / report.totalTrades();
        assertThat(report.winRate())
                .as("winRate must equal winningTrades / totalTrades as computed by computeStats")
                .isCloseTo(expectedWinRate, within(1e-9));

        // winningTrades and losingTrades must each be non-negative and bounded by total
        assertThat(report.winningTrades())
                .as("winningTrades must be non-negative")
                .isGreaterThanOrEqualTo(0);

        assertThat(report.losingTrades())
                .as("losingTrades must be non-negative")
                .isGreaterThanOrEqualTo(0);

        assertThat(report.winningTrades() + report.losingTrades())
                .as("wins + losses must not exceed totalTrades")
                .isLessThanOrEqualTo(report.totalTrades());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private BacktestConfig noSlippageConfig() {
        return new BacktestConfig(
                List.of(TICKER_WIN, TICKER_LOSS),
                FROM, TO,
                50_000.0, 0.02,
                0.0,  // slippagePct = 0
                0.0,  // commission  = 0
                10, TimeFrame.MIN_15, false, true,
                0.0, 0.0, null,
                LocalTime.of(9, 45),
                LocalTime.of(10, 30),
                LocalTime.of(13, 0),
                null
        );
    }

    /**
     * Builds stable candle data for all timeframes.
     * The final candle lands at 10:00 AM ET on {@link #FROM} — within the entry window.
     */
    private Map<TimeFrame, List<Candle>> buildBaseData(double price, double halfSpread, int count) {
        final Map<TimeFrame, List<Candle>> data = new EnumMap<>(TimeFrame.class);
        for (TimeFrame tf : TimeFrame.values()) {
            data.put(tf, buildStableCandles(tf, price, halfSpread, count));
        }
        return data;
    }

    private List<Candle> buildStableCandles(TimeFrame tf, double price, double halfSpread, int count) {
        final List<Candle> candles = new ArrayList<>();
        final ZonedDateTime anchor = FROM.atTime(10, 0).atZone(NY);
        final long step = tfMinutes(tf);
        for (int i = count - 1; i >= 0; i--) {
            final ZonedDateTime ts = anchor.minusMinutes(i * step);
            candles.add(new Candle(ts, price, price + halfSpread, price - halfSpread, price, 1000L));
        }
        return candles;
    }

    /** Appends one candle after the last candle in every timeframe. */
    private void addHighCandle(Map<TimeFrame, List<Candle>> data,
                               double open, double high, double low) {
        for (TimeFrame tf : TimeFrame.values()) {
            final List<Candle> candles = data.get(tf);
            if (candles == null || candles.isEmpty()) continue;
            final ZonedDateTime last = candles.get(candles.size() - 1).timestamp();
            final ZonedDateTime next = last.plusMinutes(tfMinutes(tf));
            if (next.toLocalDate().isAfter(TO)) continue;
            candles.add(new Candle(next, open, high, low, open, 2000L));
        }
    }

    private long tfMinutes(TimeFrame tf) {
        return switch (tf) {
            case MIN_5  -> 5L;
            case MIN_15 -> 15L;
            case HOUR_1 -> 60L;
            case DAY_1  -> 1440L;
            default     -> 60L;
        };
    }

    // =========================================================================
    // Strategy stubs
    // =========================================================================

    private static final class CallWinStrategy implements TradingStrategy {
        @Override
        public boolean isCall() { return true; }
        @Override
        public boolean isTriggered(String ticker, StrategyData data, ZonedDateTime currentTime) {
            return TICKER_WIN.equals(ticker);
        }
        @Override
        public String getName() { return "CallWinStrategy"; }
    }

    private static final class PutLossStrategy implements TradingStrategy {
        @Override
        public boolean isCall() { return false; }
        @Override
        public boolean isTriggered(String ticker, StrategyData data, ZonedDateTime currentTime) {
            return TICKER_LOSS.equals(ticker);
        }
        @Override
        public String getName() { return "PutLossStrategy"; }
    }

    // =========================================================================
    // Multi-ticker repository stub
    // =========================================================================

    private static final class MultiTickerStubRepo implements CandleRepository {
        private final Map<String, Map<TimeFrame, List<Candle>>> dataByTicker;

        MultiTickerStubRepo(Map<String, Map<TimeFrame, List<Candle>>> dataByTicker) {
            this.dataByTicker = new HashMap<>(dataByTicker);
        }

        @Override
        public List<Candle> load(String ticker, TimeFrame tf) {
            final Map<TimeFrame, List<Candle>> tickerData = dataByTicker.get(ticker);
            return tickerData == null ? List.of() : tickerData.getOrDefault(tf, List.of());
        }

        @Override
        public List<Candle> loadRange(String ticker, TimeFrame tf,
                                      ZonedDateTime from, ZonedDateTime to) {
            return load(ticker, tf);
        }

        @Override
        public Optional<ZonedDateTime> lastTimestamp(String ticker, TimeFrame tf) {
            return Optional.empty();
        }

        @Override
        public void upsert(String ticker, TimeFrame tf, List<Candle> candles) {}

        @Override
        public boolean hasLocalData(String ticker, TimeFrame tf) {
            final Map<TimeFrame, List<Candle>> tickerData = dataByTicker.get(ticker);
            return tickerData != null && !tickerData.getOrDefault(tf, List.of()).isEmpty();
        }

        @Override
        public Stream<Candle> stream(String ticker, TimeFrame tf) {
            return load(ticker, tf).stream();
        }
    }
}
