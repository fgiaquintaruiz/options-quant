package com.fgiaquinta.optionsquant.controller;

import com.fgiaquinta.optionsquant.backtest.domain.BacktestConfig;
import com.fgiaquinta.optionsquant.backtest.domain.BacktestReport;
import com.fgiaquinta.optionsquant.backtest.engine.BacktestEngine;
import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * REST API for running backtests.
 */
@Slf4j
@RestController
@RequestMapping("/api/backtest")
@RequiredArgsConstructor
public class BacktestController {

    private final BacktestEngine backtestEngine;
    private final IbkrProperties ibkrProperties;

    /**
     * Run a backtest with default parameters.
     * POST /api/backtest/run?from=2025-01-01&to=2026-04-01&tickers=SPY,AAPL
     */
    @Timed(value = "backtest.run", description = "Run a backtest")
    @PostMapping("/run")
    public ResponseEntity<BacktestReport> run(
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(required = false) String tickers,
            @RequestParam(defaultValue = "50000") double initialCapital,
            @RequestParam(defaultValue = "0.02") double riskPct,
            @RequestParam(defaultValue = "0.005") double slippagePct,
            @RequestParam(defaultValue = "0.65") double commission,
            @RequestParam(defaultValue = "3") int maxConcurrent,
            @RequestParam(defaultValue = "MIN_15") TimeFrame execTimeframe
    ) {
        log.info(">>> POST /api/backtest/run from={} to={} tickers={}", from, to, tickers);

        LocalDate fromDate = LocalDate.parse(from);
        LocalDate toDate = LocalDate.parse(to);
        List<String> tickerList = tickers != null && !tickers.isEmpty()
                ? List.of(tickers.split(","))
                : ibkrProperties.tickers();

        BacktestConfig config = new BacktestConfig(
                tickerList, fromDate, toDate,
                initialCapital, riskPct, slippagePct, commission,
                maxConcurrent, execTimeframe, true
        );

        BacktestReport report = backtestEngine.run(config);

        log.info("<<< POST /api/backtest/run - {} trades, return={:.2f}%",
                report.totalTrades(), report.totalReturnPct() * 100);
        return ResponseEntity.ok(report);
    }

    /**
     * Run a backtest with the full configured ticker list.
     * POST /api/backtest/run-all?from=2025-01-01&to=2026-04-01
     */
    @PostMapping("/run-all")
    public ResponseEntity<BacktestReport> runAll(
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(defaultValue = "50000") double initialCapital,
            @RequestParam(defaultValue = "0.02") double riskPct
    ) {
        log.info(">>> POST /api/backtest/run-all from={} to={}", from, to);

        BacktestConfig config = BacktestConfig.defaults(
                ibkrProperties.tickers(),
                LocalDate.parse(from),
                LocalDate.parse(to)
        );

        BacktestReport report = backtestEngine.run(config);

        log.info("<<< POST /api/backtest/run-all - {} trades, return={:.2f}%",
                report.totalTrades(), report.totalReturnPct() * 100);
        return ResponseEntity.ok(report);
    }
}
