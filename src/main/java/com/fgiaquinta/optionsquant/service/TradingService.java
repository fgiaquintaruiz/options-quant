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
    private final AccountManager accountManager;
    private final IbkrProperties ibkrProperties;
    private final StaircaseReEntryFilter staircaseFilter;

    public TradingService(StrategyScannerService scannerService, OrderExecutionService orderExecutionService,
                          AccountManager accountManager, IbkrProperties ibkrProperties,
                          StaircaseReEntryFilter staircaseFilter) {
        this.scannerService = scannerService;
        this.orderExecutionService = orderExecutionService;
        this.accountManager = accountManager;
        this.ibkrProperties = ibkrProperties;
        this.staircaseFilter = staircaseFilter;
    }

    /**
     * Scans all tickers and optionally auto-executes signals.
     *
     * @param autoExecute If true, places bracket orders for all signals (paper or live based on config)
     * @param qty Number of contracts per trade (overrides account-based calc if > 0)
     * @param maxConcurrentTrades Max concurrent open positions (0 = unlimited)
     * @return Scan result with execution status
     */
    public TradingResult scanAndExecute(boolean autoExecute, int qty, int maxConcurrentTrades) {
        log.info(">>> scanAndExecute(autoExecute={}, qty={}, maxConcurrent={})", autoExecute, qty, maxConcurrentTrades);
        long startTime = System.currentTimeMillis();

        // Step 1: Scan for signals
        StrategyScannerService.ScanResult scanResult = scannerService.scanAll(true, true);

        List<ExecutionResult> executions = new ArrayList<>();

        // Step 2: Execute if enabled and auto-execute is on in config
        if (autoExecute && ibkrProperties.autoExecute()) {
            for (StrategyScannerService.Signal signal : scanResult.signals()) {
                try {
                    boolean isCall = "CALL".equals(signal.direction());
                    TradePlan plan = signal.tradePlan();

                    if (plan == null) {
                        log.warn("No trade plan for signal {} - skipping execution", signal);
                        executions.add(new ExecutionResult(signal, false, "No trade plan", null));
                        continue;
                    }

                    // Staircase Re-Entry Filter: block if price hasn't improved since last exit
                    if (!staircaseFilter.isReEntryAllowed(signal.ticker(), isCall, signal.currentPrice())) {
                        log.warn("⏳ [Staircase] BLOCKED: {} {} at ${:.2f} - price not improved since last exit",
                                signal.ticker(), signal.direction(), signal.currentPrice());
                        executions.add(new ExecutionResult(signal, false, "Staircase re-entry blocked", null));
                        continue;
                    }

                    // Check concurrent trades limit
                    if (!accountManager.canOpenNewTrade(maxConcurrentTrades)) {
                        log.warn("Max concurrent trades reached ({}) - skipping {}", maxConcurrentTrades, signal.ticker());
                        executions.add(new ExecutionResult(signal, false, "Max concurrent trades reached", null));
                        continue;
                    }

                    // Calculate position size based on 2% risk (or use override)
                    int effectiveQty;
                    if (qty > 0) {
                        effectiveQty = qty;
                    } else {
                        effectiveQty = accountManager.calculateQuantity(plan.entryPrice, plan.stopLoss);
                    }

                    if (effectiveQty < 1) {
                        log.warn("Calculated quantity is 0 for {} (balance=${,.2f}, SL distance too small?) - skipping",
                                signal.ticker(), accountManager.getCurrentBalance());
                        executions.add(new ExecutionResult(signal, false, "Quantity calculated as 0", null));
                        continue;
                    }

                    log.info("🎯 Executing: {} {} @ {} | TP={} SL={} | Qty={} (balance=${,.2f})",
                            signal.ticker(), signal.direction(), plan.entryPrice,
                            plan.takeProfit, plan.stopLoss, effectiveQty, accountManager.getCurrentBalance());

                    OrderExecutionService.OrderResult orderResult = orderExecutionService.placeOptionBracket(
                            signal.ticker(),
                            isCall,
                            effectiveQty,
                            plan,
                            signal.strategy()
                    );

                    if (orderResult != null) {
                        accountManager.addActiveTrade();
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

        log.info("<<< scanAndExecute: {} signals, {} executed in {}ms | Account: ${,.2f} | Active trades: {}",
                scanResult.totalSignals(), executed, elapsed,
                accountManager.getCurrentBalance(), accountManager.getActiveTradeCount());

        return new TradingResult(scanResult, executions, elapsed);
    }

    /**
     * Executes a single signal manually (e.g., from API call).
     * Uses account-based position sizing if qty <= 0.
     */
    public ExecutionResult executeSignal(String ticker, String direction, int qty, double entryPrice, double tp, double sl) {
        log.info(">>> executeSignal: ticker={}, direction={}, qty={}, entry={}, tp={}, sl={}",
                ticker, direction, qty, entryPrice, tp, sl);

        boolean isCall = "CALL".equalsIgnoreCase(direction) || direction.toUpperCase().contains("CALL");
        TradePlan plan = new TradePlan(entryPrice, tp, sl, isCall, java.time.LocalTime.of(15, 55));

        int effectiveQty;
        if (qty > 0) {
            effectiveQty = qty;
        } else {
            effectiveQty = accountManager.calculateQuantity(entryPrice, sl);
        }

        if (effectiveQty < 1) {
            return new ExecutionResult(null, false, "Quantity calculated as 0", null);
        }

        try {
            OrderExecutionService.OrderResult orderResult = orderExecutionService.placeOptionBracket(
                    ticker, isCall, effectiveQty, plan, "manual"
            );

            if (orderResult != null) {
                accountManager.addActiveTrade();
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

    /**
     * Executes a manual trade from Telegram webhook callback.
     * Format: ticker, strategy, direction, price
     */
    public boolean executeManualTrade(String ticker, String strategy, String direction, double price) {
        log.info(">>> executeManualTrade: ticker={}, strategy={}, direction={}, price={}", 
                ticker, strategy, direction, price);

        try {
            boolean isCall = "CALL".equalsIgnoreCase(direction);
            
            // Create a simple trade plan with default TP/SL (20% TP, 10% SL)
            double tp = price * 1.20;  // 20% profit target
            double sl = price * 0.90;  // 10% stop loss
            TradePlan plan = new TradePlan(price, tp, sl, isCall, java.time.LocalTime.of(15, 55));

            // Calculate position size
            int qty = accountManager.calculateQuantity(price, sl);
            if (qty < 1) {
                qty = 1;  // Default to 1 contract if calculation fails
            }

            // Place the bracket order
            OrderExecutionService.OrderResult orderResult = orderExecutionService.placeOptionBracket(
                    ticker, isCall, qty, plan, strategy
            );

            if (orderResult != null) {
                accountManager.addActiveTrade();
                log.info("✅ Manual trade executed: {} {} @ ${}, qty={}", ticker, direction, price, qty);
                return true;
            } else {
                log.error("❌ Manual trade failed: order result was null");
                return false;
            }
        } catch (Exception e) {
            log.error("❌ Manual trade execution failed: {}", e.getMessage(), e);
            return false;
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

    // ===== Account Manager Delegation =====

    public double getAccountBalance() {
        return accountManager.getCurrentBalance();
    }

    public int getActiveTradeCount() {
        return accountManager.getActiveTradeCount();
    }

    public boolean canOpenNewTrade(int maxConcurrent) {
        return accountManager.canOpenNewTrade(maxConcurrent);
    }

    public void connectAccountManager() {
        accountManager.connect();
    }

    public double getRiskPerTradePct() {
        return ibkrProperties.riskPerTradePct();
    }
}
