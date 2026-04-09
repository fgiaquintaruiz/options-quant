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
     * Creates the "Golden Rule" time condition: don't execute after 21:55 ET.
     * This prevents holding options overnight.
     */
    public static TimeCondition createGoldenRuleCondition(String timeHms) {
        TimeCondition tc = (TimeCondition) OrderCondition.create(OrderConditionType.Time);
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        tc.time(today + " " + timeHms + " US/Eastern");
        tc.isMore(true);
        tc.conjunctionConnection(false); // OR logic with other conditions
        return tc;
    }
}
