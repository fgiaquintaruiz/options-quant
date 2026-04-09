package com.fgiaquinta.optionsquant.service;

import com.fgiaquinta.optionsquant.config.IbkrProperties;
import com.fgiaquinta.optionsquant.strategy.model.TradePlan;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Orchestrates the full trading flow:
 * 1. Scan for signals (via StrategyScannerService)
 * 2. Optionally execute via OrderExecutionService (paper or live)
 */
@Slf4j
@Service
public class TradingService {

    private final StrategyScannerService scannerService;
    private final OrderExecutionService orderExecutionService;
    private final IbkrProperties ibkrProperties;

    public TradingService(StrategyScannerService scannerService, OrderExecutionService orderExecutionService, IbkrProperties ibkrProperties) {
        this.scannerService = scannerService;
        this.orderExecutionService = orderExecutionService;
        this.ibkrProperties = ibkrProperties;
    }

    /**
     * Scans all tickers and optionally auto-executes signals.
     *
     * @param autoExecute If true, places bracket orders for all signals (paper or live based on config)
     * @param qty Number of contracts per trade (overrides default if > 0)
     * @return Scan result with execution status
     */
    public TradingResult scanAndExecute(boolean autoExecute, int qty) {
        log.info(">>> scanAndExecute(autoExecute={}, qty={})", autoExecute, qty);
        long startTime = System.currentTimeMillis();

        // Step 1: Scan for signals
        StrategyScannerService.ScanResult scanResult = scannerService.scanAll(true, true);

        List<ExecutionResult> executions = new ArrayList<>();

        // Step 2: Execute if enabled and auto-execute is on in config
        if (autoExecute && ibkrProperties.autoExecute()) {
            int effectiveQty = qty > 0 ? qty : ibkrProperties.defaultQty();

            for (StrategyScannerService.Signal signal : scanResult.signals()) {
                try {
                    boolean isCall = "CALL".equals(signal.direction());
                    TradePlan plan = signal.tradePlan();

                    if (plan == null) {
                        log.warn("No trade plan for signal {} - skipping execution", signal);
                        executions.add(new ExecutionResult(signal, false, "No trade plan", null));
                        continue;
                    }

                    log.info("🎯 Executing: {} {} @ {} | TP={} SL={}",
                            signal.ticker(), signal.direction(), plan.entryPrice, plan.takeProfit, plan.stopLoss);

                    OrderExecutionService.OrderResult orderResult = orderExecutionService.placeOptionBracket(
                            signal.ticker(),
                            isCall,
                            effectiveQty,
                            plan,
                            signal.strategy()
                    );

                    if (orderResult != null) {
                        executions.add(new ExecutionResult(signal, true, "Order placed", orderResult));
                        log.info("✅ Order placed for {}: strike={}, expiry={}, orders=[{},{},{}]",
                                signal.ticker(), orderResult.strike(), orderResult.expiration(),
                                orderResult.parentId(), orderResult.tpOrderId(), orderResult.slOrderId());
                    } else {
                        executions.add(new ExecutionResult(signal, false, "Order placement failed", null));
                    }

                } catch (Exception e) {
                    log.error("Failed to execute signal {}: {}", signal, e.getMessage(), e);
                    executions.add(new ExecutionResult(signal, false, e.getMessage(), null));
                }
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        long executed = executions.stream().filter(ExecutionResult::success).count();

        log.info("<<< scanAndExecute: {} signals, {} executed in {}ms",
                scanResult.totalSignals(), executed, elapsed);

        return new TradingResult(scanResult, executions, elapsed);
    }

    /**
     * Executes a single signal manually (e.g., from API call).
     */
    public ExecutionResult executeSignal(String ticker, String direction, int qty, double entryPrice, double tp, double sl) {
        log.info(">>> executeSignal: ticker={}, direction={}, qty={}, entry={}, tp={}, sl={}",
                ticker, direction, qty, entryPrice, tp, sl);

        boolean isCall = "CALL".equalsIgnoreCase(direction) || direction.toUpperCase().contains("CALL");

        TradePlan plan = new TradePlan(entryPrice, tp, sl, isCall, java.time.LocalTime.of(15, 55));

        try {
            OrderExecutionService.OrderResult orderResult = orderExecutionService.placeOptionBracket(
                    ticker, isCall, qty, plan, "manual"
            );

            if (orderResult != null) {
                StrategyScannerService.Signal signal = new StrategyScannerService.Signal(
                        ticker, "manual", direction, entryPrice, java.time.ZonedDateTime.now(), plan
                );
                return new ExecutionResult(signal, true, "Order placed", orderResult);
            } else {
                return new ExecutionResult(null, false, "Order placement failed", null);
            }
        } catch (Exception e) {
            log.error("Failed to execute manual signal: {}", e.getMessage(), e);
            return new ExecutionResult(null, false, e.getMessage(), null);
        }
    }

    /**
     * Pre-flight check: resolves option chain without placing an order.
     * Useful to verify the ticker has valid options before trading.
     */
    public OptionCheckResult checkOptionChain(String ticker) {
        log.info(">>> checkOptionChain: {}", ticker);
        try {
            OrderExecutionService.OptionChainResult chain = orderExecutionService.resolveOptionChain(ticker);
            return new OptionCheckResult(ticker, true, chain.expiration(), chain.validStrikes().size(), null);
        } catch (Exception e) {
            log.error("Failed to resolve option chain for {}: {}", ticker, e.getMessage());
            return new OptionCheckResult(ticker, false, null, 0, e.getMessage());
        }
    }

    // ===== Response Records =====

    public record TradingResult(
            StrategyScannerService.ScanResult scanResult,
            List<ExecutionResult> executions,
            long elapsedMs
    ) {}

    public record ExecutionResult(
            StrategyScannerService.Signal signal,
            boolean success,
            String message,
            OrderExecutionService.OrderResult orderResult
    ) {}

    public record OptionCheckResult(
            String ticker,
            boolean success,
            String nearestExpiration,
            int availableStrikes,
            String error
    ) {}
}
