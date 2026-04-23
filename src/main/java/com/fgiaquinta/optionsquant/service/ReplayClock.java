package com.fgiaquinta.optionsquant.service;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Virtual clock for live-replay-mode. When active, all scanner time queries
 * resolve to the virtual time; when inactive, returns real wall time.
 *
 * Thread-safe via AtomicReference swap.
 */
@Service
public class ReplayClock {

    public static final Set<Integer> VALID_SPEEDS = Set.of(30, 60, 180, 360);

    public record State(boolean active, ZonedDateTime virtualNow, int speed, String runId) {
        public static final State INACTIVE = new State(false, null, 0, null);
    }

    private final AtomicReference<State> stateRef = new AtomicReference<>(State.INACTIVE);

    public State snapshot() {
        return stateRef.get();
    }

    public boolean isActive() {
        return stateRef.get().active();
    }

    public ZonedDateTime getNow() {
        State current = stateRef.get();
        return current.active() ? current.virtualNow() : ZonedDateTime.now();
    }

    public void activate(ZonedDateTime virtualNow, int speed, String runId) {
        requireValidSpeed(speed);
        State proposed = new State(true, virtualNow, speed, runId);
        if (!stateRef.compareAndSet(State.INACTIVE, proposed)) {
            throw new IllegalStateException("ReplayClock already active");
        }
    }

    public void advance(Duration delta) {
        stateRef.updateAndGet(current -> {
            if (!current.active()) {
                throw new IllegalStateException("Cannot advance an inactive ReplayClock");
            }
            return new State(true, current.virtualNow().plus(delta), current.speed(), current.runId());
        });
    }

    public void setSpeed(int speed) {
        requireValidSpeed(speed);
        stateRef.updateAndGet(current -> {
            if (!current.active()) {
                throw new IllegalStateException("Cannot change speed on inactive ReplayClock");
            }
            return new State(true, current.virtualNow(), speed, current.runId());
        });
    }

    public void deactivate() {
        stateRef.set(State.INACTIVE);
    }

    private static void requireValidSpeed(int speed) {
        if (!VALID_SPEEDS.contains(speed)) {
            throw new IllegalArgumentException(
                "Invalid replay speed: " + speed + ". Valid presets: " + VALID_SPEEDS);
        }
    }
}
