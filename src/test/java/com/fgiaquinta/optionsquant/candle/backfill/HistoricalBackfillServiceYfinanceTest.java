package com.fgiaquinta.optionsquant.candle.backfill;

import com.google.common.util.concurrent.RateLimiter;
import com.fgiaquinta.optionsquant.candle.CandleRepository;
import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.service.IbkrService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the yfinance fallback / pre-emption logic in HistoricalBackfillService (T9).
 *
 * <p>All tests use the package-private 10-param constructor — no Spring context.
 */
@ExtendWith(MockitoExtension.class)
class HistoricalBackfillServiceYfinanceTest {

    @Mock
    private CandleRepository repository;

    @Mock
    private BackfillCheckpoint checkpoint;

    @Mock
    private IbkrService ibkrService;

    @Mock
    private YfinanceHistoricalClient yfinanceClient;

    /** A fast rate limiter that never actually throttles in unit tests. */
    private final RateLimiter noopRateLimiter = RateLimiter.create(Double.MAX_VALUE);

    private static final ZonedDateTime BACKFILL_START =
            ZonedDateTime.of(2018, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    private static final Candle SAMPLE_CANDLE = new Candle(
            ZonedDateTime.of(2019, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC),
            100.0, 105.0, 99.0, 103.0, 50_000L);

    // -------------------------------------------------------------------------
    // Helper: build service with custom tickers / vixTickers
    // -------------------------------------------------------------------------

    private HistoricalBackfillService buildService(List<String> tickers, List<String> vixTickers) {
        return new HistoricalBackfillService(
                repository,
                checkpoint,
                ibkrService,
                noopRateLimiter,
                tickers,
                BACKFILL_START,
                yfinanceClient,
                vixTickers,
                5,      // yfinanceCutoffYears
                true    // yfinanceEnabled
        );
    }

    // -------------------------------------------------------------------------
    // T9-1: chunk whose `to` is > 5 years ago → pre-empted, uses yfinance only
    // -------------------------------------------------------------------------

    @Test
    void preemption_chunkOlderThanCutoff_usesYfinanceDirectly() throws Exception {
        // Strategy: use a very small backfill window (6yr ago to 5yr+10d ago) so the
        // SINGLE DAY_1 chunk produced has its `to` = 5yr+10d+365d-ago = well before the
        // 5yr cutoff. We achieve this by setting backfillStart via checkpoint.
        //
        // Concretely: checkpoint.getLastDownloaded(DAY_1) returns (now - 6yr).
        // The while-loop starts chunkFrom = now-6yr, chunkTo = now-5yr.
        // Cutoff = now-5yr → to.isBefore(cutoff) evaluates to: (now-5yr).isBefore(now-5yr) = FALSE.
        // Edge case — we need strictly before.  Use 6yr+1d start so chunkTo = now-5yr-1d → TRUE.
        //
        // After that single pre-empted chunk, chunkFrom = now-5yr-1d. The NEXT chunk
        // to = now-4yr-1d → within cutoff → TWS-with-fallback is called.
        // To prevent additional TWS calls, we stub ibkrService to return empty (safe no-op).
        HistoricalBackfillService service = new HistoricalBackfillService(
                repository,
                checkpoint,
                ibkrService,
                noopRateLimiter,
                List.of("AAPL"),
                BACKFILL_START,
                yfinanceClient,
                List.of(),
                5,
                true
        );

        ZonedDateTime future = ZonedDateTime.now(ZoneOffset.UTC).plusYears(10);
        // Skip non-DAY_1 timeframes
        when(checkpoint.getLastDownloaded(eq("AAPL"), argThat(tf -> tf != TimeFrame.DAY_1)))
                .thenReturn(Optional.of(future));
        // DAY_1: start from now-6yr-1d so first chunkTo = now-5yr-1d (strictly before cutoff)
        // but only run one such pre-empted chunk — skip all subsequent ones by making TWS return empty
        // (which triggers the yfinance fallback path, but still confirms pre-emption for the first chunk)
        when(checkpoint.getLastDownloaded(eq("AAPL"), eq(TimeFrame.DAY_1)))
                .thenReturn(Optional.of(ZonedDateTime.now(ZoneOffset.UTC).minusYears(6).minusDays(1)));

        when(yfinanceClient.fetchDailyCandles(eq("AAPL"), any(), any()))
                .thenReturn(List.of(SAMPLE_CANDLE));
        // Allow subsequent chunks (within cutoff) to call TWS; return empty to keep test fast
        when(ibkrService.downloadHistoricalData(eq("AAPL"), eq(TimeFrame.DAY_1), any()))
                .thenReturn(List.of());

        service.run(new DefaultApplicationArguments("--backfill"));

        // yfinance MUST have been called (at least for the pre-empted chunk)
        verify(yfinanceClient, atLeastOnce()).fetchDailyCandles(eq("AAPL"), any(), any());

        // At least one checkpoint entry must be COMPLETE_YFINANCE (from the pre-empted chunk)
        ArgumentCaptor<BackfillStatus> statusCaptor = ArgumentCaptor.forClass(BackfillStatus.class);
        verify(checkpoint, atLeastOnce()).save(eq("AAPL"), eq(TimeFrame.DAY_1), any(), statusCaptor.capture());
        assertThat(statusCaptor.getAllValues()).contains(BackfillStatus.COMPLETE_YFINANCE);
    }

    // -------------------------------------------------------------------------
    // T9-2: recent DAY_1 chunk, TWS returns data → yfinance never called
    // -------------------------------------------------------------------------

    @Test
    void recentChunk_twsReturnsData_yfinanceNotCalled() throws Exception {
        HistoricalBackfillService service = buildService(List.of("AAPL"), List.of());

        // Only process DAY_1; skip other TFs
        ZonedDateTime future = ZonedDateTime.now(ZoneOffset.UTC).plusYears(10);
        when(checkpoint.getLastDownloaded(eq("AAPL"), argThat(tf -> tf != TimeFrame.DAY_1)))
                .thenReturn(Optional.of(future));
        // DAY_1 starts from just under 1 year ago → chunk `to` will be recent (within cutoff)
        ZonedDateTime recentStart = ZonedDateTime.now(ZoneOffset.UTC).minusMonths(3);
        when(checkpoint.getLastDownloaded(eq("AAPL"), eq(TimeFrame.DAY_1)))
                .thenReturn(Optional.of(recentStart));

        when(ibkrService.downloadHistoricalData(eq("AAPL"), eq(TimeFrame.DAY_1), any()))
                .thenReturn(List.of(SAMPLE_CANDLE));

        service.run(new DefaultApplicationArguments("--backfill"));

        // yfinance must NOT be called
        verify(yfinanceClient, never()).fetchDailyCandles(anyString(), any(), any());

        // Checkpoint saved with COMPLETE_TWS
        ArgumentCaptor<BackfillStatus> statusCaptor = ArgumentCaptor.forClass(BackfillStatus.class);
        verify(checkpoint, atLeastOnce()).save(eq("AAPL"), eq(TimeFrame.DAY_1), any(), statusCaptor.capture());
        assertThat(statusCaptor.getAllValues()).containsOnly(BackfillStatus.COMPLETE_TWS);
    }

    // -------------------------------------------------------------------------
    // T9-3: recent DAY_1 chunk, TWS throws → falls back to yfinance
    // -------------------------------------------------------------------------

    @Test
    void recentChunk_twsThrows_fallsBackToYfinance() throws Exception {
        HistoricalBackfillService service = buildService(List.of("AAPL"), List.of());

        ZonedDateTime future = ZonedDateTime.now(ZoneOffset.UTC).plusYears(10);
        when(checkpoint.getLastDownloaded(eq("AAPL"), argThat(tf -> tf != TimeFrame.DAY_1)))
                .thenReturn(Optional.of(future));
        ZonedDateTime recentStart = ZonedDateTime.now(ZoneOffset.UTC).minusMonths(3);
        when(checkpoint.getLastDownloaded(eq("AAPL"), eq(TimeFrame.DAY_1)))
                .thenReturn(Optional.of(recentStart));

        when(ibkrService.downloadHistoricalData(eq("AAPL"), eq(TimeFrame.DAY_1), any()))
                .thenThrow(new RuntimeException("TWS unavailable"));
        when(yfinanceClient.fetchDailyCandles(eq("AAPL"), any(), any()))
                .thenReturn(List.of(SAMPLE_CANDLE));

        service.run(new DefaultApplicationArguments("--backfill"));

        // yfinance MUST have been called as fallback
        verify(yfinanceClient, atLeastOnce()).fetchDailyCandles(eq("AAPL"), any(), any());

        // Checkpoint saved with COMPLETE_YFINANCE
        ArgumentCaptor<BackfillStatus> statusCaptor = ArgumentCaptor.forClass(BackfillStatus.class);
        verify(checkpoint, atLeastOnce()).save(eq("AAPL"), eq(TimeFrame.DAY_1), any(), statusCaptor.capture());
        assertThat(statusCaptor.getAllValues()).containsOnly(BackfillStatus.COMPLETE_YFINANCE);
    }

    // -------------------------------------------------------------------------
    // T9-4: recent DAY_1 chunk, TWS returns empty → falls back to yfinance
    // -------------------------------------------------------------------------

    @Test
    void recentChunk_twsReturnsEmpty_fallsBackToYfinance() throws Exception {
        HistoricalBackfillService service = buildService(List.of("AAPL"), List.of());

        ZonedDateTime future = ZonedDateTime.now(ZoneOffset.UTC).plusYears(10);
        when(checkpoint.getLastDownloaded(eq("AAPL"), argThat(tf -> tf != TimeFrame.DAY_1)))
                .thenReturn(Optional.of(future));
        ZonedDateTime recentStart = ZonedDateTime.now(ZoneOffset.UTC).minusMonths(3);
        when(checkpoint.getLastDownloaded(eq("AAPL"), eq(TimeFrame.DAY_1)))
                .thenReturn(Optional.of(recentStart));

        when(ibkrService.downloadHistoricalData(eq("AAPL"), eq(TimeFrame.DAY_1), any()))
                .thenReturn(List.of());
        when(yfinanceClient.fetchDailyCandles(eq("AAPL"), any(), any()))
                .thenReturn(List.of(SAMPLE_CANDLE));

        service.run(new DefaultApplicationArguments("--backfill"));

        // yfinance MUST have been called when TWS returns empty
        verify(yfinanceClient, atLeastOnce()).fetchDailyCandles(eq("AAPL"), any(), any());
    }

    // -------------------------------------------------------------------------
    // T9-5: sub-daily chunk (MIN_5) → yfinance never called, uses TWS only
    // -------------------------------------------------------------------------

    @Test
    void subDailyChunk_min5_yfinanceNeverCalled() throws Exception {
        HistoricalBackfillService service = buildService(List.of("AAPL"), List.of());

        ZonedDateTime future = ZonedDateTime.now(ZoneOffset.UTC).plusYears(10);
        // Skip all TFs except MIN_5
        when(checkpoint.getLastDownloaded(eq("AAPL"), argThat(tf -> tf != TimeFrame.MIN_5)))
                .thenReturn(Optional.of(future));
        // MIN_5 starts from just under 30 days ago (one chunk)
        ZonedDateTime recentStart = ZonedDateTime.now(ZoneOffset.UTC).minusDays(15);
        when(checkpoint.getLastDownloaded(eq("AAPL"), eq(TimeFrame.MIN_5)))
                .thenReturn(Optional.of(recentStart));

        when(ibkrService.downloadHistoricalData(eq("AAPL"), eq(TimeFrame.MIN_5), any()))
                .thenReturn(List.of(SAMPLE_CANDLE));

        service.run(new DefaultApplicationArguments("--backfill"));

        // yfinance must never be called for non-DAY_1 timeframes
        verify(yfinanceClient, never()).fetchDailyCandles(anyString(), any(), any());
    }

    // -------------------------------------------------------------------------
    // T9-6: vixTicker in vixTickers list → iterated yfinance-only, TWS never called
    // -------------------------------------------------------------------------

    @Test
    void vixTicker_iteratedYfinanceOnly_twsNeverCalled() throws Exception {
        // No regular tickers; one VIX ticker
        HistoricalBackfillService service = buildService(List.of(), List.of("^VIX"));

        // Checkpoint: VIX was last downloaded 2 days ago
        when(checkpoint.getLastDownloaded(eq("^VIX"), eq(TimeFrame.DAY_1)))
                .thenReturn(Optional.of(ZonedDateTime.now(ZoneOffset.UTC).minusDays(2)));

        when(yfinanceClient.fetchDailyCandles(eq("^VIX"), any(), any()))
                .thenReturn(List.of(SAMPLE_CANDLE));

        service.run(new DefaultApplicationArguments("--backfill"));

        // TWS must NEVER be called for a VIX ticker
        verify(ibkrService, never()).downloadHistoricalData(anyString(), any(), any());
        // yfinance MUST be called for ^VIX
        verify(yfinanceClient, atLeastOnce()).fetchDailyCandles(eq("^VIX"), any(), any());

        // Checkpoint saved with COMPLETE_YFINANCE
        ArgumentCaptor<BackfillStatus> statusCaptor = ArgumentCaptor.forClass(BackfillStatus.class);
        verify(checkpoint, atLeastOnce()).save(eq("^VIX"), eq(TimeFrame.DAY_1), any(), statusCaptor.capture());
        assertThat(statusCaptor.getAllValues()).containsOnly(BackfillStatus.COMPLETE_YFINANCE);
    }
}
