package com.fgiaquinta.optionsquant;

import com.fgiaquinta.optionsquant.engine.ForensicEngine;
import com.fgiaquinta.optionsquant.services.IbkrService;
import com.fgiaquinta.optionsquant.strategies.*;
import com.fgiaquinta.optionsquant.models.TimeFrame;
import org.ta4j.core.BarSeries;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class BacktestRunner {
    static void main(String[] args) {
        try {
            PrintStream outConsole = System.out;
            System.setOut(new PrintStream(new MultiOutputStream(outConsole), true, StandardCharsets.UTF_8));

            System.out.println("INICIANDO BACKTEST ULTRA-RAPIDO (JAVA 25)");

            IbkrService ibkr = new IbkrService();
            List<TradingStrategy> strategies = List.of(
                    new C1SqueezeCallStrategy(ibkr),
                    new C2TrendCallStrategy(ibkr),
                    new P1SqueezePutStrategy(ibkr),
                    new P2TrendPutStrategy(ibkr),
                    new C3BounceCallStrategy(ibkr),
                    new P3BouncePutStrategy(ibkr),
                    new C4OpeningCallStrategy(ibkr),
                    new P4OpeningPutStrategy(ibkr),
                    new P5ContinuationPutStrategy(ibkr)
            );

            ForensicEngine auditor = new ForensicEngine(ibkr, strategies);
            ibkr.setForensicEngine(auditor);

            String[] tickers = {"SPY", "NVDA", "AAPL", "TSLA", "AMD", "MSFT"};
            for (String t : tickers) ibkr.startMarketDataTracking(t);

            Thread.sleep(2000); // Espera para carga de CSV

            long start = System.currentTimeMillis();
            Arrays.stream(tickers).parallel().filter(t -> !t.equals("SPY")).forEach(ticker -> {
                // AQUÍ ESTÁ LA CORRECCIÓN: Usamos el Enum TimeFrame.HOUR_1
                BarSeries s1h = ibkr.getSeries(ticker, TimeFrame.HOUR_1);

                if (s1h != null) auditor.runFullAudit(ticker, s1h);
            });

            System.out.println("\nTIEMPO TOTAL: " + (System.currentTimeMillis() - start) + "ms");
            System.exit(0);
        } catch (Exception e) { e.printStackTrace(); }
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