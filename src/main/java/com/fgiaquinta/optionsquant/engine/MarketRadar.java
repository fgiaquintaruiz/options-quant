package com.fgiaquinta.optionsquant.engine;

import com.fgiaquinta.optionsquant.services.IbkrService;
import com.fgiaquinta.optionsquant.utils.DataManager;
import com.fgiaquinta.optionsquant.models.TimeFrame;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MarketRadar {
    private boolean forceMacroFavorable = false;

    private final List<String> hotTickers = new CopyOnWriteArrayList<>();
    private final AiNewsInterpreter newsInterpreter = new AiNewsInterpreter();

    // 👉 Nuevos componentes inyectados para el Live Trading
    private final IbkrService ibkrService;
    private final DataManager dataManager;
    private final List<String> activeTickers;

    // Constructor actualizado para recibir todo el motor
    public MarketRadar(IbkrService ibkrService, DataManager dataManager, List<String> activeTickers) {
        this.ibkrService = ibkrService;
        this.dataManager = dataManager;
        this.activeTickers = activeTickers;
    }

    // =========================================================================
    // 👉 1. MOTOR ASÍNCRONO: Descarga de datos sin bloquear el sistema
    // =========================================================================
    public void prepareMarketDataAsync() {
        System.out.println("🔄 [MarketRadar] Solicitando Base de Datos a IBKR en Segundo Plano...");

        // Creamos un hilo paralelo
        new Thread(() -> {
            for (String ticker : activeTickers) {
                // NOTA: Si tu IbkrService.requestHistoricalDataForCache recibe 2 o 3 parámetros,
                // ajusta esto según cómo lo tengas en IbkrService.java
                ibkrService.requestHistoricalDataForCache(ticker, TimeFrame.DAY_1);
                ibkrService.requestHistoricalDataForCache(ticker, TimeFrame.HOUR_1);
                ibkrService.requestHistoricalDataForCache(ticker, TimeFrame.MIN_15);
                ibkrService.requestHistoricalDataForCache(ticker, TimeFrame.MIN_5);

                try {
                    Thread.sleep(150); // Pausa anti-saturación de API (50 peticiones / 10 seg)
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            System.out.println("✅ [MarketRadar] Todas las peticiones de historial fueron enviadas a IBKR.");
        }, "DataFetcherThread").start();
    }

    // =========================================================================
    // 👉 3. MÉTODOS ORIGINALES (Macro & Noticias)
    // =========================================================================
    public void addHotTicker(String symbol) {
        if (!hotTickers.contains(symbol)) {
            hotTickers.add(symbol);
            System.out.println("🔭 Added to Radar: " + symbol);
        }
    }

    public void setForceMacroFavorable(boolean force) {
        this.forceMacroFavorable = force;
        System.out.println("🌐 [MarketRadar] Macro Environment Force: " + (force ? "ON (Always Green)" : "OFF"));
    }

    public boolean isHot(String ticker) {
        return hotTickers.contains(ticker);
    }

    public boolean isMacroFavorable(boolean isCall) {
        if (forceMacroFavorable) return true;

        System.out.println("🌐 Checking Macro-Technical environment (SPY 50-SMA Trend)...");
        try {
            BarSeries spy = dataManager.getSeries("SPY", TimeFrame.DAY_1);

            // If we don't have enough data to calculate a 50 SMA, allow trade to proceed safely
            if (spy == null || spy.getBarCount() < 50) {
                System.out.println("⚠️ [MarketRadar] Not enough SPY daily data for SMA. Bypassing technical macro check.");
                return true;
            }

            ClosePriceIndicator closePrice = new ClosePriceIndicator(spy);
            SMAIndicator sma50 = new SMAIndicator(closePrice, 50);

            double currentSpyPrice = closePrice.getValue(spy.getEndIndex()).doubleValue();
            double smaValue = sma50.getValue(spy.getEndIndex()).doubleValue();

            if (isCall) {
                boolean favorable = currentSpyPrice > smaValue;
                if (!favorable) System.out.println("🛑 [MarketRadar] Blocked CALL: SPY is below 50 SMA (Macro Downtrend).");
                return favorable;
            } else {
                boolean favorable = currentSpyPrice < smaValue;
                if (!favorable) System.out.println("🛑 [MarketRadar] Blocked PUT: SPY is above 50 SMA (Macro Uptrend).");
                return favorable;
            }
        } catch (Exception e) {
            System.err.println("❌ [MarketRadar] Error checking Macro: " + e.getMessage());
            return true; // En caso de fallo, no bloqueamos la estrategia por defecto
        }
    }
}