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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * TDD — RED tests written BEFORE applying the delta=0.60 approximation to PnL.
 *
 * <p>Business rule: ITM options with delta ≈ 0.60 move $0.60 per $1 move in the underlying.
 * The formula must be: grossPnl = priceMove × qty × 100 × OPTIONS_DELTA
 *
 * <p>Test approach:
 * <ul>
 *   <li>Entry window shrunk to a single candle (9:45–9:46 ET) — exactly ONE entry per run.</li>
 *   <li>TP=9999 (unreachable), SL=0 (unreachable) — trade survives until forced close at 13:00.</li>
 *   <li>All candles after entry are at EXIT_PRICE — forced close uses that exact price.</li>
 *   <li>grossPnl is asserted directly on the single trade.</li>
 * </ul>
 *
 * <p>With entry=200.0, exit=201.0, qty from position sizing:
 * <ul>
 *   <li>WITHOUT delta: grossPnl = (201.0 − 200.0) × qty × 100 = 100 × qty</li>
 *   <li>WITH delta=0.60: grossPnl = (201.0 − 200.0) × qty × 100 × 0.60 = 60 × qty</li>
 * </ul>
 *
 * <p>These tests are RED before the fix and GREEN after multiplying by
 * {@code BacktestConfig.OPTIONS_DELTA} in both PnL formulas.
 */
class BacktestEnginePnlDeltaTest {

    private static final ZoneId NY     = ZoneId.of("America/New_York");
    private static final String TICKER = "TEST";
    private static final LocalDate FROM = LocalDate.of(2024, 1, 2);
    private static final LocalDate TO   = LocalDate.of(2024, 1, 31);

    /** Entry price — small enough that riskPerContract stays below maxRisk. */
    private static final double ENTRY_PRICE = 200.0;

    /** Exit price — $1.00 above entry. */
    private static final double CALL_EXIT = 201.0;

    /** Exit price — $1.00 below entry. */
    private static final double PUT_EXIT = 199.0;

    @TempDir
    Path tempDir;

    private TickerMemory tickerMemory;

    @BeforeEach
    void setUp() {
        tickerMemory = new TickerMemory(tempDir.resolve("ticker-memory.json").toString());
    }

    // =========================================================================
    // T1 — CALL closePosition applies OPTIONS_DELTA
    //
    // Setup: exactly one entry at ENTRY_PRICE, forced-close at CALL_EXIT ($1 move up).
    // Expected grossPnl = 1.0 × qty × 100 × 0.60 = 60 × qty
    // Forbidden grossPnl = 1.0 × qty × 100 × 1.00 = 100 × qty  (old formula)
    // =========================================================================
    @Test
    void closePosition_call_appliesDelta60() {
        Map<TimeFrame, List<Candle>> data = buildTestData(ENTRY_PRICE, CALL_EXIT);

        BacktestConfig config = singleEntryConfig();
        BacktestReport report = runWith(new AlwaysTriggerCallStrategy(), config, data);

        List<TradeRecord> forcedTrades = report.trades().stream()
                .filter(t -> "FORCED".equals(t.exitReason()) || "EOS".equals(t.exitReason()))
                .filter(t -> Math.abs(t.entryPrice() - ENTRY_PRICE) < 0.01)
                .toList();

        assertThat(forcedTrades)
                .as("Should have exactly one CALL trade entering at ENTRY_PRICE for delta assertion")
                .isNotEmpty();

        forcedTrades.forEach(trade -> {
            int qty = trade.quantity();
            double priceDelta = trade.exitPrice() - trade.entryPrice();
            double expectedGrossPnl = priceDelta * qty * 100 * 0.60;
            double forbiddenGrossPnl = priceDelta * qty * 100 * 1.00;

            assertThat(trade.grossPnl())
                    .as("CALL grossPnl must apply delta=0.60: expected %.2f but must NOT be %.2f",
                            expectedGrossPnl, forbiddenGrossPnl)
                    .isNotCloseTo(forbiddenGrossPnl, within(0.01))
                    .isCloseTo(expectedGrossPnl, within(0.01));
        });
    }

