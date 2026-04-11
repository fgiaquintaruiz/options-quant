package com.fgiaquinta.optionsquant.indicators;

import org.ta4j.core.indicators.CachedIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.Num;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class WordenStochasticIndicator extends CachedIndicator<Num> {
    private final ClosePriceIndicator closePrice;
    private final int period;

    public WordenStochasticIndicator(ClosePriceIndicator closePrice, int period) {
        super(closePrice);
        this.closePrice = closePrice;
        this.period = period;
    }

    @Override
    protected Num calculate(int index) {
        // Handle initial bars where the period is not yet reached
        if (index < period - 1) {
            return getBarSeries().numOf(50);
        }

        double currentClose = closePrice.getValue(index).doubleValue();
        List<Double> historicalCloses = new ArrayList<>();

        // 1. Collect all close prices within the specified lookback period
        for (int i = index - period + 1; i <= index; i++) {
            historicalCloses.add(closePrice.getValue(i).doubleValue());
        }

        // 2. Sort the prices to determine the Percentile Rank
        Collections.sort(historicalCloses);

        // Find the 0-based position (Rank) of the current close in the sorted list
        int rank = historicalCloses.indexOf(currentClose);

        // 3. Apply the Worden formula: (100 / (n - 1)) * Rank
        // This calculates the relative position of the price as a percentage (0-100)
        double result = (100.0 / (period - 1)) * rank;

        return getBarSeries().numOf(result);
    }
}