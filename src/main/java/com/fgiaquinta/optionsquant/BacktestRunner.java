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
import java.util.*;

public class BacktestRunner {

    // 👉 VARIABLE DE CONTROL: ¿Cuántos tickers de tu config.yaml quieres procesar?
    private static final int MAX_TICKERS_TO_PROCESS = 999;

    public static void main(String[] args) {
        System.setOut(new java.io.PrintStream(System.out, true, StandardCharsets.UTF_8));
        try {
            System.out.println("🚀 STARTING MULTI-TIMEFRAME PORTFOLIO BACKTEST (OPTIONSQUANT STRATEGIES)");

            ConfigLoader.getConfig();
            AccountManager accountManager = new AccountManager();
            IbkrService ibkr = new IbkrService(accountManager);

            // Inyectamos TU DataManager original
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

            int tickersLimit = Math.min(MAX_TICKERS_TO_PROCESS, configTickers.size());
            List<String> tickersToProcess = new ArrayList<>(configTickers.subList(0, tickersLimit));

            List<String> tickersToDownload = new ArrayList<>(tickersToProcess);
            if (!tickersToDownload.contains("SPY")) {
                tickersToDownload.add("SPY");
            }

            for (String ticker : tickersToDownload) {
                System.out.println("💾 Intentando cargar historial local para: " + ticker);

                boolean has1D = loadLocalDataToCache(ticker, TimeFrame.DAY_1, dataManager);
                boolean has1H = loadLocalDataToCache(ticker, TimeFrame.HOUR_1, dataManager);
                boolean has15m = loadLocalDataToCache(ticker, TimeFrame.MIN_15, dataManager);
                boolean has5m = loadLocalDataToCache(ticker, TimeFrame.MIN_5, dataManager);

                if (!has1D || !has1H || !has15m || !has5m) {
                    System.out.println("🌐 Faltan datos para " + ticker + ". Solicitando descarga a IBKR...");
                    ibkr.startMarketDataTracking(ticker);
                    needsBackfill = true;
                    try { Thread.sleep(1000); } catch (InterruptedException e) {}
                }
            }

            if (needsBackfill) {
                System.out.println("⏳ Esperando a que IBKR descargue los datos históricos faltantes...");
                ibkr.waitForBackfillCompletion();

                System.out.println("💾 Guardando los nuevos datos en CSV para futuras simulaciones...");
                for (String ticker : tickersToDownload) {
                    DataManager.saveToCsv(dataManager.getSeries(ticker, TimeFrame.DAY_1));
                    DataManager.saveToCsv(dataManager.getSeries(ticker, TimeFrame.HOUR_1));
                    DataManager.saveToCsv(dataManager.getSeries(ticker, TimeFrame.MIN_15));
                    DataManager.saveToCsv(dataManager.getSeries(ticker, TimeFrame.MIN_5));
                }
            }

            // =========================================================
            // FASE 2: SIMULACIÓN DE PORTAFOLIO
            // =========================================================
            List<TradingStrategy> customStrategies = Arrays.asList(
                    new P3BouncePutStrategy(ibkr) // <--- Cambia esto por la estrategia que quieras probar
            );

            int globalTrades = 0;
            int globalAciertos = 0;
            int globalFallos = 0;
            double globalRendimiento = 0.0;

            // 👉 Definimos la zona horaria de España para la auditoría visual
            ZoneId spainZone = ZoneId.of("Europe/Madrid");

            for (String targetTicker : tickersToProcess) {
                BarSeries targetSeries = dataManager.getSeries(targetTicker, TimeFrame.MIN_5);

                if (targetSeries == null || targetSeries.isEmpty()) {
                    System.out.println("⚠️ ERROR: No hay datos suficientes para " + targetTicker + ". Saltando al siguiente...");
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

                        java.time.format.DateTimeFormatter dtf = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

                        // 👉 FIX: Convertimos la hora de Nueva York a España antes de imprimirla
                        String entryTimeStr = targetSeries.getBar(entryIndex).getEndTime().withZoneSameInstant(spainZone).format(dtf);
                        String exitTimeStr = targetSeries.getBar(exitIndex).getEndTime().withZoneSameInstant(spainZone).format(dtf);

                        // 👉 FIX: Agregamos el nombre de la estrategia en cada línea
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
                System.out.printf("   🎯 Win Rate Global      : %.2f%%\n", ((double)globalAciertos / globalTrades) * 100.0);
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

    private static boolean loadLocalDataToCache(String ticker, TimeFrame timeFrame, DataManager dataManager) {
        String suffix;
        switch(timeFrame) {
            case MIN_5: suffix = "5min"; break;
            case MIN_15: suffix = "15min"; break;
            case HOUR_1: suffix = "1hour"; break;
            case DAY_1: suffix = "1day"; break;
            default: suffix = timeFrame.name(); break;
        }

        String cacheKey = ticker + "_" + suffix;
        File file = new File("data/" + cacheKey + ".csv");

        if (!file.exists()) {
            System.out.println("⚠️ ALERTA: No se encontró el archivo CSV local -> " + file.getAbsolutePath());
            return false;
        }

        BarSeries series = new BaseBarSeriesBuilder().withName(cacheKey).build();

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line = br.readLine(); // Saltar la cabecera
            while ((line = br.readLine()) != null) {
                String[] values = line.split(",");
                if (values.length < 6) continue;

                // 👉 FIX: Parseamos como LocalDateTime primero, y luego le asignamos la zona local
                java.time.LocalDateTime localDateTime = java.time.LocalDateTime.parse(values[0], MarketTimeUtils.CSV_FORMATTER);
                ZonedDateTime dateTime = localDateTime.atZone(java.time.ZoneId.systemDefault());

                double open = Double.parseDouble(values[1]);
                double high = Double.parseDouble(values[2]);
                double low = Double.parseDouble(values[3]);
                double close = Double.parseDouble(values[4]);
                double volume = Double.parseDouble(values[5]);

                series.addBar(dateTime, open, high, low, close, volume);
            }

            dataManager.putSeries(ticker, timeFrame, series);
            System.out.println("💾 Loaded " + series.getBarCount() + " bars from local CSV for " + cacheKey);
            return true;

        } catch (Exception e) {
            System.out.println("❌ Error crítico parseando " + cacheKey + ": " + e.getMessage());
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
                ZonedDateTime nyTime = currentTime.withZoneSameInstant(java.time.ZoneId.of("America/New_York"));
                ZonedDateTime nyEntryTime = entryTime.withZoneSameInstant(java.time.ZoneId.of("America/New_York"));

                // 👉 FIX: Llamamos al nuevo RiskCalculator pasándole el DataManager para que extraiga la data macro de 1H
                com.fgiaquinta.optionsquant.models.TradePlan plan = com.fgiaquinta.optionsquant.utils.RiskCalculator.generatePlan(dataManager, ticker, entryTime, isCall, entryPrice);

                // 👉 NUEVA REGLA: Cierra si es otro día, o si son las 15:50 (NY Time)
                if (nyTime.getDayOfYear() != nyEntryTime.getDayOfYear() ||
                        (nyTime.getHour() == 15 && nyTime.getMinute() >= 50) ||
                        nyTime.getHour() >= 16) {
                    return true;
                }

                // Salida por Precio (Stop Loss y Take Profit dinámicos amplios)
                double high = targetSeries.getBar(index).getHighPrice().doubleValue();
                double low = targetSeries.getBar(index).getLowPrice().doubleValue();

                if (isCall) {
                    if (high >= plan.takeProfit) return true;
                    if (low <= plan.stopLoss) return true;
                } else {
                    if (low <= plan.takeProfit) return true;
                    if (high >= plan.stopLoss) return true;
                }

                return false;
            }
        };

        return new BaseStrategy(customStrategy.getName(), entryRule, dynamicExitRule);
    }
}