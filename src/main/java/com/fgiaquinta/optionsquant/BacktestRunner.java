package com.fgiaquinta.optionsquant;

import com.fgiaquinta.optionsquant.engine.AccountManager;
import com.fgiaquinta.optionsquant.engine.ForensicEngine;
import com.fgiaquinta.optionsquant.services.IbkrService;
import com.fgiaquinta.optionsquant.strategies.*;
import com.fgiaquinta.optionsquant.models.TimeFrame;
import com.fgiaquinta.optionsquant.utils.ConfigLoader;
import org.ta4j.core.BarSeries;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class BacktestRunner {
    static void main(String[] args) {
        System.setOut(new java.io.PrintStream(System.out, true, java.nio.charset.StandardCharsets.UTF_8));
        try {
            PrintStream outConsole = System.out;
            System.setOut(new PrintStream(new MultiOutputStream(outConsole), true, StandardCharsets.UTF_8));

            System.out.println("🚀 STARTING ULTRA-FAST BACKTEST (JAVA 25)");

            // 1. Load Configuration
            ConfigLoader.getConfig();

            // 2. Initialize Core Modules to satisfy SRP architecture
            AccountManager accountManager = new AccountManager();
            IbkrService ibkr = new IbkrService(accountManager);

            // 3. Connect to IBKR with a random client ID to avoid conflicts with Main.java
            int randomClientId = new java.util.Random().nextInt(99999) + 1;
            ibkr.connect(
                    ConfigLoader.getConfig().ibkr.host,
                    ConfigLoader.getConfig().ibkr.port,
                    randomClientId
            );

            // 4. Initialize Strategies
            List<TradingStrategy> strategies = List.of(
                    new C1SqueezeCallStrategy(ibkr),
                    new C2TrendCallStrategy(ibkr),
                    new P1SqueezePutStrategy(ibkr),
                    new P2TrendPutStrategy(ibkr),
                    new C3BounceCallStrategy(ibkr),
                    new P3BouncePutStrategy(ibkr),
                    new C4OpeningCallStrategy(ibkr),
                    new P4OpeningPutStrategy(ibkr),
                    new C5ContinuationCallStrategy(ibkr),
                    new P5ContinuationPutStrategy(ibkr)
            );

            ForensicEngine auditor = new ForensicEngine(ibkr, strategies);

            String[] tickers = {"SPY", "NVDA", "AAPL", "TSLA", "AMD", "MSFT"};
            for (String t : tickers) ibkr.startMarketDataTracking(t);

            Thread.sleep(2000); // Wait for CSV data loading

            long start = System.currentTimeMillis();

            Arrays.stream(tickers).parallel().filter(t -> !t.equals("SPY")).forEach(ticker -> {
                // We use the TimeFrame.HOUR_1 Enum
                BarSeries s1h = ibkr.getSeries(ticker, TimeFrame.HOUR_1);

                if (s1h != null) auditor.runFullAudit(ticker, s1h);
            });

            System.out.println("\nTOTAL TIME: " + (System.currentTimeMillis() - start) + "ms");
            System.exit(0);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static class MultiOutputStream extends OutputStream {
        private final OutputStream[] o;
        public MultiOutputStream(OutputStream... o) { this.o = o; }
        @Override public void write(int b) throws IOException { for (OutputStream os : o) os.write(b); }
        @Override public void write(byte[] b, int off, int len) throws IOException { for (OutputStream os : o) os.write(b, off, len); }
        @Override public void flush() throws IOException { for (OutputStream os : o) os.flush(); }
        @Override public void close() throws IOException { for (OutputStream os : o) os.close(); }
    }
}