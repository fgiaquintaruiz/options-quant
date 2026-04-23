package com.fgiaquinta.optionsquant.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Sliding-window rate limiter for bracket orders during live-replay-mode.
 * At high speeds (60x+) the scanner can fire many signals in seconds; this
 * gate caps how many bracket placements reach TWS within a rolling 60s window.
 *
 * Not thread-safe at the deque level — callers must synchronize or invoke from
 * the replay scheduler thread only. MarketScanner integration invokes from its
 * scan thread which is serialized.
 */
@Service
public class ReplayOrderGate {

    private static final Duration WINDOW = Duration.ofSeconds(60);

    private final Clock clock;
    private final int maxPerWindow;
    private final Deque<Instant> hits = new ArrayDeque<>();

    public ReplayOrderGate(
            @Value("${replay.max-orders-per-minute:10}") int maxPerWindow) {
        this(Clock.systemUTC(), maxPerWindow);
    }

    /** Package-private for deterministic tests. */
    ReplayOrderGate(Clock clock, int maxPerWindow) {
        this.clock = clock;
        this.maxPerWindow = maxPerWindow;
    }

    /** Returns true if the order is allowed; false if it would exceed the cap. */
    public synchronized boolean tryConsume() {
        Instant now = clock.instant();
        evictExpired(now);
        if (hits.size() >= maxPerWindow) {
            return false;
        }
        hits.addLast(now);
        return true;
    }

    public synchronized int count() {
        evictExpired(clock.instant());
        return hits.size();
    }

    public synchronized void reset() {
        hits.clear();
    }

    private void evictExpired(Instant now) {
        Instant cutoff = now.minus(WINDOW);
        while (!hits.isEmpty() && !hits.peekFirst().isAfter(cutoff)) {
            hits.pollFirst();
        }
    }
}
