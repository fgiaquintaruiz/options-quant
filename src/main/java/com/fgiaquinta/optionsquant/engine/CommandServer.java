package com.fgiaquinta.optionsquant.engine;

import java.io.*;
import java.net.*;

public class CommandServer {
    private final TradeManager tradeManager;
    private final PreMarketRoutine preMarketRoutine;
    private final int port = 7070;

    public CommandServer(TradeManager tradeManager, PreMarketRoutine preMarketRoutine) {
        this.tradeManager = tradeManager;
        this.preMarketRoutine = preMarketRoutine;
    }

    public void start() {
        new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(port)) {
                System.out.println("📟 [Remote Console] Listening on port " + port);

                while (true) {
                    try (Socket clientSocket = serverSocket.accept();
                         PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true);
                         BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()))) {

                        out.println("--- OPTIONSQUANT TRADING ENGINE REMOTE CONSOLE ---");
                        out.println("Available: 'skip', 'trigger <ticker> <strategy> <price>', 'exit'");
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
                            } else if (lowerInput.startsWith("trigger ")) {
                                handleTrigger(input, out);
                            } else if (lowerInput.equals("exit")) {
                                out.println("Goodbye.");
                                break;
                            } else {
                                out.println("❓ Unknown command. Try 'skip' or 'trigger'.");
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
            // Split by one or more spaces
            String[] parts = input.split("\\s+");
            if (parts.length < 4) {
                out.println("⚠️ Format error. Use: trigger <TICKER> <STRATEGY> <PRICE>");
                return;
            }

            String ticker = parts[1].toUpperCase();
            String strategy = parts[2];
            double price = Double.parseDouble(parts[3]);

            out.println("🚀 Dispatching manual trigger for " + ticker + "...");

            // Calls evaluateSignal directly in TradeManager
            tradeManager.evaluateSignal(ticker, strategy, price);

            out.println("✅ Processed.");
        } catch (Exception e) {
            out.println("⚠️ Command Error: " + e.getMessage());
        }
    }
}