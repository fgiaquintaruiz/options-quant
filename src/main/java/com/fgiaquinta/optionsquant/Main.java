package com.fgiaquinta.optionsquant;

import com.fgiaquinta.optionsquant.engine.*;
import com.fgiaquinta.optionsquant.services.IbkrService;
import com.fgiaquinta.optionsquant.services.TelegramService;
import com.fgiaquinta.optionsquant.utils.ConfigLoader;
import com.fgiaquinta.optionsquant.strategies.*;

import java.util.Arrays;
import java.util.List;

public class Main {
    static void main(String[] args) {
        System.setOut(new java.io.PrintStream(System.out, true, java.nio.charset.StandardCharsets.UTF_8) {
            @Override
            public void println(String x) {
                String timestamp = java.time.LocalDateTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                super.println("[" + timestamp + "] " + x);
            }
        });
        TelegramService.sendSimpleMessage("🚀 Quant Engine Connection Test: OK");
        System.out.println("🚀 Starting Hybrid Quant Trading Engine...");

        // 1. Load Configuration
        ConfigLoader.getConfig();

        // ==========================================
        // 🧪 AI PIPELINE TEST BLOCK
        // ==========================================
        System.out.println("🧪 Running AI Pipeline Diagnostics...");

        if (ConfigLoader.getConfig().ai.enabled) {

            // Test 1: News Interpretation
            System.out.println("\n--- Testing AiNewsInterpreter ---");
            AiNewsInterpreter testInterpreter = new AiNewsInterpreter();
            String fakeHeadline = "Nvidia shatters earnings expectations with massive demand for new Blackwell AI chips.";
            System.out.println("Headline: " + fakeHeadline);

            com.fgiaquinta.optionsquant.models.AnalysisResult newsResult = testInterpreter.analyzeHeadline(fakeHeadline);
            if (newsResult != null) {
                System.out.println("✅ AI Response: " + newsResult);
            } else {
                System.err.println("❌ AI News Interpreter failed to return a result.");
            }

            // Test 2: Strategy Optimization
            System.out.println("\n--- Testing AiStrategyOptimizer ---");
            AiStrategyOptimizer testOptimizer = new AiStrategyOptimizer();
            String fakeBacktestMetrics = "{\"ticker\": \"NVDA\", \"strategy\": \"C5_CONTINUATION\", \"totalTrades\": 10, \"winRate\": 0.30, \"totalProfitPct\": -0.05, \"maxDrawdown\": 0.08}";
            System.out.println("Metrics fed to AI: " + fakeBacktestMetrics);

            com.fgiaquinta.optionsquant.models.OptimizationResult optResult = testOptimizer.analyzeBacktest(fakeBacktestMetrics);
            if (optResult != null) {
                System.out.println("✅ AI Recommendation: " + optResult);
            } else {
                System.err.println("❌ AI Strategy Optimizer failed to return a result.");
            }

            System.out.println("==========================================\n");
        } else {
            System.out.println("⚠️ AI is disabled in config.yaml. Skipping tests.");
        }
        // ==========================================

        // 2. Initialize Core Modules (Dependency Injection)
        AccountManager accountManager = new AccountManager();
        IbkrService ibkrService = new IbkrService(accountManager);
        MarketRadar marketRadar = new MarketRadar();

        // 3. Initialize Tactical Managers
        TradeManager tradeManager = new TradeManager(ibkrService, marketRadar, accountManager);
        AiNewsInterpreter aiNewsInterpreter = new AiNewsInterpreter();

        // 🔌 Wire the AI News Interpreter and Radar into the IBKR data stream
        ibkrService.setNewsRouting(aiNewsInterpreter, marketRadar);

        // 4. Initialize Strategies and Strategy Engine
        List<TradingStrategy> strategies = Arrays.asList(
                new C1SqueezeCallStrategy(ibkrService),
                new C2TrendCallStrategy(ibkrService),
                new C3BounceCallStrategy(ibkrService),
                new C4OpeningCallStrategy(ibkrService),
                new C5ContinuationCallStrategy(ibkrService),
                new P1SqueezePutStrategy(ibkrService),
                new P2TrendPutStrategy(ibkrService),
                new P3BouncePutStrategy(ibkrService),
                new P4OpeningPutStrategy(ibkrService),
                new P5ContinuationPutStrategy(ibkrService)
        );

        StrategyEngine strategyEngine = new StrategyEngine(ibkrService, strategies, tradeManager);
        strategyEngine.startMaintenanceScheduler();

        // Wire the engine to the IBKR Service so historicalDataEnd triggers onBarAdded
        ibkrService.setStrategyEngine(strategyEngine);

        // 5. Connect to IBKR Gateway/TWS
        ibkrService.connect(
                ConfigLoader.getConfig().ibkr.host,
                ConfigLoader.getConfig().ibkr.port,
                1 // Client ID
        );

        // 6. Start Account Sync (Using the dynamic account ID from config)
        ibkrService.startAccountSync(ConfigLoader.getConfig().ibkr.accountId);

        // 7. Request base market data and news subscriptions
        ibkrService.subscribeToNewsProviders();
        for (String ticker : ConfigLoader.getConfig().ibkr.tickers) {
            ibkrService.startMarketDataTracking(ticker);
        }

        // 8. Add Graceful Shutdown Hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n🛑 Shutting down engine...");
            strategyEngine.shutdown();
            ibkrService.disconnect(); // Ensure you have a disconnect method in IbkrService
            System.out.println("👋 Shutdown complete. All threads closed.");
        }));

        System.out.println("🚀 System Online and waiting for market events.");
    }
}