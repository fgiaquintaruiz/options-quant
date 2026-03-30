package com.fgiaquinta.optionsquant;

import com.fgiaquinta.optionsquant.engine.ForensicEngine;
import com.fgiaquinta.optionsquant.services.IbkrService;
import com.fgiaquinta.optionsquant.strategies.*;
import org.ta4j.core.BarSeries;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class BacktestRunner {
    public static void main(String[] args) {
        try {
            String fileName = "analisis_forense_optionsquant.txt";
            PrintStream outConsole = System.out;
            FileOutputStream fos = new FileOutputStream(fileName);
            PrintStream outFile = new PrintStream(fos);

            // Salida dual: Consola + Archivo
            System.setOut(new PrintStream(new MultiOutputStream(outConsole, outFile), true, StandardCharsets.UTF_8));

            System.out.println("INICIANDO BACKTEST ULTRA-RAPIDO (JAVA 25)");

            IbkrService ibkr = new IbkrService();
            List<TradingStrategy> strategies = List.of(
                    new C1SqueezeCallStrategy(ibkr),
                    new C2TrendCallStrategy(ibkr),
                    new P1SqueezePutStrategy(),
                    new P2TrendPutStrategy(ibkr)
            );

            ForensicEngine auditor = new ForensicEngine(ibkr, strategies);
            ibkr.setForensicEngine(auditor);

            String[] tickers = {"SPY", "NVDA", "AAPL", "TSLA", "AMD", "MSFT"};
            for (String t : tickers) ibkr.startMarketDataTracking(t);

            Thread.sleep(2000); // Espera para carga de CSV

            long start = System.currentTimeMillis();
            Arrays.stream(tickers).parallel().filter(t -> !t.equals("SPY")).forEach(ticker -> {
                BarSeries s1h = ibkr.getSeries(ticker + "_1hour");
                if (s1h != null) auditor.runFullAudit(ticker, s1h);
            });

            System.out.println("\nTIEMPO TOTAL: " + (System.currentTimeMillis() - start) + "ms");
            System.exit(0);
        } catch (Exception e) { e.printStackTrace(); }
    }

    private static class MultiOutputStream extends OutputStream {
        private final OutputStream[] o;
        public MultiOutputStream(OutputStream... s) { this.o = s; }
        @Override public void write(int b) throws IOException { for (OutputStream s : o) s.write(b); }
    }
}