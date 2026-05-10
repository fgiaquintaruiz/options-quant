package com.fgiaquinta.optionsquant.candle.backfill;

import com.google.common.util.concurrent.RateLimiter;
import com.fgiaquinta.optionsquant.candle.CandleRepository;
import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.service.IbkrService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for TWS error handling in downloadChunkTwsOnly (T2, T6, T7).
 *
 * <p>T2: IbkrHistoricalDataException from ibkrService → no checkpoint written, returns 0
 * T6: Normal path with N candles → checkpoint written as COMPLETE_TWS, no exception
 * T7: N-candle result → upsert called AND checkpoint written COMPLETE_TWS, returns N
 */
@ExtendWith(MockitoExtension.class)
class HistoricalBackfillServiceTwsErrorTest {

    @Mock
    private CandleRepository repository;

    @Mock
    private BackfillCheckpoint checkpoint;

    @Mock
    private IbkrService ibkrService;

    @Mock
    private YfinanceHistoricalClient yfinanceClient;

    private final RateLimiter noopRateLimiter = RateLimiter.create(Double.MAX_VALUE);

    private static final ZonedDateTime BACKFILL_START =
            ZonedDateTime.of(2018, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    private static final Candle SAMPLE_CANDLE = new Candle(
            ZonedDateTime.of(2019, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC),
            100.0, 105.0, 99.0, 103.0, 50_000L);

    private HistoricalBackfillService service;

    @BeforeEach
    void setUp() {
        service = new HistoricalBackfillService(
                repository, checkpoint, ibkrService, noopRateLimiter,
                List.of("AAPL"), BACKFILL_START, yfinanceClient, List.of()
        );
    }

    // -------------------------------------------------------------------------
    // T2: IbkrHistoricalDataException(200) → no checkpoint written, continues, returns 0
    // -------------------------------------------------------------------------

    @Test
    void whenIbkrHistoricalDataExceptionThrown_noCheckpointWrittenAndContinues() throws Exception {
        ZonedDateTime future = ZonedDateTime.now(ZoneOffset.UTC).plusYears(5);

        // Only process MIN_15 for AAPL (skip all other timeframes)
        when(checkpoint.getLastDownloaded(eq("AAPL"), eq(TimeFrame.MIN_15)))
                .thenReturn(Optional.empty());
        when(checkpoint.getLastDownloaded(eq("AAPL"), argThat(tf -> tf != TimeFrame.MIN_15)))
                .thenReturn(Optional.of(future));

        // ibkrService throws IbkrHistoricalDataException (error code 200)
        when(ibkrService.downloadHistoricalData(eq("AAPL"), eq(TimeFrame.MIN_15), any()))
                .thenThrow(new IbkrHistoricalDataException(200, "No security definition has been found"));

        // Must NOT throw — error is absorbed
        assertDoesNotThrow(() -> service.run(new DefaultApplicationArguments("--backfill")));

        // Checkpoint MUST NOT be written for MIN_15 when IbkrHistoricalDataException is thrown
        verify(checkpoint, never()).save(eq("AAPL"), eq(TimeFrame.MIN_15), any(), any(BackfillStatus.class));
    }

    // -------------------------------------------------------------------------
    // T6: Happy path — N candles → checkpoint written as COMPLETE_TWS, no exception
    // -------------------------------------------------------------------------

    @Test
    void whenTwsDeliversNCandles_checkpointWrittenAsCompleteTws() throws Exception {
        ZonedDateTime future = ZonedDateTime.now(ZoneOffset.UTC).plusYears(5);

        when(checkpoint.getLastDownloaded(eq("AAPL"), eq(TimeFrame.MIN_15)))
                .thenReturn(Optional.empty());
        when(checkpoint.getLastDownloaded(eq("AAPL"), argThat(tf -> tf != TimeFrame.MIN_15)))
                .thenReturn(Optional.of(future));

        List<Candle> candles = List.of(SAMPLE_CANDLE);
        when(ibkrService.downloadHistoricalData(eq("AAPL"), eq(TimeFrame.MIN_15), any()))
                .thenReturn(candles);

        assertDoesNotThrow(() -> service.run(new DefaultApplicationArguments("--backfill")));

        // Checkpoint MUST be saved as COMPLETE_TWS
        verify(checkpoint, atLeastOnce())
                .save(eq("AAPL"), eq(TimeFrame.MIN_15), any(), eq(BackfillStatus.COMPLETE_TWS));
    }

    // -------------------------------------------------------------------------
    // T7: N-candle result → upsert called AND checkpoint written COMPLETE_TWS
    // -------------------------------------------------------------------------

    @Test
    void whenTwsDeliversNCandles_upsertCalledAndCheckpointSaved() throws Exception {
        ZonedDateTime future = ZonedDateTime.now(ZoneOffset.UTC).plusYears(5);

        when(checkpoint.getLastDownloaded(eq("AAPL"), eq(TimeFrame.HOUR_1)))
                .thenReturn(Optional.empty());
        when(checkpoint.getLastDownloaded(eq("AAPL"), argThat(tf -> tf != TimeFrame.HOUR_1)))
                .thenReturn(Optional.of(future));

        List<Candle> candles = List.of(SAMPLE_CANDLE, SAMPLE_CANDLE);
        when(ibkrService.downloadHistoricalData(eq("AAPL"), eq(TimeFrame.HOUR_1), any()))
                .thenReturn(candles);

        assertDoesNotThrow(() -> service.run(new DefaultApplicationArguments("--backfill")));

        // upsert must be called with the candles
        verify(repository, atLeastOnce()).upsert(eq("AAPL"), eq(TimeFrame.HOUR_1), eq(candles));
        // checkpoint must be saved as COMPLETE_TWS
        verify(checkpoint, atLeastOnce())
                .save(eq("AAPL"), eq(TimeFrame.HOUR_1), any(), eq(BackfillStatus.COMPLETE_TWS));
    }
}
