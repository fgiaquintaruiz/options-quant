package com.fgiaquinta.optionsquant.utils;

import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.Bar;

import java.io.*;
import java.time.ZonedDateTime;

public class DataManager {
    private static final String DATA_DIR = "data/";

    private DataManager() {
        // Utility class
    }

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
                    // Use centralized configuration for parsing
                    ZonedDateTime time = ZonedDateTime.parse(values[0], MarketTimeUtils.CSV_FORMATTER);

                    // Explicitly bind the loaded time to the market timezone
                    time = time.withZoneSameInstant(MarketTimeUtils.MARKET_ZONE);

                    double open = Double.parseDouble(values[1]);
                    double high = Double.parseDouble(values[2]);
                    double low = Double.parseDouble(values[3]);
                    double close = Double.parseDouble(values[4]);
                    double volume = Double.parseDouble(values[5]);

                    series.addBar(time, open, high, low, close, volume);
                }
            }
            System.out.println("💾 Loading " + cacheKey + " from local CSV...");
        } catch (Exception e) {
            System.err.println("❌ Error leyendo CSV para " + cacheKey + ": " + e.getMessage());
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
                pw.printf(java.util.Locale.US, "%s,%.2f,%.2f,%.2f,%.2f,%.0f%n",
                        // Use centralized configuration for saving
                        bar.getEndTime().format(MarketTimeUtils.CSV_FORMATTER),
                        bar.getOpenPrice().doubleValue(),
                        bar.getHighPrice().doubleValue(),
                        bar.getLowPrice().doubleValue(),
                        bar.getClosePrice().doubleValue(),
                        bar.getVolume().doubleValue());
            }
            System.out.println("💾 Successfully saved: data/" + cacheKey + ".csv");
        } catch (IOException e) {
            System.err.println("❌ Error saving CSV for " + cacheKey + ": " + e.getMessage());
        }
    }
}