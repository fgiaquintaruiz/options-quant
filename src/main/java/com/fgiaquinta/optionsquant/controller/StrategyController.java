package com.fgiaquinta.optionsquant.controller;

import com.fgiaquinta.optionsquant.service.StrategyScannerService;
import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST API for strategy scanning.
 */
@Slf4j
@RestController
@RequestMapping("/api/strategies")
@RequiredArgsConstructor
public class StrategyController {

    private final StrategyScannerService scannerService;

    @Timed(value = "strategies.scanAll", description = "Scan all tickers against all strategies")
    @PostMapping("/scan")
    public ResponseEntity<StrategyScannerService.ScanResult> scanAll(
            @RequestParam(defaultValue = "true") boolean includeTradePlans
    ) {
        log.info(">>> POST /api/strategies/scan includeTradePlans={}", includeTradePlans);

        StrategyScannerService.ScanResult result = scannerService.scanAll(includeTradePlans);

        log.info("<<< POST /api/strategies/scan - {} signals", result.totalSignals());
        return ResponseEntity.ok(result);
    }

    @Timed(value = "strategies.scanTicker", description = "Scan a single ticker against all strategies")
    @PostMapping("/scan/{ticker}")
    public ResponseEntity<List<StrategyScannerService.Signal>> scanTicker(
            @PathVariable String ticker,
            @RequestParam(defaultValue = "true") boolean includeTradePlans
    ) {
        log.info(">>> POST /api/strategies/scan/{} includeTradePlans={}", ticker, includeTradePlans);

        List<StrategyScannerService.Signal> signals = scannerService.scanTicker(ticker, includeTradePlans);

        log.info("<<< POST /api/strategies/scan/{} - {} signals", ticker, signals.size());
        return ResponseEntity.ok(signals);
    }
}
