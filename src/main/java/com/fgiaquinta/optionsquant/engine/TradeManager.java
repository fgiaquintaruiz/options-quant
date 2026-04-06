package com.fgiaquinta.optionsquant.engine;

import com.fgiaquinta.optionsquant.analyzers.VolatilityAnalyzer;
import com.fgiaquinta.optionsquant.models.TradePlan;
import com.fgiaquinta.optionsquant.services.IbkrService;
import com.fgiaquinta.optionsquant.utils.ConfigLoader;
import com.fgiaquinta.optionsquant.utils.RiskCalculator;

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

        // ==========================================
        // 👉 1. ELEGIR LA TEMPORALIDAD PARA EL RIESGO
        // ==========================================
        // Usamos MIN_15 (o HOUR_1) para que el Stop Loss sea sólido y no salte por ruido.
        org.ta4j.core.BarSeries seriesForRisk = ibkrService.getSeries(ticker, com.fgiaquinta.optionsquant.models.TimeFrame.MIN_15);

        if (seriesForRisk == null || seriesForRisk.isEmpty()) {
            System.out.println("❌ [TradeManager] Error: No se encontraron datos de MIN_15 para " + ticker + " para calcular el riesgo. Abortando...");
            return;
        }

        // ==========================================
        // 👉 2. GENERAR EL PLAN DE TRADE (CEREBRO)
        // ==========================================
        int lastIndex = seriesForRisk.getEndIndex();
        // 1. Extraemos la hora de la vela actual en la que estamos entrando
        java.time.ZonedDateTime entryTime = seriesForRisk.getBar(lastIndex).getEndTime();

// 2. Generamos el plan pasándole el DataManager para que pueda leer la volatilidad (ATR) en la temporalidad macro de 1 Hora
        com.fgiaquinta.optionsquant.models.TradePlan plan = com.fgiaquinta.optionsquant.utils.RiskCalculator.generatePlan(
                ibkrService.getDataManager(), // Pasamos el caché central
                ticker,                       // El nombre de la acción
                entryTime,                    // La hora de entrada
                isCall,                       // Si es Call o Put
                price                         // Precio de entrada
        );

        // Imprimimos el plan para auditoría
        System.out.printf("📋 [TradeManager] Plan Generado -> Entrada: %.2f | TP: %.2f | SL: %.2f%n",
                plan.entryPrice, plan.takeProfit, plan.stopLoss);

        // ==========================================
        // 👉 3. CALCULAR CANTIDAD (MONEY MANAGEMENT)
        // ==========================================
        int qty = accountManager.calculateQuantity(plan.entryPrice, plan.stopLoss);
        System.out.println("⚖️ [TradeManager] Calculated Position Qty: " + qty);

        if (qty < 1) {
            System.out.println("📉 [TradeManager] Aborting: Quantity is 0. Risk is too high or account equity is too low.");
            return;
        }

        // ==========================================
        // 👉 4. EJECUCIÓN (MANDAR ORDEN A TWS)
        // ==========================================
        if (forceExecution || com.fgiaquinta.optionsquant.utils.ConfigLoader.getConfig().getBoolean("ibkr", "autoExecute")) {
            System.out.printf("📊 [Forensic] Trade disparado. Balance actual: %.2f | Estrategia: %s%n",
                    accountManager.getCurrentBalance(), strategyName);
            System.out.println("🚀 [TradeManager] Executing via IBKR -> " + (isCall ? "CALL" : "PUT") + " | Qty: " + qty);

            // Usamos las variables exactas del 'plan' generado
            ibkrService.placeOrder(ticker, isCall ? "CALL" : "PUT", qty, plan.entryPrice, plan.takeProfit, plan.stopLoss, strategyName);
            accountManager.addActiveTrade();
        } else {
            System.out.println("📩 [TradeManager] Auto-execute is false. Sending Telegram alert.");
            com.fgiaquinta.optionsquant.services.TelegramService.sendSignalConfirmation(ticker, strategyName, plan.entryPrice, plan.takeProfit, plan.stopLoss, qty);
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