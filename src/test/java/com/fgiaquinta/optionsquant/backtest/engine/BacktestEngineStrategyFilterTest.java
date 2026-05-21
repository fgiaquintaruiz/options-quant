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
 * TDD — RED/GREEN tests for strategyFilter in BacktestConfig.
 *
 * <p>When strategyFilter is non-null, BacktestEngine must skip any strategy whose
 * {@link com.fgiaquinta.optionsquant.strategy.TradingStrategy#getCode()} is NOT in the filter list.
 * When null, all strategies run as before.
 */
class BacktestEngineStrategyFilterTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");
    private static final String TICKER = "TEST";
    private static final LocalDate DATE = LocalDate.of(2023, 6, 15);

    @TempDir
    Path tempDir;

    private TickerMemory tickerMemory;

    @BeforeEach
    void setUp() {
        tickerMemory = new TickerMemory(tempDir.resolve("ticker-memory.json").toString());
    }

    // =========================================================================
    // T1 — strategyFilter = ["strategya"] → only StrategyA runs, StrategyB is skipped
    //
    // Two strategies: StrategyA always triggers, StrategyB always triggers.
    // With filter = ["strategya"] (getCode() value), only StrategyA trades should appear.
    // =========================================================================
    @Test
    void runBacktest_withStrategyFilter_onlyRunsFilteredStrategies() {
        ZonedDateTime signalTime = DATE.atTime(10, 0).atZone(NY);
        Map<TimeFrame, List<Candle>> data = buildBaseDataEndingAt(signalTime, 100.0);

        BacktestConfig config = configWithFilter(List.of("strategya"));

        CandleRepository stubRepo = new StubCandleRepository(TICKER, data);
        BacktestEngine engine = new BacktestEngine(
                stubRepo, tickerMemory, 1, false,
                List.of(new StrategyA(), new StrategyB()));
        BacktestReport report = engine.run(config, false, null);

        assertThat(report.trades())
                .as("With strategyFilter=[StrategyA], only StrategyA trades should appear")
                .isNotEmpty();

        assertThat(report.trades())
                .as("StrategyB must be excluded by the filter")
                .noneMatch(t -> "StrategyB".equals(t.strategy()) || t.strategy().contains("B"));

        assertThat(report.trades())
                .as("All trades must come from StrategyA")
                .allMatch(t -> t.strategy().equals("StrategyA"));
    }

    // =========================================================================
    // T2 — strategyFilter = null → all strategies run (backward-compatible)
    //
    // Both strategies run and both contribute trades.
    // =========================================================================
    @Test
    void runBacktest_withNullStrategyFilter_runsAllStrategies() {
        ZonedDateTime signalTime = DATE.atTime(10, 0).atZone(NY);
        Map<TimeFrame, List<Candle>> data = buildBaseDataEndingAt(signalTime, 100.0);

        BacktestConfig config = configWithFilter(null);

        CandleRepository stubRepo = new StubCandleRepository(TICKER, data);
        BacktestEngine engine = new BacktestEngine(
                stubRepo, tickerMemory, 1, false,
                List.of(new StrategyA(), new StrategyB()));
        BacktestReport report = engine.run(config, false, null);

        assertThat(report.trades())
                .as("With strategyFilter=null, all strategies must run")
                .isNotEmpty();

        List<String> strategyNames = report.trades().stream()
                .map(TradeRecord::strategy)
                .distinct()
                .toList();

        assertThat(strategyNames)
                .as("Both StrategyA and StrategyB must have generated trades")
                .containsExactlyInAnyOrder("StrategyA", "StrategyB");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private BacktestConfig configWithFilter(List<String> strategyFilter) {
        return new BacktestConfig(
                List.of(TICKER),
                DATE,
                DATE.plusDays(1),
                50_000.0, 0.02,
                0.0,   // slippagePct = 0
                0.0,   // commission = 0
                10, TimeFrame.MIN_15, false, true,
                0.0, 0.0, null,
                LocalTime.of(9, 45),
                LocalTime.of(10, 30),
                LocalTime.of(13, 0),
                strategyFilter
        );
    }

    private Map<TimeFrame, List<Candle>> buildBaseDataEndingAt(ZonedDateTime signalTime, double price) {
        Map<TimeFrame, List<Candle>> data = new EnumMap<>(TimeFrame.class);
        for (TimeFrame tf : TimeFrame.values()) {
            data.put(tf, buildStableCandles(tf, signalTime, price, 40));
        }
        return data;
    }

    private List<Candle> buildStableCandles(TimeFrame tf, ZonedDateTime endTime, double price, int count) {
        List<Candle> candles = new ArrayList<>();
        long step = tfMinutes(tf);
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
    // Strategy stubs — each triggers always and has a unique name / class name
    // =========================================================================

    private static final class StrategyA implements TradingStrategy {
        @Override
        public boolean isCall() { return true; }
        @Override
        public boolean isTriggered(String ticker, StrategyData data, ZonedDateTime currentTime) {
            return true;
        }
        @Override
        public String getName() { return "StrategyA"; }
        @Override
        public String getCode() { return "strategya"; }
    }

    private static final class StrategyB implements TradingStrategy {
        @Override
        public boolean isCall() { return true; }
        @Override
        public boolean isTriggered(String ticker, StrategyData data, ZonedDateTime currentTime) {
            return true;
        }
        @Override
        public String getName() { return "StrategyB"; }
        @Override
        public String getCode() { return "strategyb"; }
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
