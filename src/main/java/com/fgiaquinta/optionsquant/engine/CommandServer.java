package com.fgiaquinta.optionsquant.engine;

import java.io.*;
import java.net.*;

public class CommandServer {
    private final int port = 7070;

    private final TradeManager tradeManager;
    private final PreMarketRoutine preMarketRoutine;
    private final MarketRadar marketRadar;

    public CommandServer(TradeManager tradeManager, PreMarketRoutine preMarketRoutine, MarketRadar marketRadar) {
        this.tradeManager = tradeManager;
        this.preMarketRoutine = preMarketRoutine;
        this.marketRadar = marketRadar;
    }

    public void start() {
        new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(port)) {
                System.out.println("📟 [Remote Console] Listening on port " + port);

                while (true) {
                    try (Socket clientSocket = serverSocket.accept();
                         PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true);
                         BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()))) {

                        // 👉 IMPROVED HELP MENU
                        out.println("--- 🚀 OPTIONSQUANT TRADING ENGINE REMOTE CONSOLE ---");
                        out.println("Available Commands:");
                        out.println("  skip                           - Bypass AI and set system LIVE");
                        out.println("  macro green                    - Force macro filters to FAVORABLE");
                        out.println("  trigger <TICKER> <PRICE>       - Quick test (Defaults to C1SqueezeCallStrategy)");
                        out.println("  trigger <TICK> <STRAT> <PRICE> - Run a specific strategy");
                        out.println("  exit                           - Close connection");
                        out.println("---------------------------------------------");
                        out.println("Available Strategies:");
                        out.println("  Calls: C1SqueezeCallStrategy, C2TrendCallStrategy, C3BounceCallStrategy, C4OpeningCallStrategy, C5ContinuationCallStrategy");
                        out.println("  Puts:  P1SqueezePutStrategy, P2TrendPutStrategy, P3BouncePutStrategy, P4OpeningPutStrategy, P5ContinuationPutStrategy");
                        out.print("> ");
                        out.flush();

                        String input;
                        while ((input = in.readLine()) != null) {
                            input = input.trim();
                            if (input.isEmpty()) { continue; }

                            String lowerInput = input.toLowerCase();

                            if (lowerInput.equals("skip")) {
                                preMarketRoutine.forceReady();
                                out.println("⚡ AI Bypass activated. System is now LIVE.");
                            } else if (lowerInput.equals("macro green")) {
                                marketRadar.setForceMacroFavorable(true);
                                out.println("🌐 Macro filters disabled. All environments are now FAVORABLE.");
                            } else if (lowerInput.startsWith("trigger ")) {
                                handleTrigger(input, out);
                            } else if (lowerInput.equals("exit")) {
                                out.println("Goodbye.");
                                break;
                            } else {
                                out.println("❓ Unknown command. Type 'trigger' or 'skip'.");
                            }
                            out.print("> ");
                            out.flush();
                        }
                    } catch (IOException e) {
                        System.err.println("❌ [Remote Console] Connection lost.");
                    }
                }
            } catch (IOException e) {
                System.err.println("❌ [Remote Console] Server Error: " + e.getMessage());
            }
        }).start();
    }

    private void handleTrigger(String input, PrintWriter out) {
        try {
            String[] parts = input.split("\\s+");

            // Allow 3 parts (trigger TICK PRICE) or 4 parts (trigger TICK STRAT PRICE)
            if (parts.length < 3) {
                out.println("⚠️ Format error. Use: trigger <TICKER> <PRICE>");
                return;
            }

            String ticker = parts[1].toUpperCase();
            String strategy;
            double price;

            // 👉 QUICK TRIGGER LOGIC
            if (parts.length == 3) {
                strategy = "C1SqueezeCallStrategy"; // Default test strategy
                price = Double.parseDouble(parts[2]);
                out.println("ℹ️ No strategy specified. Defaulting to: " + strategy);
            } else {
                strategy = parts[2];
                price = Double.parseDouble(parts[3]);
            }

            marketRadar.addHotTicker(ticker);
            out.println("🚀 Dispatching manual trigger for " + ticker + " at $" + price + "...");

            // Calls evaluateSignal directly in TradeManager
            tradeManager.evaluateSignal(ticker, strategy, price);

            out.println("✅ Processed.");
        } catch (NumberFormatException e) {
            out.println("⚠️ Command Error: Price must be a valid number.");
        } catch (Exception e) {
            out.println("⚠️ Command Error: " + e.getMessage());
        }
    }
}