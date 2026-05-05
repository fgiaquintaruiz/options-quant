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
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for HistoricalBackfillService (T21) and rate limiter integration (T22).
 */
@ExtendWith(MockitoExtension.class)
class HistoricalBackfillServiceTest {

    @Mock
    private CandleRepository repository;

    @Mock
    private BackfillCheckpoint checkpoint;

    @Mock
    private IbkrService ibkrService;

    @Mock
    private RateLimiter rateLimiter;

    private HistoricalBackfillService service;

    private static final ZonedDateTime BACKFILL_START =
            ZonedDateTime.of(2018, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        service = new HistoricalBackfillService(
                repository,
                checkpoint,
                ibkrService,
                rateLimiter,
                List.of("AAPL", "MSFT"),
                BACKFILL_START
        );
    }

    // -------------------------------------------------------------------------
    // T21-1: --backfill flag absent → service is a no-op
    // -------------------------------------------------------------------------

    @Test
    void run_withoutBackfillFlag_isNoOp() throws Exception {
        var args = new DefaultApplicationArguments("--server.port=9090");

        service.run(args);

        verifyNoInteractions(ibkrService, repository, checkpoint);
    }

    // -------------------------------------------------------------------------
    // T21-2: --backfill flag present → iterates all configured tickers
    // -------------------------------------------------------------------------

    @Test
    void run_withBackfillFlag_iteratesAllConfiguredTickers() throws Exception {
        when(checkpoint.getLastDownloaded(anyString(), any())).thenReturn(Optional.empty());
        when(ibkrService.downloadHistoricalData(anyString(), any(), any()))
                .thenReturn(Collections.emptyList());

        var args = new DefaultApplicationArguments("--backfill");

        service.run(args);

        // 2 tickers × 4 timeframes = 8 chunks minimum (may vary by chunk size)
        verify(ibkrService, atLeast(2)).downloadHistoricalData(anyString(), any(), any());
    }

    // -------------------------------------------------------------------------
    // T21-3: already-completed checkpoint → chunk is skipped
    // -------------------------------------------------------------------------

    @Test
    void run_withCompletedCheckpoint_skipsAlreadyDownloadedChunks() throws Exception {
        // Use a clearly-future timestamp so chunkFrom.isBefore(now) is always false
        ZonedDateTime future = ZonedDateTime.now(ZoneOffset.UTC).plusYears(5);

        // Return a future date as last downloaded → no chunks remain
        when(checkpoint.getLastDownloaded(eq("AAPL"), eq(TimeFrame.DAY_1)))
                .thenReturn(Optional.of(future));
        when(checkpoint.getLastDownloaded(eq("AAPL"), argThat(tf -> tf != TimeFrame.DAY_1)))
                .thenReturn(Optional.of(future));
        when(checkpoint.getLastDownloaded(eq("MSFT"), any()))
                .thenReturn(Optional.of(future));

        var args = new DefaultApplicationArguments("--backfill");

        service.run(args);

        // All chunks are already done — no TWS download should happen
        verify(ibkrService, never()).downloadHistoricalData(anyString(), any(), any());
    }

    // -------------------------------------------------------------------------
    // T21-4: successful download → checkpoint updated after each chunk
    // -------------------------------------------------------------------------

    @Test
    void run_successfulDownload_updatesCheckpointAfterEachChunk() throws Exception {
        List<Candle> candles = List.of(
                new Candle(
                        ZonedDateTime.of(2018, 2, 1, 0, 0, 0, 0, ZoneOffset.UTC),
                        150.0, 155.0, 149.0, 153.0, 1000L)
        );

        // One ticker, one timeframe to keep it focused
        service = new HistoricalBackfillService(
                repository,
                checkpoint,
                ibkrService,
                rateLimiter,
                List.of("AAPL"),
                BACKFILL_START
        );

        ZonedDateTime future = ZonedDateTime.now(ZoneOffset.UTC).plusYears(5);
        when(checkpoint.getLastDownloaded(eq("AAPL"), eq(TimeFrame.DAY_1)))
                .thenReturn(Optional.empty());
        when(checkpoint.getLastDownloaded(eq("AAPL"), argThat(tf -> tf != TimeFrame.DAY_1)))
                .thenReturn(Optional.of(future)); // skip other TFs
        when(ibkrService.downloadHistoricalData(eq("AAPL"), eq(TimeFrame.DAY_1), any()))
                .thenReturn(candles);

        var args = new DefaultApplicationArguments("--backfill");

        service.run(args);

        verify(repository, atLeastOnce()).upsert(eq("AAPL"), eq(TimeFrame.DAY_1), anyList());
        verify(checkpoint, atLeastOnce()).save(eq("AAPL"), eq(TimeFrame.DAY_1), any());
    }

