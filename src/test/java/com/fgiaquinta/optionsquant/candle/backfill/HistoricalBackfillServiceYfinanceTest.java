package com.fgiaquinta.optionsquant.candle.backfill;

import com.google.common.util.concurrent.RateLimiter;
import com.fgiaquinta.optionsquant.candle.CandleRepository;
import com.fgiaquinta.optionsquant.candle.RepositoryException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        when(checkpoint.getLastDownloaded(eq("AAPL"), argThat(tf -> tf != TimeFrame.DAY_1), eq(ChunkOrigin.HISTORICAL)))
                .thenReturn(Optional.of(future));
        when(checkpoint.getLastDownloaded(eq("AAPL"), eq(TimeFrame.DAY_1), eq(ChunkOrigin.HISTORICAL)))
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
        when(checkpoint.getLastDownloaded(eq("AAPL"), argThat(tf -> tf != TimeFrame.DAY_1), eq(ChunkOrigin.HISTORICAL)))
                .thenReturn(Optional.of(future));
        ZonedDateTime recentStart = ZonedDateTime.now(ZoneOffset.UTC).minusMonths(3);
        when(checkpoint.getLastDownloaded(eq("AAPL"), eq(TimeFrame.DAY_1), eq(ChunkOrigin.HISTORICAL)))
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
        when(checkpoint.getLastDownloaded(eq("AAPL"), argThat(tf -> tf != TimeFrame.DAY_1), eq(ChunkOrigin.HISTORICAL)))
                .thenReturn(Optional.of(future));
        ZonedDateTime recentStart = ZonedDateTime.now(ZoneOffset.UTC).minusMonths(3);
        when(checkpoint.getLastDownloaded(eq("AAPL"), eq(TimeFrame.DAY_1), eq(ChunkOrigin.HISTORICAL)))
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
        when(checkpoint.getLastDownloaded(eq("AAPL"), argThat(tf -> tf != TimeFrame.DAY_1), eq(ChunkOrigin.HISTORICAL)))
                .thenReturn(Optional.of(future));
        ZonedDateTime recentStart = ZonedDateTime.now(ZoneOffset.UTC).minusMonths(3);
        when(checkpoint.getLastDownloaded(eq("AAPL"), eq(TimeFrame.DAY_1), eq(ChunkOrigin.HISTORICAL)))
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
        when(checkpoint.getLastDownloaded(eq("AAPL"), argThat(tf -> tf != TimeFrame.MIN_5), eq(ChunkOrigin.HISTORICAL)))
                .thenReturn(Optional.of(future));
        // MIN_5 starts from just under 30 days ago (one chunk)
        ZonedDateTime recentStart = ZonedDateTime.now(ZoneOffset.UTC).minusDays(15);
        when(checkpoint.getLastDownloaded(eq("AAPL"), eq(TimeFrame.MIN_5), eq(ChunkOrigin.HISTORICAL)))
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
        when(checkpoint.getLastDownloaded(eq("AAPL"), argThat(tf -> tf != TimeFrame.DAY_1), eq(ChunkOrigin.HISTORICAL)))
                .thenReturn(Optional.of(future));
        // Chunk: 2020-02-01 → 2020-02-01+365d, well inside crisis2020 period
        ZonedDateTime chunkStart = ZonedDateTime.of(2020, 2, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        ZonedDateTime afterChunk = chunkStart.plusDays(366);
        when(checkpoint.getLastDownloaded(eq("AAPL"), eq(TimeFrame.DAY_1), eq(ChunkOrigin.HISTORICAL)))
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
        when(checkpoint.getLastDownloaded(eq("AAPL"), argThat(tf -> tf != TimeFrame.DAY_1), eq(ChunkOrigin.HISTORICAL)))
                .thenReturn(Optional.of(future));
        // Chunk: 2021-01-01 → 2022-01-01, completely outside the 2020 window
        ZonedDateTime chunkStart = ZonedDateTime.of(2021, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        ZonedDateTime afterChunk = chunkStart.plusDays(366);
        when(checkpoint.getLastDownloaded(eq("AAPL"), eq(TimeFrame.DAY_1), eq(ChunkOrigin.HISTORICAL)))
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
        when(checkpoint.getLastDownloaded(eq("AAPL"), argThat(tf -> tf != TimeFrame.DAY_1), eq(ChunkOrigin.HISTORICAL)))
                .thenReturn(Optional.of(future));
        // No checkpoint for DAY_1 → starts from backfillStart (2018-01-01).
        // The while-loop will iterate all annual chunks from 2018 to now, but every chunk
        // after 2018-03-31 is skipped by isChunkInAnyPeriod — only one yfinance call happens.
        when(checkpoint.getLastDownloaded(eq("AAPL"), eq(TimeFrame.DAY_1), eq(ChunkOrigin.HISTORICAL)))
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
    // Gap 1: yfinanceClient.fetchDailyCandles() throws RuntimeException
    //         → no try-catch in downloadChunkYfinance(), exception propagates
    // -------------------------------------------------------------------------

    @Test
    void gap_yfinanceClientThrows_returnZero_upsertNeverCalled() {
        HistoricalBackfillService service = buildService(List.of("AAPL"), List.of());

        ZonedDateTime future = ZonedDateTime.now(ZoneOffset.UTC).plusYears(10);
        when(checkpoint.getLastDownloaded(eq("AAPL"), argThat(tf -> tf != TimeFrame.DAY_1), eq(ChunkOrigin.HISTORICAL)))
                .thenReturn(Optional.of(future));
        // One DAY_1 chunk starting 3 months ago
        when(checkpoint.getLastDownloaded(eq("AAPL"), eq(TimeFrame.DAY_1), eq(ChunkOrigin.HISTORICAL)))
                .thenReturn(Optional.of(ZonedDateTime.now(ZoneOffset.UTC).minusMonths(3)));

        when(yfinanceClient.fetchDailyCandles(eq("AAPL"), any(), any()))
                .thenThrow(new RuntimeException("yfinance sidecar timeout"));

        // downloadChunkYfinance() has try-catch → run() completes normally, returns 0
        service.run(new DefaultApplicationArguments("--backfill"));

        // upsert must never be called when yfinanceClient throws
        verify(repository, never()).upsert(anyString(), any(), anyList());
    }

    // -------------------------------------------------------------------------
    // Gap 2: repository.upsert() throws RepositoryException mid-batch
    //         → no try-catch in downloadChunkYfinance(), exception propagates
    // -------------------------------------------------------------------------

    @Test
    void gap_repositoryUpsertThrows_runCompletesNormally_checkpointNeverCalled() {
        HistoricalBackfillService service = buildService(List.of("AAPL"), List.of());

        ZonedDateTime future = ZonedDateTime.now(ZoneOffset.UTC).plusYears(10);
        when(checkpoint.getLastDownloaded(eq("AAPL"), argThat(tf -> tf != TimeFrame.DAY_1), eq(ChunkOrigin.HISTORICAL)))
                .thenReturn(Optional.of(future));
        // One DAY_1 chunk starting 3 months ago
        when(checkpoint.getLastDownloaded(eq("AAPL"), eq(TimeFrame.DAY_1), eq(ChunkOrigin.HISTORICAL)))
                .thenReturn(Optional.of(ZonedDateTime.now(ZoneOffset.UTC).minusMonths(3)));

        // yfinance returns valid candles so upsert() is reached
        when(yfinanceClient.fetchDailyCandles(eq("AAPL"), any(), any()))
                .thenReturn(List.of(SAMPLE_CANDLE));
        doThrow(new RepositoryException("DB write failure"))
                .when(repository).upsert(eq("AAPL"), eq(TimeFrame.DAY_1), anyList());

        // downloadChunkYfinance() has try-catch → RepositoryException is caught, run() completes normally
        service.run(new DefaultApplicationArguments("--backfill"));

        // checkpoint.save() must NOT be called because upsert() blew up before it
        verify(checkpoint, never()).save(eq("AAPL"), eq(TimeFrame.DAY_1), any(), any());
    }

    // -------------------------------------------------------------------------
    // Gap 3: BackfillProperties with empty periods list
    //         → isChunkInAnyPeriod() returns true for all chunks (no filtering),
    //           every chunk proceeds to yfinance download normally
    // -------------------------------------------------------------------------

    @Test
    void gap_emptyPeriodsList_allChunksDownloadedWithNoFiltering() {
        // periods = List.of() → the 5-arg constructor stores an empty list.
        // isChunkInAnyPeriod() returns true when periods is empty (line 286 in production code),
        // so no chunk is ever skipped.
        HistoricalBackfillService service = new HistoricalBackfillService(
                repository,
                checkpoint,
                ibkrService,
                noopRateLimiter,
                List.of("AAPL"),
                BACKFILL_START,
                yfinanceClient,
                List.of(),
                List.of()   // ← empty periods
        );

        ZonedDateTime future = ZonedDateTime.now(ZoneOffset.UTC).plusYears(10);
        when(checkpoint.getLastDownloaded(eq("AAPL"), argThat(tf -> tf != TimeFrame.DAY_1), eq(ChunkOrigin.HISTORICAL)))
                .thenReturn(Optional.of(future));
        // One DAY_1 chunk starting 3 months ago
        when(checkpoint.getLastDownloaded(eq("AAPL"), eq(TimeFrame.DAY_1), eq(ChunkOrigin.HISTORICAL)))
                .thenReturn(Optional.of(ZonedDateTime.now(ZoneOffset.UTC).minusMonths(3)));

        when(yfinanceClient.fetchDailyCandles(eq("AAPL"), any(), any()))
                .thenReturn(List.of(SAMPLE_CANDLE));

        service.run(new DefaultApplicationArguments("--backfill"));

        // With empty periods no chunk is filtered → yfinance IS called
        verify(yfinanceClient, atLeastOnce()).fetchDailyCandles(eq("AAPL"), any(), any());
        // And candles are persisted
        verify(repository, atLeastOnce()).upsert(eq("AAPL"), eq(TimeFrame.DAY_1), anyList());
    }

    // -------------------------------------------------------------------------
    // Gap 1: computeEffectiveRanges() — chunk spanning 3 non-adjacent periods
    //         → 3 separate intersections, no merging
    // -------------------------------------------------------------------------

    @Test
    void computeEffectiveRanges_chunkSpansThreePeriods_returnsThreeSeparateRanges() {
        HistoricalBackfillService.BackfillPeriod p1 =
                new HistoricalBackfillService.BackfillPeriod(
                        LocalDate.of(2018, 1, 1), LocalDate.of(2018, 3, 31));
        HistoricalBackfillService.BackfillPeriod p2 =
                new HistoricalBackfillService.BackfillPeriod(
                        LocalDate.of(2019, 1, 1), LocalDate.of(2019, 12, 31));
        HistoricalBackfillService.BackfillPeriod p3 =
                new HistoricalBackfillService.BackfillPeriod(
                        LocalDate.of(2020, 2, 1), LocalDate.of(2020, 5, 31));

        HistoricalBackfillService service = new HistoricalBackfillService(
                repository, checkpoint, ibkrService, noopRateLimiter,
                List.of("AAPL"), BACKFILL_START, yfinanceClient, List.of(),
                List.of(p1, p2, p3)
        );

        ZonedDateTime chunkFrom = ZonedDateTime.of(2018, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        ZonedDateTime chunkTo   = ZonedDateTime.of(2020, 12, 31, 0, 0, 0, 0, ZoneOffset.UTC);

        List<HistoricalBackfillService.BackfillPeriod> ranges =
                service.computeEffectiveRanges(chunkFrom, chunkTo);

        assertThat(ranges).hasSize(3);
        assertThat(ranges.get(0).from()).isEqualTo(LocalDate.of(2018, 1, 1));
        assertThat(ranges.get(0).to()).isEqualTo(LocalDate.of(2018, 3, 31));
        assertThat(ranges.get(1).from()).isEqualTo(LocalDate.of(2019, 1, 1));
        assertThat(ranges.get(1).to()).isEqualTo(LocalDate.of(2019, 12, 31));
        assertThat(ranges.get(2).from()).isEqualTo(LocalDate.of(2020, 2, 1));
        assertThat(ranges.get(2).to()).isEqualTo(LocalDate.of(2020, 5, 31));
    }

    // -------------------------------------------------------------------------
    // Gap 1b: computeEffectiveRanges() — two adjacent periods (gap = 0 days)
    //          plusDays(1) merge logic → single merged range
    // -------------------------------------------------------------------------

    @Test
    void computeEffectiveRanges_adjacentPeriods_mergedIntoSingleRange() {
        // nextFrom (2019-07-01) <= currentTo.plusDays(1) (2019-07-01) → merge fires
        HistoricalBackfillService.BackfillPeriod firstHalf =
                new HistoricalBackfillService.BackfillPeriod(
                        LocalDate.of(2019, 1, 1), LocalDate.of(2019, 6, 30));
        HistoricalBackfillService.BackfillPeriod secondHalf =
                new HistoricalBackfillService.BackfillPeriod(
                        LocalDate.of(2019, 7, 1), LocalDate.of(2019, 12, 31));

        HistoricalBackfillService service = new HistoricalBackfillService(
                repository, checkpoint, ibkrService, noopRateLimiter,
                List.of("AAPL"), BACKFILL_START, yfinanceClient, List.of(),
                List.of(firstHalf, secondHalf)
        );

        ZonedDateTime chunkFrom = ZonedDateTime.of(2019, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        ZonedDateTime chunkTo   = ZonedDateTime.of(2019, 12, 31, 0, 0, 0, 0, ZoneOffset.UTC);

        List<HistoricalBackfillService.BackfillPeriod> ranges =
                service.computeEffectiveRanges(chunkFrom, chunkTo);

        assertThat(ranges).hasSize(1);
        assertThat(ranges.get(0).from()).isEqualTo(LocalDate.of(2019, 1, 1));
        assertThat(ranges.get(0).to()).isEqualTo(LocalDate.of(2019, 12, 31));
    }

    // -------------------------------------------------------------------------
    // Gap 2A: MIN_5 chunk inside a configured period → TWS called, yfinance never called
    // -------------------------------------------------------------------------

    @Test
    void subDaily_min5_chunkInsidePeriod_twsCalled_yfinanceNeverCalled() throws Exception {
        HistoricalBackfillService.BackfillPeriod volatility2022 =
                new HistoricalBackfillService.BackfillPeriod(
                        LocalDate.of(2022, 1, 1), LocalDate.of(2022, 11, 30));

        HistoricalBackfillService service = new HistoricalBackfillService(
                repository, checkpoint, ibkrService, noopRateLimiter,
                List.of("AAPL"), BACKFILL_START, yfinanceClient, List.of(),
                List.of(volatility2022)
        );

        ZonedDateTime future = ZonedDateTime.now(ZoneOffset.UTC).plusYears(10);
        // Skip all timeframes except MIN_5
        when(checkpoint.getLastDownloaded(eq("AAPL"), argThat(tf -> tf != TimeFrame.MIN_5), eq(ChunkOrigin.HISTORICAL)))
                .thenReturn(Optional.of(future));
        // MIN_5 chunk starts 2022-02-01: inside volatility2022 period
        ZonedDateTime chunkStart = ZonedDateTime.of(2022, 2, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        ZonedDateTime afterChunk = chunkStart.plusDays(30).plusDays(1);
        when(checkpoint.getLastDownloaded(eq("AAPL"), eq(TimeFrame.MIN_5), eq(ChunkOrigin.HISTORICAL)))
                .thenReturn(Optional.of(chunkStart))
                .thenReturn(Optional.of(afterChunk));

        when(ibkrService.downloadHistoricalData(eq("AAPL"), eq(TimeFrame.MIN_5), any()))
                .thenReturn(List.of(SAMPLE_CANDLE));

        service.run(new DefaultApplicationArguments("--backfill"));

        verify(ibkrService, atLeastOnce()).downloadHistoricalData(eq("AAPL"), eq(TimeFrame.MIN_5), any());
        verify(yfinanceClient, never()).fetchDailyCandles(anyString(), any(), any());
    }

    // -------------------------------------------------------------------------
    // Gap 2B: MIN_5 chunk outside all configured periods → skipped entirely
    //          no TWS call, no checkpoint write
    // -------------------------------------------------------------------------

    @Test
    void subDaily_min5_chunkOutsideAllPeriods_skippedNoTwsNoCheckpoint() throws Exception {
        // Period is entirely in the past (2021-01); backfillStart is 2021-02-01 so every
        // MIN_5 chunk starts after the period end → all chunks are OUT_OF_RANGE.
        HistoricalBackfillService.BackfillPeriod narrowPast =
                new HistoricalBackfillService.BackfillPeriod(
                        LocalDate.of(2021, 1, 1), LocalDate.of(2021, 1, 31));

        ZonedDateTime afterPeriodEnd = ZonedDateTime.of(2021, 2, 1, 0, 0, 0, 0, ZoneOffset.UTC);

        HistoricalBackfillService service = new HistoricalBackfillService(
                repository, checkpoint, ibkrService, noopRateLimiter,
                List.of("AAPL"), afterPeriodEnd, yfinanceClient, List.of(),
                List.of(narrowPast)
        );

        ZonedDateTime future = ZonedDateTime.now(ZoneOffset.UTC).plusYears(10);
        when(checkpoint.getLastDownloaded(eq("AAPL"), argThat(tf -> tf != TimeFrame.MIN_5), eq(ChunkOrigin.HISTORICAL)))
                .thenReturn(Optional.of(future));
        // No MIN_5 checkpoint → loop starts from afterPeriodEnd (2021-02-01), already past narrowPast
        when(checkpoint.getLastDownloaded(eq("AAPL"), eq(TimeFrame.MIN_5), eq(ChunkOrigin.HISTORICAL)))
                .thenReturn(Optional.empty());

        service.run(new DefaultApplicationArguments("--backfill"));

        verify(ibkrService, never()).downloadHistoricalData(anyString(), any(), any());
        verify(yfinanceClient, never()).fetchDailyCandles(anyString(), any(), any());
        verify(checkpoint, never()).save(anyString(), any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // T9-6: vixTicker in vixTickers list → iterated yfinance-only, TWS never called
    // -------------------------------------------------------------------------

    @Test
    void vixTicker_iteratedYfinanceOnly_twsNeverCalled() throws Exception {
        // No regular tickers; one VIX ticker
        HistoricalBackfillService service = buildService(List.of(), List.of("^VIX"));

        // Checkpoint: VIX was last downloaded 2 days ago
        when(checkpoint.getLastDownloaded(eq("^VIX"), eq(TimeFrame.DAY_1), eq(ChunkOrigin.HISTORICAL)))
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
