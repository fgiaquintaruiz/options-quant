package com.fgiaquinta.optionsquant;

import com.fgiaquinta.optionsquant.engine.AccountManager;
import com.fgiaquinta.optionsquant.engine.FastBacktester;
import com.fgiaquinta.optionsquant.services.IbkrService;
import com.fgiaquinta.optionsquant.strategies.*;
import com.fgiaquinta.optionsquant.models.TimeFrame;
import com.fgiaquinta.optionsquant.utils.ConfigLoader;

import org.ta4j.core.*;
import org.ta4j.core.rules.AbstractRule;
import org.ta4j.core.rules.StopGainRule;
import org.ta4j.core.rules.StopLossRule;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class BacktestRunner {
    public static void main(String[] args) {
        System.setOut(new java.io.PrintStream(System.out, true, StandardCharsets.UTF_8));
        try {
            System.out.println("🚀 STARTING VISUAL DIAGNOSTIC BACKTEST (JAVA 25)");

            // 1. Cargar Configuración
            ConfigLoader.getConfig();

            // 2. Inicializar Módulos Core
            AccountManager accountManager = new AccountManager();
            IbkrService ibkr = new IbkrService(accountManager);

            // 3. Conectar a IBKR con ID aleatorio
            int randomClientId = new java.util.Random().nextInt(99999) + 1;
            ibkr.connect("127.0.0.1", 7497, randomClientId);
            Thread.sleep(1000);

            // 4. Instanciar Estrategias a evaluar
            List<TradingStrategy> strategies = Arrays.asList(
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

            // 5. Instanciar el motor de diagnóstico
            FastBacktester backtester = new FastBacktester();

            String[] tickers = {"SPY",  "MSFT"};//"NVDA", "AAPL", "TSLA", "AMD",
            for (String t : tickers) ibkr.startMarketDataTracking(t);

            Thread.sleep(2000); // Esperar carga de CSVs locales

            long start = System.currentTimeMillis();

            // Obtener el benchmark (SPY) para las estrategias que validan el mercado macro
            BarSeries spy1H = ibkr.getSeries("SPY", TimeFrame.HOUR_1);

            Arrays.stream(tickers).parallel().filter(t -> !t.equals("SPY")).forEach(ticker -> {
                // Obtener las series de temporalidad principal
                BarSeries target1H = ibkr.getSeries(ticker, TimeFrame.HOUR_1);

                // NOTA: Si tus estrategias de rebote usan 15M, debes cargarlas también aquí
                // BarSeries target15M = ibkr.getSeries(ticker, TimeFrame.MIN_15);

                if (target1H != null && !target1H.isEmpty()) {
                    if (ticker.equals("MSFT")) {
                        debugSpecificCandle(target1H, "2025-10-17 10:00");
                    }
                    for (TradingStrategy strategy : strategies) {

                        // A. Adaptar la estrategia con TP/SL simulados
                        Strategy ta4jStrategy = buildTa4jStrategy(strategy, target1H, spy1H);

                        // B. Ejecutar la Auditoría Visual
                        backtester.runDiagnosticVerification(ticker, target1H, strategy, ta4jStrategy);
                    }
                }
            });

            System.out.println("\nTOTAL TIME: " + (System.currentTimeMillis() - start) + "ms");
            System.exit(0);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Convierte tu lógica manual (TradingStrategy) en un modelo ta4j (Strategy) inyectando
     * reglas de salida ficticias para que los trades se cierren y puedan ser auditados.
     */
    private static Strategy buildTa4jStrategy(TradingStrategy customStrategy, BarSeries targetSeries, BarSeries benchmarkSeries) {
        // Regla de Entrada: Usa tu lógica exacta ya programada
        Rule entryRule = new AbstractRule() {
            @Override
            public boolean isSatisfied(int index, TradingRecord tradingRecord) {
                return customStrategy.isTriggered(index, targetSeries, benchmarkSeries);
            }
        };

        // Reglas de Salida Ficticias (Necesarias para el backtest)
        ClosePriceIndicator closePrice = new ClosePriceIndicator(targetSeries);
        Rule stopGain = new StopGainRule(closePrice, 2.0); // Vender si sube 2%
        Rule stopLoss = new StopLossRule(closePrice, 1.0); // Vender si baja 1%

        // Regla de Tiempo: Vender si han pasado 15 velas y no tocó ni TP ni SL
        Rule timeExit = new AbstractRule() {
            @Override
            public boolean isSatisfied(int index, TradingRecord tradingRecord) {
                if (tradingRecord.getCurrentPosition() != null) {
                    return (index - tradingRecord.getCurrentPosition().getEntry().getIndex()) >= 15;
                }
                return false;
            }
        };

        // Combina las salidas: Cierra por Ganancia O Pérdida O Tiempo
        Rule exitRule = stopGain.or(stopLoss).or(timeExit);

        return new BaseStrategy(customStrategy.getName(), entryRule, exitRule);
    }

    /**
     * Helper method to find a specific date in the BarSeries and print its exact OHLC values.
     * This ensures the CSV parser is reading timezones and prices correctly.
     */
    private static void debugSpecificCandle(BarSeries series, String targetDateTime) {
        System.out.println("🕵️ DEBUGGING CANDLE: " + targetDateTime);
        java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

        boolean found = false;
        for (int i = 0; i < series.getBarCount(); i++) {
            Bar bar = series.getBar(i);
            String barTime = bar.getEndTime().format(formatter);

            if (barTime.equals(targetDateTime)) {
                System.out.printf("   [Index %d] O: %.2f | H: %.2f | L: %.2f | C: %.2f | Vol: %.0f%n",
                        i,
                        bar.getOpenPrice().doubleValue(),
                        bar.getHighPrice().doubleValue(),
                        bar.getLowPrice().doubleValue(),
                        bar.getClosePrice().doubleValue(),
                        bar.getVolume().doubleValue()
                );
                found = true;
                break;
            }
        }

        if (!found) {
            System.out.println("   ❌ Candle not found. Check if the market was open or timezone offset.");
        }
    }
}
