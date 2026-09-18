package com.fgiaquinta.optionsquant.utils;

import com.fgiaquinta.optionsquant.models.TimeFrame;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.Bar;

import java.io.*;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DataManager {
    private static final String DATA_DIR = "data/";

    // =========================================================
    // 👉 NUEVO: CACHÉ EN MEMORIA PARA EL STAGGERED POLLING
    // =========================================================
    // Un mapa doble: Ticker -> (Temporalidad -> Serie de Velas)
    private final Map<String, Map<TimeFrame, BarSeries>> cache = new ConcurrentHashMap<>();

    public DataManager() {
        // Constructor público para poder inyectarlo en el StrategyEngine
    }

    // Guarda una serie en la caché RAM del bot
    public void putSeries(String ticker, TimeFrame timeFrame, BarSeries series) {
        cache.computeIfAbsent(ticker, k -> new ConcurrentHashMap<>()).put(timeFrame, series);
    }

    // Recupera una serie de la caché RAM
    public BarSeries getSeries(String ticker, TimeFrame timeFrame) {
        Map<TimeFrame, BarSeries> tickerData = cache.get(ticker);
        if (tickerData != null) {
            return tickerData.get(timeFrame);
        }
        return null;
    }

    // Verifica que tengamos TODAS las temporalidades necesarias para operar
    public boolean hasAllRequiredData(String ticker) {
        Map<TimeFrame, BarSeries> data = cache.get(ticker);
        if (data == null) return false;

        // Exigimos las 4 temporalidades de la estrategia
        return data.containsKey(TimeFrame.DAY_1) &&
                data.containsKey(TimeFrame.HOUR_1) &&
                data.containsKey(TimeFrame.MIN_15) &&
                data.containsKey(TimeFrame.MIN_5);
    }

    // =========================================================
    // 👉 INTACTO: LÓGICA DE CARGA Y DESCARGA CSV (NO SE TOCA)
    // =========================================================
    public static BarSeries loadSeries(String cacheKey) {
        BarSeries series = new BaseBarSeriesBuilder().withName(cacheKey).build();
        File file = new File(DATA_DIR + cacheKey + ".csv");

        if (!file.exists()) {
            return series;
        }

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            boolean isHeader = true;
            while ((line = br.readLine()) != null) {
                if (isHeader) {
                    isHeader = false;
                    continue;
                }
                String[] values = line.split(",");
                if (values.length >= 6) {

                    // 1. Read the text assuming it is in local time (e.g., Spain)
                    LocalDateTime localTimeText = LocalDateTime.parse(values[0], MarketTimeUtils.CSV_FORMATTER);

                    // 2. Tag it with the local system timezone, then immediately shift it to NY Market Time
                    ZonedDateTime marketTime = ZonedDateTime.of(localTimeText, ZoneId.systemDefault())
                            .withZoneSameInstant(MarketTimeUtils.MARKET_ZONE);

                    double open = Double.parseDouble(values[1]);
                    double high = Double.parseDouble(values[2]);
                    double low = Double.parseDouble(values[3]);
                    double close = Double.parseDouble(values[4]);
                    double volume = Double.parseDouble(values[5]);

                    series.addBar(marketTime, open, high, low, close, volume);
                }
            }
            System.out.println("💾 Loaded " + series.getBarCount() + " bars from local CSV for " + cacheKey);
        } catch (IOException e) {
            System.err.println("❌ Error loading CSV for " + cacheKey + ": " + e.getMessage());
        }
        return series;
    }

    public static void saveToCsv(BarSeries series) {
        if (series == null || series.getBarCount() == 0) return;

        String cacheKey = series.getName();
        File dir = new File(DATA_DIR);
        if (!dir.exists()) dir.mkdirs();

        File file = new File(DATA_DIR + cacheKey + ".csv");

        try (PrintWriter pw = new PrintWriter(new FileWriter(file))) {
            pw.println("Time,Open,High,Low,Close,Volume");
            for (int i = 0; i < series.getBarCount(); i++) {
                Bar bar = series.getBar(i);

                // Convert internal NY time to local system time before saving
                ZonedDateTime localTimeForCsv = bar.getEndTime().withZoneSameInstant(ZoneId.systemDefault());

                pw.printf(java.util.Locale.US, "%s,%.2f,%.2f,%.2f,%.2f,%.0f%n",
                        localTimeForCsv.format(MarketTimeUtils.CSV_FORMATTER),
                        bar.getOpenPrice().doubleValue(),
                        bar.getHighPrice().doubleValue(),
                        bar.getLowPrice().doubleValue(),
                        bar.getClosePrice().doubleValue(),
                        bar.getVolume().doubleValue());
            }
            System.out.println("💾 Successfully saved: data/" + cacheKey + ".csv");
        } catch (IOException e) {
            System.err.println("❌ Error saving to CSV: " + e.getMessage());
        }
    }

    // Este método centraliza la carga de datos desde los CSV a la caché de memoria
    public boolean loadLocalDataFromCsv(String ticker, TimeFrame timeFrame) {
        String suffix;
        switch (timeFrame) {
            case MIN_5:
                suffix = "5min";
                break;
            case MIN_15:
                suffix = "15min";
                break;
            case HOUR_1:
                suffix = "1hour";
                break;
            case DAY_1:
                suffix = "1day";
                break;
            default:
                suffix = timeFrame.name();
                break;
        }

        String cacheKey = ticker + "_" + suffix;
        java.io.File file = new java.io.File("data/" + cacheKey + ".csv");

        if (!file.exists()) {
            System.out.println("⚠️ ALERTA: No se encontró el archivo CSV local -> " + file.getAbsolutePath());
            return false;
        }

        org.ta4j.core.BarSeries series = new org.ta4j.core.BaseBarSeriesBuilder().withName(cacheKey).build();

        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(file))) {
            String line = br.readLine(); // Saltar la cabecera
            while ((line = br.readLine()) != null) {
                String[] values = line.split(",");
                if (values.length < 6) continue;

                java.time.LocalDateTime localDateTime = java.time.LocalDateTime.parse(values[0], MarketTimeUtils.CSV_FORMATTER);
                java.time.ZonedDateTime dateTime = localDateTime.atZone(java.time.ZoneId.systemDefault());

                double open = Double.parseDouble(values[1]);
                double high = Double.parseDouble(values[2]);
                double low = Double.parseDouble(values[3]);
                double close = Double.parseDouble(values[4]);
                double volume = Double.parseDouble(values[5]);

                series.addBar(dateTime, open, high, low, close, volume);
            }

            putSeries(ticker, timeFrame, series);
            System.out.println("💾 Loaded " + series.getBarCount() + " bars from local CSV for " + cacheKey);
            return true;

        } catch (Exception e) {
            System.out.println("❌ Error crítico parseando " + cacheKey + ": " + e.getMessage());
            return false;
        }
    }

    // 👉 Verifica si un ticker ya tiene sus 4 gráficas listas en memoria
    public boolean isTickerReady(String ticker) {
        org.ta4j.core.BarSeries d1 = getSeries(ticker, com.fgiaquinta.optionsquant.models.TimeFrame.DAY_1);
        org.ta4j.core.BarSeries h1 = getSeries(ticker, com.fgiaquinta.optionsquant.models.TimeFrame.HOUR_1);
        org.ta4j.core.BarSeries m15 = getSeries(ticker, com.fgiaquinta.optionsquant.models.TimeFrame.MIN_15);
        org.ta4j.core.BarSeries m5 = getSeries(ticker, com.fgiaquinta.optionsquant.models.TimeFrame.MIN_5);

        return d1 != null && !d1.isEmpty() &&
                h1 != null && !h1.isEmpty() &&
                m15 != null && !m15.isEmpty() &&
                m5 != null && !m5.isEmpty();
    }

    /**
     * 👉 PERSISTENCIA: Guarda la serie actual de la memoria al archivo CSV local.
     */
    public void saveSeriesToCsv(String ticker, com.fgiaquinta.optionsquant.models.TimeFrame timeFrame) {
        org.ta4j.core.BarSeries series = getSeries(ticker, timeFrame);
        if (series == null || series.isEmpty()) return;

        String suffix;
        switch (timeFrame) {
            case MIN_5: suffix = "5min"; break;
            case MIN_15: suffix = "15min"; break;
            case HOUR_1: suffix = "1hour"; break;
            case DAY_1: suffix = "1day"; break;
            default: suffix = timeFrame.name(); break;
        }

        java.io.File file = new java.io.File("data/" + ticker + "_" + suffix + ".csv");

        try (java.io.PrintWriter writer = new java.io.PrintWriter(new java.io.FileWriter(file))) {
            // Escribir cabecera
            writer.println("Time,Open,High,Low,Close,Volume");

            for (int i = 0; i < series.getBarCount(); i++) {
                org.ta4j.core.Bar bar = series.getBar(i);
                // Usamos el formateador de MarketTimeUtils para mantener la consistencia
                String timeStr = bar.getEndTime().format(MarketTimeUtils.CSV_FORMATTER);

                writer.printf(java.util.Locale.US, "%s,%.2f,%.2f,%.2f,%.2f,%.0f%n",
                        timeStr,
                        bar.getOpenPrice().doubleValue(),
                        bar.getHighPrice().doubleValue(),
                        bar.getLowPrice().doubleValue(),
                        bar.getClosePrice().doubleValue(),
                        bar.getVolume().doubleValue());
            }
            // System.out.println("💾 [DataManager] Archivo actualizado: " + file.getName());
        } catch (java.io.IOException e) {
            System.err.println("❌ Error guardando CSV para " + ticker + ": " + e.getMessage());
        }
    }
}