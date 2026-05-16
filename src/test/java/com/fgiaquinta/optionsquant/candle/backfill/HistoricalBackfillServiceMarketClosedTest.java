package com.fgiaquinta.optionsquant.candle.backfill;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.common.util.concurrent.RateLimiter;
import com.fgiaquinta.optionsquant.candle.CandleRepository;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.service.IbkrService;
import com.fgiaquinta.optionsquant.service.MarketCalendarService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;
import org.slf4j.LoggerFactory;
import org.springframework.boot.DefaultApplicationArguments;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the market-closed skip behavior in the live_tail phase.
 *
 * <p>When the US equity market (NYSE/NASDAQ) is closed, the live_tail phase must be
 * skipped entirely. Historical backfill chunks must remain unaffected.
 *
 * <p>TDD order: tests were written RED before the implementation existed.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HistoricalBackfillServiceMarketClosedTest {

    @Mock private CandleRepository repository;
    @Mock private BackfillCheckpoint checkpoint;
    @Mock private IbkrService ibkrService;
    @Mock private YfinanceHistoricalClient yfinanceClient;
    @Mock private MarketCalendarService marketCalendarService;

    private final RateLimiter noopRateLimiter = RateLimiter.create(Double.MAX_VALUE);

    private static final ZonedDateTime BACKFILL_START =
            ZonedDateTime.of(2018, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    private static final ZonedDateTime FUTURE =
            ZonedDateTime.now(ZoneOffset.UTC).plusYears(10);

    // A live_tail period: yesterday → today, so it definitely intersects live chunks
    private static final List<HistoricalBackfillService.BackfillPeriod> PERIODS_WITH_LIVE_TAIL =
            List.of(
                    new HistoricalBackfillService.BackfillPeriod(
                            LocalDate.of(2022, 1, 1), LocalDate.of(2022, 11, 30)),
                    new HistoricalBackfillService.BackfillPeriod(
                            LocalDate.of(2022, 12, 1), LocalDate.now(ZoneOffset.UTC))
            );

    private ListAppender<ILoggingEvent> logAppender;
    private Logger backfillLogger;

    @BeforeEach
    void setUp() {
        logAppender = new ListAppender<>();
        backfillLogger = (Logger) LoggerFactory.getLogger(HistoricalBackfillService.class);
        logAppender.start();
        backfillLogger.addAppender(logAppender);
        backfillLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void tearDown() {
        if (backfillLogger != null && logAppender != null) {
            backfillLogger.detachAppender(logAppender);
            logAppender.stop();
        }
    }

    private HistoricalBackfillService buildService(
            List<HistoricalBackfillService.BackfillPeriod> periods,
            MarketCalendarService calendar) {
        return new HistoricalBackfillService(
                repository, checkpoint, ibkrService, noopRateLimiter,
                List.of("AAPL"), BACKFILL_START, yfinanceClient, List.of(),
                periods, null, calendar
        );
    }

    // -------------------------------------------------------------------------
    // MC-1: when market is CLOSED → no live_tail TWS/yfinance calls dispatched
    // -------------------------------------------------------------------------

    @Test
    void whenMarketIsClosed_liveTailPhaseIsSkipped() throws Exception {
        when(marketCalendarService.isRegularMarketHours(any())).thenReturn(false);

        // All historical chunks already done; only the live_tail chunk remains
        when(checkpoint.getLastDownloaded(eq("AAPL"), eq(TimeFrame.DAY_1), eq(ChunkOrigin.HISTORICAL)))
                .thenReturn(Optional.of(ZonedDateTime.now(ZoneOffset.UTC).minusDays(2)));
        when(checkpoint.getLastDownloaded(eq("AAPL"), argThat(tf -> tf != TimeFrame.DAY_1), eq(ChunkOrigin.HISTORICAL)))
                .thenReturn(Optional.of(FUTURE));

        HistoricalBackfillService service = buildService(PERIODS_WITH_LIVE_TAIL, marketCalendarService);

        service.run(new DefaultApplicationArguments("--backfill"));

        // With market closed, any chunk in live_tail must NOT trigger a download
        verify(yfinanceClient, never()).fetchDailyCandles(anyString(), any(), any());
        verify(ibkrService, never()).downloadHistoricalData(anyString(), any(), any());
    }

    // -------------------------------------------------------------------------
    // MC-2: when market is CLOSED → log line contains expected message
    // -------------------------------------------------------------------------

    @Test
    void whenMarketIsClosed_logsSkipMessage() throws Exception {
        when(marketCalendarService.isRegularMarketHours(any())).thenReturn(false);

        // All historical chunks already done; only the live_tail chunk remains
        when(checkpoint.getLastDownloaded(eq("AAPL"), eq(TimeFrame.DAY_1), eq(ChunkOrigin.HISTORICAL)))
                .thenReturn(Optional.of(ZonedDateTime.now(ZoneOffset.UTC).minusDays(2)));
        when(checkpoint.getLastDownloaded(eq("AAPL"), argThat(tf -> tf != TimeFrame.DAY_1), eq(ChunkOrigin.HISTORICAL)))
                .thenReturn(Optional.of(FUTURE));

        HistoricalBackfillService service = buildService(PERIODS_WITH_LIVE_TAIL, marketCalendarService);

        service.run(new DefaultApplicationArguments("--backfill"));

        boolean marketClosedLogged = logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .anyMatch(msg -> msg.contains("[live_tail]") && msg.contains("Market closed"));

        assertThat(marketClosedLogged)
                .as("Expected a log line containing '[live_tail]' and 'Market closed' when market is closed")
                .isTrue();
    }

    // -------------------------------------------------------------------------
    // MC-3: when market is OPEN → live_tail chunks are downloaded normally
    // -------------------------------------------------------------------------

    @Test
    void whenMarketIsOpen_liveTailPhaseRunsNormally() throws Exception {
        when(marketCalendarService.isRegularMarketHours(any())).thenReturn(true);
        when(yfinanceClient.fetchDailyCandles(anyString(), any(), any()))
                .thenReturn(Collections.emptyList());

        // All historical chunks already done; only the live_tail chunk remains
        when(checkpoint.getLastDownloaded(eq("AAPL"), eq(TimeFrame.DAY_1), eq(ChunkOrigin.HISTORICAL)))
                .thenReturn(Optional.of(ZonedDateTime.now(ZoneOffset.UTC).minusDays(2)));
        when(checkpoint.getLastDownloaded(eq("AAPL"), argThat(tf -> tf != TimeFrame.DAY_1), eq(ChunkOrigin.HISTORICAL)))
                .thenReturn(Optional.of(FUTURE));

        HistoricalBackfillService service = buildService(PERIODS_WITH_LIVE_TAIL, marketCalendarService);

        service.run(new DefaultApplicationArguments("--backfill"));

        // Market is open — live_tail DAY_1 chunk must proceed to yfinance
        verify(yfinanceClient, atLeastOnce()).fetchDailyCandles(anyString(), any(), any());
    }

    // -------------------------------------------------------------------------
    // MC-4: market check is called ONCE per run, not per ticker/timeframe
    // -------------------------------------------------------------------------

    @Test
    void marketCheck_isCalledOncePerRun() throws Exception {
        when(marketCalendarService.isRegularMarketHours(any())).thenReturn(false);

        when(checkpoint.getLastDownloaded(eq("AAPL"), eq(TimeFrame.DAY_1), eq(ChunkOrigin.HISTORICAL)))
                .thenReturn(Optional.of(ZonedDateTime.now(ZoneOffset.UTC).minusDays(2)));
        when(checkpoint.getLastDownloaded(eq("AAPL"), argThat(tf -> tf != TimeFrame.DAY_1), eq(ChunkOrigin.HISTORICAL)))
                .thenReturn(Optional.of(FUTURE));

        HistoricalBackfillService service = buildService(PERIODS_WITH_LIVE_TAIL, marketCalendarService);

        service.run(new DefaultApplicationArguments("--backfill"));

        // isRegularMarketHours must be invoked exactly ONCE per run, regardless of ticker/TF count
        verify(marketCalendarService, times(1)).isRegularMarketHours(any());
    }

    // -------------------------------------------------------------------------
    // MC-5: no live_tail configured → market check is NOT performed at all
    // -------------------------------------------------------------------------

    @Test
    void whenNoLiveTailConfigured_marketCheckIsNotPerformed() throws Exception {
        // Periods with no live_tail (last period ends in the past, not today)
        List<HistoricalBackfillService.BackfillPeriod> periodsNoLiveTail = List.of(
                new HistoricalBackfillService.BackfillPeriod(
                        LocalDate.of(2022, 1, 1), LocalDate.of(2022, 11, 30))
        );

        when(checkpoint.getLastDownloaded(anyString(), any(), any(ChunkOrigin.class))).thenReturn(Optional.of(FUTURE));

        HistoricalBackfillService service = buildService(periodsNoLiveTail, marketCalendarService);

        service.run(new DefaultApplicationArguments("--backfill"));

        // No live_tail → market calendar must never be consulted
        verify(marketCalendarService, never()).isRegularMarketHours(any());
    }
}
