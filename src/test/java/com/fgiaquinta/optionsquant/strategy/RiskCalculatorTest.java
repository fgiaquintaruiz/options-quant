package com.fgiaquinta.optionsquant.strategy;

import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import com.fgiaquinta.optionsquant.strategy.data.StrategyData;
import com.fgiaquinta.optionsquant.strategy.model.TradePlan;
import com.fgiaquinta.optionsquant.strategy.utils.RiskCalculator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RiskCalculatorTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    private StrategyData createTestData() {
        // Create minimal 1H data with enough bars for ATR(14)
        List<Candle> hourCandles = new ArrayList<>();
        ZonedDateTime base = ZonedDateTime.of(2026, 4, 9, 9, 30, 0, 0, NY);
        double basePrice = 500.0;
        for (int i = 0; i < 30; i++) {
            double open = basePrice + (Math.random() - 0.5) * 2;
            double close = open + (Math.random() - 0.5) * 2;
            double high = Math.max(open, close) + Math.random();
            double low = Math.min(open, close) - Math.random();
            hourCandles.add(new Candle(base.plusHours(i), open, high, low, close, 1000000));
        }

        Map<TimeFrame, List<Candle>> data = new EnumMap<>(TimeFrame.class);
        data.put(TimeFrame.HOUR_1, hourCandles);
        // Add minimal other timeframes
        List<Candle> filler = List.of(new Candle(base, 500, 501, 499, 500.5, 1000000));
        data.put(TimeFrame.MIN_5, filler);
        data.put(TimeFrame.MIN_15, filler);
        data.put(TimeFrame.DAY_1, filler);

        return new StrategyData(data);
    }

    @Test
    @DisplayName("generatePlan should create a valid trade plan for CALL")
    void generateCallPlan() {
        StrategyData data = createTestData();
        ZonedDateTime entryTime = ZonedDateTime.of(2026, 4, 9, 15, 30, 0, 0, NY);

        TradePlan plan = RiskCalculator.generatePlan(data, "SPY", entryTime, true, 500.0);

        assertThat(plan.entryPrice).isEqualTo(500.0);
        assertThat(plan.isCall).isTrue();
        assertThat(plan.takeProfit).isGreaterThan(plan.entryPrice);
        assertThat(plan.stopLoss).isLessThan(plan.entryPrice);
        assertThat(plan.atr).isGreaterThan(0);
    }

    @Test
    @DisplayName("generatePlan should create a valid trade plan for PUT")
    void generatePutPlan() {
        StrategyData data = createTestData();
        ZonedDateTime entryTime = ZonedDateTime.of(2026, 4, 9, 15, 30, 0, 0, NY);

        TradePlan plan = RiskCalculator.generatePlan(data, "SPY", entryTime, false, 500.0);

        assertThat(plan.entryPrice).isEqualTo(500.0);
        assertThat(plan.isCall).isFalse();
        assertThat(plan.takeProfit).isLessThan(plan.entryPrice);
        assertThat(plan.stopLoss).isGreaterThan(plan.entryPrice);
    }

    @Test
    @DisplayName("generatePlan should cap TP distance at 0.9% of entry")
    void tpCap() {
        // Use very volatile data to trigger the cap
        List<Candle> volatileHour = new ArrayList<>();
        ZonedDateTime base = ZonedDateTime.of(2026, 4, 9, 9, 30, 0, 0, NY);
        for (int i = 0; i < 30; i++) {
            double swing = 10.0 + Math.random() * 5; // huge swings
            double open = 500.0 + swing;
            double close = 500.0 - swing;
            volatileHour.add(new Candle(base.plusHours(i), open, open + 2, close - 2, close, 1000000));
        }

        Map<TimeFrame, List<Candle>> data = new EnumMap<>(TimeFrame.class);
        data.put(TimeFrame.HOUR_1, volatileHour);
        List<Candle> filler = List.of(new Candle(base, 500, 501, 499, 500.5, 1000000));
        data.put(TimeFrame.MIN_5, filler);
        data.put(TimeFrame.MIN_15, filler);
        data.put(TimeFrame.DAY_1, filler);

        StrategyData sd = new StrategyData(data);
        TradePlan plan = RiskCalculator.generatePlan(sd, "SPY", base, true, 500.0);

        // TP should be capped at 0.9% = 4.5
        assertThat(plan.takeProfit - plan.entryPrice).isLessThanOrEqualTo(4.55);
    }
}
