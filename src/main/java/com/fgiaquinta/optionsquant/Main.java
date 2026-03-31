package com.fgiaquinta.optionsquant;

import com.fgiaquinta.optionsquant.engine.*;
import com.fgiaquinta.optionsquant.services.IbkrService;
import com.fgiaquinta.optionsquant.utils.ConfigLoader;
import com.fgiaquinta.optionsquant.strategies.*;

import java.util.Arrays;
import java.util.List;

public class Main {
    public static void main(String[] args) {
        System.out.println("🚀 Starting Hybrid Quant Trading Engine...");

        // 1. Load Configuration
        ConfigLoader.getConfig();

        // 2. Initialize Core Modules (Dependency Injection)
        AccountManager accountManager = new AccountManager();
        IbkrService ibkrService = new IbkrService(accountManager);
        MarketRadar marketRadar = new MarketRadar();

        // 3. Initialize Tactical Managers
        TradeManager tradeManager = new TradeManager(ibkrService, marketRadar, accountManager);
        AiNewsInterpreter aiNewsInterpreter = new AiNewsInterpreter();

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

        System.out.println("✅ System Online and waiting for market events.");
    }
}