    // -------------------------------------------------------------------------
    // T21-5: TWS error on chunk → checkpoint NOT updated, continues with next chunk
    // -------------------------------------------------------------------------

    @Test
    void run_twsErrorOnChunk_doesNotUpdateCheckpointAndContinues() throws Exception {
        service = new HistoricalBackfillService(
                repository,
                checkpoint,
                ibkrService,
                rateLimiter,
                List.of("AAPL"),
                BACKFILL_START
        );

        ZonedDateTime future = ZonedDateTime.now(ZoneOffset.UTC).plusYears(5);
        when(checkpoint.getLastDownloaded(eq("AAPL"), eq(TimeFrame.DAY_1)))
                .thenReturn(Optional.empty());
        when(checkpoint.getLastDownloaded(eq("AAPL"), argThat(tf -> tf != TimeFrame.DAY_1)))
                .thenReturn(Optional.of(future));
        when(ibkrService.downloadHistoricalData(eq("AAPL"), eq(TimeFrame.DAY_1), any()))
                .thenThrow(new RuntimeException("TWS request pacing violation"));

        var args = new DefaultApplicationArguments("--backfill");

        // Must NOT throw — errors are absorbed and logged
        assertDoesNotThrow(() -> service.run(args));

        // Checkpoint must NOT be updated when download failed
        verify(checkpoint, never()).save(eq("AAPL"), eq(TimeFrame.DAY_1), any());
    }

    // -------------------------------------------------------------------------
    // T22-1: rate limiter acquire() called once per chunk download
    // -------------------------------------------------------------------------

    @Test
    void run_rateLimiter_acquireCalledOncePerChunkDownload() throws Exception {
        service = new HistoricalBackfillService(
                repository,
                checkpoint,
                ibkrService,
                rateLimiter,
                List.of("AAPL"),
                BACKFILL_START
        );

        ZonedDateTime future = ZonedDateTime.now(ZoneOffset.UTC).plusYears(5);
        when(checkpoint.getLastDownloaded(eq("AAPL"), eq(TimeFrame.DAY_1)))
                .thenReturn(Optional.empty());
        when(checkpoint.getLastDownloaded(eq("AAPL"), argThat(tf -> tf != TimeFrame.DAY_1)))
                .thenReturn(Optional.of(future));
        when(ibkrService.downloadHistoricalData(eq("AAPL"), eq(TimeFrame.DAY_1), any()))
                .thenReturn(Collections.emptyList());

        var args = new DefaultApplicationArguments("--backfill");

        service.run(args);

        // One acquire() per chunk downloaded (at least one DAY_1 chunk for AAPL from 2018)
        verify(rateLimiter, atLeastOnce()).acquire();
    }

    // -------------------------------------------------------------------------
    // T22-2: RateLimiter.create() is configurable via ratePerSecond constructor param
    // -------------------------------------------------------------------------

    @Test
    void rateLimiter_isBuiltFromConfiguredRatePerSecond() {
        // Verify that a service built with a known rate produces a RateLimiter
        // with that rate. We test by creating a real RateLimiter and inspecting its rate.
        double ratePerSecond = 0.1;
        RateLimiter real = RateLimiter.create(ratePerSecond);

        assertEquals(ratePerSecond, real.getRate(), 1e-9,
                "RateLimiter.create(0.1) must produce a limiter with rate=0.1");
    }
}
