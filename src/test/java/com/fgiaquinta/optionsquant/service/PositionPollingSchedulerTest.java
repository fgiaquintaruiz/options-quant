package com.fgiaquinta.optionsquant.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PositionPollingSchedulerTest {

    private AccountManager accountManager;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger schedulerLogger;

    @BeforeEach
    void setUp() {
        accountManager = mock(AccountManager.class);

        // Attach a ListAppender to the PositionPollingScheduler logger so we can assert log output
        schedulerLogger = (Logger) LoggerFactory.getLogger(PositionPollingScheduler.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        schedulerLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        schedulerLogger.detachAppender(logAppender);
    }

    // ── pollPositions() behavior ────────────────────────────────────────────────

    @Test
    @DisplayName("pollPositions_whenConnected_callsReqPositionsOnce")
    void pollPositions_whenConnected_callsReqPositionsOnce() {
        // GIVEN — AccountManager reports connected
        when(accountManager.isConnected()).thenReturn(true);
        PositionPollingScheduler scheduler = new PositionPollingScheduler(accountManager, 10_000L);

        // WHEN
        scheduler.pollPositions();

        // THEN — reqPositions() invoked exactly once
        verify(accountManager, times(1)).reqPositions();
    }

    @Test
    @DisplayName("pollPositions_whenNotConnected_skipsReqPositions_andLogsWarn")
    void pollPositions_whenNotConnected_skipsReqPositions_andLogsWarn() {
        // GIVEN — AccountManager is disconnected
        when(accountManager.isConnected()).thenReturn(false);
        PositionPollingScheduler scheduler = new PositionPollingScheduler(accountManager, 10_000L);

        // WHEN
        scheduler.pollPositions();

        // THEN — reqPositions() must NOT be called
        verify(accountManager, never()).reqPositions();

        // AND — a WARN log entry is emitted with the expected message
        assertThat(logAppender.list)
                .filteredOn(e -> e.getLevel() == Level.WARN)
                .anyMatch(e -> e.getFormattedMessage()
                        .equals("AccountManager not connected, skipping position poll"));
    }

    // ── @PostConstruct property validation ─────────────────────────────────────

    @Test
    @DisplayName("validatePollInterval_withValueBelow5000_throwsIllegalStateException")
    void validatePollInterval_withValueBelow5000_throwsIllegalStateException() {
        // GIVEN — a property value below the minimum
        PositionPollingScheduler scheduler = new PositionPollingScheduler(accountManager, 4_000L);

        // THEN — validation must reject it
        assertThatThrownBy(scheduler::validatePollInterval)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ibkr.positions-poll-seconds must be between 5000 and 60000 (got: 4000)");
    }

    @Test
    @DisplayName("validatePollInterval_withValueAbove60000_throwsIllegalStateException")
    void validatePollInterval_withValueAbove60000_throwsIllegalStateException() {
        // GIVEN — a property value above the maximum
        PositionPollingScheduler scheduler = new PositionPollingScheduler(accountManager, 70_000L);

        // THEN
        assertThatThrownBy(scheduler::validatePollInterval)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ibkr.positions-poll-seconds must be between 5000 and 60000 (got: 70000)");
    }

    @Test
    @DisplayName("validatePollInterval_withExactDefault10000_doesNotThrow")
    void validatePollInterval_withExactDefault10000_doesNotThrow() {
        // GIVEN — the nominal default value
        PositionPollingScheduler scheduler = new PositionPollingScheduler(accountManager, 10_000L);

        // THEN — no exception
        scheduler.validatePollInterval();  // must not throw
    }

    @Test
    @DisplayName("validatePollInterval_withLowerBoundary5000_doesNotThrow")
    void validatePollInterval_withLowerBoundary5000_doesNotThrow() {
        // GIVEN — exactly at the lower boundary
        PositionPollingScheduler scheduler = new PositionPollingScheduler(accountManager, 5_000L);

        // THEN
        scheduler.validatePollInterval();  // must not throw
    }

    @Test
    @DisplayName("validatePollInterval_withUpperBoundary60000_doesNotThrow")
    void validatePollInterval_withUpperBoundary60000_doesNotThrow() {
        // GIVEN — exactly at the upper boundary
        PositionPollingScheduler scheduler = new PositionPollingScheduler(accountManager, 60_000L);

        // THEN
        scheduler.validatePollInterval();  // must not throw
    }
}
