package com.fgiaquinta.optionsquant.engine;

import com.fgiaquinta.optionsquant.services.IbkrService;
import com.fgiaquinta.optionsquant.strategies.TradingStrategy;
import java.util.Scanner;

public class MarketSimulator {
    private final IbkrService ibkrService;
    private final StrategyEngine strategyEngine;
    private final TradeManager tradeManager;
    private final MarketRadar marketRadar;

    // Inject the required dependencies
    public MarketSimulator(IbkrService ibkrService, StrategyEngine strategyEngine, TradeManager tradeManager, MarketRadar marketRadar) {
        this.ibkrService = ibkrService;
        this.strategyEngine = strategyEngine;
        this.tradeManager = tradeManager;
        this.marketRadar = marketRadar;
    }

    public void runInteractiveSimulation(String ticker, int reqId) {
        System.out.println("🧪 [Simulator] INTERACTIVE MODE started for " + ticker);
        System.out.println("👉 Type a price (e.g., '150.5') to inject a candle.");
        System.out.println("👉 Type 'trigger <ticker> <strategy> <price>' to force a trade.");
        System.out.println("👉 Type 'exit' to close.");

        new Thread(() -> {
            Scanner scanner = new Scanner(System.in);
            int tickCount = 0;

            while (true) {
                String input = scanner.nextLine().trim();

                if ("exit".equalsIgnoreCase(input)) {
                    System.out.println("🛑 [Simulator] Interactive session ended.");
                    break;
                }

                // Command Parser: Handle forced strategy triggers
                if (input.toLowerCase().startsWith("trigger ")) {
                    try {
                        String[] parts = input.split(" ");
                        String targetTicker = parts[1].toUpperCase();
                        String strategyName = parts[2];
                        double entryPrice = Double.parseDouble(parts[3]);

                        System.out.println("⚡ [Simulator] Forcing signal for " + strategyName + " on " + targetTicker);

                        // Find the strategy object by name in the engine
                        TradingStrategy targetStrategy = strategyEngine.getStrategies().stream()
                                .filter(s -> s.getClass().getSimpleName().equalsIgnoreCase(strategyName) ||
                                        s.getName().equalsIgnoreCase(strategyName))
                                .findFirst()
                                .orElse(null);

                        if (targetStrategy != null) {
                            marketRadar.addHotTicker(targetTicker);
                            // Bypass technical evaluation and send directly to TradeManager
                            tradeManager.evaluateSignal(targetTicker, strategyName, entryPrice);
                        } else {
                            System.err.println("⚠️ Strategy not found. Please check the name.");
                        }
                    } catch (Exception e) {
                        System.err.println("⚠️ Invalid command format. Use: trigger <ticker> <strategy> <price>");
                    }
                    continue; // Skip the standard candle injection below
                }

                // Standard Candle Injection Logic
                try {
                    double price = Double.parseDouble(input);
                    String fakeTime = "20260402  10:" + String.format("%02d", tickCount % 60) + ":00";

                    com.ib.client.Bar mockBar = new com.ib.client.Bar(
                            fakeTime, price - 1, price + 1, price - 2, price,
                            com.ib.client.Decimal.get(100), 100, com.ib.client.Decimal.get(price)
                    );

                    System.out.println("📈 [Simulator] Injecting Tick -> " + ticker + " @ $" + price);
                    ibkrService.historicalDataUpdate(reqId, mockBar);
                    tickCount++;
                } catch (NumberFormatException e) {
                    System.err.println("⚠️ Invalid input. Enter a number or a valid command.");
                }
            }
        }).start();
    }
}