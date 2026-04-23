package com.fgiaquinta.optionsquant.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReplaySchedulerTest {

    private static final ZonedDateTime SESSION_OPEN =
            ZonedDateTime.of(2026, 4, 22, 14, 30, 0, 0, ZoneId.of("UTC"));

    private ReplayClock clock;
    private MarketScanner scanner;
    private OrderExecutionService orderService;
    private ReplayScheduler scheduler;

    @BeforeEach
    void setUp() {
        clock = new ReplayClock();
        scanner = mock(MarketScanner.class);
        orderService = mock(OrderExecutionService.class);
        when(orderService.isConnected()).thenReturn(true);

        scheduler = new ReplayScheduler(clock, scanner, orderService);
    }

    @Test
    void tick_advancesClockByFifteenMinutesAndInvokesScanner() {
        clock.activate(SESSION_OPEN, 60, "run-1");

        scheduler.tick();

        assertThat(clock.getNow()).isEqualTo(SESSION_OPEN.plusMinutes(15));
        verify(scanner).scanAndExecute();
    }

    @Test
    void tick_cumulativelyAdvances() {
        clock.activate(SESSION_OPEN, 60, "run-1");

        scheduler.tick();
        scheduler.tick();
        scheduler.tick();

        assertThat(clock.getNow()).isEqualTo(SESSION_OPEN.plusMinutes(45));
    }

    @Test
    void tick_abortsAndDeactivatesWhenTwsDisconnected() {
        clock.activate(SESSION_OPEN, 60, "run-1");
        when(orderService.isConnected()).thenReturn(false);

        scheduler.tick();

        assertThat(clock.isActive()).isFalse();
        verify(scanner, never()).scanAndExecute();
    }

    @Test
    void tick_isNoOpWhenClockInactive() {
        // Clock not activated
        scheduler.tick();

        verify(scanner, never()).scanAndExecute();
    }

    @Test
    void computeNextDelayMillis_scalesInverselyWithSpeed() {
        long base = Duration.ofMinutes(15).toMillis();

        long at30x = ReplayScheduler.computeNextDelayMillis(30, 0);
        long at60x = ReplayScheduler.computeNextDelayMillis(60, 0);
        long at180x = ReplayScheduler.computeNextDelayMillis(180, 0);
        long at360x = ReplayScheduler.computeNextDelayMillis(360, 0);

        assertThat(at30x).isEqualTo(base / 30);
        assertThat(at60x).isEqualTo(base / 60);
        assertThat(at180x).isEqualTo(base / 180);
        assertThat(at360x).isEqualTo(base / 360);
    }

    @Test
    void computeNextDelayMillis_addsJitter() {
        long base = Duration.ofMinutes(15).toMillis() / 60;

        long withJitter100 = ReplayScheduler.computeNextDelayMillis(60, 100);
        long withJitter800 = ReplayScheduler.computeNextDelayMillis(60, 800);

        assertThat(withJitter100).isEqualTo(base + 100);
        assertThat(withJitter800).isEqualTo(base + 800);
    }
}
