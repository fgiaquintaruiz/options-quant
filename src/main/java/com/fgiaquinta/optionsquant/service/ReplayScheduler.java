package com.fgiaquinta.optionsquant.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Drives the virtual clock forward during live-replay-mode. Each "tick"
 * advances the {@link ReplayClock} by 15 minutes (scanner cadence) and
 * invokes the live scanner pipeline via {@link MarketScanner#scanAndExecute}.
 *
 * Real-world delay between ticks = (15min / speedMultiplier) + jitter,
 * mimicking IBKR latency.
 */
@Slf4j
@Service
public class ReplayScheduler {

    private static final Duration TICK_STEP = Duration.ofMinutes(15);

    private final ReplayClock clock;
    private final MarketScanner scanner;
    private final OrderExecutionService orderService;
    private final Supplier<ZonedDateTime> wallClock;

    private int jitterMinMs = 100;
    private int jitterMaxMs = 800;

    private final AtomicReference<ScheduledExecutorService> executorRef = new AtomicReference<>();
    private final AtomicReference<ScheduledFuture<?>> taskRef = new AtomicReference<>();

    public ReplayScheduler(ReplayClock clock,
                           @org.springframework.context.annotation.Lazy MarketScanner scanner,
                           OrderExecutionService orderService) {
        this(clock, scanner, orderService, ZonedDateTime::now);
    }

    /** Package-private constructor for testing — accepts an injectable wall clock. */
    ReplayScheduler(ReplayClock clock,
                    MarketScanner scanner,
                    OrderExecutionService orderService,
                    Supplier<ZonedDateTime> wallClock) {
        this.clock = clock;
        this.scanner = scanner;
        this.orderService = orderService;
        this.wallClock = wallClock;
    }

    @Value("${replay.jitter-ms-min:100}")
    public void setJitterMinMs(int jitterMinMs) { this.jitterMinMs = jitterMinMs; }

    @Value("${replay.jitter-ms-max:800}")
    public void setJitterMaxMs(int jitterMaxMs) { this.jitterMaxMs = jitterMaxMs; }

    /** Single step: advance clock, then run one scan. No-op when clock inactive. */
    public void tick() {
        if (!clock.isActive()) return;
        if (!orderService.isConnected()) {
            log.warn("Replay aborting — TWS disconnected mid-run");
            clock.deactivate();
            stop();
            return;
        }
        clock.advance(TICK_STEP);
        ZonedDateTime virtualNow = clock.snapshot().virtualNow();
        if (!virtualNow.isBefore(wallClock.get())) {
            log.info("Replay auto-stop: virtualNow={} has reached wall-clock now={} — stopping",
                    virtualNow, wallClock.get());
            clock.deactivate();
            stop();
            return;
        }
        scanner.scanAndExecute();
    }

    public void start() {
        if (executorRef.get() != null) return;
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "replay-scheduler");
            t.setDaemon(true);
            return t;
        });
        executorRef.set(exec);
        scheduleNext(exec);
    }

    public void stop() {
        ScheduledFuture<?> task = taskRef.getAndSet(null);
        if (task != null) task.cancel(false);
        ScheduledExecutorService exec = executorRef.getAndSet(null);
        if (exec != null) exec.shutdownNow();
    }

    private void scheduleNext(ScheduledExecutorService exec) {
        if (!clock.isActive()) { stop(); return; }
        int speed = clock.snapshot().speed();
        int jitter = ThreadLocalRandom.current().nextInt(jitterMinMs, jitterMaxMs + 1);
        long delay = computeNextDelayMillis(speed, jitter);
        ScheduledFuture<?> fut = exec.schedule(() -> {
            try { tick(); } catch (Exception e) { log.error("Replay tick failed", e); }
            scheduleNext(exec);
        }, delay, TimeUnit.MILLISECONDS);
        taskRef.set(fut);
    }

    /** Pure helper: real-time delay between ticks given speed and jitter. */
    public static long computeNextDelayMillis(int speed, int jitterMs) {
        return (TICK_STEP.toMillis() / speed) + jitterMs;
    }
}
