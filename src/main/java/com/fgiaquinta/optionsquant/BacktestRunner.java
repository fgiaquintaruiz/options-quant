package com.fgiaquinta.optionsquant;

import com.fgiaquinta.optionsquant.engine.AccountManager;
import com.fgiaquinta.optionsquant.services.IbkrService;
import com.fgiaquinta.optionsquant.strategies.*;
import com.fgiaquinta.optionsquant.models.TimeFrame;
import com.fgiaquinta.optionsquant.utils.ConfigLoader;
import com.fgiaquinta.optionsquant.utils.DataManager;
import com.fgiaquinta.optionsquant.utils.MarketTimeUtils;

import org.ta4j.core.*;
import org.ta4j.core.rules.AbstractRule;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class BacktestRunner {

    // 👉 Variable para controlar cuántos tickers procesar. (Pon 999 para procesar todos)
    private static final int MAX_TICKERS_TO_PROCESS = 65;

    // Formato de hora para los logs del sistema
    private static final DateTimeFormatter LOG_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 👉 NUEVO: Método helper para imprimir mensajes con Fecha y Hora exacta
     */
    private static void logInfo(String message) {
        System.out.println("[" + LocalDateTime.now().format(LOG_FMT) + "] " + message);
    }

    public static void main(String[] args) {
        try {
            // =========================================================
            // 👉 NUEVO: CONFIGURACIÓN DE LOGS DUAL (Consola + Archivo)
            // =========================================================
            java.io.PrintStream consoleOut = System.out;
            java.io.PrintStream fileOut = new java.io.PrintStream(new java.io.FileOutputStream("backtest_resultados.log"), true, "UTF-8");

            java.io.PrintStream dualStream = new java.io.PrintStream(new java.io.OutputStream() {
                @Override
                public void write(int b) {
                    consoleOut.write(b);
                    fileOut.write(b);
                }

                @Override
                public void write(byte[] b, int off, int len) {
                    consoleOut.write(b, off, len);
                    fileOut.write(b, off, len);
                }
            }, true, "UTF-8");

            System.setOut(dualStream);
            System.setErr(dualStream); // También guardamos los errores

            logInfo("🚀 STARTING MULTI-TIMEFRAME PORTFOLIO BACKTEST (OPTIONSQUANT STRATEGIES)");

            ConfigLoader.getConfig();
            AccountManager accountManager = new AccountManager();
            IbkrService ibkr = new IbkrService(accountManager);

            DataManager dataManager = new DataManager();
            ibkr.setDataManager(dataManager);

            int randomClientId = new java.util.Random().nextInt(1000);
            ibkr.connect("127.0.0.1", 7497, randomClientId);

            List<String> configTickers = ConfigLoader.getConfig().getList("ibkr", "tickers");
            List<String> activeTickers = new ArrayList<>(configTickers);

            if (!activeTickers.contains("SPY")) {
                activeTickers.add("SPY");
            }

            // =========================================================
            // FASE 1: DESCARGA DE DATOS (Offline o IBKR)
            // =========================================================
            boolean needsBackfill = false;

            int tickersLimit = Math.min(MAX_TICKERS_TO_PROCESS, activeTickers.size());
            List<String> tickersToProcess = new ArrayList<>(activeTickers.subList(0, tickersLimit));

            List<String> tickersToDownload = new ArrayList<>(tickersToProcess);
            if (!tickersToDownload.contains("SPY")) {
                tickersToDownload.add("SPY");
            }

            for (String ticker : tickersToDownload) {
                logInfo("💾 Intentando cargar historial local para: " + ticker);

                boolean has1D = loadLocalDataToCache(ticker, TimeFrame.DAY_1, dataManager);
                boolean has1H = loadLocalDataToCache(ticker, TimeFrame.HOUR_1, dataManager);
                boolean has15m = loadLocalDataToCache(ticker, TimeFrame.MIN_15, dataManager);
                boolean has5m = loadLocalDataToCache(ticker, TimeFrame.MIN_5, dataManager);

                if (!has1D || !has1H || !has15m || !has5m) {
                    logInfo("🌐 Faltan datos para " + ticker + ". Solicitando descarga a IBKR...");
                    ibkr.startMarketDataTracking(ticker);
                    needsBackfill = true;
                    // Retraso de 1 segundo para evitar que IBKR nos penalice por descargar muy rápido
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                    }
                }
            }

            if (needsBackfill) {
                logInfo("⏳ Esperando a que IBKR descargue los datos históricos faltantes...");
                ibkr.waitForBackfillCompletion();

                logInfo("💾 Guardando los nuevos datos en CSV para futuras simulaciones...");
                for (String ticker : tickersToDownload) {
                    DataManager.saveToCsv(dataManager.getSeries(ticker, TimeFrame.DAY_1));
                    DataManager.saveToCsv(dataManager.getSeries(ticker, TimeFrame.HOUR_1));
                    DataManager.saveToCsv(dataManager.getSeries(ticker, TimeFrame.MIN_15));
                    DataManager.saveToCsv(dataManager.getSeries(ticker, TimeFrame.MIN_5));
                }
                logInfo("✅ Todos los datos han sido guardados en disco exitosamente.");
            }

            // =========================================================
            // FASE 2: SIMULACIÓN DE PORTAFOLIO
            // =========================================================
            List<TradingStrategy> customStrategies = Arrays.asList(
                    new C4OpeningCallStrategy(ibkr)
            );

            int globalTrades = 0;
            int globalAciertos = 0;
            int globalFallos = 0;
            double globalRendimiento = 0.0;

            ZoneId spainZone = ZoneId.of("Europe/Madrid");

            for (String targetTicker : tickersToProcess) {
                BarSeries targetSeries = dataManager.getSeries(targetTicker, TimeFrame.MIN_5);

                if (targetSeries == null || targetSeries.isEmpty()) {
                    logInfo("⚠️ ERROR: No hay datos suficientes para " + targetTicker + ". Saltando al siguiente...");
                    continue;
                }

                for (TradingStrategy customStrategy : customStrategies) {
                    Strategy ta4jStrategy = buildTa4jStrategy(customStrategy, targetSeries, dataManager);
                    BarSeriesManager seriesManager = new BarSeriesManager(targetSeries);
                    TradingRecord tradingRecord = seriesManager.run(ta4jStrategy);

                    System.out.println("\n=========================================================");
                    System.out.println("🔍 AUDITORÍA: " + customStrategy.getName() + " en " + targetTicker);
                    System.out.println("=========================================================");

                    int aciertos = 0;
                    int fallos = 0;
                    double sumaRendimientos = 0.0;

                    boolean isCall = customStrategy.getName().toLowerCase().contains("call");
                    List<Position> positions = tradingRecord.getPositions();

                    for (int i = 0; i < positions.size(); i++) {
                        Position position = positions.get(i);
                        double entryPrice = position.getEntry().getNetPrice().doubleValue();
                        double exitPrice = position.getExit().getNetPrice().doubleValue();

                        double resultPct;
                        if (isCall) {
                            resultPct = ((exitPrice - entryPrice) / entryPrice) * 100.0;
                        } else {
                            resultPct = ((entryPrice - exitPrice) / entryPrice) * 100.0;
                        }

                        sumaRendimientos += resultPct;

                        int entryIndex = position.getEntry().getIndex();
                        int exitIndex = position.getExit().getIndex();

                        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

                        String entryTimeStr = targetSeries.getBar(entryIndex).getEndTime().withZoneSameInstant(spainZone).format(dtf);
                        String exitTimeStr = targetSeries.getBar(exitIndex).getEndTime().withZoneSameInstant(spainZone).format(dtf);

                        System.out.println("🎯 TRADE #" + (i + 1) + " [" + customStrategy.getName() + " | " + targetTicker + "]");
                        System.out.printf("   🟢 ENTRADA: %s | Precio: $%.2f \n", entryTimeStr, entryPrice);
                        System.out.printf("   🔴 SALIDA:  %s | Precio: $%.2f \n", exitTimeStr, exitPrice);

                        if (resultPct > 0) {
                            System.out.printf("   ✅ RESULTADO: +%.2f%%\n", resultPct);
                            aciertos++;
                        } else {
                            System.out.printf("   ❌ RESULTADO: %.2f%%\n", resultPct);
                            fallos++;
                        }
                    }

                    System.out.println("---------------------------------------------------------");
                    System.out.println("📊 PARCIAL " + targetTicker + " -> Trades: " + positions.size() + " | Aciertos: " + aciertos + " | Fallos: " + fallos);

                    globalTrades += positions.size();
                    globalAciertos += aciertos;
                    globalFallos += fallos;
                    globalRendimiento += sumaRendimientos;
                }
            }

            // =========================================================
            // FASE 3: REPORTE GLOBAL
            // =========================================================
            System.out.println("\n#########################################################");
            System.out.println("🏆 RESUMEN GLOBAL DEL PORTAFOLIO (" + tickersToProcess.size() + " TICKERS)");
            System.out.println("#########################################################");
            System.out.println("   Total Trades Ejecutados : " + globalTrades);
            System.out.println("   Operaciones Ganadoras   : " + globalAciertos);
            System.out.println("   Operaciones Perdedoras  : " + globalFallos);
            if (globalTrades > 0) {
                System.out.printf("   🎯 Win Rate Global      : %.2f%%\n", ((double) globalAciertos / globalTrades) * 100.0);
                System.out.printf("   💰 Rendimiento Neto     : %s%.2f%%\n", (globalRendimiento > 0 ? "+" : ""), globalRendimiento);
            } else {
                System.out.println("   ⚠️ No se dispararon trades en este periodo.");
            }
            System.out.println("#########################################################");

            ibkr.disconnect();
            System.exit(0);

        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Helper interno para leer el CSV e inyectarlo en el DataManager
     */
    private static boolean loadLocalDataToCache(String ticker, TimeFrame timeFrame, DataManager dataManager) {
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
        File file = new File("data/" + cacheKey + ".csv");

        if (!file.exists()) {
            logInfo("⚠️ ALERTA: No se encontró el archivo CSV local -> " + file.getAbsolutePath());
            return false;
        }

        BarSeries series = new org.ta4j.core.BaseBarSeriesBuilder().withName(cacheKey).build();

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line = br.readLine(); // Saltar la cabecera
            while ((line = br.readLine()) != null) {
                String[] values = line.split(",");
                if (values.length < 6) continue;

                LocalDateTime localDateTime = LocalDateTime.parse(values[0], MarketTimeUtils.CSV_FORMATTER);
                ZonedDateTime dateTime = localDateTime.atZone(ZoneId.systemDefault());

                double open = Double.parseDouble(values[1]);
                double high = Double.parseDouble(values[2]);
                double low = Double.parseDouble(values[3]);
                double close = Double.parseDouble(values[4]);
                double volume = Double.parseDouble(values[5]);

                series.addBar(dateTime, open, high, low, close, volume);
            }

            dataManager.putSeries(ticker, timeFrame, series);
            logInfo("💾 Loaded " + series.getBarCount() + " bars from local CSV for " + cacheKey);
            return true;

        } catch (Exception e) {
            logInfo("❌ Error crítico parseando " + cacheKey + ": " + e.getMessage());
            return false;
        }
    }

    private static Strategy buildTa4jStrategy(TradingStrategy customStrategy, BarSeries targetSeries, DataManager dataManager) {

        AbstractRule entryRule = new AbstractRule() {
            @Override
            public boolean isSatisfied(int index, TradingRecord tradingRecord) {
                String ticker = targetSeries.getName().split("_")[0];
                ZonedDateTime currentTime = targetSeries.getBar(index).getEndTime();
                return customStrategy.isTriggered(ticker, dataManager, currentTime);
            }
        };

        AbstractRule dynamicExitRule = new AbstractRule() {
            @Override
            public boolean isSatisfied(int index, TradingRecord tradingRecord) {
                if (tradingRecord.getCurrentPosition() == null) return false;

                String ticker = targetSeries.getName().split("_")[0];
                boolean isCall = customStrategy.getName().toLowerCase().contains("call");
                double entryPrice = tradingRecord.getCurrentPosition().getEntry().getNetPrice().doubleValue();
                int entryIndex = tradingRecord.getCurrentPosition().getEntry().getIndex();

                ZonedDateTime currentTime = targetSeries.getBar(index).getEndTime();
                ZonedDateTime entryTime = targetSeries.getBar(entryIndex).getEndTime();

                com.fgiaquinta.optionsquant.models.TradePlan plan = com.fgiaquinta.optionsquant.utils.RiskCalculator.generatePlan(dataManager, ticker, entryTime, isCall, entryPrice);

                ZonedDateTime nyTime = currentTime.withZoneSameInstant(java.time.ZoneId.of("America/New_York"));
                ZonedDateTime nyEntryTime = entryTime.withZoneSameInstant(java.time.ZoneId.of("America/New_York"));

                // 1. Cierre Intradía de Emergencia (15:50 NY)
                if (nyTime.getDayOfYear() != nyEntryTime.getDayOfYear() ||
                        (nyTime.getHour() == 15 && nyTime.getMinute() >= 50) || nyTime.getHour() >= 16) {
                    return true;
                }

                double high = targetSeries.getBar(index).getHighPrice().doubleValue();
                double low = targetSeries.getBar(index).getLowPrice().doubleValue();

                // =========================================================
                // 👉 2. SIMULACIÓN DE BRACKET ORDER (TP y SL estáticos de IBKR)
                // =========================================================
                if (isCall) {
                    if (high >= plan.takeProfit) return true; // Toco el TP!
                    if (low <= plan.stopLoss) return true;    // Toco el SL!
                } else {
                    if (low <= plan.takeProfit) return true;  // Toco el TP!
                    if (high >= plan.stopLoss) return true;   // Toco el SL!
                }

                // =========================================================
                // 👉 3. TIME STOP (Mata-Zombies / Anti-Theta)
                // =========================================================
                // Si han pasado más de 18 velas (90 minutos) y la orden sigue abierta,
                // significa que el mercado está en rango lateral. ¡Salimos!
                int barsHeld = index - entryIndex;
                if (barsHeld >= 18) {
                    return true;
                }

                return false;
            }
        };

        return new BaseStrategy(customStrategy.getName(), entryRule, dynamicExitRule);
    }
}