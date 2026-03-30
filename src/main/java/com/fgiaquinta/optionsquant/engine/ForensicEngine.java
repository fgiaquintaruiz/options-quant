package com.fgiaquinta.optionsquant.engine;

import com.fgiaquinta.optionsquant.services.IbkrService;
import com.fgiaquinta.optionsquant.strategies.TradingStrategy;
import com.fgiaquinta.optionsquant.utils.ForensicLogger;
import com.fgiaquinta.optionsquant.models.TimeFrame;
import org.ta4j.core.BarSeries;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import java.util.List;

public class ForensicEngine {
    private final IbkrService ibkrService;
    private final List<TradingStrategy> strategies;

    public ForensicEngine(IbkrService ibkrService, List<TradingStrategy> strategies) {
        this.ibkrService = ibkrService;
        this.strategies = strategies;
    }

    public void runFullAudit(String ticker, BarSeries series1h) {
        // Updated to use the Multi-Timeframe signature
        BarSeries series15m = ibkrService.getSeries(ticker, TimeFrame.MIN_15);
        BarSeries spySeries = ibkrService.getSeries("SPY", TimeFrame.HOUR_1);

        if (series15m == null || spySeries == null) return;

        Long2IntMap index15m = new Long2IntOpenHashMap(series15m.getBarCount());
        for (int j = 0; j < series15m.getBarCount(); j++) {
            index15m.put(series15m.getBar(j).getEndTime().toEpochSecond(), j);
        }

        for (int i = 200; i < series1h.getBarCount(); i++) {
            for (TradingStrategy strategy : strategies) {
                if (strategy.isTriggered(i, series1h, spySeries)) {
                    double entryPrice = series1h.getBar(i).getClosePrice().doubleValue();

                    double tp = strategy.calculateTP(entryPrice);
                    double sl = strategy.calculateSL(entryPrice, ticker);

                    ForensicLogger.log(ticker, series1h, i, strategy.getClass().getSimpleName(), tp, sl);
                }
            }
        }
    }
}