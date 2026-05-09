package com.fgiaquinta.optionsquant.candle.backfill;

import com.google.common.util.concurrent.RateLimiter;
import com.fgiaquinta.optionsquant.candle.CandleRepository;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.service.IbkrService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the timeframe iteration ORDER in HistoricalBackfillService.
 *
 * <p>The backfill must process timeframes in this priority across ALL tickers:
 * 1. DAY_1   (yfinance — fast, high signal)
 * 2. HOUR_1  (TWS)
 * 3. MIN_15  (TWS)
 * 4. MIN_5   (TWS — slowest, processed LAST)
 *
 * <p>Critically, ALL tickers complete a higher-priority timeframe BEFORE any ticker
 * begins the next one. MIN_5 is delayed until DAY_1, HOUR_1 and MIN_15 are done for
 * every ticker. This protects against TWS pacing burnout consuming the most-valuable
 * (DAY_1/HOUR_1) data first if the run is interrupted.
 */
@ExtendWith(MockitoExtension.class)
class HistoricalBackfillServicePriorityOrderTest {

    @Mock
    private CandleRepository repository;

    @Mock
    private BackfillCheckpoint checkpoint;

    @Mock
    private IbkrService ibkrService;

    @Mock
    private YfinanceHistoricalClient yfinanceClient;

    /** Fast rate limiter that never throttles in tests. */
    private final RateLimiter noopRateLimiter = RateLimiter.create(Double.MAX_VALUE);

    /**
     * Use a recent backfill-start so each ticker × timeframe needs only a small,
     * predictable number of chunks.
     */
    private static final ZonedDateTime BACKFILL_START =
            ZonedDateTime.now(ZoneOffset.UTC).minusDays(5);

