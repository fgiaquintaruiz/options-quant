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
 * TDD — RED tests written BEFORE modifying BacktestEngine position sizing logic.
 *
 * <p>Tests validate three fixes:
 * <ol>
 *   <li>qty uses fixed capital ({@code config.initialCapital()}) instead of dynamic per-ticker equity.</li>
 *   <li>{@code Math.max(1, qty)} is removed — qty=0 means no position is opened.</li>
 *   <li>Positions with qty=0 (riskPerContract &gt; maxRisk) are silently skipped.</li>
 * </ol>
 *
 * <p>Strategy: a {@link TradingStrategy} mock that always triggers is injected via
 * the package-private test constructor. Synthetic 1H candles provide enough ATR data
 * (14+ bars) so that {@link com.fgiaquinta.optionsquant.strategy.utils.RiskCalculator} can compute a real SL distance.
 * ATR is controlled by tuning the high/low spread so that riskPerContract
 * ({@code |entry - SL| × 100}) lands at a known value relative to maxRisk ($1,000).
 *
 * <p>All assertions operate on the returned {@link BacktestReport#trades()} list.
 */
class BacktestEnginePositionSizingTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");
    private static final String TICKER = "TEST";
    private static final LocalDate FROM = LocalDate.of(2024, 1, 2);
    private static final LocalDate TO   = LocalDate.of(2024, 1, 31);

    /** Always-triggers call strategy stub (direction "CALL" is inferred from class name). */
    private static final TradingStrategy ALWAYS_TRIGGER_CALL = new AlwaysTriggerCallStrategy();

    @TempDir
    Path tempDir;

    private TickerMemory tickerMemory;

    @BeforeEach
    void setUp() {
        tickerMemory = new TickerMemory(tempDir.resolve("ticker-memory.json").toString());
    }

    // -------------------------------------------------------------------------
    // T1 — qty uses config.initialCapital() (fixed $50k), NOT per-ticker equity
    //
    // With 511 tickers, per-ticker equity = $50k/511 ≈ $97.85.
    // maxRisk(old) = $97.85 × 2% ≈ $1.96
    // maxRisk(new) = $50k × 2% = $1,000
    //
    // We set up candles with a tight ATR (~$1 spread) so that
    // riskPerContract ≈ |entry - SL| × 100 ≈ $200 (ATR × SL_mult × 100).
    // floor($1.96 / $200) = 0  →  old Math.max(1,0) = 1  (WRONG)
    // floor($1,000 / $200) = 5  (CORRECT)
    //
    // After the fix, qty must be >= 2 (at least 5 with ATR ~$1 and riskPerContract ~$200).
    // -------------------------------------------------------------------------
    @Test
    void qty_usesFixedCapital_notPerTickerEquity() {
        // 511 tickers: per-ticker equity = $50k/511 ≈ $97.85 → old maxRisk ≈ $1.96
        // Only the first ticker (T0) has candle data — others return empty and are skipped.
        List<String> tickers = buildTickerList(511);
        BacktestConfig config = new BacktestConfig(
                tickers, FROM, TO,
                50_000.0, 0.02, 0.0, 0.0,
                10, TimeFrame.MIN_15, false, true, 0.0, 0.0, null,
                java.time.LocalTime.of(9, 45),
                java.time.LocalTime.of(10, 30),
                java.time.LocalTime.of(13, 0)
        );

        // Provide synthetic data: tight ATR candles so riskPerContract ≈ $200
        Map<TimeFrame, List<Candle>> tickerData = buildTickerData(20.0, 0.50);
        BacktestReport report = runBacktest(null, config, tickerData);

        // With fixed capital: maxRisk=$1,000, riskPerContract≈$200 → qty ≈ 5
        // With per-ticker equity: maxRisk≈$1.96 → qty=0 → Math.max(1,0)=1 (old bug)
        // After fix: qty must be > 1, proving fixed capital is being used
        assertThat(report.trades())
                .isNotEmpty()
                .allSatisfy(t -> assertThat(t.quantity())
                        .as("qty should reflect $50k capital, not $97.85 per-ticker equity")
                        .isGreaterThan(1));
    }

    // -------------------------------------------------------------------------
    // T2 — qty = 0 when riskPerContract > maxRisk; NO position is opened
    //
    // Fixed-pct model: riskPerContract = entryPrice × 0.0033 × 100 = entryPrice × 0.33
    // entry = $10,000  →  riskPerContract = $3,300
    // maxRisk = $50k × 2% = $1,000
    // floor($1,000 / $3,300) = 0  →  position must NOT be opened
    // -------------------------------------------------------------------------
    @Test
    void qty_isZero_whenRiskPerContractExceedsMaxRisk_noPositionOpened() {
        BacktestConfig config = BacktestConfig.defaults(List.of(TICKER), FROM, TO);

        // entryPrice=$10,000 → riskPerContract=10,000×0.0033×100=$3,300 > maxRisk=$1,000
        Map<TimeFrame, List<Candle>> tickerData = buildTickerData(10_000.0, 0.01);
        BacktestReport report = runBacktest(null, config, tickerData);

        assertThat(report.trades())
                .as("No trades should be opened when riskPerContract ($3,300) > maxRisk ($1,000)")
                .isEmpty();
    }

    // -------------------------------------------------------------------------
    // T3 — qty is capped at 10 (upper bound preserved)
    //
    // entry = $100, ATR tiny (~$0.01 spread) → riskPerContract ≈ $0.50
    // maxRisk = $50k × 2% = $1,000
    // floor($1,000 / $0.50) = 2,000  →  capped to 10
    // -------------------------------------------------------------------------
    @Test
    void qty_isCappedAtTen_whenRiskPerContractIsVerySmall() {
        BacktestConfig config = BacktestConfig.defaults(List.of(TICKER), FROM, TO);

        // Tiny ATR: entry=$100, spread=$0.01 per bar → riskPerContract ≈ $0.50
        Map<TimeFrame, List<Candle>> tickerData = buildTickerData(100.0, 0.005);
        BacktestReport report = runBacktest(null, config, tickerData);

        assertThat(report.trades())
                .isNotEmpty()
                .allSatisfy(t -> assertThat(t.quantity())
                        .as("qty should be capped at 10")
                        .isLessThanOrEqualTo(10));
    }

    // -------------------------------------------------------------------------
    // T4 — Math.max(1, qty) is gone: qty stays 0, position is skipped
    //
    // Fixed-pct model: riskPerContract = entryPrice × 0.0033 × 100 = entryPrice × 0.33
    // entry = $5,000  →  riskPerContract = $1,650 > maxRisk = $1,000  →  qty = 0
    // Confirms Math.max(1,0) is not applied — no position opened.
    // -------------------------------------------------------------------------
    @Test
    void qty_staysZero_notForcedToOne_byMathMax() {
        BacktestConfig config = BacktestConfig.defaults(List.of(TICKER), FROM, TO);

        // entryPrice=$5,000 → riskPerContract=5,000×0.0033×100=$1,650 > maxRisk=$1,000
        Map<TimeFrame, List<Candle>> tickerData = buildTickerData(5_000.0, 0.01);
        BacktestReport report = runBacktest(null, config, tickerData);

        assertThat(report.trades())
                .as("Math.max(1, qty) must be removed — riskPerContract $1,650 > maxRisk $1,000 → qty=0 → no trade")
                .isEmpty();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Runs a backtest with a single provided ticker's data injected via a CandleRepository stub.
     * The {@code tickerData} map is served by a stub repository for the first ticker in config.
     */
    private BacktestReport runBacktest(BacktestEngine ignoredEngine,
                                        BacktestConfig config,
                                        Map<TimeFrame, List<Candle>> tickerData) {
        String ticker = config.tickers().get(0);
        CandleRepository stubRepo = new StubCandleRepository(ticker, tickerData);
        BacktestEngine engine = new BacktestEngine(
                stubRepo, tickerMemory, 1, false, List.of(ALWAYS_TRIGGER_CALL));
        return engine.run(config);
    }

    /**
     * Builds 60 MIN_15 candles and 60 HOUR_1 candles for the test ticker.
     *
     * @param basePrice  close/open price
     * @param halfSpread half the high-low spread (controls ATR)
     */
    private Map<TimeFrame, List<Candle>> buildTickerData(double basePrice, double halfSpread) {
        Map<TimeFrame, List<Candle>> data = new EnumMap<>(TimeFrame.class);
        for (TimeFrame tf : TimeFrame.values()) {
            data.put(tf, buildCandles(tf, basePrice, halfSpread));
        }
        return data;
    }

    private List<Candle> buildCandles(TimeFrame tf, double basePrice, double halfSpread) {
        List<Candle> candles = new ArrayList<>();
        ZonedDateTime base;
        int minuteStep;
        switch (tf) {
            case MIN_5  -> { base = FROM.atTime(9, 30).atZone(NY); minuteStep = 5; }
            case MIN_15 -> { base = FROM.atTime(9, 30).atZone(NY); minuteStep = 15; }
            case HOUR_1 -> { base = FROM.atTime(9, 0).atZone(NY);  minuteStep = 60; }
            case DAY_1  -> { base = FROM.atTime(9, 30).atZone(NY); minuteStep = 60 * 24; }
            default     -> { base = FROM.atTime(9, 30).atZone(NY); minuteStep = 60; }
        }
        // 60 candles, each within the FROM-TO date range (stays in January 2024)
        for (int i = 0; i < 60; i++) {
            ZonedDateTime ts = base.plusMinutes((long) i * minuteStep);
            // Keep within backtest date range
            if (ts.toLocalDate().isBefore(FROM) || ts.toLocalDate().isAfter(TO)) continue;
            candles.add(new Candle(ts, basePrice, basePrice + halfSpread, basePrice - halfSpread, basePrice, 1000L));
        }
        return candles;
    }

    private List<String> buildTickerList(int count) {
        List<String> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add("T" + i);
        }
        return list;
    }

    // =========================================================================
    // Strategy stub — always triggers, named to produce "CALL" direction
    // =========================================================================

    private static final class AlwaysTriggerCallStrategy implements TradingStrategy {
        @Override
        public boolean isTriggered(String ticker, StrategyData data, ZonedDateTime currentTime) {
            return true;
        }

        /** Name contains "call" so BacktestEngine sets isCall = true. */
        @Override
        public String getName() {
            return "c1 squeeze";
        }

        /** Class simple name must contain "call" for BacktestEngine's isCall check. */
        @Override
        public String toString() {
            return "AlwaysTriggerCallStrategy";
        }
    }

    // =========================================================================
    // CandleRepository stubs
    // =========================================================================

    private static final class NoOpCandleRepository implements CandleRepository {
        @Override public List<Candle> load(String ticker, TimeFrame tf) { return List.of(); }
        @Override public List<Candle> loadRange(String ticker, TimeFrame tf, ZonedDateTime from, ZonedDateTime to) { return List.of(); }
        @Override public Optional<ZonedDateTime> lastTimestamp(String ticker, TimeFrame tf) { return Optional.empty(); }
        @Override public void upsert(String ticker, TimeFrame tf, List<Candle> candles) {}
        @Override public boolean hasLocalData(String ticker, TimeFrame tf) { return false; }
        @Override public Stream<Candle> stream(String ticker, TimeFrame tf) { return Stream.empty(); }
    }

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
