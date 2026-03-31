package com.fgiaquinta.optionsquant.utils;

import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;

public class ForensicLogger {
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final ZoneId ZONE = ZoneId.of("Europe/Madrid");
    private static final ConcurrentHashMap<String, PrintStream> fileMap = new ConcurrentHashMap<>();

    public static void logWithFastUtil(String strategy, String ticker, Bar b1h, BarSeries s15m, Long2IntMap map, double tp, double sl) {
        long time = b1h.getEndTime().toEpochSecond();
        if (!map.containsKey(time)) return;
        int idx = map.get(time);

        double entry = b1h.getClosePrice().doubleValue();
        boolean isCall = strategy.contains("CALL");

        PrintStream ps = getStreamForStrategy(strategy);

        ps.println("\n============================================================");
        ps.printf("ANALISIS: %s | %s | ENTRADA: %.2f | TP: %.2f | SL: %.2f%n", ticker, strategy, entry, tp, sl);
        ps.println("Hora  |  Open  |  High  |  Low   |  Close |  Vol");

        int start = Math.max(0, idx - 26);
        int end = Math.min(s15m.getBarCount() - 1, idx + 26);
        String res = "EN CURSO";

        for (int j = start; j <= end; j++) {
            Bar b = s15m.getBar(j);
            if (j > idx && res.equals("EN CURSO")) {
                double h = b.getHighPrice().doubleValue(), l = b.getLowPrice().doubleValue();
                if (isCall) { if (h >= tp) res = "EXITO"; else if (l <= sl) res = "FALLO"; }
                else { if (l <= tp) res = "EXITO"; else if (h >= sl) res = "FALLO"; }
            }
            ps.printf("%s | %.2f | %.2f | %.2f | %.2f | %.0f %s%n",
                    b.getEndTime().withZoneSameInstant(ZONE).format(FMT),
                    b.getOpenPrice().doubleValue(), b.getHighPrice().doubleValue(),
                    b.getLowPrice().doubleValue(), b.getClosePrice().doubleValue(),
                    b.getVolume().doubleValue(), (j == idx ? "[ENTRADA]" : ""));
        }
        ps.println("RESULTADO: " + res + "\n============================================================\n");
    }

    private static PrintStream getStreamForStrategy(String strategy) {
        return fileMap.computeIfAbsent(strategy, name -> {
            // Formato: reports/c1_squeeze_call.txt
            String fileName = "reports/" + name.toLowerCase() + ".txt";
            try {
                File dir = new File("reports");
                if (!dir.exists()) dir.mkdirs();

                PrintStream ps = new PrintStream(new FileOutputStream(fileName, true), true, StandardCharsets.UTF_8);
                System.out.println("💾 Strategy report saved: " + fileName);
                return ps;
            } catch (Exception e) {
                return System.out;
            }
        });
    }

    public static void logExecution(String ticker, String action, double avgPrice, double shares, double commission, String execId) {
        String fileName = "logs/executions_" + LocalDate.now() + ".txt";
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));

        String entry = String.format("[%s] EXECUTION: %s | %s | Price: %.4f | Qty: %.0f | Comm: %.2f | ID: %s%n",
                timestamp, ticker, action, avgPrice, shares, commission, execId);

        try {
            Files.createDirectories(Paths.get("logs"));
            Files.write(Paths.get(fileName), entry.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            System.out.println("📝 Execution registered: " + ticker);
        } catch (IOException e) {
            System.err.println("❌ Error subscribing in Forensic Log: " + e.getMessage());
        }
    }
}