package com.fgiaquinta.optionsquant;

import com.fgiaquinta.optionsquant.engine.StrategyEngine;
import com.fgiaquinta.optionsquant.services.IbkrService;
import com.fgiaquinta.optionsquant.strategies.*;
import com.fgiaquinta.optionsquant.models.TimeFrame;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public class Main {
    private static final String MASTER_KEY = System.getenv("OPTIONSQUANT_MASTER_KEY");

    public static void main(String[] args) throws Exception {
        // Startup security validation
        if (MASTER_KEY == null || MASTER_KEY.isEmpty()) {
            System.err.println("❌ ERROR: OPTIONSQUANT_MASTER_KEY environment variable is not set.");
            System.exit(1);
        }

        System.out.println("✅ Seguridad cargada correctamente.");
        System.setOut(new java.io.PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.out.println("=== MOTOR DE TRADING NATIVO (JAVA 25 + VIRTUAL THREADS) ===");

        IbkrService ibkr = new IbkrService();
        List<TradingStrategy> strategies = new ArrayList<>();
        strategies.add(new C1SqueezeCallStrategy(ibkr));
        strategies.add(new C2TrendCallStrategy(ibkr));
        strategies.add(new P1SqueezePutStrategy(ibkr));
        strategies.add(new P2TrendPutStrategy(ibkr));
        strategies.add(new C3BounceCallStrategy(ibkr));
        strategies.add(new P3BouncePutStrategy(ibkr));

        StrategyEngine liveEngine = new StrategyEngine(ibkr, strategies);
        ibkr.setStrategyEngine(liveEngine);

        // 1. Asset Tracking
        String[] tickers = {"SPY", "NVDA", "AAPL", "TSLA", "AMD", "MSFT"};
        for (String t : tickers) {
            ibkr.startMarketDataTracking(t);
        }

        // 2. WEBHOOK SERVER
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/webhook", exchange -> {
            // Webhook logic stays the same
            exchange.close();
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        System.out.println(">>> SISTEMA ONLINE Y SINCRONIZANDO CONTRATOS <<<");

        // 3. STARTUP RE-SCAN (Virtual Thread)
        // Wait 3 seconds to let contracts and expirations download from IBKR before scanning
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(3000);
                System.out.println("🔍 Escaneo inicial de seguridad...");
                for (String t : tickers) {
                    // Corrected to use the new Multi-Timeframe signature
                    var series = ibkr.getSeries(t, TimeFrame.HOUR_1);
                    if (series != null && !series.isEmpty()) {
                        liveEngine.onBarAdded(t, series);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }
}