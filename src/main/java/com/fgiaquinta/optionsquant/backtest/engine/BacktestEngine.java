package com.fgiaquinta.optionsquant.backtest.engine;

import com.fgiaquinta.optionsquant.backtest.domain.*;
import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.service.CandleCsvService;
import com.fgiaquinta.optionsquant.strategy.TradingStrategy;
import com.fgiaquinta.optionsquant.strategy.data.StrategyData;
import com.fgiaquinta.optionsquant.strategy.model.TradePlan;
import com.fgiaquinta.optionsquant.strategy.utils.RiskCalculator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Core backtest engine.
 *
 * Iterates through historical candles on the execution timeframe.
 * At each candle, builds StrategyData, runs all strategies,
 * opens trades on signals, manages TP/SL exits.
 *
 * Follows SRP: orchestrates only. Fills → FillEngine, reporting → BacktestReporter.
 */
@Slf4j
@Service
public class BacktestEngine {

    private static final ZoneId NY = ZoneId.of("America/New_York");
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final List<TradingStrategy> strategies;
    private final CandleCsvService csvService;

    public BacktestEngine(CandleCsvService csvService) {
        this.csvService = csvService;
        this.strategies = List.of(
                new com.fgiaquinta.optionsquant.strategy.C1SqueezeCallStrategy(),
                new com.fgiaquinta.optionsquant.strategy.C2TrendCallStrategy(),
                new com.fgiaquinta.optionsquant.strategy.C3BounceCallStrategy(),
                new com.fgiaquinta.optionsquant.strategy.C4OpeningCallStrategy(),
                new com.fgiaquinta.optionsquant.strategy.C5ContinuationCallStrategy(),
                new com.fgiaquinta.optionsquant.strategy.C6ReversalCallStrategy(),
                new com.fgiaquinta.optionsquant.strategy.P1SqueezePutStrategy(),
                new com.fgiaquinta.optionsquant.strategy.P2TrendPutStrategy(),
                new com.fgiaquinta.optionsquant.strategy.P3BouncePutStrategy(),
                new com.fgiaquinta.optionsquant.strategy.P4OpeningPutStrategy(),
                new com.fgiaquinta.optionsquant.strategy.P5ContinuationPutStrategy(),
                new com.fgiaquinta.optionsquant.strategy.P6ReversalPutStrategy()
        );
    }

    /**
     * Runs a backtest with the given configuration.
     */
    public BacktestReport run(BacktestConfig config) {
        log.info(">>> Backtest: tickers={}, {} to {}, capital=${}, risk={}%",
                config.tickers().size(), config.fromDate(), config.toDate(),
                config.initialCapital(), config.riskPerTradePct() * 100);

        long startTime = System.currentTimeMillis();

        FillEngine fillEngine = new SimulatedFillEngine(config.slippagePct(), config.commissionPerContract());
        CsvBacktestReporter reporter = new CsvBacktestReporter(Path.of("backtest"));

        double equity = config.initialCapital();
        reporter.onStart(ZonedDateTime.now(NY), equity);

        // Load all data for all tickers
        Map<String, Map<TimeFrame, List<Candle>>> allData = new LinkedHashMap<>();
        for (String ticker : config.tickers()) {
            Map<TimeFrame, List<Candle>> tickerData = new EnumMap<>(TimeFrame.class);
            for (TimeFrame tf : TimeFrame.values()) {
                List<Candle> candles = csvService.loadFromCsv(ticker, tf);
                List<Candle> filtered = candles.stream()
                        .filter(c -> !c.timestamp().toLocalDate().isBefore(config.fromDate())
                                && !c.timestamp().toLocalDate().isAfter(config.toDate()))
                        .toList();
                tickerData.put(tf, filtered);
            }
            allData.put(ticker, tickerData);
        }

        TimeFrame execTf = config.executionTimeframe();

        // Build sorted list of execution candles across all tickers
        List<CandleEvent> events = new ArrayList<>();
        for (String ticker : config.tickers()) {
            List<Candle> execCandles = allData.get(ticker).get(execTf);
            if (execCandles != null) {
                for (Candle c : execCandles) {
                    events.add(new CandleEvent(ticker, c));
                }
            }
        }
        events.sort(Comparator.comparing(e -> e.candle().timestamp()));

        log.info("Loaded {} candle events across {} tickers", events.size(), config.tickers().size());

        Map<String, List<OpenPosition>> openPositions = new LinkedHashMap<>();
        for (String ticker : config.tickers()) {
            openPositions.put(ticker, new ArrayList<>());
        }

        double peakEquity = equity;

        for (CandleEvent event : events) {
            String ticker = event.ticker();
            Candle currentCandle = event.candle();
            ZonedDateTime candleTime = currentCandle.timestamp();

            // Build StrategyData with all candles up to this point
            Map<TimeFrame, List<Candle>> dataUpToNow = buildDataUpTo(allData, ticker, candleTime);
            if (dataUpToNow.size() < 4) continue;

            StrategyData data = new StrategyData(dataUpToNow);

            // Check exits for open positions
            checkAndCloseExpositions(ticker, currentCandle, candleTime, openPositions, fillEngine, reporter, equity);

            // Update equity with unrealized
            equity = calculateEquity(equity, openPositions, currentCandle);
            if (equity > peakEquity) peakEquity = equity;
            reporter.onEquityUpdate(candleTime, equity);

            // Run strategies if we have room
            int totalOpen = openPositions.values().stream().mapToInt(List::size).sum();
            if (totalOpen < config.maxConcurrentTrades()) {
                ZonedDateTime nyTime = candleTime.withZoneSameInstant(NY);
                runStrategies(ticker, data, nyTime, currentCandle, candleTime, config, equity, openPositions, fillEngine, reporter);
            }
        }

        // Close remaining positions at last candle
        closeRemainingPositions(openPositions, allData, execTf, fillEngine, reporter);

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("<<< Backtest complete: equity={}, elapsed={}ms", equity, elapsed);
        return reporter.onFinish(elapsed);
    }