    // =========================================================================
    // T2 — PUT closePosition applies OPTIONS_DELTA
    //
    // Setup: exactly one entry at ENTRY_PRICE, forced-close at PUT_EXIT ($1 move down).
    // Expected grossPnl = 1.0 × qty × 100 × 0.60 = 60 × qty
    // Forbidden grossPnl = 1.0 × qty × 100 × 1.00 = 100 × qty  (old formula)
    // =========================================================================
    @Test
    void closePosition_put_appliesDelta60() {
        Map<TimeFrame, List<Candle>> data = buildTestData(ENTRY_PRICE, PUT_EXIT);

        BacktestConfig config = singleEntryConfig();
        BacktestReport report = runWith(new AlwaysTriggerPutStrategy(), config, data);

        List<TradeRecord> forcedTrades = report.trades().stream()
                .filter(t -> "FORCED".equals(t.exitReason()) || "EOS".equals(t.exitReason()))
                .filter(t -> Math.abs(t.entryPrice() - ENTRY_PRICE) < 0.01)
                .toList();

        assertThat(forcedTrades)
                .as("Should have exactly one PUT trade entering at ENTRY_PRICE for delta assertion")
                .isNotEmpty();

        forcedTrades.forEach(trade -> {
            int qty = trade.quantity();
            double priceDelta = trade.entryPrice() - trade.exitPrice(); // PUT: entry - exit
            double expectedGrossPnl = priceDelta * qty * 100 * 0.60;
            double forbiddenGrossPnl = priceDelta * qty * 100 * 1.00;

            assertThat(trade.grossPnl())
                    .as("PUT grossPnl must apply delta=0.60: expected %.2f but must NOT be %.2f",
                            expectedGrossPnl, forbiddenGrossPnl)
                    .isNotCloseTo(forbiddenGrossPnl, within(0.01))
                    .isCloseTo(expectedGrossPnl, within(0.01));
        });
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private BacktestReport runWith(TradingStrategy strategy, BacktestConfig config,
                                   Map<TimeFrame, List<Candle>> data) {
        CandleRepository repo = new StubCandleRepository(TICKER, data);
        BacktestEngine engine = new BacktestEngine(repo, tickerMemory, 1, false, List.of(strategy));
        return engine.run(config, false, null);
    }

    /**
     * Config with entry window 9:45–9:46 (a single 1-minute slice so only one entry fires),
     * no slippage, no commission, forced-close at 13:00.
     * TP/SL are set via RiskCalculator — we set the candles so TP/SL are never hit;
     * the position survives until the forced close at 13:00.
     */
    private BacktestConfig singleEntryConfig() {
        return new BacktestConfig(
                List.of(TICKER), FROM, TO,
                50_000.0, 0.02,
                0.0,   // slippagePct = 0 for clean arithmetic
                0.0,   // commission = 0 for clean arithmetic
                1,     // maxConcurrentTrades=1 — only ONE position at a time
                TimeFrame.MIN_15, false, true, 0.0, 0.0, null,
                LocalTime.of(9, 45),  // entryWindowStart
                LocalTime.of(9, 46),  // entryWindowEnd — 1-minute window, ONE entry only
                LocalTime.of(13, 0)   // forcedCloseTime
        );
    }

    /**
     * Builds test candle data:
     * - 40 base candles at entryPrice (ending at 9:45 AM ET — last one is the entry candle)
     * - 20 post-entry candles at exitPrice (from 10:00 to 18:00 ET, covering 13:00 forced close)
     *
     * <p>TP is unreachable (need a move > 0.67%) and SL is unreachable (need a move > 0.33%)
     * when the candle spread is tiny (0.001), so the position survives until 13:00 forced close.
     */
    private Map<TimeFrame, List<Candle>> buildTestData(double entryPrice, double exitPrice) {
        Map<TimeFrame, List<Candle>> data = new EnumMap<>(TimeFrame.class);
        for (TimeFrame tf : TimeFrame.values()) {
            data.put(tf, buildCandles(tf, entryPrice, exitPrice));
        }
        return data;
    }

    /**
     * Builds a candle series:
     * 1. 40 candles at entryPrice with tiny spread (0.001) so TP/SL are never touched.
     *    Anchored so the last candle is at 9:45 AM ET (within entry window 9:45–9:46).
     * 2. 25 candles at exitPrice (from 10:00 to ~16:15 ET), covering the 13:00 forced-close.
     *    Spread is tiny so TP/SL on the open position are never touched by these candles.
     */
    private List<Candle> buildCandles(TimeFrame tf, double entryPrice, double exitPrice) {
        List<Candle> candles = new ArrayList<>();
        long step = tfMinutes(tf);

        // Phase 1: 40 base candles ending at 9:45 AM ET
        ZonedDateTime entryAnchor = FROM.atTime(9, 45).atZone(NY);
        for (int i = 39; i >= 0; i--) {
            ZonedDateTime ts = entryAnchor.minusMinutes(step * i);
            candles.add(new Candle(ts, entryPrice,
                    entryPrice + 0.001, entryPrice - 0.001, entryPrice, 1000L));
        }

        // Phase 2: 25 exit-price candles from 10:00 ET, covering 13:00 forced-close
        ZonedDateTime exitStart = FROM.atTime(10, 0).atZone(NY);
        for (int i = 0; i < 25; i++) {
            ZonedDateTime ts = exitStart.plusMinutes(step * i);
            if (ts.toLocalDate().isAfter(TO)) break;
            candles.add(new Candle(ts, exitPrice,
                    exitPrice + 0.001, exitPrice - 0.001, exitPrice, 1000L));
        }

        return candles;
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

    private static final class AlwaysTriggerCallStrategy implements TradingStrategy {
        @Override
        public boolean isTriggered(String ticker, StrategyData data, ZonedDateTime currentTime) {
            return true;
        }
        @Override public String getName() { return "c1 squeeze"; }
    }

    private static final class AlwaysTriggerPutStrategy implements TradingStrategy {
        @Override
        public boolean isTriggered(String ticker, StrategyData data, ZonedDateTime currentTime) {
            return true;
        }
        @Override public String getName() { return "p1 squeeze"; }
    }

    // =========================================================================
    // Repository stub
    // =========================================================================

    private static final class StubCandleRepository implements CandleRepository {
        private final String ticker;
        private final Map<TimeFrame, List<Candle>> data;

        StubCandleRepository(String ticker, Map<TimeFrame, List<Candle>> data) {
            this.ticker = ticker;
            this.data = data;
        }

        @Override public List<Candle> load(String t, TimeFrame tf) {
            return ticker.equals(t) ? data.getOrDefault(tf, List.of()) : List.of();
        }
        @Override public List<Candle> loadRange(String t, TimeFrame tf, ZonedDateTime from, ZonedDateTime to) {
            return load(t, tf);
        }
        @Override public Optional<ZonedDateTime> lastTimestamp(String t, TimeFrame tf) { return Optional.empty(); }
        @Override public void upsert(String t, TimeFrame tf, List<Candle> candles) {}
        @Override public boolean hasLocalData(String t, TimeFrame tf) {
            return ticker.equals(t) && !data.getOrDefault(tf, List.of()).isEmpty();
        }
        @Override public Stream<Candle> stream(String t, TimeFrame tf) {
            return load(t, tf).stream();
        }
    }
}
