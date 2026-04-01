package com.fgiaquinta.optionsquant;

import com.fgiaquinta.optionsquant.engine.*;
import com.fgiaquinta.optionsquant.models.AppConfig;
import com.fgiaquinta.optionsquant.services.IbkrService;
import com.fgiaquinta.optionsquant.services.TelegramService;
import com.fgiaquinta.optionsquant.utils.ConfigLoader;
import com.fgiaquinta.optionsquant.strategies.*;
import com.fgiaquinta.optionsquant.utils.LogManager;
import com.fgiaquinta.optionsquant.utils.TunnelManager;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.stream.Collectors;

public class Main {
    private static final TunnelManager tunnelManager = new TunnelManager();
    private static HttpServer httpServer;

    public static void main(String[] args) {
        LogManager.initialize();
        tunnelManager.start("http://localhost:8080");

        System.out.println("🚀 Starting Hybrid Quant Trading Engine...");
        AppConfig config = ConfigLoader.getConfig();

        // 6. Run the Pre-Market AI Routine safely
        System.out.println("🤖 Initiating AI Pre-Market Routine with Gemini...");
        FastBacktester backtester = new FastBacktester();
        AiStrategyOptimizer strategyOptimizer = new AiStrategyOptimizer();

        // 1. Initialize core services first
        AccountManager accountManager = new AccountManager();
        IbkrService ibkrService = new IbkrService(accountManager);
        PreMarketRoutine preMarket = new PreMarketRoutine(backtester, strategyOptimizer, ibkrService);
        MarketRadar marketRadar = new MarketRadar(ibkrService);
        TradeManager tradeManager = new TradeManager(ibkrService, marketRadar, accountManager, preMarket);

        // 2. Define strategies
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

        ibkrService.setStrategyEngine(strategyEngine);
        // 3. Connect to IBKR
        AppConfig.IbkrConfig ibkr = config.ibkr;
        ibkrService.connect(ibkr.host, ibkr.port, new Random().nextInt());

        // 4. Start market data tracking to load CSVs and initiate Backfill
        List<String> tickers = Arrays.asList("SPY", "QQQ");
        for (String ticker : tickers) {
            ibkrService.startMarketDataTracking(ticker);
        }

        // 5. EVENT-DRIVEN WAIT: Replaces the while loop
        // This will block until all historicalDataEnd events are received
        ibkrService.waitForBackfillCompletion();

        try {
            System.out.println("🧠 Gemini is analyzing market sentiment and optimizing strategies...");
            preMarket.runDailyAnalysis("SPY", ibkrService, strategies);
        } catch (Exception e) {
            System.err.println("❌ AI Analysis failed: " + e.getMessage());
        }

        // 7. Start HTTP Server and setup Shutdown Hook
        startHttpServer(ibkrService);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("🛑 Shutting down...");
            if (httpServer != null) httpServer.stop(0);
            tunnelManager.shutdown();
            strategyEngine.shutdown();
            ibkrService.disconnect();
        }));

        System.out.println("🚀 System Online and waiting for market events.");
    }

    private static void startHttpServer(IbkrService ibkr) {
        try {
            httpServer = HttpServer.create(new InetSocketAddress(8080), 0);
            httpServer.createContext("/execute", exchange -> {
                Map<String, String> params = Arrays.stream(exchange.getRequestURI().getQuery().split("&"))
                        .map(s -> s.split("="))
                        .collect(Collectors.toMap(a -> a[0], a -> a[1]));

                ibkr.placeOrder(params.get("ticker"), params.get("side"),
                        Integer.parseInt(params.get("qty")), Double.parseDouble(params.get("lmt")),
                        Double.parseDouble(params.get("tp")), Double.parseDouble(params.get("sl")), "Telegram-Manual");

                String response = "Order Sent to IBKR!";
                exchange.sendResponseHeaders(200, response.length());
                exchange.getResponseBody().write(response.getBytes());
                exchange.close();
            });
            httpServer.start();
            System.out.println("🌐 Web Callback Server started on port 8080.");
        } catch (Exception e) { System.err.println("❌ Web Server Error: " + e.getMessage()); }
    }
}