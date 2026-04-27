package com.fgiaquinta.optionsquant.trading;

import com.ib.client.OrderCondition;
import com.ib.client.OrderConditionType;
import com.ib.client.PriceCondition;
import com.ib.client.TimeCondition;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Creates IBKR order conditions for price triggers and time guards.
 */
public class ConditionBuilder {

    private ConditionBuilder() {}

    /**
     * Creates a price condition based on the underlying stock.
     * @param underlyingConId The conId of the STOCK contract (not the option)
     * @param exchange The exchange of the stock (e.g., "ISLAND", "SMART")
     * @param isMore True if price must be >= trigger, False if price must be <= trigger
     * @param triggerPrice The price level to trigger the condition
     */
    public static PriceCondition createPriceCondition(int underlyingConId, String exchange, boolean isMore, double triggerPrice) {
        PriceCondition condition = (PriceCondition) OrderCondition.create(OrderConditionType.Price);
        condition.conId(underlyingConId);
        condition.exchange(exchange);
        condition.isMore(isMore);
        condition.price(triggerPrice);
        return condition;
    }

    /**
     * Creates the "Golden Rule" time condition: don't execute after 15:50 ET.
     * This gives a 10-minute margin before US equity options close at 16:00 ET,
     * preventing market-sell rejections that would leave positions open overnight.
     */
    public static TimeCondition createGoldenRuleCondition(String timeHms) {
        TimeCondition tc = (TimeCondition) OrderCondition.create(OrderConditionType.Time);
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        tc.time(today + " " + timeHms + " US/Eastern");
        tc.isMore(true);
        tc.conjunctionConnection(false); // OR logic with other conditions
        return tc;
    }

    /**
     * Creates a TimeCondition that fires at exactly 14:50 ET on today's date.
     * Intended for scheduling an end-of-day close for external (non-app-tracked) positions,
     * giving a 10-minute margin before the typical 15:00 ET liquidity drop.
     *
     * <p><strong>CRITICAL — isMore(false) semantics require paper-test verification (Phase 6.6).</strong>
     * IBKR's TWS documentation is ambiguous: {@code isMore(false)} may mean "fire when time IS BEFORE
     * 14:50" rather than "fire AT 14:50". If the conditional order fires too early or not at all during
     * paper trading, flip to {@code isMore(true)} and re-test. Do NOT change this without a paper test.
     *
     * @return A {@link TimeCondition} with time="yyyyMMdd 14:50:00 US/Eastern", isMore=false
     */
    public static TimeCondition createClose1450Condition() {
        TimeCondition tc = (TimeCondition) OrderCondition.create(OrderConditionType.Time);
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        tc.time(today + " 14:50:00 US/Eastern");
        tc.isMore(false);
        return tc;
    }
}