    private Map<TimeFrame, List<Candle>> buildDataUpTo(
            Map<String, Map<TimeFrame, List<Candle>>> allData, String ticker, ZonedDateTime candleTime) {
        Map<TimeFrame, List<Candle>> result = new EnumMap<>(TimeFrame.class);
        Map<TimeFrame, List<Candle>> tickerData = allData.get(ticker);
        if (tickerData == null) return result;

        for (Map.Entry<TimeFrame, List<Candle>> entry : tickerData.entrySet()) {
            List<Candle> upToNow = entry.getValue().stream()
                    .filter(c -> !c.timestamp().isAfter(candleTime))
                    .toList();
            if (!upToNow.isEmpty()) {
                result.put(entry.getKey(), upToNow);
            }
        }
        return result;
    }

    private void checkAndCloseExpositions(String ticker, Candle candle, ZonedDateTime time,
            Map<String, List<OpenPosition>> openPositions, FillEngine fillEngine,
            CsvBacktestReporter reporter, double equity) {
        List<OpenPosition> positions = openPositions.get(ticker);
        if (positions == null) return;

        Iterator<OpenPosition> it = positions.iterator();
        while (it.hasNext()) {
            OpenPosition pos = it.next();
            boolean tpHit = pos.isCall ? candle.high() >= pos.tp : candle.low() <= pos.tp;
            boolean slHit = pos.isCall ? candle.low() <= pos.sl : candle.high() >= pos.sl;

            if (tpHit || slHit) {
                double exitPrice = tpHit ? pos.tp : pos.sl;
                String exitReason = tpHit ? "TP" : "SL";
                closePosition(ticker, pos, exitPrice, time, exitReason, fillEngine, reporter);
                it.remove();
                continue; // skip runup/drawdown tracking for closed positions
            }

            // Track max runup/drawdown
            if (pos.isCall) {
                pos.maxRunup = Math.max(pos.maxRunup, (candle.high() - pos.entryPrice) * pos.quantity * 100);
                pos.maxDrawdown = Math.max(pos.maxDrawdown, (pos.entryPrice - candle.low()) * pos.quantity * 100);
            } else {
                pos.maxRunup = Math.max(pos.maxRunup, (pos.entryPrice - candle.low()) * pos.quantity * 100);
                pos.maxDrawdown = Math.max(pos.maxDrawdown, (candle.high() - pos.entryPrice) * pos.quantity * 100);
            }
        }
    }

    private double calculateEquity(double cash, Map<String, List<OpenPosition>> openPositions, Candle candle) {
        double unrealized = 0;
        for (List<OpenPosition> positions : openPositions.values()) {
            for (OpenPosition pos : positions) {
                if (pos.isCall) {
                    unrealized += (candle.close() - pos.entryPrice) * pos.quantity * 100;
                } else {
                    unrealized += (pos.entryPrice - candle.close()) * pos.quantity * 100;
                }
            }
        }
        return cash + unrealized;
    }

