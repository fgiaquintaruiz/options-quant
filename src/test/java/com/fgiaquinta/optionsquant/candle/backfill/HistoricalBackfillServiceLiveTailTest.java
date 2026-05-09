package com.fgiaquinta.optionsquant.candle.backfill;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.google.common.util.concurrent.RateLimiter;
import com.fgiaquinta.optionsquant.candle.CandleRepository;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.service.IbkrService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Tests for the live-tail period feature: chunks beyond the last configured
 * critical period must be treated as the dynamic "live_tail" range
 * [max(periods.to)+1day, now()] so that backfill keeps the database current.
 *
 * <p>Bug under test: with hardcoded periods like
 * <pre>candles.backfill.periods = [..., {from: 2022-01, to: 2022-11}]</pre>
 * any chunk after 2022-11 is skipped as OUT_OF_RANGE. The live_tail period must
 * close that gap automatically.
 */
@ExtendWith(MockitoExtension.class)
class HistoricalBackfillServiceLiveTailTest {

    @Mock private CandleRepository repository;
    @Mock private BackfillCheckpoint checkpoint;
    @Mock private IbkrService ibkrService;
    @Mock private RateLimiter rateLimiter;
    @Mock private YfinanceHistoricalClient yfinanceClient;

    private static final ZonedDateTime BACKFILL_START =
            ZonedDateTime.of(2018, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

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

    private HistoricalBackfillService buildService(List<HistoricalBackfillService.BackfillPeriod> configuredPeriods) {
        BackfillProperties props = new BackfillProperties() {
            @Override
            public List<HistoricalBackfillService.BackfillPeriod> parsedPeriods() {
                return configuredPeriods;
            }
        };

        return new HistoricalBackfillService(
                repository,
                checkpoint,
                ibkrService,
                rateLimiter,
                List.of("AAPL"),
                BACKFILL_START,
                yfinanceClient,
                List.of(),
                props.parsedPeriodsWithLiveTail()
        );
    }

    // -------------------------------------------------------------------------
    // (a) Chunk inside a critical period must NOT be skipped
    // -------------------------------------------------------------------------
    @Test
    void chunk_dentroDePeriodoCritico_noSkipea() {
        var period = new HistoricalBackfillService.BackfillPeriod(
                LocalDate.of(2022, 1, 1), LocalDate.of(2022, 11, 30));

        HistoricalBackfillService service = buildService(List.of(period));

        ZonedDateTime chunkFrom = ZonedDateTime.of(2022, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        ZonedDateTime chunkTo   = ZonedDateTime.of(2022, 7, 1, 0, 0, 0, 0, ZoneOffset.UTC);

        assertThat(service.isChunkInAnyPeriod(chunkFrom, chunkTo))
                .as("Chunk fully inside critical period must be considered in-range")
                .isTrue();
    }

    // -------------------------------------------------------------------------
    // (b) Chunk in a gap between two critical periods must be skipped
    // -------------------------------------------------------------------------
    @Test
    void chunk_entreDosPeriodosCriticos_skipeaOutOfRange() {
        var p1 = new HistoricalBackfillService.BackfillPeriod(
                LocalDate.of(2019, 1, 1), LocalDate.of(2019, 12, 31));
        var p2 = new HistoricalBackfillService.BackfillPeriod(
                LocalDate.of(2022, 1, 1), LocalDate.of(2022, 11, 30));

        HistoricalBackfillService service = buildService(List.of(p1, p2));

        ZonedDateTime chunkFrom = ZonedDateTime.of(2020, 8, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        ZonedDateTime chunkTo   = ZonedDateTime.of(2020, 9, 1, 0, 0, 0, 0, ZoneOffset.UTC);

        assertThat(service.isChunkInAnyPeriod(chunkFrom, chunkTo))
                .as("Chunk in the gap between two periods must be OUT_OF_RANGE")
                .isFalse();
    }

    // -------------------------------------------------------------------------
    // (c) Chunk in the live_tail range must NOT be skipped, and the log
    //     line emitted while downloading must include the "[live_tail]" tag.
    // -------------------------------------------------------------------------
    @Test
    void chunk_dentroDeLiveTailPeriod_noSkipeaYLogMarcaLiveTail() throws Exception {
        var period = new HistoricalBackfillService.BackfillPeriod(
                LocalDate.of(2022, 1, 1), LocalDate.of(2022, 11, 30));

        HistoricalBackfillService service = buildService(List.of(period));

        // Pick a chunk that should fall inside the live_tail (after 2022-11)
        // but before "now" so the production code's chunkFrom.isBefore(now) test holds.
        ZonedDateTime chunkFrom = ZonedDateTime.of(2024, 3, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        ZonedDateTime chunkTo   = ZonedDateTime.of(2024, 4, 1, 0, 0, 0, 0, ZoneOffset.UTC);

        assertThat(service.isChunkInAnyPeriod(chunkFrom, chunkTo))
                .as("Chunk inside live_tail must be in-range")
                .isTrue();

        // Drive an actual chunk download for AAPL DAY_1 starting near the live_tail
        // so the service emits the live_tail-tagged log line.
        ZonedDateTime nowMinusEpsilon = ZonedDateTime.now(ZoneOffset.UTC).minusDays(2);
        when(checkpoint.getLastDownloaded(eq("AAPL"), eq(TimeFrame.DAY_1)))
                .thenReturn(Optional.of(nowMinusEpsilon));
        when(checkpoint.getLastDownloaded(eq("AAPL"), argThat(tf -> tf != TimeFrame.DAY_1)))
                .thenReturn(Optional.of(ZonedDateTime.now(ZoneOffset.UTC).plusYears(5)));
        when(yfinanceClient.fetchDailyCandles(anyString(), any(), any()))
                .thenReturn(Collections.emptyList());

        var args = new org.springframework.boot.DefaultApplicationArguments("--backfill");
        service.run(args);

        boolean liveTailLogged = logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .anyMatch(msg -> msg.contains("[live_tail]"));

        assertThat(liveTailLogged)
                .as("Expected at least one log line tagged [live_tail] when downloading a chunk in the live_tail window")
                .isTrue();
    }

    // -------------------------------------------------------------------------
    // (d) Chunk before the very first configured period must be skipped
    // -------------------------------------------------------------------------
    @Test
    void chunk_antesDePrimerPeriodo_skipeaOutOfRange() {
        var period = new HistoricalBackfillService.BackfillPeriod(
                LocalDate.of(2022, 1, 1), LocalDate.of(2022, 11, 30));

        HistoricalBackfillService service = buildService(List.of(period));

        ZonedDateTime chunkFrom = ZonedDateTime.of(2007, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        ZonedDateTime chunkTo   = ZonedDateTime.of(2007, 2, 1, 0, 0, 0, 0, ZoneOffset.UTC);

        assertThat(service.isChunkInAnyPeriod(chunkFrom, chunkTo))
                .as("Chunk strictly before the first configured period must be OUT_OF_RANGE")
                .isFalse();
    }
}
