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
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TDD — RED/GREEN tests for win/loss detection in BacktestEngine.
 *
 * <p>Bug context: after the RiskCalculator refactor to fixed-percentage TP/SL
 * (TP=0.67%, SL=0.33% from entry), CALL trades were reported as win=0 even when
 * exitPrice &gt; entryPrice. The root cause: with default 0.5% slippage applied to
 * the underlying stock price, the entry fill cost eats into the tiny TP margin,
 * making netPnl negative even on TP exits.
 *
 * <p>Fix: win/loss detection must be based on whether the trade hit its directional
 * target (TP → win, SL → loss), not on net PnL after slippage costs.
 *
 * <p>Test approach: use BacktestConfig with slippagePct=0 and commission=0 to isolate
 * pure directional win/loss logic. These tests are RED with the pre-fix code that
 * computes {@code isWin = netPnl > 0} when the entry fill price is inflated by slippage,
 * and GREEN after the fix that uses exit reason (TP vs SL) for win/loss determination.
 *
 * <p>All tests use a fixed entry price of $100 so TP/SL levels are predictable:
 * <ul>
 *   <li>CALL TP = $100 × 1.0067 = $100.67</li>
 *   <li>CALL SL = $100 × 0.9967 = $99.67</li>
 *   <li>PUT  TP = $100 × 0.9933 = $99.33</li>
 *   <li>PUT  SL = $100 × 1.0033 = $100.33</li>
 * </ul>
 */
class BacktestEngineWinLossTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");
    private static final String TICKER = "TEST";
    private static final LocalDate FROM = LocalDate.of(2024, 1, 2);
    private static final LocalDate TO   = LocalDate.of(2024, 1, 31);

    /** Entry price for all tests — predictable TP/SL values. */
    private static final double ENTRY = 100.0;

    /** CALL TP: price must rise above this level. */
    private static final double CALL_TP = Math.round(ENTRY * 1.0067 * 100.0) / 100.0; // 100.67

    /** CALL SL: price must fall below this level. */
    private static final double CALL_SL = Math.round(ENTRY * 0.9967 * 100.0) / 100.0; // 99.67

    /** PUT TP: price must fall below this level. */
    private static final double PUT_TP = Math.round(ENTRY * 0.9933 * 100.0) / 100.0;  // 99.33

    /** PUT SL: price must rise above this level. */
    private static final double PUT_SL = Math.round(ENTRY * 1.0033 * 100.0) / 100.0;  // 100.33

    @TempDir
    Path tempDir;

    private TickerMemory tickerMemory;

    @BeforeEach
    void setUp() {
        tickerMemory = new TickerMemory(tempDir.resolve("ticker-memory.json").toString());
    }

    // =========================================================================
    // T1 — CALL exits at TP (exitPrice >= tp) → isWin = true
    //
    // Candle high touches CALL_TP+0.50 (above TP), triggering TP exit.
    // exitPrice = CALL_TP = 100.67 > entryPrice = 100.
    // With slippage=0: netPnl = (100.67 - 100) * qty * 100 > 0 → win=true ✓
    // With slippage=0.005 (pre-fix): netPnl < 0 → win=false → BUG
    // After fix: win based on exitReason=TP → win=true regardless of slippage.
    // =========================================================================
    @Test
    void callTrade_exitAtOrAboveTP_isWin() {
        // Entry candles: open below TP, then one candle whose high exceeds CALL_TP
        Map<TimeFrame, List<Candle>> data = buildBaseData(ENTRY, 0.01);
        addTpTriggerCandle(data, ENTRY, CALL_TP + 0.50, ENTRY - 0.10);  // high > TP

        BacktestConfig config = noSlippageConfig(List.of(TICKER));
        BacktestReport report = runWith(new AlwaysTriggerCallStrategy(), config, data);

        List<TradeRecord> tpTrades = report.trades().stream()
                .filter(t -> "TP".equals(t.exitReason()))
                .toList();

        assertThat(tpTrades)
                .as("Should have at least one CALL trade that closed at TP")
                .isNotEmpty();

        assertThat(tpTrades)
                .as("CALL trade: exitPrice >= TP, so isWin must be true")
                .allSatisfy(t -> {
                    assertThat(t.exitPrice()).as("exitPrice should be at CALL_TP").isEqualTo(CALL_TP);
                    assertThat(t.isWin()).as("CALL TP hit must be a win").isTrue();
                });
    }

    // =========================================================================
    // T2 — PUT exits at TP (exitPrice <= tp) → isWin = true
    //
    // Candle low dips to PUT_TP - 0.50 (below PUT TP), triggering TP exit.
    // exitPrice = PUT_TP = 99.33 < entryPrice = 100.
    // With slippage=0: netPnl = (100 - 99.33) * qty * 100 > 0 → win=true ✓
    // With slippage=0.005 (pre-fix): entry fill higher, makes PUT even MORE profitable
    //   so PUT always wins — but this test verifies directional logic is correct.
    // =========================================================================
    @Test
    void putTrade_exitAtOrBelowTP_isWin() {
        Map<TimeFrame, List<Candle>> data = buildBaseData(ENTRY, 0.01);
        addTpTriggerCandle(data, ENTRY, ENTRY + 0.10, PUT_TP - 0.50);  // low < PUT_TP

        BacktestConfig config = noSlippageConfig(List.of(TICKER));
        BacktestReport report = runWith(new AlwaysTriggerPutStrategy(), config, data);

        List<TradeRecord> tpTrades = report.trades().stream()
                .filter(t -> "TP".equals(t.exitReason()))
                .toList();

        assertThat(tpTrades)
                .as("Should have at least one PUT trade that closed at TP")
                .isNotEmpty();

        assertThat(tpTrades)
                .as("PUT trade: exitPrice <= PUT_TP, so isWin must be true")
                .allSatisfy(t -> {
                    assertThat(t.exitPrice()).as("exitPrice should be at PUT_TP").isEqualTo(PUT_TP);
                    assertThat(t.isWin()).as("PUT TP hit must be a win").isTrue();
                });
    }

    // =========================================================================
    // T3 — CALL exits at SL (price fell below sl) → isWin = false
    //
    // Candle low dips to CALL_SL - 0.50, triggering SL exit.
    // exitPrice = CALL_SL = 99.67 < entryPrice = 100 → loss.
    // =========================================================================
    @Test
    void callTrade_exitBelowSL_isLoss() {
        Map<TimeFrame, List<Candle>> data = buildBaseData(ENTRY, 0.01);
        addSlTriggerCandle(data, ENTRY, ENTRY + 0.10, CALL_SL - 0.50);  // low < CALL_SL

        BacktestConfig config = noSlippageConfig(List.of(TICKER));
        BacktestReport report = runWith(new AlwaysTriggerCallStrategy(), config, data);

        List<TradeRecord> slTrades = report.trades().stream()
                .filter(t -> "SL".equals(t.exitReason()))
                .toList();

        assertThat(slTrades)
                .as("Should have at least one CALL trade that closed at SL")
                .isNotEmpty();

        assertThat(slTrades)
                .as("CALL trade: exitPrice at SL < entry, so isWin must be false")
                .allSatisfy(t -> {
                    assertThat(t.exitPrice()).as("exitPrice should be at CALL_SL").isEqualTo(CALL_SL);
                    assertThat(t.isWin()).as("CALL SL hit must be a loss").isFalse();
                });
    }

    // =========================================================================
    // T4 — PUT exits at SL (price rose above sl) → isWin = false
    //
    // Candle high rises to PUT_SL + 0.50, triggering SL exit.
    // exitPrice = PUT_SL = 100.33 > entryPrice = 100 → for PUT this is a loss.
    // =========================================================================
    @Test
    void putTrade_exitAboveSL_isLoss() {
        Map<TimeFrame, List<Candle>> data = buildBaseData(ENTRY, 0.01);
        addSlTriggerCandle(data, ENTRY, PUT_SL + 0.50, ENTRY - 0.10);  // high > PUT_SL

        BacktestConfig config = noSlippageConfig(List.of(TICKER));
        BacktestReport report = runWith(new AlwaysTriggerPutStrategy(), config, data);

        List<TradeRecord> slTrades = report.trades().stream()
                .filter(t -> "SL".equals(t.exitReason()))
                .toList();

        assertThat(slTrades)
                .as("Should have at least one PUT trade that closed at SL")
                .isNotEmpty();

        assertThat(slTrades)
                .as("PUT trade: exitPrice at SL > entry, so isWin must be false")
                .allSatisfy(t -> {
                    assertThat(t.exitPrice()).as("exitPrice should be at PUT_SL").isEqualTo(PUT_SL);
                    assertThat(t.isWin()).as("PUT SL hit must be a loss").isFalse();
                });
    }

    // =========================================================================
    // T5 — CALL TP hit with DEFAULT slippage (0.005) must still be a win
    //
    // This is the regression test for the original bug:
    // With slippage=0.005 and TP_PCT=0.0067, entry fill is higher than raw entry.
    // Pre-fix: grossPnl too small → netPnl < 0 → isWin=false (BUG).
    // Post-fix: isWin based on exitReason=TP → isWin=true regardless of netPnl.
    // =========================================================================
    @Test
    void callTrade_exitAtTP_withDefaultSlippage_isWin() {
        Map<TimeFrame, List<Candle>> data = buildBaseData(ENTRY, 0.01);
        addTpTriggerCandle(data, ENTRY, CALL_TP + 0.50, ENTRY - 0.10);

        // Use default slippage (0.5%) — this was causing the bug
        BacktestConfig config = defaultSlippageConfig(List.of(TICKER));
        BacktestReport report = runWith(new AlwaysTriggerCallStrategy(), config, data);

        List<TradeRecord> tpTrades = report.trades().stream()
                .filter(t -> "TP".equals(t.exitReason()))
                .toList();

        assertThat(tpTrades)
                .as("CALL TP hit with default slippage must still be a win (regression test)")
                .isNotEmpty();

        assertThat(tpTrades)
                .allSatisfy(t ->
                        assertThat(t.isWin())
                                .as("CALL TP hit must be a win regardless of slippage costs — isWin must not depend on netPnl")
                                .isTrue());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private BacktestReport runWith(TradingStrategy strategy, BacktestConfig config,
                                   Map<TimeFrame, List<Candle>> tickerData) {
        String ticker = config.tickers().get(0);
        CandleRepository stubRepo = new StubCandleRepository(ticker, tickerData);
        BacktestEngine engine = new BacktestEngine(stubRepo, tickerMemory, 1, false, List.of(strategy));
        return engine.run(config, false, null);
    }

    /**
     * BacktestConfig with no slippage and no commission for clean directional testing.
     */
    private BacktestConfig noSlippageConfig(List<String> tickers) {
        return new BacktestConfig(
                tickers, FROM, TO,
                50_000.0, 0.02,
                0.0,    // slippagePct = 0
                0.0,    // commission = 0
                10, TimeFrame.MIN_15, false, true, 0.0, 0.0, null,
                java.time.LocalTime.of(9, 45),
                java.time.LocalTime.of(10, 30),
                java.time.LocalTime.of(13, 0),
                null    // strategyFilter — run all strategies
        );
    }

    /**
     * BacktestConfig with default slippage (0.5%) — reproduces the production bug.
     */
    private BacktestConfig defaultSlippageConfig(List<String> tickers) {
        return new BacktestConfig(
                tickers, FROM, TO,
                50_000.0, 0.02,
                0.005,  // slippagePct = 0.5% (default)
                0.65,   // commission = $0.65/contract (default)
                10, TimeFrame.MIN_15, false, true, 0.0, 0.0, null,
                java.time.LocalTime.of(9, 45),
                java.time.LocalTime.of(10, 30),
                java.time.LocalTime.of(13, 0),
                null    // strategyFilter — run all strategies
        );
    }

    /**
     * Builds base candle data: 40 stable candles near ENTRY price.
     * Enough bars for 14-period ATR in RiskCalculator.
     */
    private Map<TimeFrame, List<Candle>> buildBaseData(double price, double halfSpread) {
        Map<TimeFrame, List<Candle>> data = new EnumMap<>(TimeFrame.class);
        for (TimeFrame tf : TimeFrame.values()) {
            data.put(tf, buildStableCandles(tf, price, halfSpread, 40));
        }
        return data;
    }

    /**
     * Appends a candle whose high is at {@code high} and low is at {@code low},
     * designed to trigger a TP exit on the next engine iteration.
     */
    private void addTpTriggerCandle(Map<TimeFrame, List<Candle>> data,
                                    double open, double high, double low) {
        for (TimeFrame tf : TimeFrame.values()) {
            List<Candle> candles = data.get(tf);
            if (candles == null || candles.isEmpty()) continue;
            ZonedDateTime last = candles.get(candles.size() - 1).timestamp();
            ZonedDateTime next = last.plusMinutes(tfMinutes(tf));
            if (next.toLocalDate().isAfter(TO)) continue;
            candles.add(new Candle(next, open, high, low, open, 1000L));
        }
    }

    /**
     * Appends a candle designed to trigger an SL exit.
     */
    private void addSlTriggerCandle(Map<TimeFrame, List<Candle>> data,
                                    double open, double high, double low) {
        addTpTriggerCandle(data, open, high, low); // same shape, different TP/SL levels
    }

    private List<Candle> buildStableCandles(TimeFrame tf, double price, double halfSpread, int count) {
        List<Candle> candles = new ArrayList<>();
        // Anchor: last candle ends at 10:00 AM ET on FROM date — well inside the 9:45-10:30 window.
        // Build backwards from the anchor so the final (signal) candle is within the entry window.
        ZonedDateTime anchor = FROM.atTime(10, 0).atZone(NY);
        long step = tfMinutes(tf);
        for (int i = count - 1; i >= 0; i--) {
            ZonedDateTime ts = anchor.minusMinutes((long) i * step);
            candles.add(new Candle(ts, price, price + halfSpread, price - halfSpread, price, 1000L));
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
    // Strategy stubs — direction inferred from class name by BacktestEngine
    // =========================================================================

    private static final class AlwaysTriggerCallStrategy implements TradingStrategy {
        @Override
        public boolean isCall() { return true; }
        @Override
        public boolean isTriggered(String ticker, StrategyData data, ZonedDateTime currentTime) {
            return true;
        }
        @Override
        public String getName() { return "c1 squeeze"; }
    }

    private static final class AlwaysTriggerPutStrategy implements TradingStrategy {
        @Override
        public boolean isCall() { return false; }
        @Override
        public boolean isTriggered(String ticker, StrategyData data, ZonedDateTime currentTime) {
            return true;
        }
        @Override
        public String getName() { return "p1 squeeze"; }
    }

    // =========================================================================
    // Repository stubs
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
