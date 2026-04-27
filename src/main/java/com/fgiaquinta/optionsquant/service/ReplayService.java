package com.fgiaquinta.optionsquant.service;

import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrator for live-replay-mode. Validates preconditions, preloads
 * candles via {@link ReplayCandleSource}, activates the virtual clock,
 * and starts the {@link ReplayScheduler}.
 */
@Slf4j
@Service
public class ReplayService {

    public static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    private static final LocalTime MARKET_OPEN = LocalTime.of(10, 0);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(22, 0);
    private static final List<TimeFrame> REPLAY_TIMEFRAMES = List.of(TimeFrame.MIN_5, TimeFrame.MIN_15, TimeFrame.HOUR_1);
    // US session virtual-open (14:30 UTC = 09:30 ET)
    private static final LocalTime US_SESSION_OPEN_UTC = LocalTime.of(14, 30);

    public static class ReplayRejectedException extends RuntimeException {
        public ReplayRejectedException(String message) { super(message); }
        public ReplayRejectedException(String message, Throwable cause) { super(message, cause); }
    }

    public record StartResult(String runId, ZonedDateTime virtualNow, int speed) {}

    private final ReplayClock clock;
    private final ReplayCandleSource source;
    private final ReplayScheduler scheduler;
    private final OrderExecutionService orderService;
    private final IbkrProperties ibkrProperties;
    private final Clock wallClock;

    @org.springframework.beans.factory.annotation.Autowired
    public ReplayService(ReplayClock clock, ReplayCandleSource source, ReplayScheduler scheduler,
                         OrderExecutionService orderService, IbkrProperties ibkrProperties) {
        this(clock, source, scheduler, orderService, ibkrProperties, Clock.system(MADRID));
    }

    // Package-private for tests (injectable wall clock).
    ReplayService(ReplayClock clock, ReplayCandleSource source, ReplayScheduler scheduler,
                  OrderExecutionService orderService, IbkrProperties ibkrProperties, Clock wallClock) {
        this.clock = clock;
        this.source = source;
        this.scheduler = scheduler;
        this.orderService = orderService;
        this.ibkrProperties = ibkrProperties;
        this.wallClock = wallClock;
    }

    public StartResult start(LocalDate replayDate, int speed) {
        if (clock.isActive()) throw new ReplayRejectedException("already-active");
        if (!ReplayClock.VALID_SPEEDS.contains(speed))
            throw new ReplayRejectedException("invalid-speed: " + speed);
        if (isRealMarketOpen()) throw new ReplayRejectedException("market-open");
        if (!orderService.isConnected()) throw new ReplayRejectedException("tws-disconnected");

        Set<String> tickers = new HashSet<>(ibkrProperties.hotTickers());
        try {
            source.preload(replayDate, tickers, REPLAY_TIMEFRAMES);
        } catch (ReplayCandleSource.MissingDataException e) {
            throw new ReplayRejectedException(e.getMessage(), e);
        }

        String runId = "R-" + UUID.randomUUID().toString().substring(0, 8);
        ZonedDateTime virtualOpen = replayDate.atTime(US_SESSION_OPEN_UTC).atZone(ZoneId.of("UTC"));
        clock.activate(virtualOpen, speed, runId);
        scheduler.start();

        log.info("🎬 Replay started — runId={} date={} speed={}x tickers={}",
                runId, replayDate, speed, tickers.size());
        return new StartResult(runId, virtualOpen, speed);
    }

    public void stop() {
        log.info("🛑 Replay stop requested");
        scheduler.stop();
        clock.deactivate();
        source.clear();
    }

    public void setSpeed(int speed) {
        clock.setSpeed(speed);
    }

    public ReplayClock.State status() {
        return clock.snapshot();
    }

    private boolean isRealMarketOpen() {
        ZonedDateTime nowMadrid = ZonedDateTime.now(wallClock.withZone(MADRID));
        DayOfWeek day = nowMadrid.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return false;
        LocalTime t = nowMadrid.toLocalTime();
        return !t.isBefore(MARKET_OPEN) && t.isBefore(MARKET_CLOSE);
    }
}
