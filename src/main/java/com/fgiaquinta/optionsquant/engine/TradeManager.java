package com.fgiaquinta.optionsquant.engine;

import com.fgiaquinta.optionsquant.analyzers.VolatilityAnalyzer;
import com.fgiaquinta.optionsquant.services.IbkrService;
import com.fgiaquinta.optionsquant.utils.ConfigLoader;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TradeManager {
    private final Map<String, Double> lastExits = new ConcurrentHashMap<>();

    private final IbkrService ibkrService;
    private final MarketRadar marketRadar;
    private final AccountManager accountManager;
    private final PreMarketRoutine preMarket;

    public TradeManager(IbkrService ibkrService, MarketRadar marketRadar, AccountManager accountManager,
                        PreMarketRoutine preMarket) {
        this.ibkrService = ibkrService;
        this.marketRadar = marketRadar;
        this.accountManager = accountManager;
        this.preMarket = preMarket;
    }

    // ==========================================
    // 👉 MÉTODO NORMAL (Llamado por las estrategias automáticamente)
    // ==========================================
    public void evaluateSignal(String ticker, String strategyName, double price) {
        // Llama al método principal asumiendo que NO es una ejecución forzada
        evaluateSignal(ticker, strategyName, price, false);
    }

    // ==========================================
    // 👉 MÉTODO PRINCIPAL (Llamado por el Webhook de Telegram)
    // ==========================================
    public void evaluateSignal(String ticker, String strategyName, double price, boolean forceExecution) {
        System.out.println("🧐 [TradeManager] evaluateSignal entered for: " + strategyName + " on " + ticker + " at " + price + (forceExecution ? " (MANUAL OVERRIDE)" : ""));

        // Si es una ejecución forzada por Telegram, saltamos los bloqueos de pre-market y radar para obedecerte inmediatamente
        if (!forceExecution) {
            if (!preMarket.isSafeToTrade()) {
                System.out.println("🛑 [TradeManager] Trade Blocked: AI Pre-Market Routine is still running. Waiting for system readiness...");
                return;
            }

            // 1. RADAR CHECK: Only trade if the ticker made the "Hot List" today
            if (!marketRadar.isHot(ticker)) {
                System.out.println("⏭️ [TradeManager] Skipping " + ticker + ": Not in today's AI Hot List.");
                return;
            }
        }

        // Determine direction based on strategy name
        boolean isCall = strategyName.toLowerCase().contains("call") || strategyName.toLowerCase().contains("long");
        Double lastExitPrice = lastExits.get(ticker);

        // 2. MACRO ENVIRONMENT CHECK (Existing)
        if (!forceExecution && !marketRadar.isEnvironmentFavorable(isCall)) {
            System.out.println("🛑 [TradeManager] Trade Blocked: Macro conditions unfavorable.");
            return;
        }

        // ==========================================
        // 👉 ADDED: STAIRCASE LOGIC (RE-ENTRY FILTER)
        // ==========================================
        if (!forceExecution && lastExitPrice != null && lastExits.containsKey(ticker)) {
            // For a CALL: Price must be LOWER than our last exit (we want a better entry)
            if (isCall && price >= lastExitPrice) {
                System.out.println("⏳ [TradeManager] Staircase Block: " + ticker + " Call price " + price +
                        " is not better than last exit " + lastExitPrice);
                return;
            }
            // For a PUT: Price must be HIGHER than our last exit
            else if (!isCall && price <= lastExitPrice) {
                System.out.println("⏳ [TradeManager] Staircase Block: " + ticker + " Put price " + price +
                        " is not better than last exit " + lastExitPrice);
                return;
            } else {
                System.out.println("✅ [TradeManager] Price improved since last exit. Allowing re-entry.");
            }
        }

        // 3. Check Concurrency Limits
        if (!forceExecution && !accountManager.canOpenNewTrade()) {
            System.out.println("🚫 [TradeManager] Trade blocked: Maximum concurrent trades reached.");
            return;
        }

        // Get daily series for ATR
        org.ta4j.core.BarSeries dailySeries = ibkrService.getSeries(ticker, com.fgiaquinta.optionsquant.models.TimeFrame.DAY_1);

        double atr;
        if (dailySeries == null || dailySeries.isEmpty()) {
            System.out.println("⚠️ [TradeManager] Warning: Daily series is NULL or EMPTY for " + ticker + ". Falling back to 2% fixed ATR.");
            atr = price * 0.02;
        } else {
            atr = com.fgiaquinta.optionsquant.analyzers.VolatilityAnalyzer.calculateATR(dailySeries, 14);
            System.out.println("📊 [TradeManager] Calculated ATR(14): " + atr);
        }

        // Fetch the AI-optimized settings using the exact composite key used in PreMarketRoutine
        String compositeKey = ticker + "_" + strategyName;
        com.fgiaquinta.optionsquant.models.OptimizationResult aiParams = preMarket.getOverridesFor(compositeKey);

        double tpDist;
        double slDist;

        if (aiParams != null) {
            System.out.println("🧠 [TradeManager] Applying AI-optimized parameters for " + ticker + " (" + strategyName + ")");
            // Access the public variables directly instead of getters
            tpDist = atr * aiParams.recommendedTpAtr;
            slDist = atr * aiParams.recommendedSlAtr;
        } else {
            // Fallback to the standard config.yaml if the AI has no overrides
            tpDist = atr * com.fgiaquinta.optionsquant.utils.ConfigLoader.getConfig().getDouble("global", "tpAtrMultiplier");
            slDist = atr * com.fgiaquinta.optionsquant.utils.ConfigLoader.getConfig().getDouble("global", "slAtrMultiplier");
        }

        double tp = Math.round((isCall ? price + tpDist : price - tpDist) * 100.0) / 100.0;
        double sl = Math.round((isCall ? price - slDist : price + slDist) * 100.0) / 100.0;

        System.out.println("🎯 [TradeManager] Levels Calculated -> TP: " + tp + " | SL: " + sl);

        int qty = accountManager.calculateQuantity(price, sl);
        System.out.println("⚖️ [TradeManager] Calculated Position Qty: " + qty);

        if (qty < 1) {
            System.out.println("📉 [TradeManager] Aborting: Quantity is 0. Risk is too high or account equity is too low.");
            return;
        }

        // ==========================================
        // 👉 AQUI APLICAMOS LA LÓGICA DEL WEBHOOK
        // ==========================================
        if (forceExecution || com.fgiaquinta.optionsquant.utils.ConfigLoader.getConfig().getBoolean("ibkr", "autoExecute")) {
            System.out.printf("📊 [Forensic] Trade disparado. Balance actual: %.2f | Estrategia: %s%n",
                    accountManager.getCurrentBalance(), strategyName);
            System.out.println("🚀 [TradeManager] Executing via IBKR -> " + (isCall ? "CALL" : "PUT") + " | Qty: " + qty);
            ibkrService.placeOrder(ticker, isCall ? "CALL" : "PUT", qty, price, tp, sl, strategyName);
            accountManager.addActiveTrade();
        } else {
            System.out.println("📩 [TradeManager] Auto-execute is false. Sending Telegram alert.");
            com.fgiaquinta.optionsquant.services.TelegramService.sendSignalConfirmation(ticker, strategyName, price, tp, sl, qty);
        }
    }

    /**
     * Call this from your IBKR callback (execDetails or orderStatus)
     * when a position is closed.
     */
    public void recordExit(String ticker, double price) {
        lastExits.put(ticker, price);
        System.out.println("💾 [TradeManager] recorded last exit for " + ticker + " at " + price);
    }
}