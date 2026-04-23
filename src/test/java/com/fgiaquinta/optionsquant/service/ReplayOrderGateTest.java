package com.fgiaquinta.optionsquant.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayOrderGateTest {

    /** Mutable clock for deterministic window tests. */
    private static class MutableClock extends Clock {
        final AtomicReference<Instant> nowRef = new AtomicReference<>(Instant.parse("2026-04-22T14:30:00Z"));
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId z) { return this; }
        @Override public Instant instant() { return nowRef.get(); }
        void advance(Duration d) { nowRef.updateAndGet(i -> i.plus(d)); }
    }

    @Test
    void tryConsume_allowsUpToCapWithinWindow() {
        MutableClock c = new MutableClock();
        ReplayOrderGate gate = new ReplayOrderGate(c, 10);

        for (int i = 0; i < 10; i++) {
            assertTrue(gate.tryConsume(), "Order " + (i + 1) + " should be allowed");
        }
    }

    @Test
    void tryConsume_blocksOverCap() {
        MutableClock c = new MutableClock();
        ReplayOrderGate gate = new ReplayOrderGate(c, 10);

        for (int i = 0; i < 10; i++) gate.tryConsume();

        assertFalse(gate.tryConsume(), "11th order within 60s must be blocked");
    }

    @Test
    void tryConsume_allowsAgainAfterWindowRollsOff() {
        MutableClock c = new MutableClock();
        ReplayOrderGate gate = new ReplayOrderGate(c, 10);
        for (int i = 0; i < 10; i++) gate.tryConsume();
        assertFalse(gate.tryConsume());

        c.advance(Duration.ofSeconds(61));

        assertTrue(gate.tryConsume(), "After 61s the oldest entry has rolled off");
    }

    @Test
    void tryConsume_partialRollOffHonoursCap() {
        MutableClock c = new MutableClock();
        ReplayOrderGate gate = new ReplayOrderGate(c, 3);

        assertTrue(gate.tryConsume()); // t=0
        c.advance(Duration.ofSeconds(30));
        assertTrue(gate.tryConsume()); // t=30
        assertTrue(gate.tryConsume()); // t=30
        assertFalse(gate.tryConsume()); // blocked at cap=3

        c.advance(Duration.ofSeconds(31)); // t=61, only t=0 rolled off

        assertTrue(gate.tryConsume(), "One slot freed, 4th consume allowed");
        assertFalse(gate.tryConsume(), "Still at cap, 5th blocked");
    }

    @Test
    void reset_clearsWindow() {
        MutableClock c = new MutableClock();
        ReplayOrderGate gate = new ReplayOrderGate(c, 10);
        for (int i = 0; i < 10; i++) gate.tryConsume();
        assertFalse(gate.tryConsume());

        gate.reset();

        assertTrue(gate.tryConsume(), "Reset must clear the window");
    }

    @Test
    void count_reflectsCurrentWindow() {
        MutableClock c = new MutableClock();
        ReplayOrderGate gate = new ReplayOrderGate(c, 10);

        gate.tryConsume();
        gate.tryConsume();
        gate.tryConsume();

        assertEquals(3, gate.count());
    }
}
