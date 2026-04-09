package com.fgiaquinta.optionsquant.controller;

import com.fgiaquinta.optionsquant.service.TradingService;
import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST API for trading operations.
 */
@Slf4j
@RestController
@RequestMapping("/api/trading")
@RequiredArgsConstructor
public class TradingController {

    private final TradingService tradingService;

    /**
     * Scans all tickers and optionally auto-executes signals.
     * POST /api/trading/scan-and-execute?autoExecute=true&qty=1
     */
    @Timed(value = "trading.scanAndExecute", description = "Scan and optionally execute trades")
    @PostMapping("/scan-and-execute")
    public ResponseEntity<TradingService.TradingResult> scanAndExecute(
            @RequestParam(defaultValue = "false") boolean autoExecute,
            @RequestParam(defaultValue = "0") int qty
    ) {
        log.info(">>> POST /api/trading/scan-and-execute autoExecute={} qty={}", autoExecute, qty);

        TradingService.TradingResult result = tradingService.scanAndExecute(autoExecute, qty);

        log.info("<<< POST /api/trading/scan-and-execute - {} signals, {} executions",
                result.scanResult().totalSignals(), result.executions().size());
        return ResponseEntity.ok(result);
    }

    /**
     * Manually execute a single signal.
     * POST /api/trading/execute?ticker=AAPL&direction=CALL&qty=1&entryPrice=215.50&tp=217.50&sl=214.00
     */
    @Timed(value = "trading.executeSignal", description = "Execute a single trade")
    @PostMapping("/execute")
    public ResponseEntity<TradingService.ExecutionResult> executeSignal(
            @RequestParam String ticker,
            @RequestParam String direction,
            @RequestParam(defaultValue = "1") int qty,
            @RequestParam double entryPrice,
            @RequestParam double tp,
            @RequestParam double sl
    ) {
        log.info(">>> POST /api/trading/execute ticker={} direction={} qty={} entry={} tp={} sl={}",
                ticker, direction, qty, entryPrice, tp, sl);

        TradingService.ExecutionResult result = tradingService.executeSignal(ticker, direction, qty, entryPrice, tp, sl);

        log.info("<<< POST /api/trading/execute - success={}", result.success());
        return ResponseEntity.ok(result);
    }

    /**
     * Check if a ticker has valid options available.
     * GET /api/trading/check-options?ticker=AAPL
     */
    @GetMapping("/check-options")
    public ResponseEntity<TradingService.OptionCheckResult> checkOptions(
            @RequestParam String ticker
    ) {
        log.info(">>> GET /api/trading/check-options ticker={}", ticker);

        TradingService.OptionCheckResult result = tradingService.checkOptionChain(ticker);

        log.info("<<< GET /api/trading/check-options - ticker={} success={}", ticker, result.success());
        return ResponseEntity.ok(result);
    }
}
