package com.fgiaquinta.optionsquant.engine;

import com.fgiaquinta.optionsquant.services.IbkrService;
import java.util.ArrayList;
import java.util.List;

public class MarketSimulator {
    private final IbkrService ibkrService;
    private final StrategyEngine strategyEngine;

    public MarketSimulator(IbkrService ibkrService, StrategyEngine strategyEngine) {
        this.ibkrService = ibkrService;
        this.strategyEngine = strategyEngine;
    }

    /**
     * Feeds a handcrafted list of candles into the system to force a strategy trigger.
     */
    public void runSyntheticTest(String ticker, int reqId) {
        System.out.println("🧪 [Simulator] Starting synthetic market feed for " + ticker);

        // 1. Create a fake sequence of closing prices designed to trigger a strategy
        // For example, a sudden drop followed by a massive spike (Bounce/Reversal)
        double[] mockCloses = { 150.0, 148.0, 145.0, 144.0, 144.5, 146.0, 149.0, 152.0, 155.0 };

        new Thread(() -> {
            for (int i = 0; i < mockCloses.length; i++) {
                double price = mockCloses[i];

                // Create a mock IBKR Bar (You might need to adjust parameters based on your IBKR API version)
                // Format typically: time, open, high, low, close, volume, count, wap
                String fakeTime = "20260402  10:0" + i + ":00";

                // Note: If your IB API uses Decimal for volume, use Decimal.get(1000)
                com.ib.client.Bar mockBar = new com.ib.client.Bar(
                        fakeTime, price - 1, price + 1, price - 2, price, null, 100, null
                );

                System.out.println("📈 [Simulator] Injecting Tick: " + price);

                // 2. Inject the fake bar directly into the IbkrService event handler
                ibkrService.historicalDataUpdate(reqId, mockBar);

                // Wait 2 seconds between candles to simulate live market speed
                try { Thread.sleep(2000); } catch (InterruptedException e) {}
            }
            System.out.println("🧪 [Simulator] End of synthetic feed.");
        }).start();
    }
}