    private HistoricalBackfillService newService(List<String> tickers, List<String> vixTickers) {
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
    // 1. DAY_1 must be processed for ALL tickers BEFORE HOUR_1 starts for any ticker
    // -------------------------------------------------------------------------
    @Test
    void backfill_processesDay1ForAllTickersBeforeOtherTimeframes() throws Exception {
        HistoricalBackfillService service = newService(List.of("AAPL", "MSFT"), List.of());

        when(checkpoint.getLastDownloaded(anyString(), any())).thenReturn(Optional.empty());
        when(ibkrService.downloadHistoricalData(anyString(), any(), any()))
                .thenReturn(Collections.emptyList());
        when(yfinanceClient.fetchDailyCandles(anyString(), any(), any()))
                .thenReturn(Collections.emptyList());

        service.run(new DefaultApplicationArguments("--backfill"));

        // InOrder against both clients — DAY_1 (yfinance) for both tickers
        // must happen before any TWS call.
        InOrder order = inOrder(yfinanceClient, ibkrService);
        order.verify(yfinanceClient, atLeastOnce()).fetchDailyCandles(eq("AAPL"), any(), any());
        order.verify(yfinanceClient, atLeastOnce()).fetchDailyCandles(eq("MSFT"), any(), any());
        // Only AFTER both DAY_1s should any TWS download begin.
        order.verify(ibkrService, atLeastOnce()).downloadHistoricalData(anyString(), any(), any());
    }

    // -------------------------------------------------------------------------
    // 2. MIN_5 must come LAST across all tickers — after DAY_1, HOUR_1, MIN_15
    // -------------------------------------------------------------------------
    @Test
    void backfill_processesMin5LastAcrossAllTickers() throws Exception {
        HistoricalBackfillService service = newService(List.of("AAPL", "MSFT"), List.of());

        when(checkpoint.getLastDownloaded(anyString(), any())).thenReturn(Optional.empty());
        when(ibkrService.downloadHistoricalData(anyString(), any(), any()))
                .thenReturn(Collections.emptyList());
        when(yfinanceClient.fetchDailyCandles(anyString(), any(), any()))
                .thenReturn(Collections.emptyList());

        service.run(new DefaultApplicationArguments("--backfill"));

        InOrder order = inOrder(yfinanceClient, ibkrService);
        // DAY_1 first (both tickers)
        order.verify(yfinanceClient, atLeastOnce()).fetchDailyCandles(anyString(), any(), any());
        // Then HOUR_1 across tickers
        order.verify(ibkrService, atLeastOnce())
                .downloadHistoricalData(anyString(), eq(TimeFrame.HOUR_1), any());
        // Then MIN_15 across tickers
        order.verify(ibkrService, atLeastOnce())
                .downloadHistoricalData(anyString(), eq(TimeFrame.MIN_15), any());
        // Finally MIN_5 — last
        order.verify(ibkrService, atLeastOnce())
                .downloadHistoricalData(anyString(), eq(TimeFrame.MIN_5), any());
    }

    // -------------------------------------------------------------------------
    // 3. VIX tickers must be processed only in the DAY_1 phase (yfinance-only)
    // -------------------------------------------------------------------------
    @Test
    void backfill_handlesVixTickerInDay1PhaseOnly() throws Exception {
        HistoricalBackfillService service = newService(List.of("AAPL"), List.of("VIX"));

        when(checkpoint.getLastDownloaded(anyString(), any())).thenReturn(Optional.empty());
        when(ibkrService.downloadHistoricalData(anyString(), any(), any()))
                .thenReturn(Collections.emptyList());
        when(yfinanceClient.fetchDailyCandles(anyString(), any(), any()))
                .thenReturn(Collections.emptyList());

        service.run(new DefaultApplicationArguments("--backfill"));

        // VIX must get DAY_1 calls via yfinance
        verify(yfinanceClient, atLeastOnce()).fetchDailyCandles(eq("VIX"), any(), any());
        // VIX must NEVER hit TWS at any timeframe
        verify(ibkrService, never()).downloadHistoricalData(eq("VIX"), any(), any());
    }

    // -------------------------------------------------------------------------
    // 4. Existing checkpoint behavior is preserved — completed chunks are skipped.
    //    When every (ticker, tf) is already complete, no downloads happen.
    // -------------------------------------------------------------------------
    @Test
    void backfill_skipsTimeframeIfTickerCheckpointAlreadyComplete() throws Exception {
        HistoricalBackfillService service = newService(List.of("AAPL", "MSFT"), List.of());

        ZonedDateTime future = ZonedDateTime.now(ZoneOffset.UTC).plusYears(10);
        when(checkpoint.getLastDownloaded(anyString(), any())).thenReturn(Optional.of(future));

        service.run(new DefaultApplicationArguments("--backfill"));

        verify(ibkrService, never()).downloadHistoricalData(anyString(), any(), any());
        verify(yfinanceClient, never()).fetchDailyCandles(anyString(), any(), any());
    }

    // -------------------------------------------------------------------------
    // 5. Sanity: when DAY_1 is already complete for one ticker but not another,
    //    the first ticker's DAY_1 is skipped but HOUR_1/MIN_15/MIN_5 must still
    //    happen AFTER all DAY_1 work is done across the cohort.
    // -------------------------------------------------------------------------
    @Test
    void backfill_skipsCompletedDay1ButPreservesPriorityOrderForRemaining() throws Exception {
        HistoricalBackfillService service = newService(List.of("AAPL", "MSFT"), List.of());

        ZonedDateTime future = ZonedDateTime.now(ZoneOffset.UTC).plusYears(10);
        // AAPL DAY_1 already done; everything else pending
        when(checkpoint.getLastDownloaded(eq("AAPL"), eq(TimeFrame.DAY_1)))
                .thenReturn(Optional.of(future));
        when(checkpoint.getLastDownloaded(eq("AAPL"), argThat(tf -> tf != TimeFrame.DAY_1)))
                .thenReturn(Optional.empty());
        when(checkpoint.getLastDownloaded(eq("MSFT"), any())).thenReturn(Optional.empty());

        when(ibkrService.downloadHistoricalData(anyString(), any(), any()))
                .thenReturn(Collections.emptyList());
        when(yfinanceClient.fetchDailyCandles(anyString(), any(), any()))
                .thenReturn(Collections.emptyList());

        service.run(new DefaultApplicationArguments("--backfill"));

        // AAPL DAY_1 is skipped; MSFT DAY_1 still runs
        verify(yfinanceClient, never()).fetchDailyCandles(eq("AAPL"), any(), any());
        verify(yfinanceClient, atLeastOnce()).fetchDailyCandles(eq("MSFT"), any(), any());

        // MSFT DAY_1 must still run BEFORE any HOUR_1 starts
        InOrder order = inOrder(yfinanceClient, ibkrService);
        order.verify(yfinanceClient, atLeastOnce()).fetchDailyCandles(eq("MSFT"), any(), any());
        order.verify(ibkrService, atLeastOnce())
                .downloadHistoricalData(anyString(), eq(TimeFrame.HOUR_1), any());
    }
}
