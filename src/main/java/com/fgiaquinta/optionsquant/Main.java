package com.fgiaquinta.optionsquant;

import com.fgiaquinta.optionsquant.engine.StrategyEngine;
import com.fgiaquinta.optionsquant.services.IbkrService;
import com.fgiaquinta.optionsquant.strategies.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public class Main {
    private static final String MASTER_KEY = System.getenv("OPTIONSQUANT_MASTER_KEY");

    static void main(String[] args) throws Exception {
        System.setOut(new java.io.PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.out.println("=== MOTOR DE TRADING NATIVO (JAVA 25 + VIRTUAL THREADS) ===");

        IbkrService ibkr = new IbkrService();
        List<TradingStrategy> strategies = new ArrayList<>();
        strategies.add(new C1SqueezeCallStrategy(ibkr));
        strategies.add(new C2TrendCallStrategy(ibkr));
        strategies.add(new P1SqueezePutStrategy());
        strategies.add(new P2TrendPutStrategy(ibkr));

        StrategyEngine liveEngine = new StrategyEngine(ibkr, strategies);
        ibkr.setStrategyEngine(liveEngine);

        // 1. Rastreo de Activos
        String[] tickers = {"SPY", "NVDA", "AAPL", "TSLA", "AMD", "MSFT"};
        for (String t : tickers) {
            ibkr.startMarketDataTracking(t);
        }

        // 2. SERVIDOR WEBHOOK
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/webhook", exchange -> {
            // ... (lógica de webhook igual que antes)
            exchange.close();
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        System.out.println(">>> SISTEMA ONLINE Y SINCRONIZANDO CONTRATOS <<<");

        // 3. RE-ESCANEO DE ARRANQUE (Virtual Thread)
        // Esperamos 5 segundos a que los contratos/vencimientos bajen de IBKR y escaneamos
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(3000); // 3 segundos para dejar que bajen los contratos
                System.out.println("🔍 Escaneo inicial de seguridad...");
                for (String t : tickers) {
                    var series = ibkr.getSeries(t + "_1hour");
                    if (series != null) liveEngine.onBarAdded(t, series);
                }
            } catch (Exception e) { e.printStackTrace(); }
        });
    }
}