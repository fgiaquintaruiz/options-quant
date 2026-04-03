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

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

public class Main {
    private static final TunnelManager tunnelManager = new TunnelManager();
    private static HttpServer httpServer;

    static void main(String[] args) {
        LogManager.initialize();

        // 👉 NUEVO: Inicializamos Telegram Service e inyectamos al TunnelManager
        TelegramService telegramService = new TelegramService();
        tunnelManager.setTelegramService(telegramService);
        tunnelManager.start("http://localhost:9090");

        System.out.println("🚀 Starting Hybrid Quant Trading Engine...");
        AppConfig config = ConfigLoader.getConfig();
        List<String> activeTickers = config.getList("ibkr", "tickers");

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

        // 3. Request ALL Data (Live & Historical) using your master method
        for (String ticker : activeTickers) {
            marketRadar.addHotTicker(ticker); // 👈 CRÍTICO: Si no haces esto, el bot nunca operará esos tickers
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
        if (!ibkrService.waitForAccountSync(15)) {
            System.err.println("⚠️ Warning: Account balance didn't arrive in time!");
        }
        System.out.println("🔗 Requesting options chains and contract details...");
        ibkrService.requestInitialMetadata(activeTickers);
        ibkrService.subscribeToNewsProviders();

        boolean isSimulation = config.getBoolean("global", "simulationMode");
        boolean useAi = config.getBoolean("global", "useAiAnalysis");

        // 5. Run the Pre-Market AI routine (ONLY IF NOT IN SIMULATION)
        marketRadar.setForceMacroFavorable(true);
        if (!isSimulation && useAi) {
            try {
                System.out.println("🤖 Initiating AI Pre-Market Routine...");

                for (String ticker : activeTickers) {
                    preMarketRoutine.runDailyAnalysis(ticker, ibkrService, strategies);
                }
                preMarketRoutine.setSafeToTrade(true);
            } catch (Exception e) {
                System.err.println("❌ AI Analysis failed: " + e.getMessage());
            }
        } else {
            System.out.println("⚡ Skipping AI Pre-Market Routine (Simulation Mode Active)");
            preMarketRoutine.forceReady();
            marketRadar.setForceMacroFavorable(true);
            CommandServer remoteConsole = new CommandServer(tradeManager, preMarketRoutine, marketRadar);
            remoteConsole.start();
            preMarketRoutine.setSafeToTrade(true);
        }

        // 👉 Pasamos los servicios necesarios al servidor HTTP
        startHttpServer(telegramService, tradeManager);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("🛑 Shutting down...");
            if (httpServer != null) httpServer.stop(0);
            tunnelManager.shutdown();
            strategyEngine.shutdown();
            ibkrService.disconnect();
        }));

        // 👉 NUEVO: Esperar 2 segundos para que los hilos asíncronos de IBKR terminen de imprimir
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 👉 NUEVO: Log visual prominente
        System.out.println("=========================================================");
        System.out.println("✅ SYSTEM FULLY INITIALIZED AND READY TO TRADE");
        System.out.println("=========================================================");

        // 👉 NUEVO: REGISTRAR EL WEBHOOK CUANDO TODO ESTÁ REALMENTE LISTO
        // Usamos un pequeño hilo asíncrono para no bloquear la ejecución principal
        new Thread(() -> {
            try {
                // Le damos 2 segunditos extra de gracia al servidor 9090 para estabilizarse
                Thread.sleep(2000);
                String currentTunnelUrl = com.fgiaquinta.optionsquant.services.TelegramService.getExternalUrl();
                if (currentTunnelUrl != null && !currentTunnelUrl.contains("localhost")) {
                    System.out.println("🌐 Registrando Webhook en Telegram de forma diferida...");
                    telegramService.registerWebhook(currentTunnelUrl);
                } else {
                    System.err.println("⚠️ No se pudo registrar Webhook: La URL del túnel no está lista.");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private static void startHttpServer(TelegramService telegramService, TradeManager tradeManager) {
        try {
            httpServer = HttpServer.create(new InetSocketAddress(9090), 0);

            // 👉 NUEVO: Contexto Webhook True-Silent
            httpServer.createContext("/webhook", exchange -> {
                if ("POST".equals(exchange.getRequestMethod())) {
                    try {
                        InputStream is = exchange.getRequestBody();
                        String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);

                        java.util.regex.Matcher dataMatcher = java.util.regex.Pattern.compile("\"data\":\"([^\"]+)\"").matcher(body);
                        java.util.regex.Matcher idMatcher = java.util.regex.Pattern.compile("\"id\":\"([^\"]+)\"").matcher(body);

                        if (dataMatcher.find() && idMatcher.find()) {
                            String callbackData = dataMatcher.group(1);
                            String callbackQueryId = idMatcher.group(1);

                            System.out.println("📲 Señal silenciosa recibida desde Telegram: " + callbackData);

                            // Responder a Telegram inmediatamente con HTTP 200
                            String response = "OK";
                            exchange.sendResponseHeaders(200, response.length());
                            OutputStream os = exchange.getResponseBody();
                            os.write(response.getBytes());
                            os.close();

                            // Detener la animación de carga del botón en Telegram
                            telegramService.answerCallbackQuery(callbackQueryId, "Orden enviada a IBKR 🚀");

                            // Procesar la orden
                            String[] parts = callbackData.split("\\|");
                            if (parts.length >= 4 && parts[0].equals("E")) {
                                String ticker = parts[1];
                                String type = parts[2];
                                double price = Double.parseDouble(parts[3]);

                                System.out.println("🎯 Ejecutando trade manual por Webhook: " + ticker + " " + type + " $" + price);
                                tradeManager.evaluateSignal(ticker, type, price, true);
                            }
                        } else {
                            exchange.sendResponseHeaders(200, 0);
                            exchange.getResponseBody().close();
                        }
                    } catch (Exception e) {
                        System.err.println("❌ Error procesando Webhook: " + e.getMessage());
                        exchange.sendResponseHeaders(500, -1);
                        exchange.close();
                    }
                } else {
                    exchange.sendResponseHeaders(405, -1);
                    exchange.close();
                }
            });

            httpServer.start();
            System.out.println("🌐 True-Silent Webhook Server started on port 9090. Listening on /webhook");
        } catch (Exception e) {
            System.err.println("❌ Web Server Error: " + e.getMessage());
        }
    }

}