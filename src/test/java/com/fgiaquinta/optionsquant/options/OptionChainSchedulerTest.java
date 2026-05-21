package com.fgiaquinta.optionsquant.options;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Tests OptionChainScheduler for correct cron expressions and delegation behaviour.
 */
class OptionChainSchedulerTest {

    private OptionChainRecorderService recorderService;
    private OptionChainScheduler scheduler;

    private static final ZoneId ET = ZoneId.of("America/New_York");

    @BeforeEach
    void setUp() {
        recorderService = mock(OptionChainRecorderService.class);
        scheduler = new OptionChainScheduler(recorderService);
    }

    // ─── @Scheduled annotation verification ──────────────────────────────────

    @Test
    @DisplayName("scheduledSnapshot15m has correct cron (hour 9 only) and ET zone")
    void scheduledSnapshot15m_hasCorrectCronAnnotation() throws Exception {
        Method method = OptionChainScheduler.class.getDeclaredMethod("scheduledSnapshot15m");
        Scheduled annotation = method.getAnnotation(Scheduled.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.cron()).isEqualTo("0 30/15 9 * * MON-FRI");
        assertThat(annotation.zone()).isEqualTo("America/New_York");
    }

    @Test
    @DisplayName("scheduledSnapshot15mFullHours has correct cron (hours 10-15) and ET zone")
    void scheduledSnapshot15mFullHours_hasCorrectCronAnnotation() throws Exception {
        Method method = OptionChainScheduler.class.getDeclaredMethod("scheduledSnapshot15mFullHours");
        Scheduled annotation = method.getAnnotation(Scheduled.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.cron()).isEqualTo("0 0/15 10-15 * * MON-FRI");
        assertThat(annotation.zone()).isEqualTo("America/New_York");
    }

    @Test
    @DisplayName("scheduledSnapshot5mOpening has correct cron and ET zone")
    void scheduledSnapshot5mOpening_hasCorrectCronAnnotation() throws Exception {
        Method method = OptionChainScheduler.class.getDeclaredMethod("scheduledSnapshot5mOpening");
        Scheduled annotation = method.getAnnotation(Scheduled.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.cron()).isEqualTo("0 30/5 9 * * MON-FRI");
        assertThat(annotation.zone()).isEqualTo("America/New_York");
    }

    // ─── delegation to recorderService ───────────────────────────────────────

    @Test
    @DisplayName("runSnapshotForAllTickers calls snapshotAsync once per ticker (16 tickers)")
    void runSnapshotForAllTickers_calls16Times() {
        scheduler.runSnapshotForAllTickers("SCHEDULED");

        // 16 active tickers — one snapshotAsync call each
        verify(recorderService, times(16))
                .snapshotAsync(anyString(), anyString(), isNull(), eq("BOTH"), eq("SCHEDULED"));
    }

    @Test
    @DisplayName("runSnapshotForAllTickers includes all 16 expected tickers")
    void runSnapshotForAllTickers_includesAllExpectedTickers() {
        scheduler.runSnapshotForAllTickers("SCHEDULED");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(recorderService, times(16))
                .snapshotAsync(captor.capture(), anyString(), isNull(), anyString(), anyString());

        List<String> capturedTickers = captor.getAllValues();
        assertThat(capturedTickers).containsExactlyInAnyOrder(
                "NVDA", "AMD", "AMDL", "TSLA", "META", "AVGO",
                "COIN", "MSTR", "AMZN", "SPY", "AAPL", "URA",
                "MU", "SMH", "OXY", "GLD"
        );
    }

    @Test
    @DisplayName("scheduledSnapshot5mOpening skips when time is past 9:40 ET")
    void scheduledSnapshot5mOpening_skipsAfter940() {
        // Inject a clock that returns 9:45 ET — should NOT fire
        ZonedDateTime past940 = ZonedDateTime.now(ET)
                .withHour(9).withMinute(45).withSecond(0).withNano(0);

        scheduler.runSnapshot5mOpeningAt(past940);

        verifyNoInteractions(recorderService);
    }

    @Test
    @DisplayName("scheduledSnapshot5mOpening fires at 9:30 ET")
    void scheduledSnapshot5mOpening_firesAt930() {
        ZonedDateTime at930 = ZonedDateTime.now(ET)
                .withHour(9).withMinute(30).withSecond(0).withNano(0);

        scheduler.runSnapshot5mOpeningAt(at930);

        verify(recorderService, times(16))
                .snapshotAsync(anyString(), anyString(), isNull(), eq("BOTH"), eq("SCHEDULED_OPENING"));
    }

    @Test
    @DisplayName("scheduledSnapshot5mOpening fires at 9:40 ET (boundary — inclusive)")
    void scheduledSnapshot5mOpening_firesAt940() {
        ZonedDateTime at940 = ZonedDateTime.now(ET)
                .withHour(9).withMinute(40).withSecond(0).withNano(0);

        scheduler.runSnapshot5mOpeningAt(at940);

        verify(recorderService, times(16))
                .snapshotAsync(anyString(), anyString(), isNull(), eq("BOTH"), eq("SCHEDULED_OPENING"));
    }
}
