package com.fgiaquinta.optionsquant.candle.backfill;

import com.google.common.util.concurrent.RateLimiter;
import com.fgiaquinta.optionsquant.candle.CandleRepository;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.service.IbkrService;
import com.fgiaquinta.optionsquant.service.TickerService;
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

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;

/**
 * Tests for resolveTickerList SKIPPED_PERMANENT exclusion logic (Phase 6).
 *
 * <p>Verifies that tickers with ALL timeframes permanently skipped are excluded
 * from backfill by default, and included when --retry-permanent-skips is set.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HistoricalBackfillServiceSkipPermanentFilterTest {

    @Mock
    private CandleRepository repository;

    @Mock
    private BackfillCheckpoint checkpoint;

    @Mock
    private IbkrService ibkrService;

    @Mock
    private YfinanceHistoricalClient yfinanceClient;

    @Mock
    private TickerService tickerService;

    private final RateLimiter noopRateLimiter = RateLimiter.create(Double.MAX_VALUE);

    private static final ZonedDateTime BACKFILL_START =
            ZonedDateTime.of(2018, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    private static final ZonedDateTime FUTURE =
            ZonedDateTime.now(ZoneOffset.UTC).plusYears(10);

    @BeforeEach
    void setUp() {
        // Default: no ticker is permanently skipped
        when(checkpoint.isAllTimeframesPermanentlySkipped(anyString())).thenReturn(false);
    }

    // -------------------------------------------------------------------------
    // T1: default mode — ticker with ALL timeframes SKIPPED_PERMANENT is excluded
    // -------------------------------------------------------------------------

    @Test
    void resolveTickerList_defaultMode_excludesSkippedPermanentTickers() throws Exception {
        // DELISTED has all timeframes permanently skipped
        when(checkpoint.isAllTimeframesPermanentlySkipped("DELISTED")).thenReturn(true);
        // AAPL is normal
        when(checkpoint.isAllTimeframesPermanentlySkipped("AAPL")).thenReturn(false);
        // All already-done
        when(checkpoint.getLastDownloaded(anyString(), any(), any(ChunkOrigin.class))).thenReturn(Optional.of(FUTURE));

        HistoricalBackfillService service = new HistoricalBackfillService(
                repository, checkpoint, ibkrService, noopRateLimiter,
                List.of("AAPL", "DELISTED"), BACKFILL_START, yfinanceClient, List.of(),
                List.of(), tickerService
        );

        service.run(new DefaultApplicationArguments("--backfill"));

        // AAPL must be processed
        verify(checkpoint, atLeastOnce()).getLastDownloaded(eq("AAPL"), any(), any(ChunkOrigin.class));
        // DELISTED must NOT be processed (excluded from ticker list)
        verify(checkpoint, never()).getLastDownloaded(eq("DELISTED"), any(), any(ChunkOrigin.class));
    }

    // -------------------------------------------------------------------------
    // T2: --retry-permanent-skips flag — SKIPPED_PERMANENT tickers ARE included
    // -------------------------------------------------------------------------

    @Test
    void resolveTickerList_withRetryPermanentSkipsFlag_includesSkippedPermanentTickers() throws Exception {
        // DELISTED has all timeframes permanently skipped
        when(checkpoint.isAllTimeframesPermanentlySkipped("DELISTED")).thenReturn(true);
        // All already-done
        when(checkpoint.getLastDownloaded(anyString(), any(), any(ChunkOrigin.class))).thenReturn(Optional.of(FUTURE));

        HistoricalBackfillService service = new HistoricalBackfillService(
                repository, checkpoint, ibkrService, noopRateLimiter,
                List.of("AAPL", "DELISTED"), BACKFILL_START, yfinanceClient, List.of(),
                List.of(), tickerService
        );

        service.run(new DefaultApplicationArguments("--backfill", "--retry-permanent-skips"));

        // With --retry-permanent-skips, DELISTED MUST be included and processed
        verify(checkpoint, atLeastOnce()).getLastDownloaded(eq("DELISTED"), any(), any(ChunkOrigin.class));
    }

    // -------------------------------------------------------------------------
    // T3: partially skipped ticker (only some timeframes SKIPPED_PERMANENT) is NOT excluded
    // -------------------------------------------------------------------------

    @Test
    void resolveTickerList_partiallySkippedTicker_isNotExcluded() throws Exception {
        // PARTIAL is not all-timeframes-skipped
        when(checkpoint.isAllTimeframesPermanentlySkipped("PARTIAL")).thenReturn(false);
        // All already-done
        when(checkpoint.getLastDownloaded(anyString(), any(), any(ChunkOrigin.class))).thenReturn(Optional.of(FUTURE));

        HistoricalBackfillService service = new HistoricalBackfillService(
                repository, checkpoint, ibkrService, noopRateLimiter,
                List.of("PARTIAL"), BACKFILL_START, yfinanceClient, List.of(),
                List.of(), tickerService
        );

        service.run(new DefaultApplicationArguments("--backfill"));

        // PARTIAL must still be processed — it has at least one active timeframe
        verify(checkpoint, atLeastOnce()).getLastDownloaded(eq("PARTIAL"), any(), any(ChunkOrigin.class));
    }
}
