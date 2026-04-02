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

    static void main(String[] args) {
        LogManager.initialize();
        tunnelManager.start("http://localhost:9090");

        System.out.println("🚀 Starting Hybrid Quant Trading Engine...");
        AppConfig config = ConfigLoader.getConfig();
        List<String> activeTickers = config.getList("ibkr", "tickers");

        // 6. Run the Pre-Market AI Routine safely
        System.out.println("🤖 Initiating AI Pre-Market Routine with Gemini...");
        FastBacktester backtester = new FastBacktester();
        AiStrategyOptimizer strategyOptimizer = new AiStrategyOptimizer();

        // 1. Initialize core services first
        AccountManager accountManager = new AccountManager();
        accountManager.setAccountId(config.getString("ibkr", "accountId"));
        IbkrService ibkrService = new IbkrService(accountManager);
        MarketRadar marketRadar = new MarketRadar(ibkrService);
        ibkrService.setMarketRadar(marketRadar);
        ibkrService.startMarketScreener();
        PreMarketRoutine preMarketRoutine = new PreMarketRoutine(backtester, strategyOptimizer, ibkrService, marketRadar);
        TradeManager tradeManager = new TradeManager(ibkrService, marketRadar, accountManager, preMarketRoutine);
        ibkrService.setTradeManager(tradeManager);
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
        boolean isSimulation = config.getBoolean("global", "simulationMode");

        if (config.getBoolean("global", "simulationMode")) {
            preMarketRoutine.forceReady();
            marketRadar.setForceMacroFavorable(true);
            CommandServer remoteConsole = new CommandServer(tradeManager, preMarketRoutine, marketRadar);
            remoteConsole.start();
        }
        ibkrService.setStrategyEngine(strategyEngine);
        String host = config.getString("ibkr", "host");
        int port = Integer.parseInt(config.getString("ibkr", "port"));
        int randomClientId = new java.util.Random().nextInt(99999) + 1;
        ibkrService.connect(host, port, randomClientId);

        System.out.println("⏳ Waiting for IBKR handshake...");
        if (!ibkrService.waitForConnection(15)) {
            System.err.println("❌ FAILED to connect to TWS. Check your settings!");
            System.exit(1);
        }

        if (activeTickers == null || activeTickers.isEmpty()) {
            System.err.println("❌ ERROR: No tickers found in config.yaml! Defaulting to SPY, QQQ.");
            activeTickers = List.of("SPY","QQQ");
        }

        // 2. Request Contract Metadata (CRITICAL FOR OPTIONS TRADING!)
        System.out.println("🔗 Requesting options chains and contract details...");
        ibkrService.requestInitialMetadata(activeTickers);
        ibkrService.subscribeToNewsProviders();

        // 3. Request ALL Data (Live & Historical) using your master method
        for (String ticker : activeTickers) {
            ibkrService.startMarketDataTracking(ticker);
            // 👉 FIX: Añadir un retraso de 100ms para evitar el límite de 50 msgs/seg de IBKR
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // 4. Block the main thread until the pendingBackfills list is empty
        ibkrService.waitForBackfillCompletion();

        // 5. Run the Pre-Market AI routine on ALL tickers
        try {
            System.out.println("🤖 Initiating AI Pre-Market Routine...");
            for (String ticker : activeTickers) {
                // 👉 CALL THE METHOD HERE
                preMarketRoutine.runDailyAnalysis(ticker, ibkrService, strategies);
            }
            preMarketRoutine.setSafeToTrade(true);
        } catch (Exception e) {
            System.err.println("❌ AI Analysis failed: " + e.getMessage());
        }

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
            httpServer = HttpServer.create(new InetSocketAddress(9090), 0);
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
        } catch (Exception e) {
            System.err.println("❌ Web Server Error: " + e.getMessage());
        }
    }
}