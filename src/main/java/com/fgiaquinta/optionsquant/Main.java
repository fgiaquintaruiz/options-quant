package com.fgiaquinta.optionsquant;

import com.fgiaquinta.optionsquant.engine.*;
import com.fgiaquinta.optionsquant.models.AppConfig;
import com.fgiaquinta.optionsquant.services.IbkrService;
import com.fgiaquinta.optionsquant.services.TelegramService;
import com.fgiaquinta.optionsquant.utils.ConfigLoader;
import com.fgiaquinta.optionsquant.strategies.*;
import com.fgiaquinta.optionsquant.utils.LogManager;
import com.fgiaquinta.optionsquant.utils.TunnelManager;
import com.fgiaquinta.optionsquant.utils.DataManager;
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

    public static void main(String[] args) {
        LogManager.initialize();

        // Inicializamos Telegram Service e inyectamos al TunnelManager
        TelegramService telegramService = new TelegramService();
        tunnelManager.setTelegramService(telegramService);
        tunnelManager.start("http://localhost:9090");

        System.out.println("🚀 Starting Hybrid Quant Trading Engine...");
        AppConfig config = ConfigLoader.getConfig();
        List<String> activeTickers = config.getList("ibkr", "tickers");

        try {
            // 1. Inicializar Módulos Core
            AccountManager accountManager = new AccountManager();
            IbkrService ibkrService = new IbkrService(accountManager);

            DataManager dataManager = new DataManager();
            ibkrService.setDataManager(dataManager);

            MarketRadar marketRadar = new MarketRadar(ibkrService);

            // 2. 👉 CORRECCIÓN: Inicializar dependencias de IA y Pre-Mercado
            FastBacktester fastBacktester = new FastBacktester();
            AiStrategyOptimizer aiOptimizer = new AiStrategyOptimizer();

            // Ahora sí, instanciamos la rutina con todos sus parámetros
            PreMarketRoutine preMarketRoutine = new PreMarketRoutine(fastBacktester, aiOptimizer, ibkrService, marketRadar);

            System.out.println("🤖 Initiating AI Pre-Market Routine with Gemini...");
            try {
                preMarketRoutine.executeDailyRoutine(activeTickers);
            } catch (Exception e) {
                System.err.println("⚠️ AI Pre-Market Error: " + e.getMessage());
            }

            // 3. Inicializar Trade Manager
            TradeManager tradeManager = new TradeManager(ibkrService, marketRadar, accountManager, preMarketRoutine);

            // 4. Cargar Estrategias... (Aquí sigue la lista de tus estrategias)
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

            // ========================================================
            // 👉 NUEVO: 2. Pasar el DataManager al StrategyEngine
            // ========================================================
            StrategyEngine strategyEngine = new StrategyEngine(ibkrService, strategies, tradeManager, dataManager);

            System.out.println("🔌 Connecting to IBKR (" + config.getString("ibkr", "host") + ":" + config.getInt("ibkr", "port") + ")...");
            ibkrService.connect(
                    config.getString("ibkr", "host"),
                    config.getInt("ibkr", "port"),
                    new Random().nextInt(1000)
            );

            if (!ibkrService.waitForAccountSync(15)) {
                System.out.println("⚠️ Warning: Account sync timed out. Proceeding anyway...");
            }

            // ========================================================
            // 👉 NUEVO: 3. Arrancar el Poller en lugar del viejo Tracking
            // ========================================================
            strategyEngine.startMaintenanceScheduler();
            strategyEngine.setActiveTickers(activeTickers);
            strategyEngine.startStaggeredPolling();

            // NOTA: El viejo bucle "for" que hacía ibkrService.startMarketDataTracking
            // ha sido eliminado porque el Poller ahora lo hace todo de forma inteligente.

        // 👉 Pasamos los servicios necesarios al servidor HTTP
        startHttpServer(telegramService, tradeManager);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("🛑 Shutting down system...");
                strategyEngine.shutdown();
                ibkrService.disconnect();
                if (httpServer != null) httpServer.stop(0);
                tunnelManager.shutdown();;
            }));

        } catch (Exception e) {
            System.err.println("❌ Critical System Failure: " + e.getMessage());
            e.printStackTrace();
        }
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