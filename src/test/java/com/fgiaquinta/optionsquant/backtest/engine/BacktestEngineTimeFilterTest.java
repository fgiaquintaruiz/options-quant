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

/**
 * TDD — RED/GREEN tests for global time-window filter (9:45–10:30 ET) and forced close (13:00 ET).
 *
 * <p>The opening-window rule restricts ALL strategy entries to a fixed 45-minute window each morning.
 * Any candle outside that window must be skipped — no trade generated regardless of pattern.
 * Open positions that haven't hit TP or SL by 13:00 ET are force-closed at that candle's price.
 *
 * <p>C4/P4 (9:30–9:35 ET) and C5/P5 (9:45–9:55 ET) keep their own internal sniper filters
 * unchanged — those are MORE restrictive and live inside the strategy, not the engine.
 */
class BacktestEngineTimeFilterTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");
    private static final String TICKER = "TEST";
    private static final LocalDate DATE = LocalDate.of(2023, 6, 15); // summer → EDT (UTC-4)

    @TempDir
    Path tempDir;

    private TickerMemory tickerMemory;

    @BeforeEach
    void setUp() {
        tickerMemory = new TickerMemory(tempDir.resolve("ticker-memory.json").toString());
    }

    // =========================================================================
    // T1 — Candle at 11:00 AM ET (outside window) → no trade generated
    //
    // Strategy always triggers. The engine must reject because 11:00 > 10:30.
    // Expected: empty trade list (no entry, no exit).
    // =========================================================================
    @Test
    void givenCandleAt11amET_whenProcessed_thenNoTradeGenerated() {
        // 11:00 AM ET in summer = 15:00 UTC
        ZonedDateTime candleTime = DATE.atTime(11, 0).atZone(NY);
        Map<TimeFrame, List<Candle>> data = buildSingleCandleData(candleTime, 100.0);

        BacktestConfig config = defaultConfig();
        BacktestReport report = runWith(new AlwaysTriggerCallStrategy(), config, data);

        assertThat(report.trades())
                .as("Candle at 11:00 AM ET is outside the 9:45-10:30 window — no trade should be opened")
                .isEmpty();
    }

    // =========================================================================
    // T2 — Candle at 10:00 AM ET (inside window) → trade generated
    //
    // 10:00 AM ET is between 9:45 and 10:30 → entry allowed.
    // =========================================================================
    @Test
    void givenCandleAt10amET_whenProcessed_thenTradeGenerated() {
        // Need enough base candles (40) for ATR calculation, then the signal candle
        ZonedDateTime signalTime = DATE.atTime(10, 0).atZone(NY);
        Map<TimeFrame, List<Candle>> data = buildBaseDataEndingAt(signalTime, 100.0);

        BacktestConfig config = defaultConfig();
        BacktestReport report = runWith(new AlwaysTriggerCallStrategy(), config, data);

        assertThat(report.trades())
                .as("Candle at 10:00 AM ET is inside the 9:45-10:30 window — trade should be opened")
                .isNotEmpty();
    }

    // =========================================================================
    // T3 — Forced close at 13:00 PM ET
    //
    // Trade opened inside window (10:00 AM ET). Neither TP nor SL hit.
    // At 13:00 PM ET a candle arrives → position must close with exitReason="FORCED".
    // =========================================================================
    @Test
    void givenOpenTradeAt10am_whenCandleAt1pmET_thenForcedCloseWithCorrectReason() {
        // Build data: 40 stable candles starting 9:30 (for ATR), signal at 10:00
        // then candles at 10:15, 10:30 ... 12:45, then at 13:00 ET
        ZonedDateTime signal = DATE.atTime(10, 0).atZone(NY);
        ZonedDateTime forceClose = DATE.atTime(13, 0).atZone(NY);

        Map<TimeFrame, List<Candle>> data = buildDataWithForcedCloseScenario(signal, forceClose, 100.0);

        BacktestConfig config = defaultConfig();
        BacktestReport report = runWith(new AlwaysTriggerCallStrategy(), config, data);

        assertThat(report.trades())
                .as("Trade opened at 10:00 AM should be force-closed at 13:00 PM")
                .isNotEmpty();

        assertThat(report.trades())
                .as("Exit reason must be FORCED, not EOS")
                .anyMatch(t -> "FORCED".equals(t.exitReason()));

        // Verify the FORCED trade's exit time is at 13:00 ET (or the candle at/after 13:00)
        List<TradeRecord> forcedTrades = report.trades().stream()
                .filter(t -> "FORCED".equals(t.exitReason()))
                .toList();

        assertThat(forcedTrades)
                .allSatisfy(t -> {
                    ZonedDateTime exitET = t.exitTime().withZoneSameInstant(NY);
                    assertThat(exitET.toLocalTime())
                            .as("FORCED exit must happen at or after 13:00 ET")
                            .isAfterOrEqualTo(LocalTime.of(13, 0));
                });
    }

    // =========================================================================
    // T4 — Timezone: UTC 14:45Z = 10:45 AM ET (EDT, summer) → inside window
    //
    // Validates correct UTC→ET conversion using America/New_York ZoneId.
    // Summer: UTC-4. 14:45Z = 10:45 AM ET (within 9:45–10:30 window).
    // Wait — 14:45 UTC - 4h = 10:45 ET → inside window.
    // =========================================================================
    @Test
    void givenTimestampUtc14h45_summerEdt_thenInsideEntryWindow() {
        // 2023-06-15T14:45:00Z = 10:45 AM ET (EDT, UTC-4) — INSIDE 9:45-10:30 window
        ZonedDateTime utcTime = ZonedDateTime.parse("2023-06-15T14:45:00Z");
        Map<TimeFrame, List<Candle>> data = buildBaseDataEndingAt(utcTime.withZoneSameInstant(NY), 100.0);

        BacktestConfig config = defaultConfig();
        BacktestReport report = runWith(new AlwaysTriggerCallStrategy(), config, data);

        assertThat(report.trades())
                .as("14:45Z = 10:45 AM ET (summer) is inside the 9:45-10:30 window — trade must be generated")
                .isNotEmpty();
    }

    // =========================================================================
    // T5 — Timezone: UTC 15:31Z = 11:31 AM ET (EDT, summer) → outside window
    //
    // 2023-06-15T15:31:00Z = 11:31 AM ET (UTC-4). 11:31 > 10:30 → rejected.
    // =========================================================================
    @Test
    void givenTimestampUtc15h31_summerEdt_thenOutsideEntryWindow() {
        // 2023-06-15T15:31:00Z = 11:31 AM ET (EDT, UTC-4) — OUTSIDE 9:45-10:30 window
        ZonedDateTime utcTime = ZonedDateTime.parse("2023-06-15T15:31:00Z");
        Map<TimeFrame, List<Candle>> data = buildSingleCandleData(utcTime.withZoneSameInstant(NY), 100.0);

        BacktestConfig config = defaultConfig();
        BacktestReport report = runWith(new AlwaysTriggerCallStrategy(), config, data);

        assertThat(report.trades())
                .as("15:31Z = 11:31 AM ET (summer) is outside the 9:45-10:30 window — no trade should be generated")
                .isEmpty();
    }

    // =========================================================================
    // T6 — Window boundary: exactly at 9:45 AM ET → inside (inclusive start)
    // =========================================================================
    @Test
    void givenCandleAtWindowStart_9h45ET_thenTradeGenerated() {
        ZonedDateTime candleTime = DATE.atTime(9, 45).atZone(NY);
        Map<TimeFrame, List<Candle>> data = buildBaseDataEndingAt(candleTime, 100.0);

        BacktestConfig config = defaultConfig();
        BacktestReport report = runWith(new AlwaysTriggerCallStrategy(), config, data);

        assertThat(report.trades())
                .as("9:45 AM ET is the inclusive start of the window — trade must be allowed")
                .isNotEmpty();
    }

    // =========================================================================
    // T7 — Window boundary: exactly at 10:30 AM ET → outside (exclusive end)
    // =========================================================================
    @Test
    void givenCandleAtWindowEnd_10h30ET_thenNoTradeGenerated() {
        ZonedDateTime candleTime = DATE.atTime(10, 30).atZone(NY);
        Map<TimeFrame, List<Candle>> data = buildSingleCandleData(candleTime, 100.0);

        BacktestConfig config = defaultConfig();
        BacktestReport report = runWith(new AlwaysTriggerCallStrategy(), config, data);

        assertThat(report.trades())
                .as("10:30 AM ET is the exclusive end of the window — trade must be rejected")
                .isEmpty();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private BacktestReport runWith(TradingStrategy strategy, BacktestConfig config,
                                   Map<TimeFrame, List<Candle>> tickerData) {
        CandleRepository stubRepo = new StubCandleRepository(TICKER, tickerData);
        BacktestEngine engine = new BacktestEngine(stubRepo, tickerMemory, 1, false, List.of(strategy));
        return engine.run(config, false, null);
    }

    /**
     * Default config with entry window 9:45-10:30 ET and forced close at 13:00 ET.
     * No slippage, no commission for clean behavioral testing.
     */
    private BacktestConfig defaultConfig() {
        return new BacktestConfig(
                List.of(TICKER),
                DATE,
                DATE.plusDays(1),
                50_000.0, 0.02,
                0.0,    // slippagePct = 0
                0.0,    // commission = 0
                10, TimeFrame.MIN_15, false, true,
                0.0, 0.0, null,
                LocalTime.of(9, 45),   // entryWindowStart
                LocalTime.of(10, 30),  // entryWindowEnd
                LocalTime.of(13, 0),   // forcedCloseTime
                null    // strategyFilter — run all strategies
        );
    }

    /**
     * Single candle at the given time — no base candles for ATR.
     * Use for "outside window" tests where the signal must be rejected BEFORE ATR is needed.
     */
    private Map<TimeFrame, List<Candle>> buildSingleCandleData(ZonedDateTime time, double price) {
        Map<TimeFrame, List<Candle>> data = new EnumMap<>(TimeFrame.class);
        for (TimeFrame tf : TimeFrame.values()) {
            List<Candle> candles = new ArrayList<>();
            candles.add(new Candle(time, price, price + 0.5, price - 0.5, price, 1000L));
            data.put(tf, candles);
        }
        return data;
    }

    /**
     * 40 stable candles ending at (and including) signalTime, spaced by each timeframe's duration.
     * Provides enough bars for the 14-period ATR required by RiskCalculator.
     * The last candle IS the signal candle (inside the window).
     */
    private Map<TimeFrame, List<Candle>> buildBaseDataEndingAt(ZonedDateTime signalTime, double price) {
        Map<TimeFrame, List<Candle>> data = new EnumMap<>(TimeFrame.class);
        for (TimeFrame tf : TimeFrame.values()) {
            data.put(tf, buildStableCandles(tf, signalTime, price, 40));
        }
        return data;
    }

    /**
     * Builds a scenario for the forced-close test:
     * - 40 stable candles ending at signalTime (entry)
     * - additional flat candles between signalTime and forceCloseTime (no TP/SL hit)
     * - one candle AT forceCloseTime
     */
    private Map<TimeFrame, List<Candle>> buildDataWithForcedCloseScenario(
            ZonedDateTime signalTime, ZonedDateTime forceCloseTime, double price) {

        Map<TimeFrame, List<Candle>> data = new EnumMap<>(TimeFrame.class);
        for (TimeFrame tf : TimeFrame.values()) {
            List<Candle> candles = buildStableCandles(tf, signalTime, price, 40);

            // Add candles between signalTime and forceCloseTime (flat, no TP/SL)
            // Use a small halfSpread so that TP and SL (typically ±0.33%/±0.67% from entry) are never touched
            double halfSpread = 0.01; // $0.01 — far from any TP/SL at $100 entry
            ZonedDateTime cursor = signalTime.plusMinutes(tfMinutes(tf));
            while (!cursor.isAfter(forceCloseTime)) {
                candles.add(new Candle(cursor, price, price + halfSpread, price - halfSpread, price, 1000L));
                cursor = cursor.plusMinutes(tfMinutes(tf));
            }
            data.put(tf, candles);
        }
        return data;
    }

    /**
     * Builds count stable candles, each {@code tfMinutes} apart, ending at (and including) endTime.
     */
    private List<Candle> buildStableCandles(TimeFrame tf, ZonedDateTime endTime, double price, int count) {
        List<Candle> candles = new ArrayList<>();
        long step = tfMinutes(tf);
        // Build backwards from endTime, then reverse so candles are in chronological order
        for (int i = count - 1; i >= 0; i--) {
            ZonedDateTime ts = endTime.minusMinutes(i * step);
            candles.add(new Candle(ts, price, price + 0.5, price - 0.5, price, 1000L));
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
    // Strategy stub — always triggers regardless of market conditions
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
