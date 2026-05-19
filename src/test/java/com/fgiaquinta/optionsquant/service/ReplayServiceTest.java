package com.fgiaquinta.optionsquant.service;

import com.fgiaquinta.optionsquant.domain.TimeFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReplayServiceTest {

    // 2026-04-23 Thursday 07:00 Madrid = before market open, safe for replay
    private static final Clock OFF_HOURS =
            Clock.fixed(Instant.parse("2026-04-23T05:00:00Z"), ZoneId.of("UTC"));
    // 2026-04-23 Thursday 16:00 Madrid = US market open (after 15:30 gate), replay should reject
    private static final Clock MARKET_OPEN =
            Clock.fixed(Instant.parse("2026-04-23T14:00:00Z"), ZoneId.of("UTC"));
    // 2026-04-23 Thursday 12:00 Madrid = US market closed, replay SHOULD be allowed (new gate: 15:30)
    private static final Clock MADRID_1200 =
            Clock.fixed(Instant.parse("2026-04-23T10:00:00Z"), ZoneId.of("UTC"));
    // 2026-04-23 Thursday 16:00 Madrid = US market open, replay should be blocked
    private static final Clock MADRID_1600 =
            Clock.fixed(Instant.parse("2026-04-23T14:00:00Z"), ZoneId.of("UTC"));

    private ReplayClock replayClock;
    private ReplayCandleSource source;
    private ReplayScheduler scheduler;
    private OrderExecutionService orderService;
    private TickerService tickerService;

    @BeforeEach
    void setUp() {
        replayClock = new ReplayClock();
        source = mock(ReplayCandleSource.class);
        scheduler = mock(ReplayScheduler.class);
        orderService = mock(OrderExecutionService.class);
        tickerService = mock(TickerService.class);
        when(tickerService.getHotTickers()).thenReturn(List.of("AAPL", "NVDA"));
        when(orderService.isConnected()).thenReturn(true);
    }

    private ReplayService newService(Clock wall) {
        return new ReplayService(replayClock, source, scheduler, orderService, tickerService, wall);
    }

    @Test
    void start_rejectsWhenRealMarketOpen() {
        ReplayService svc = newService(MARKET_OPEN);

        assertThatThrownBy(() -> svc.start(LocalDate.of(2026, 4, 22), 60))
                .isInstanceOf(ReplayService.ReplayRejectedException.class)
                .hasMessageContaining("market-open");

        verify(source, never()).preload(any(), any(), any());
        verify(scheduler, never()).start();
    }

    @Test
    void start_rejectsWhenTwsDisconnected() {
        when(orderService.isConnected()).thenReturn(false);
        ReplayService svc = newService(OFF_HOURS);

        assertThatThrownBy(() -> svc.start(LocalDate.of(2026, 4, 22), 60))
                .isInstanceOf(ReplayService.ReplayRejectedException.class)
                .hasMessageContaining("tws-disconnected");

        verify(scheduler, never()).start();
    }

    @Test
    void start_rejectsWhenAlreadyActive() {
        replayClock.activate(LocalDate.of(2026, 4, 22).atStartOfDay(ZoneId.of("UTC")), 60, "prev");
        ReplayService svc = newService(OFF_HOURS);

        assertThatThrownBy(() -> svc.start(LocalDate.of(2026, 4, 22), 60))
                .isInstanceOf(ReplayService.ReplayRejectedException.class)
                .hasMessageContaining("already-active");
    }

    @Test
    void start_rejectsInvalidSpeed() {
        ReplayService svc = newService(OFF_HOURS);

        assertThatThrownBy(() -> svc.start(LocalDate.of(2026, 4, 22), 45))
                .isInstanceOf(ReplayService.ReplayRejectedException.class)
                .hasMessageContaining("invalid-speed");
    }

    @Test
    void start_preloadsActivatesAndStartsScheduler_inOrder() {
        ReplayService svc = newService(OFF_HOURS);
        LocalDate date = LocalDate.of(2026, 4, 22);

        ReplayService.StartResult result = svc.start(date, 60);

        verify(source, times(1))
                .preload(eq(date), eq(java.util.Set.of("AAPL", "NVDA")), org.mockito.ArgumentMatchers.<List<TimeFrame>>any());
        assertThat(replayClock.isActive()).isTrue();
        assertThat(replayClock.snapshot().speed()).isEqualTo(60);
        verify(scheduler, times(1)).start();
        assertThat(result.runId()).isNotBlank();
    }

    @Test
    void start_rollsBackWhenPreloadFails() {
        doThrow(new ReplayCandleSource.MissingDataException("Missing NVDA"))
                .when(source).preload(any(), any(), any());
        ReplayService svc = newService(OFF_HOURS);

        assertThatThrownBy(() -> svc.start(LocalDate.of(2026, 4, 22), 60))
                .isInstanceOf(ReplayService.ReplayRejectedException.class)
                .hasMessageContaining("Missing NVDA");

        assertThat(replayClock.isActive()).isFalse();
        verify(scheduler, never()).start();
    }

    @Test
    void start_allowsReplayAt1200MadridWhenUsMarketClosed() {
        // 12:00 Madrid is before the new 15:30 gate — US market closed, replay should be allowed
        ReplayService svc = newService(MADRID_1200);

        ReplayService.StartResult result = svc.start(LocalDate.of(2026, 4, 22), 60);

        assertThat(result.runId()).isNotBlank();
        assertThat(replayClock.isActive()).isTrue();
    }

    @Test
    void start_rejectsAt1600MadridWhenUsMarketOpen() {
        // 16:00 Madrid is after the new 15:30 gate — US market open, replay should be blocked
        ReplayService svc = newService(MADRID_1600);

        assertThatThrownBy(() -> svc.start(LocalDate.of(2026, 4, 22), 60))
                .isInstanceOf(ReplayService.ReplayRejectedException.class)
                .hasMessageContaining("market-open");
    }

    @Test
    void stop_deactivatesClockAndStopsSchedulerAndClearsSource() {
        ReplayService svc = newService(OFF_HOURS);
        svc.start(LocalDate.of(2026, 4, 22), 60);

        svc.stop();

        assertThat(replayClock.isActive()).isFalse();
        verify(scheduler).stop();
        verify(source).clear();
    }

    @Test
    void setSpeed_updatesClockWhenActive() {
        ReplayService svc = newService(OFF_HOURS);
        svc.start(LocalDate.of(2026, 4, 22), 60);

        svc.setSpeed(180);

        assertThat(replayClock.snapshot().speed()).isEqualTo(180);
    }
}
