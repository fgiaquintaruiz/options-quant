package com.fgiaquinta.optionsquant.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayClockTest {

    private static final ZonedDateTime SESSION_OPEN =
            ZonedDateTime.of(2026, 4, 22, 14, 30, 0, 0, ZoneId.of("UTC"));

    @Test
    void getNow_returnsRealTimeWhenInactive() {
        ReplayClock clock = new ReplayClock();

        ZonedDateTime before = ZonedDateTime.now();
        ZonedDateTime reported = clock.getNow();
        ZonedDateTime after = ZonedDateTime.now();

        assertFalse(clock.snapshot().active());
        assertFalse(reported.isBefore(before), "getNow must be >= wall time when inactive");
        assertFalse(reported.isAfter(after.plusSeconds(1)), "getNow must be ~ wall time when inactive");
    }

    @Test
    void activate_setsStateAndVirtualNow() {
        ReplayClock clock = new ReplayClock();

        clock.activate(SESSION_OPEN, 60, "run-001");

        ReplayClock.State state = clock.snapshot();
        assertTrue(state.active());
        assertEquals(SESSION_OPEN, state.virtualNow());
        assertEquals(60, state.speed());
        assertEquals("run-001", state.runId());
        assertEquals(SESSION_OPEN, clock.getNow());
    }

    @Test
    void advance_movesVirtualTimeForward() {
        ReplayClock clock = new ReplayClock();
        clock.activate(SESSION_OPEN, 60, "run-001");

        clock.advance(Duration.ofMinutes(15));

        assertEquals(SESSION_OPEN.plusMinutes(15), clock.getNow());
        assertEquals(SESSION_OPEN.plusMinutes(15), clock.snapshot().virtualNow());
    }

    @Test
    void advance_isCumulative() {
        ReplayClock clock = new ReplayClock();
        clock.activate(SESSION_OPEN, 60, "run-001");

        clock.advance(Duration.ofMinutes(15));
        clock.advance(Duration.ofMinutes(15));

        assertEquals(SESSION_OPEN.plusMinutes(30), clock.getNow());
    }

    @Test
    void setSpeed_preservesVirtualTime() {
        ReplayClock clock = new ReplayClock();
        clock.activate(SESSION_OPEN, 30, "run-001");
        clock.advance(Duration.ofMinutes(45));
        ZonedDateTime beforeSwitch = clock.getNow();

        clock.setSpeed(180);

        assertEquals(180, clock.snapshot().speed());
        assertEquals(beforeSwitch, clock.getNow());
    }

    @Test
    void setSpeed_rejectsInvalidPresets() {
        ReplayClock clock = new ReplayClock();
        clock.activate(SESSION_OPEN, 60, "run-001");

        assertThrows(IllegalArgumentException.class, () -> clock.setSpeed(45));
        assertThrows(IllegalArgumentException.class, () -> clock.setSpeed(0));
        assertThrows(IllegalArgumentException.class, () -> clock.setSpeed(-60));
    }

    @Test
    void activate_rejectsInvalidSpeed() {
        ReplayClock clock = new ReplayClock();

        assertThrows(IllegalArgumentException.class,
                () -> clock.activate(SESSION_OPEN, 45, "run-001"));
    }

    @Test
    void deactivate_resetsStateAndRestoresRealTime() {
        ReplayClock clock = new ReplayClock();
        clock.activate(SESSION_OPEN, 60, "run-001");
        clock.advance(Duration.ofMinutes(15));

        clock.deactivate();

        ReplayClock.State state = clock.snapshot();
        assertFalse(state.active());
        assertNotEquals(SESSION_OPEN, clock.getNow(), "Must return to real time after deactivate");
    }

    @Test
    void activate_rejectsWhenAlreadyActive() {
        ReplayClock clock = new ReplayClock();
        clock.activate(SESSION_OPEN, 60, "run-001");

        assertThrows(IllegalStateException.class,
                () -> clock.activate(SESSION_OPEN.plusDays(1), 60, "run-002"));
    }

    @Test
    void isActive_reflectsLifecycle() {
        ReplayClock clock = new ReplayClock();
        assertFalse(clock.isActive());

        clock.activate(SESSION_OPEN, 60, "run-001");
        assertTrue(clock.isActive());

        clock.deactivate();
        assertFalse(clock.isActive());
    }

    @Test
    void runId_isNotNullWhenActive() {
        ReplayClock clock = new ReplayClock();
        clock.activate(SESSION_OPEN, 60, "run-xyz");

        assertNotNull(clock.snapshot().runId());
        assertEquals("run-xyz", clock.snapshot().runId());
    }
}
