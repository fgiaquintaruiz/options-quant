package com.fgiaquinta.optionsquant.candle.csv;

import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.service.CandleCsvService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for CsvCandleRepository.
 * Uses Mockito to verify delegation to CandleCsvService without touching the filesystem.
 */
@ExtendWith(MockitoExtension.class)
class CsvCandleRepositoryTest {

    @Mock
    private CandleCsvService csvService;

    private CsvCandleRepository repo;

    private static final String TICKER = "TSLA";
    private static final TimeFrame TF = TimeFrame.HOUR_1;

    @BeforeEach
    void setUp() {
        repo = new CsvCandleRepository(csvService);
    }

    // -------------------------------------------------------------------------
    // load → delegates to csvService.loadFromCsv
    // -------------------------------------------------------------------------

    @Test
    void load_delegatesToLoadFromCsv() {
        List<Candle> expected = makeCandles(2);
        when(csvService.loadFromCsv(TICKER, TF)).thenReturn(expected);

        List<Candle> result = repo.load(TICKER, TF);

        assertEquals(expected, result);
        verify(csvService).loadFromCsv(TICKER, TF);
    }

    // -------------------------------------------------------------------------
    // upsert → delegates to csvService.saveToCsv
    // -------------------------------------------------------------------------

    @Test
    void upsert_delegatesToSaveToCsv() {
        List<Candle> candles = makeCandles(3);

        repo.upsert(TICKER, TF, candles);

        verify(csvService).saveToCsv(TICKER, TF, candles);
    }

    // -------------------------------------------------------------------------
    // hasLocalData → delegates to csvService.hasLocalData
    // -------------------------------------------------------------------------

    @Test
    void hasLocalData_delegatesCorrectly() {
        when(csvService.hasLocalData(TICKER, TF)).thenReturn(true);

        assertTrue(repo.hasLocalData(TICKER, TF));
        verify(csvService).hasLocalData(TICKER, TF);
    }

    // -------------------------------------------------------------------------
    // lastTimestamp → returns max from loaded candles
    // -------------------------------------------------------------------------

    @Test
    void lastTimestamp_returnsMaxTimestamp_fromLoadedCandles() {
        ZonedDateTime t0 = ZonedDateTime.of(2024, 1, 2, 9, 30, 0, 0, ZoneOffset.UTC);
        ZonedDateTime t1 = t0.plusHours(1);
        ZonedDateTime t2 = t0.plusHours(2); // max
        List<Candle> candles = List.of(
                new Candle(t0, 100.0, 105.0, 99.0, 103.0, 1000L),
                new Candle(t2, 200.0, 210.0, 198.0, 205.0, 2000L),
                new Candle(t1, 150.0, 155.0, 148.0, 152.0, 1500L)
        );
        when(csvService.loadFromCsv(TICKER, TF)).thenReturn(candles);

        Optional<ZonedDateTime> result = repo.lastTimestamp(TICKER, TF);

        assertTrue(result.isPresent(), "lastTimestamp must be present");
        assertEquals(t2.toEpochSecond(), result.get().toEpochSecond(),
                "lastTimestamp must return the maximum timestamp");
    }

    // -------------------------------------------------------------------------
    // loadRange → filters [from, to) correctly
    // -------------------------------------------------------------------------

    @Test
    void loadRange_filtersCorrectly_includeFromExcludeTo() {
        ZonedDateTime base = ZonedDateTime.of(2024, 1, 2, 9, 30, 0, 0, ZoneOffset.UTC);
        ZonedDateTime t0 = base;
        ZonedDateTime t1 = base.plusHours(1);
        ZonedDateTime t2 = base.plusHours(2);
        ZonedDateTime t3 = base.plusHours(3);
        List<Candle> all = List.of(
                new Candle(t0, 100.0, 105.0, 99.0,  103.0, 1000L),
                new Candle(t1, 103.0, 107.0, 102.0, 106.0, 1500L),
                new Candle(t2, 106.0, 108.0, 105.0, 107.0, 1200L),
                new Candle(t3, 107.0, 110.0, 106.0, 109.0, 1300L)
        );
        when(csvService.loadFromCsv(TICKER, TF)).thenReturn(all);

        // [t1, t3) → t1 and t2
        List<Candle> result = repo.loadRange(TICKER, TF, t1, t3);

        assertEquals(2, result.size(), "loadRange must return 2 candles in [t1, t3)");
        assertEquals(t1, result.get(0).timestamp(), "First must be t1 (inclusive)");
        assertEquals(t2, result.get(1).timestamp(), "Second must be t2");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static List<Candle> makeCandles(int count) {
        ZonedDateTime base = ZonedDateTime.of(2024, 1, 2, 9, 30, 0, 0, ZoneOffset.UTC);
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> new Candle(
                        base.plusHours(i),
                        100.0 + i, 110.0 + i, 90.0 + i, 105.0 + i, 1000L + i))
                .collect(Collectors.toList());
    }
}
