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

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the yfinance routing logic in HistoricalBackfillService (T9).
 *
 * <p>Option B: DAY_1 always uses yfinance directly; MIN_5/MIN_15/HOUR_1 always use TWS.
 * All tests use the package-private constructors — no Spring context.
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
                vixTickers
        );
    }

    // -------------------------------------------------------------------------
    // T9-1: DAY_1 chunk (old, > 5yr) → yfinance called directly, TWS never touched
    // -------------------------------------------------------------------------

    @Test
    void day1_oldChunk_usesYfinanceDirectly_twsNeverCalled() throws Exception {
        HistoricalBackfillService service = new HistoricalBackfillService(
                repository,
                checkpoint,
                ibkrService,
                noopRateLimiter,
                List.of("AAPL"),
                BACKFILL_START,
                yfinanceClient,
                List.of()
        );

        ZonedDateTime future = ZonedDateTime.now(ZoneOffset.UTC).plusYears(10);
        when(checkpoint.getLastDownloaded(eq("AAPL"), argThat(tf -> tf != TimeFrame.DAY_1)))
                .thenReturn(Optional.of(future));
        when(checkpoint.getLastDownloaded(eq("AAPL"), eq(TimeFrame.DAY_1)))
                .thenReturn(Optional.of(ZonedDateTime.now(ZoneOffset.UTC).minusYears(6).minusDays(1)));

        when(yfinanceClient.fetchDailyCandles(eq("AAPL"), any(), any()))
                .thenReturn(List.of(SAMPLE_CANDLE));

        service.run(new DefaultApplicationArguments("--backfill"));

        verify(yfinanceClient, atLeastOnce()).fetchDailyCandles(eq("AAPL"), any(), any());
        verify(ibkrService, never()).downloadHistoricalData(eq("AAPL"), eq(TimeFrame.DAY_1), any());

        ArgumentCaptor<BackfillStatus> statusCaptor = ArgumentCaptor.forClass(BackfillStatus.class);
        verify(checkpoint, atLeastOnce()).save(eq("AAPL"), eq(TimeFrame.DAY_1), any(), statusCaptor.capture());
        assertThat(statusCaptor.getAllValues()).contains(BackfillStatus.COMPLETE_YFINANCE);
    }

    // -------------------------------------------------------------------------
    // T9-2: recent DAY_1 chunk → yfinance called directly, TWS never called
    // -------------------------------------------------------------------------

    @Test
    void recentChunk_day1_usesYfinanceDirectly_twsNeverCalled() throws Exception {
        HistoricalBackfillService service = buildService(List.of("AAPL"), List.of());

        ZonedDateTime future = ZonedDateTime.now(ZoneOffset.UTC).plusYears(10);
        when(checkpoint.getLastDownloaded(eq("AAPL"), argThat(tf -> tf != TimeFrame.DAY_1)))
                .thenReturn(Optional.of(future));
        ZonedDateTime recentStart = ZonedDateTime.now(ZoneOffset.UTC).minusMonths(3);
        when(checkpoint.getLastDownloaded(eq("AAPL"), eq(TimeFrame.DAY_1)))
                .thenReturn(Optional.of(recentStart));

        when(yfinanceClient.fetchDailyCandles(eq("AAPL"), any(), any()))
                .thenReturn(List.of(SAMPLE_CANDLE));

        service.run(new DefaultApplicationArguments("--backfill"));

        verify(yfinanceClient, atLeastOnce()).fetchDailyCandles(eq("AAPL"), any(), any());
        verify(ibkrService, never()).downloadHistoricalData(eq("AAPL"), eq(TimeFrame.DAY_1), any());

        ArgumentCaptor<BackfillStatus> statusCaptor = ArgumentCaptor.forClass(BackfillStatus.class);
        verify(checkpoint, atLeastOnce()).save(eq("AAPL"), eq(TimeFrame.DAY_1), any(), statusCaptor.capture());
        assertThat(statusCaptor.getAllValues()).containsOnly(BackfillStatus.COMPLETE_YFINANCE);
    }

    // -------------------------------------------------------------------------
    // T9-3: DAY_1 chunk, yfinance returns candles → checkpoint COMPLETE_YFINANCE
    // -------------------------------------------------------------------------

    @Test
    void day1_yfinanceReturnsCandles_checkpointCompleteYfinance() throws Exception {
        HistoricalBackfillService service = buildService(List.of("AAPL"), List.of());

        ZonedDateTime future = ZonedDateTime.now(ZoneOffset.UTC).plusYears(10);
        when(checkpoint.getLastDownloaded(eq("AAPL"), argThat(tf -> tf != TimeFrame.DAY_1)))
                .thenReturn(Optional.of(future));
        ZonedDateTime recentStart = ZonedDateTime.now(ZoneOffset.UTC).minusMonths(3);
        when(checkpoint.getLastDownloaded(eq("AAPL"), eq(TimeFrame.DAY_1)))
                .thenReturn(Optional.of(recentStart));

        when(yfinanceClient.fetchDailyCandles(eq("AAPL"), any(), any()))
                .thenReturn(List.of(SAMPLE_CANDLE));

        service.run(new DefaultApplicationArguments("--backfill"));

        verify(repository, atLeastOnce()).upsert(eq("AAPL"), eq(TimeFrame.DAY_1), anyList());

        ArgumentCaptor<BackfillStatus> statusCaptor = ArgumentCaptor.forClass(BackfillStatus.class);
        verify(checkpoint, atLeastOnce()).save(eq("AAPL"), eq(TimeFrame.DAY_1), any(), statusCaptor.capture());
        assertThat(statusCaptor.getAllValues()).containsOnly(BackfillStatus.COMPLETE_YFINANCE);
    }

    // -------------------------------------------------------------------------
    // T9-4: DAY_1 chunk, yfinance returns empty → checkpoint COMPLETE_EMPTY
    // -------------------------------------------------------------------------

    @Test
    void day1_yfinanceReturnsEmpty_checkpointCompleteEmpty() throws Exception {
        HistoricalBackfillService service = buildService(List.of("AAPL"), List.of());

        ZonedDateTime future = ZonedDateTime.now(ZoneOffset.UTC).plusYears(10);
        when(checkpoint.getLastDownloaded(eq("AAPL"), argThat(tf -> tf != TimeFrame.DAY_1)))
                .thenReturn(Optional.of(future));
        ZonedDateTime recentStart = ZonedDateTime.now(ZoneOffset.UTC).minusMonths(3);
        when(checkpoint.getLastDownloaded(eq("AAPL"), eq(TimeFrame.DAY_1)))
                .thenReturn(Optional.of(recentStart));

        when(yfinanceClient.fetchDailyCandles(eq("AAPL"), any(), any()))
                .thenReturn(List.of());

        service.run(new DefaultApplicationArguments("--backfill"));

        verify(repository, never()).upsert(eq("AAPL"), eq(TimeFrame.DAY_1), anyList());

        ArgumentCaptor<BackfillStatus> statusCaptor = ArgumentCaptor.forClass(BackfillStatus.class);
        verify(checkpoint, atLeastOnce()).save(eq("AAPL"), eq(TimeFrame.DAY_1), any(), statusCaptor.capture());
        assertThat(statusCaptor.getAllValues()).containsOnly(BackfillStatus.COMPLETE_EMPTY);
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
    // T9-P1: period filter active, chunk inside a configured period → downloads
    // -------------------------------------------------------------------------

    @Test
    void chunk_in_period_downloads() throws Exception {
        HistoricalBackfillService.BackfillPeriod crisis2020 =
                new HistoricalBackfillService.BackfillPeriod(
                        LocalDate.of(2020, 2, 1), LocalDate.of(2020, 5, 31));

        HistoricalBackfillService service = new HistoricalBackfillService(
                repository,
                checkpoint,
                ibkrService,
                noopRateLimiter,
                List.of("AAPL"),
                BACKFILL_START,
                yfinanceClient,
                List.of(),
                List.of(crisis2020)
        );

        ZonedDateTime future = ZonedDateTime.now(ZoneOffset.UTC).plusYears(10);
        when(checkpoint.getLastDownloaded(eq("AAPL"), argThat(tf -> tf != TimeFrame.DAY_1)))
                .thenReturn(Optional.of(future));
        // Chunk: 2020-02-01 → 2020-02-01+365d, well inside crisis2020 period
        ZonedDateTime chunkStart = ZonedDateTime.of(2020, 2, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        ZonedDateTime afterChunk = chunkStart.plusDays(366);
        when(checkpoint.getLastDownloaded(eq("AAPL"), eq(TimeFrame.DAY_1)))
                .thenReturn(Optional.of(chunkStart))
                .thenReturn(Optional.of(afterChunk));

        // DAY_1 always uses yfinance (Option B), return a candle
        when(yfinanceClient.fetchDailyCandles(eq("AAPL"), any(), any()))
                .thenReturn(List.of(SAMPLE_CANDLE));
        // Subsequent chunks fall outside the 2020 crisis period and are skipped — no TWS call needed

        service.run(new DefaultApplicationArguments("--backfill"));

        // At least one download must have happened for the in-period chunk
        verify(yfinanceClient, atLeastOnce()).fetchDailyCandles(eq("AAPL"), any(), any());
    }

    // -------------------------------------------------------------------------
    // T9-P2: period filter active, chunk entirely outside all configured periods → skipped
    // -------------------------------------------------------------------------

    @Test
    void chunk_out_of_period_skips() throws Exception {
        // Only 2020-02 to 2020-05 is allowed
        HistoricalBackfillService.BackfillPeriod crisis2020 =
                new HistoricalBackfillService.BackfillPeriod(
                        LocalDate.of(2020, 2, 1), LocalDate.of(2020, 5, 31));

        HistoricalBackfillService service = new HistoricalBackfillService(
                repository,
                checkpoint,
                ibkrService,
                noopRateLimiter,
                List.of("AAPL"),
                BACKFILL_START,
                yfinanceClient,
                List.of(),
                List.of(crisis2020)
        );

        ZonedDateTime future = ZonedDateTime.now(ZoneOffset.UTC).plusYears(10);
        when(checkpoint.getLastDownloaded(eq("AAPL"), argThat(tf -> tf != TimeFrame.DAY_1)))
                .thenReturn(Optional.of(future));
        // Chunk: 2021-01-01 → 2022-01-01, completely outside the 2020 window
        ZonedDateTime chunkStart = ZonedDateTime.of(2021, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        ZonedDateTime afterChunk = chunkStart.plusDays(366);
        when(checkpoint.getLastDownloaded(eq("AAPL"), eq(TimeFrame.DAY_1)))
                .thenReturn(Optional.of(chunkStart))
                .thenReturn(Optional.of(afterChunk));

        service.run(new DefaultApplicationArguments("--backfill"));

        // The out-of-range chunk must be skipped: no download, no checkpoint write
        verify(yfinanceClient, never()).fetchDailyCandles(anyString(), any(), any());
        verify(ibkrService, never()).downloadHistoricalData(anyString(), any(), any());
        verify(checkpoint, never()).save(anyString(), any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // T9-P3: chunk trimmed to period exact — yfinance called with intersection, not full chunk
    // -------------------------------------------------------------------------

    @Test
    void chunk_trimmed_to_period_exact() throws Exception {
        // Period: 2018-01 → 2018-03 (first day → last day of month = 2018-03-31)
        HistoricalBackfillService.BackfillPeriod narrow =
                new HistoricalBackfillService.BackfillPeriod(
                        LocalDate.of(2018, 1, 1), LocalDate.of(2018, 3, 31));

        // backfillStart = 2018-01-01; DAY_1 chunk size = 365 days → chunk 2018-01-01 → 2019-01-01
        // Expected: yfinance called with 2018-01-01 → 2018-03-31, NOT 2019-01-01
        HistoricalBackfillService service = new HistoricalBackfillService(
                repository,
                checkpoint,
                ibkrService,
                noopRateLimiter,
                List.of("AAPL"),
                BACKFILL_START,
                yfinanceClient,
                List.of(),
                List.of(narrow)
        );

        ZonedDateTime future = ZonedDateTime.now(ZoneOffset.UTC).plusYears(10);
        when(checkpoint.getLastDownloaded(eq("AAPL"), argThat(tf -> tf != TimeFrame.DAY_1)))
                .thenReturn(Optional.of(future));
        // No checkpoint for DAY_1 → starts from backfillStart (2018-01-01).
        // The while-loop will iterate all annual chunks from 2018 to now, but every chunk
        // after 2018-03-31 is skipped by isChunkInAnyPeriod — only one yfinance call happens.
        when(checkpoint.getLastDownloaded(eq("AAPL"), eq(TimeFrame.DAY_1)))
                .thenReturn(Optional.empty());

        ArgumentCaptor<LocalDate> fromCaptor = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDate> toCaptor = ArgumentCaptor.forClass(LocalDate.class);
        when(yfinanceClient.fetchDailyCandles(eq("AAPL"), fromCaptor.capture(), toCaptor.capture()))
                .thenReturn(List.of(SAMPLE_CANDLE));

        service.run(new DefaultApplicationArguments("--backfill"));

        verify(yfinanceClient, atLeastOnce()).fetchDailyCandles(eq("AAPL"), any(), any());

        // The `to` date passed to yfinance must be 2018-03-31 (period end), NOT 2019-01-01 (chunk end)
        assertThat(toCaptor.getAllValues()).allMatch(d -> !d.isAfter(LocalDate.of(2018, 3, 31)));
        // The `from` date must be 2018-01-01 (max of chunkFrom and period.from, both equal here)
        assertThat(fromCaptor.getAllValues()).allMatch(d -> !d.isBefore(LocalDate.of(2018, 1, 1)));
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
