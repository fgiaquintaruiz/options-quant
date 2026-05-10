package com.fgiaquinta.optionsquant.candle.backfill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Thread-safe tracker for backfill progress.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Counts completed tickers and errors per timeframe phase.</li>
 *   <li>Emits a structured INFO log every N tickers (R2.1).</li>
 *   <li>Exposes a {@link #getStatus()} snapshot for the REST endpoint (R2.2).</li>
 *   <li>Calculates rolling velocity (tickers/min) over the last 10 minutes.</li>
 * </ul>
 *
 * <p>Thread-safety: AtomicInteger for counters, volatile for scalars,
 * ConcurrentLinkedDeque for the sliding window.
 */
@Slf4j
@Component
public class BackfillProgressTracker {

    private static final int VELOCITY_WINDOW_MINUTES = 10;

    private final int logEveryN;
    private final Supplier<Instant> clock;

    // Mutable phase state — reset on startPhase()
    private final AtomicInteger completedTickers = new AtomicInteger(0);
    private final AtomicInteger totalTickers = new AtomicInteger(0);
    private final AtomicInteger errors = new AtomicInteger(0);
    private volatile String currentTimeframe = "IDLE";
    private volatile boolean running = false;

    /**
     * Sliding window: timestamps of recent ticker completions within the last 10 minutes.
     * Entries are added on each recordTicker() and pruned to keep only the last 10 minutes.
     */
    private final ConcurrentLinkedDeque<Instant> recentCompletions = new ConcurrentLinkedDeque<>();

    /**
     * Production constructor — Spring-managed. N is read from config property.
     */
    @Autowired
    public BackfillProgressTracker(
            @Value("${candles.backfill.progress-log-every-n-tickers:10}") int logEveryN) {
        this(logEveryN, Instant::now);
    }

    /**
     * Test constructor — allows injecting a deterministic clock.
     */
    BackfillProgressTracker(int logEveryN, Supplier<Instant> clock) {
        this.logEveryN = logEveryN;
        this.clock = clock;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Resets state and starts tracking a new timeframe phase.
     *
     * @param timeframe human-readable label (e.g. "DAY_1", "HOUR_1")
     * @param total     number of tickers to process in this phase
     */
    public void startPhase(String timeframe, int total) {
        completedTickers.set(0);
        totalTickers.set(total);
        errors.set(0);
        currentTimeframe = timeframe;
        running = true;
        recentCompletions.clear();
        log.info("[backfill] Phase START — {} ({} tickers)", timeframe, total);
    }

    /**
     * Records a successfully completed ticker.
     * Emits a progress log line when completedTickers is a multiple of {@code logEveryN}.
     */
    public void recordTicker() {
        Instant now = clock.get();
        recentCompletions.addLast(now);
        pruneWindow(now);

        int done = completedTickers.incrementAndGet();
        if (done % logEveryN == 0) {
            logProgress(done);
        }
    }

    /**
     * Records a fetch/download error (does NOT increment completedTickers).
     */
    public void recordError() {
        errors.incrementAndGet();
    }

    /**
     * Returns an immutable snapshot of the current state.
     * Safe to call from any thread at any time (including before {@link #startPhase}).
     */
    public BackfillStatusSnapshot getStatus() {
        int done = completedTickers.get();
        int total = totalTickers.get();
        double progress = total > 0 ? (done * 100.0) / total : 0.0;
        double velocity = calculateVelocity();
        double eta = calculateEta(done, total, velocity);

        return new BackfillStatusSnapshot(
                currentTimeframe,
                done,
                total,
                progress,
                velocity,
                eta,
                errors.get(),
                running
        );
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void pruneWindow(Instant now) {
        Instant cutoff = now.minusSeconds(VELOCITY_WINDOW_MINUTES * 60L);
        while (!recentCompletions.isEmpty() && recentCompletions.peekFirst().isBefore(cutoff)) {
            recentCompletions.pollFirst();
        }
    }

    /**
     * Velocity = number of entries in the 10-minute window / window size (10 min).
     */
    private double calculateVelocity() {
        Instant now = clock.get();
        pruneWindow(now);
        int windowCount = recentCompletions.size();
        return windowCount / (double) VELOCITY_WINDOW_MINUTES;
    }

    /**
     * ETA in minutes: remaining tickers / velocity.
     * Returns 0.0 when velocity is zero (no meaningful estimate).
     */
    private double calculateEta(int done, int total, double velocity) {
        if (velocity <= 0.0) return 0.0;
        int remaining = Math.max(0, total - done);
        return remaining / velocity;
    }

    private void logProgress(int done) {
        int total = totalTickers.get();
        double progress = total > 0 ? (done * 100.0) / total : 0.0;
        double velocity = calculateVelocity();
        double eta = calculateEta(done, total, velocity);
        int errs = errors.get();

        log.info("[backfill] {} — {}/{} tickers ({}) % | {} t/min | ETA: {} min | errors: {}",
                currentTimeframe, done, total,
                String.format("%.1f", progress),
                String.format("%.1f", velocity),
                String.format("%.0f", eta),
                errs);
    }
}
