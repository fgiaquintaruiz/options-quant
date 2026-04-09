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
     * POST /api/trading/scan-and-execute?autoExecute=true&qty=1&maxConcurrent=3
     */
    @Timed(value = "trading.scanAndExecute", description = "Scan and optionally execute trades")
    @PostMapping("/scan-and-execute")
    public ResponseEntity<TradingService.TradingResult> scanAndExecute(
            @RequestParam(defaultValue = "false") boolean autoExecute,
            @RequestParam(defaultValue = "0") int qty,
            @RequestParam(defaultValue = "3") int maxConcurrent
    ) {
        log.info(">>> POST /api/trading/scan-and-execute autoExecute={} qty={} maxConcurrent={}", autoExecute, qty, maxConcurrent);

        TradingService.TradingResult result = tradingService.scanAndExecute(autoExecute, qty, maxConcurrent);

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

    /**
     * Get account status: balance, active trades, 2% risk limit.
     * GET /api/trading/account-status
     */
    @GetMapping("/account-status")
    public ResponseEntity<AccountStatusResponse> accountStatus() {
        log.debug(">>> GET /api/trading/account-status");
        double balance = tradingService.getAccountBalance();
        double riskLimit = balance * 0.02;

        AccountStatusResponse response = new AccountStatusResponse(
                balance,
                riskLimit,
                tradingService.getActiveTradeCount(),
                tradingService.canOpenNewTrade(3)
        );

        log.debug("<<< GET /api/trading/account-status - balance=${,.2f}, active={}", balance, response.activeTrades());
        return ResponseEntity.ok(response);
    }

    /**
     * Connect to IBKR to sync account balance.
     * POST /api/trading/connect-account
     */
    @PostMapping("/connect-account")
    public ResponseEntity<AccountStatusResponse> connectAccount() {
        log.info(">>> POST /api/trading/connect-account");
        tradingService.connectAccountManager();

        double balance = tradingService.getAccountBalance();
        AccountStatusResponse response = new AccountStatusResponse(
                balance,
                balance * 0.02,
                tradingService.getActiveTradeCount(),
                true
        );

        log.info("<<< POST /api/trading/connect-account - balance=${,.2f}", balance);
        return ResponseEntity.ok(response);
    }

    public record AccountStatusResponse(
            double balance,
            double riskPerTrade,
            int activeTrades,
            boolean canTrade
    ) {}
}
