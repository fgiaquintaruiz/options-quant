package com.fgiaquinta.optionsquant;

import com.fgiaquinta.optionsquant.engine.*;
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
        ConfigLoader.getConfig();

        AccountManager accountManager = new AccountManager();
        IbkrService ibkrService = new IbkrService(accountManager);
        MarketRadar marketRadar = new MarketRadar();
        TradeManager tradeManager = new TradeManager(ibkrService, marketRadar, accountManager);

        startHttpServer(ibkrService);

        List<TradingStrategy> strategies = Arrays.asList(
                new C1SqueezeCallStrategy(ibkrService), new C2TrendCallStrategy(ibkrService),
                new P1SqueezePutStrategy(ibkrService), new P2TrendPutStrategy(ibkrService)
        );

        StrategyEngine strategyEngine = new StrategyEngine(ibkrService, strategies, tradeManager);
        ibkrService.setStrategyEngine(strategyEngine);

        // 1. Connect
        ibkrService.connect(ConfigLoader.getConfig().ibkr.host, ConfigLoader.getConfig().ibkr.port, 1);

        // 2. Wait for API Handshake
        try {
            if (!ibkrService.awaitConnection(10)) {
                System.err.println("❌ IBKR Handshake failed. Shutting down.");
                System.exit(1);
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        // 3. Request Metadata and Wait for Sync
        List<String> tickers = ConfigLoader.getConfig().ibkr.tickers;
        ibkrService.prepareInitialization(tickers.size());
        ibkrService.requestInitialMetadata(tickers);
        ibkrService.subscribeToNewsProviders();

        try {
            int timeout = ConfigLoader.getConfig().ibkr.syncTimeout;
            ibkrService.awaitInitialization(timeout);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        // 4. Start Live Tracking
        for (String ticker : tickers) {
            ibkrService.startMarketDataTracking(ticker);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n🛑 Emergency Stop Initiated...");
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