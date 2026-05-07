package com.fgiaquinta.optionsquant.candle.backfill;

import com.google.common.util.concurrent.RateLimiter;
import com.fgiaquinta.optionsquant.candle.CandleRepository;
import com.fgiaquinta.optionsquant.service.IbkrService;
import com.fgiaquinta.optionsquant.service.TickerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the --backfill-all-tickers flag in HistoricalBackfillService (T12 / Phase 3).
 *
 * <p>When --backfill-all-tickers is present, the service must resolve the ticker list from
 * TickerService.getTickerSymbols() instead of the @Value-injected list.
 */
@ExtendWith(MockitoExtension.class)
class HistoricalBackfillServiceAllTickersTest {

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

    /** A fast rate limiter that never actually throttles in unit tests. */
    private final RateLimiter noopRateLimiter = RateLimiter.create(Double.MAX_VALUE);

    private static final ZonedDateTime BACKFILL_START =
            ZonedDateTime.of(2018, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    // -------------------------------------------------------------------------
    // T12-1: --backfill-all-tickers present → resolves full universe from TickerService
    // -------------------------------------------------------------------------

    @Test
    void flagPresent_resolvesFullUniverseFromTickerService() throws Exception {
        when(tickerService.getTickerSymbols()).thenReturn(List.of("A", "B", "C"));

        HistoricalBackfillService service = new HistoricalBackfillService(
                repository,
                checkpoint,
                ibkrService,
                noopRateLimiter,
                List.of("SHOULD_NOT_BE_USED"),
                BACKFILL_START,
                yfinanceClient,
                List.of(),
                List.of(),
                tickerService
        );

        // Make every chunk look already-done so we only care about ticker resolution
        ZonedDateTime future = ZonedDateTime.now(ZoneOffset.UTC).plusYears(10);
        when(checkpoint.getLastDownloaded(anyString(), any())).thenReturn(Optional.of(future));

        service.run(new DefaultApplicationArguments("--backfill", "--backfill-all-tickers"));

        // The service must have consulted TickerService for the full universe
        verify(tickerService, atLeastOnce()).getTickerSymbols();
        // SHOULD_NOT_BE_USED must never be touched
        verify(checkpoint, never()).getLastDownloaded(eq("SHOULD_NOT_BE_USED"), any());
    }

    // -------------------------------------------------------------------------
    // T12-2: --backfill-all-tickers absent → uses @Value-injected list (backward compat)
    // -------------------------------------------------------------------------

    @Test
    void flagAbsent_usesValueInjectedList_backwardCompat() throws Exception {
        HistoricalBackfillService service = new HistoricalBackfillService(
                repository,
                checkpoint,
                ibkrService,
                noopRateLimiter,
                List.of("AAPL", "MSFT"),
                BACKFILL_START,
                yfinanceClient,
                List.of(),
                List.of(),
                tickerService
        );

        // All already-done
        ZonedDateTime future = ZonedDateTime.now(ZoneOffset.UTC).plusYears(10);
        when(checkpoint.getLastDownloaded(anyString(), any())).thenReturn(Optional.of(future));

        service.run(new DefaultApplicationArguments("--backfill"));

        // TickerService must NOT be consulted when the flag is absent
        verify(tickerService, never()).getTickerSymbols();
        // The @Value tickers ARE consulted via checkpoint
        verify(checkpoint, atLeastOnce()).getLastDownloaded(eq("AAPL"), any());
        verify(checkpoint, atLeastOnce()).getLastDownloaded(eq("MSFT"), any());
    }

    // -------------------------------------------------------------------------
    // T12-3: --backfill-all-tickers present, universe empty → no iterations, no exception
    // -------------------------------------------------------------------------

    @Test
    void flagPresent_emptyUniverse_logsWarning_doesNotThrow() throws Exception {
        when(tickerService.getTickerSymbols()).thenReturn(List.of());

        HistoricalBackfillService service = new HistoricalBackfillService(
                repository,
                checkpoint,
                ibkrService,
                noopRateLimiter,
                List.of("AAPL"),
                BACKFILL_START,
                yfinanceClient,
                List.of(),
                List.of(),
                tickerService
        );

        // Must not throw
        service.run(new DefaultApplicationArguments("--backfill", "--backfill-all-tickers"));

        // Empty universe → no backfill iterations → no interactions with checkpoint, ibkr, yfinance
        verify(checkpoint, never()).getLastDownloaded(anyString(), any());
        verify(ibkrService, never()).downloadHistoricalData(anyString(), any(), any());
        verify(yfinanceClient, never()).fetchDailyCandles(anyString(), any(), any());
    }

    // -------------------------------------------------------------------------
    // T12-4: --backfill-all-tickers present, TickerService null → IllegalStateException
    // -------------------------------------------------------------------------

    @Test
    void flagPresent_tickerServiceNull_throwsClearException() throws Exception {
        HistoricalBackfillService service = new HistoricalBackfillService(
                repository,
                checkpoint,
                ibkrService,
                noopRateLimiter,
                List.of("AAPL"),
                BACKFILL_START,
                yfinanceClient,
                List.of(),
                List.of(),
                null   // TickerService intentionally null
        );

        assertThatThrownBy(() ->
                service.run(new DefaultApplicationArguments("--backfill", "--backfill-all-tickers")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("--backfill-all-tickers");
    }
}
