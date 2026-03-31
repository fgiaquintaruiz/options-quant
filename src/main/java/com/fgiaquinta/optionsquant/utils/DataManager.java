package com.fgiaquinta.optionsquant.utils;

import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.Bar;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

public class DataManager {
    private static final String DATA_DIR = "data/";
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_ZONED_DATE_TIME;

    private DataManager() {
        // Utility class
    }

    // Nota: Asegúrate de que IbkrService llame a este método pasando el cacheKey (ej. "AAPL_1hour")
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
                    ZonedDateTime time = ZonedDateTime.parse(values[0], FORMATTER);
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

        String cacheKey = series.getName(); // El nombre de la serie ahora es "AAPL_1hour", etc.
        File dir = new File(DATA_DIR);
        if (!dir.exists()) dir.mkdirs();

        File file = new File(DATA_DIR + cacheKey + ".csv");

        try (PrintWriter pw = new PrintWriter(new FileWriter(file))) {
            pw.println("Time,Open,High,Low,Close,Volume");
            for (int i = 0; i < series.getBarCount(); i++) {
                Bar bar = series.getBar(i);
                pw.printf(java.util.Locale.US, "%s,%.2f,%.2f,%.2f,%.2f,%.0f%n",
                        bar.getEndTime().format(FORMATTER),
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