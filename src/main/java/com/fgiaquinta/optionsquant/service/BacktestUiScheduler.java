package com.fgiaquinta.optionsquant.service;

import com.fgiaquinta.optionsquant.controller.BacktestDashboardController;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically runs a full UI backtest when the user has enabled auto-run via
 * {@code POST /backtest-ui/scheduler}. Delay is {@code backtest.scheduler.fixed-delay-ms}
 * (time after the previous run completes).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BacktestUiScheduler {

    private final BacktestDashboardController backtestDashboardController;

    @Scheduled(fixedDelayString = "${backtest.scheduler.fixed-delay-ms:3600000}")
    public void runScheduledBacktest() {
        try {
            backtestDashboardController.runScheduledBacktestTick();
        } catch (Exception e) {
            log.warn("Scheduled backtest tick failed: {}", e.getMessage());
        }
    }
}
