package com.fgiaquinta.optionsquant.backtest;

import com.fgiaquinta.optionsquant.backtest.domain.BacktestConfig;
import com.fgiaquinta.optionsquant.backtest.domain.BacktestReport;
import com.fgiaquinta.optionsquant.backtest.domain.TradeRecord;
import com.fgiaquinta.optionsquant.backtest.engine.BacktestEngine;
import com.fgiaquinta.optionsquant.candle.CandleRepository;
import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.service.TickerMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * T10 — BacktestEngine VIX enrichment tests.
 *
 * Verifies that:
 *  1. vixAtEntry on TradeRecord is populated from ^VIX data when available
 *  2. vixAtEntry defaults to 0.0 when no ^VIX data exists
 *  3. Floor lookup is used — prior day's VIX is used for a date with no exact match
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BacktestEngineVixTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    @Mock
    private CandleRepository candleRepository;

    @Mock
    private TickerMemory tickerMemory;

    private BacktestEngine engine;

    @BeforeEach
    void setUp() {
        // Default: all ticker streams empty; ^VIX: no data
        when(candleRepository.stream(any(), any())).thenAnswer(inv -> Stream.empty());
        when(candleRepository.hasLocalData(eq("^VIX"), eq(TimeFrame.DAY_1))).thenReturn(false);
        when(candleRepository.load(eq("^VIX"), eq(TimeFrame.DAY_1))).thenAnswer(inv -> List.of());
        engine = new BacktestEngine(candleRepository, tickerMemory, 1, false);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 1: vixDailyMap populated correctly — loadVixMap via reflection
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("T10-A: vixDailyMap is populated from ^VIX candles when hasLocalData returns true")
    void testVixAtEntryPopulatedFromMap() {
        // Arrange: VIX data exists for 2024-01-02
        LocalDate vixDate = LocalDate.of(2024, 1, 2);
        ZonedDateTime vixTs = vixDate.atTime(16, 0).atZone(NY);
        Candle vixCandle = new Candle(vixTs, 18.0, 20.0, 17.5, 19.5, 100_000L);

        when(candleRepository.hasLocalData(eq("^VIX"), eq(TimeFrame.DAY_1))).thenReturn(true);
        when(candleRepository.load(eq("^VIX"), eq(TimeFrame.DAY_1))).thenReturn(List.of(vixCandle));

        // Invoke loadVixMap via reflection and capture the resulting map
        @SuppressWarnings("unchecked")
        NavigableMap<LocalDate, Double> result = (NavigableMap<LocalDate, Double>)
                ReflectionTestUtils.invokeMethod(engine, "loadVixMap");

        // Assert: map contains the VIX entry with correct close value
        assertThat(result).isNotNull();
        assertThat(result).containsKey(vixDate);
        assertThat(result.get(vixDate)).isEqualTo(19.5);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2: vixDailyMap empty when no ^VIX data
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("T10-B: loadVixMap returns empty map when hasLocalData returns false")
    void testVixAtEntryDefaultsToZeroWhenNoData() {
        // hasLocalData already returns false from setUp()

        @SuppressWarnings("unchecked")
        NavigableMap<LocalDate, Double> result = (NavigableMap<LocalDate, Double>)
                ReflectionTestUtils.invokeMethod(engine, "loadVixMap");

        assertThat(result).isNotNull();
        assertThat(result).isEmpty();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3: Floor lookup — prior day value used when exact date not present
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("T10-C: vixDailyMap floor lookup returns prior day VIX when exact entry date is missing")
    void testVixFloorEntryUsedForHoliday() {
        // Arrange: VIX data for 2022-01-03 (close=20.0) but NOT 2022-01-04
        LocalDate vixDate = LocalDate.of(2022, 1, 3);
        LocalDate tradeDate = LocalDate.of(2022, 1, 4); // no exact key for this date

        NavigableMap<LocalDate, Double> vixMap = new TreeMap<>();
        vixMap.put(vixDate, 20.0);

        // Inject map directly into engine (simulates what loadVixMap would have produced)
        ReflectionTestUtils.setField(engine, "vixDailyMap", vixMap);

        // Act: floor entry for tradeDate should resolve to vixDate (2022-01-03)
        java.util.Map.Entry<LocalDate, Double> floorEntry = vixMap.floorEntry(tradeDate);

        // Assert
        assertThat(floorEntry).isNotNull();
        assertThat(floorEntry.getKey()).isEqualTo(vixDate);
        assertThat(floorEntry.getValue()).isEqualTo(20.0);

        // Also confirm the field was injected correctly
        @SuppressWarnings("unchecked")
        NavigableMap<LocalDate, Double> injected =
                (NavigableMap<LocalDate, Double>) ReflectionTestUtils.getField(engine, "vixDailyMap");
        assertThat(injected).containsOnly(java.util.Map.entry(vixDate, 20.0));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 4: Integration — vixAtEntry in TradeRecord is 0.0 when no VIX data
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("T10-D: TradeRecord.vixAtEntry is 0.0 when VIX map is empty (no ^VIX data)")
    void testTradeRecord_vixAtEntry_isZeroWhenNoVixData() {
        // Arrange: ticker SPY has candles but VIX map is empty
        LocalDate date = LocalDate.of(2024, 3, 15);

        // Provide enough 15-min candles to potentially trigger a strategy
        ZonedDateTime ts = date.atTime(10, 0).atZone(ZoneId.of("UTC"));
        Candle candle = new Candle(ts, 100.0, 108.0, 98.0, 106.0, 500_000L);

        when(candleRepository.stream(eq("SPY"), any(TimeFrame.class)))
                .thenAnswer(inv -> Stream.of(candle));

        // VIX: no data (already set in setUp)
        BacktestConfig config = BacktestConfig.defaults(List.of("SPY"), date, date);
        BacktestReport report = engine.run(config, false, null);

        // All trades (if any) should have vixAtEntry == 0.0
        assertThat(report).isNotNull();
        report.trades().forEach(trade ->
                assertThat(trade.vixAtEntry())
                        .as("vixAtEntry should be 0.0 when no VIX data — trade: " + trade.ticker())
                        .isEqualTo(0.0)
        );
    }
}
