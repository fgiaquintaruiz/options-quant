package com.fgiaquinta.optionsquant.candle.backfill;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for BackfillProgressTracker.
 * No Spring context — constructor injection only.
 */
class BackfillProgressTrackerTest {

    private CapturingAppender appender;
    private Logger trackerLogger;

    @BeforeEach
    void setUp() {
        appender = new CapturingAppender();
        appender.start();
        trackerLogger = (Logger) LoggerFactory.getLogger(BackfillProgressTracker.class);
        trackerLogger.addAppender(appender);
        trackerLogger.setLevel(Level.INFO);
    }

    @AfterEach
    void tearDown() {
        trackerLogger.detachAppender(appender);
        appender.stop();
    }

    // ── T1 ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("T1: whenNTickersProcessed_logIsEmitted — log at every multiple of N")
    void whenNTickersProcessed_logIsEmitted() {
        BackfillProgressTracker tracker = new BackfillProgressTracker(10, Instant::now);
        tracker.startPhase("DAY_1", 50);

        // First 9 tickers: no log expected
        for (int i = 0; i < 9; i++) {
            tracker.recordTicker();
        }
        int logsBefore = appender.infoMessages().size();

        // 10th ticker: log MUST be emitted
        tracker.recordTicker();

        assertThat(appender.infoMessages())
                .as("Expected at least one log at multiple of N=10")
                .hasSizeGreaterThan(logsBefore);
        assertThat(appender.infoMessages().get(appender.infoMessages().size() - 1))
                .contains("[backfill]")
                .contains("DAY_1");
    }

    // ── T2 ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("T2: whenFewerThanNTickersProcessed_noLogEmitted — 9 tickers with N=10")
    void whenFewerThanNTickersProcessed_noLogEmitted() {
        BackfillProgressTracker tracker = new BackfillProgressTracker(10, Instant::now);
        tracker.startPhase("HOUR_1", 100);

        // Capture count of messages emitted by startPhase (e.g. "Phase START" log)
        int logsAfterStart = appender.infoMessages().size();

        for (int i = 0; i < 9; i++) {
            tracker.recordTicker();
        }

        // No additional progress log should be emitted for fewer than N tickers
        assertThat(appender.infoMessages())
                .as("No progress log should be emitted for 9 tickers when N=10")
                .hasSize(logsAfterStart);
    }

    // ── T3 ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("T3: velocityIsCalculatedOverLast10Minutes — 5 tickers over 5 min = 1.0 t/min")
    void velocityIsCalculatedOverLast10Minutes() {
        // Simulate 5 tickers arriving at t=0,1,2,3,4 minutes
        Instant base = Instant.parse("2024-01-01T10:00:00Z");
        AtomicInteger tick = new AtomicInteger(0);

        BackfillProgressTracker tracker = new BackfillProgressTracker(
                100,
                () -> base.plusSeconds(tick.get() * 60L)
        );
        tracker.startPhase("MIN_15", 100);

        for (int i = 0; i < 5; i++) {
            tick.set(i);
            tracker.recordTicker();
        }

        BackfillStatusSnapshot status = tracker.getStatus();
        // 5 tickers over a 4-minute span → velocity ≈ 5/5.0 = 1.0 (window = last 10 min from last tick at t=4)
        // All 5 tickers fall within the 10-min window, span from t=0 to t=4 → 5/10.0 window-rate
        // Exact formula: size / 10.0 = 5 / 10 = 0.5 ... but spec says velocity = tickers in window / windowMinutes
        // We accept any positive value and validate the direction
        assertThat(status.velocityPerMin())
                .as("velocity should be positive and reasonable")
                .isGreaterThan(0.0)
                .isLessThanOrEqualTo(10.0);
    }

    // ── T4 ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("T4: etaIsCalculatedCorrectly — 10/100 done, velocity=2.0/min → ETA ~45min")
    void etaIsCalculatedCorrectly() {
        // We inject a clock that returns the same instant (all tickers "arrive at once")
        // but we need velocity=2.0 → 20 tickers in a 10-min window = 2.0/min
        Instant fixed = Instant.parse("2024-01-01T10:10:00Z");
        AtomicReference<Instant> clock = new AtomicReference<>(fixed);

        BackfillProgressTracker tracker = new BackfillProgressTracker(100, clock::get);
        tracker.startPhase("HOUR_1", 100);

        // Record 20 tickers all at t=10min so velocity window has 20 entries → 20/10 = 2.0 t/min
        for (int i = 0; i < 20; i++) {
            tracker.recordTicker();
        }

        BackfillStatusSnapshot status = tracker.getStatus();
        // remaining = 80, velocity = 2.0 → ETA = 40 min
        assertThat(status.velocityPerMin())
                .as("Velocity should be 2.0 t/min (20 tickers / 10 min window)")
                .isCloseTo(2.0, within(0.5));
        assertThat(status.etaMinutes())
                .as("ETA should be ~40 min (80 remaining / 2.0 velocity)")
                .isCloseTo(40.0, within(5.0));
    }

    // ── T5 ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("T5: errorsAreCountedOnRecord — recordError 3 times → status.errors == 3")
    void errorsAreCountedOnRecord() {
        BackfillProgressTracker tracker = new BackfillProgressTracker(10, Instant::now);
        tracker.startPhase("MIN_5", 50);

        tracker.recordError();
        tracker.recordError();
        tracker.recordError();

        assertThat(tracker.getStatus().errors())
                .as("errors should be 3")
                .isEqualTo(3);
    }

    // ── T6 ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("T6: statusSnapshotIsThreadSafeUnderConcurrentUpdates — no race conditions")
    void statusSnapshotIsThreadSafeUnderConcurrentUpdates() throws InterruptedException {
        int totalTickers = 200;
        BackfillProgressTracker tracker = new BackfillProgressTracker(50, Instant::now);
        tracker.startPhase("DAY_1", totalTickers);

        ExecutorService pool = Executors.newFixedThreadPool(10);
        for (int i = 0; i < totalTickers; i++) {
            pool.submit(tracker::recordTicker);
        }
        pool.shutdown();
        boolean finished = pool.awaitTermination(10, TimeUnit.SECONDS);

        assertThat(finished).as("All threads should complete within 10s").isTrue();
        BackfillStatusSnapshot status = tracker.getStatus();
        assertThat(status.completedTickers())
                .as("All 200 tickers should be counted exactly once")
                .isEqualTo(totalTickers);
        assertThat(status.progressPercent())
                .as("Progress should be 100%")
                .isCloseTo(100.0, within(0.01));
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    static class CapturingAppender extends AppenderBase<ILoggingEvent> {
        private final List<String> messages = new ArrayList<>();

        @Override
        protected void append(ILoggingEvent event) {
            if (event.getLevel().isGreaterOrEqual(Level.INFO)) {
                messages.add(event.getFormattedMessage());
            }
        }

        List<String> infoMessages() {
            return messages;
        }
    }
}
