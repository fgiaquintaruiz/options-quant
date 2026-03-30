package com.fgiaquinta.optionsquant.utils;

import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import java.io.*;
import java.nio.file.*;
import java.time.ZonedDateTime;

public class DataManager {
    private static final String DATA_DIR = "data/";

    /**
     * Guarda la serie en un archivo CSV.
     */
    public static void saveToCsv(BarSeries series) {
        try {
            Files.createDirectories(Paths.get(DATA_DIR));
            String fileName = DATA_DIR + series.getName() + ".csv";
            try (PrintWriter writer = new PrintWriter(new FileWriter(fileName))) {
                for (int i = 0; i < series.getBarCount(); i++) {
                    org.ta4j.core.Bar bar = series.getBar(i);
                    writer.println(String.format("%s,%s,%s,%s,%s,%s",
                            bar.getEndTime(),
                            bar.getOpenPrice().doubleValue(),
                            bar.getHighPrice().doubleValue(),
                            bar.getLowPrice().doubleValue(),
                            bar.getClosePrice().doubleValue(),
                            bar.getVolume().doubleValue()));
                }
                System.out.println("💾 Guardado correctamente: " + fileName);
            }
        } catch (IOException e) {
            System.err.println("❌ Error guardando CSV: " + e.getMessage());
        }
    }

    /**
     * Carga la serie desde un archivo CSV si existe.
     */
    public static BarSeries loadSeries(String ticker, String barSize) {
        String fullName = ticker + "_" + barSize.replace(" ", "");
        String fileName = DATA_DIR + fullName + ".csv";
        BarSeries series = new BaseBarSeriesBuilder().withName(fullName).build();

        File file = new File(fileName);
        if (file.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                System.out.println("💾 Cargando " + fullName + " desde CSV local...");
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] p = line.split(",");
                    series.addBar(ZonedDateTime.parse(p[0]),
                            Double.parseDouble(p[1]), Double.parseDouble(p[2]),
                            Double.parseDouble(p[3]), Double.parseDouble(p[4]),
                            Double.parseDouble(p[5]));
                }
            } catch (Exception e) {
                System.err.println("⚠️ Error al cargar CSV: " + e.getMessage());
            }
        }
        return series;
    }
}