    private void runStrategies(String ticker, StrategyData data, ZonedDateTime nyTime,
            Candle candle, ZonedDateTime time, BacktestConfig config, double equity,
            Map<String, List<OpenPosition>> openPositions, FillEngine fillEngine, CsvBacktestReporter reporter) {
        for (TradingStrategy strategy : strategies) {
            try {
                if (!strategy.isTriggered(ticker, data, nyTime)) continue;

                boolean isCall = strategy.getName().contains("call");
                double entryPrice = candle.close();

                // Use strategy name for per-strategy ATR tuning
                TradePlan plan = RiskCalculator.generatePlan(data, ticker, time, isCall, entryPrice, strategy.getName());
                double riskPerContract = Math.abs(entryPrice - plan.stopLoss) * 100;
                double maxRisk = equity * config.riskPerTradePct();
                
                // CRITICAL FIX: Cap at 10 contracts max to prevent position sizing bugs
                int qty = riskPerContract > 0 ? (int) Math.floor(maxRisk / riskPerContract) : 0;
                qty = Math.max(1, Math.min(qty, 10)); // FIXED: Was 100, now 10

                FillResult entryFill = fillEngine.fillEntry(ticker, isCall ? "CALL" : "PUT", qty, plan, time);

                OpenPosition pos = new OpenPosition(
                        strategy.getName(), isCall ? "CALL" : "PUT", qty,
                        entryFill.fillPrice(), plan.takeProfit, plan.stopLoss, time);
                openPositions.get(ticker).add(pos);

                log.debug("Signal: {} {} at {} entry={} qty={}", ticker, pos.direction,
                        time.format(TS_FMT), entryFill.fillPrice(), qty);
            } catch (Exception e) {
                log.debug("Strategy {} error for {}: {}", strategy.getName(), ticker, e.getMessage());
            }
        }
    }

    private void closePosition(String ticker, OpenPosition pos, double exitPrice,
            ZonedDateTime exitTime, String reason, FillEngine fillEngine,
            CsvBacktestReporter reporter) {
        FillResult exitFill = fillEngine.fillExit(ticker, pos.direction, pos.quantity, exitPrice, exitTime);
        double grossPnl = pos.isCall
                ? (exitPrice - pos.entryPrice) * pos.quantity * 100
                : (pos.entryPrice - exitPrice) * pos.quantity * 100;
        double netPnl = grossPnl - exitFill.commission() - exitFill.slippage() * pos.quantity * 100;

        reporter.onTrade(new TradeRecord(
                ticker, pos.strategy, pos.direction, pos.quantity,
                pos.entryPrice, pos.entryTime, exitPrice, exitTime, reason,
                grossPnl, exitFill.commission(), exitFill.slippage(),
                netPnl, pos.maxDrawdown, pos.maxRunup));
    }

    private void closeRemainingPositions(Map<String, List<OpenPosition>> openPositions,
            Map<String, Map<TimeFrame, List<Candle>>> allData, TimeFrame execTf,
            FillEngine fillEngine, CsvBacktestReporter reporter) {
        for (Map.Entry<String, List<OpenPosition>> entry : openPositions.entrySet()) {
            String ticker = entry.getKey();
            List<Candle> lastCandles = allData.get(ticker).get(execTf);
            if (lastCandles == null || lastCandles.isEmpty()) continue;
            Candle last = lastCandles.get(lastCandles.size() - 1);

            for (OpenPosition pos : new ArrayList<>(entry.getValue())) {
                closePosition(ticker, pos, last.close(), last.timestamp(), "EOS", fillEngine, reporter);
            }
            entry.getValue().clear();
        }
    }

    private record CandleEvent(String ticker, Candle candle) {}

    private static class OpenPosition {
        final String strategy;
        final String direction;
        final int quantity;
        final double entryPrice;
        final double tp;
        final double sl;
        final ZonedDateTime entryTime;
        double maxDrawdown = 0;
        double maxRunup = 0;
        final boolean isCall;

        OpenPosition(String strategy, String direction, int quantity, double entryPrice,
                     double tp, double sl, ZonedDateTime entryTime) {
            this.strategy = strategy;
            this.direction = direction;
            this.quantity = quantity;
            this.entryPrice = entryPrice;
            this.tp = tp;
            this.sl = sl;
            this.entryTime = entryTime;
            this.isCall = direction.equalsIgnoreCase("CALL");
        }
    }
}
