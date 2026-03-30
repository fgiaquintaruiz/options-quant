package com.fgiaquinta.optionsquant.builders;

import com.ib.client.OrderCondition;
import com.ib.client.OrderConditionType;
import com.ib.client.PriceCondition;
import com.ib.client.TimeCondition;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

public class ConditionBuilder {

    private ConditionBuilder() {
        // This utility class should not be instantiated
    }

    public static PriceCondition createPriceCondition(int conId, String exchange, double price, boolean isMore) {
        PriceCondition p = (PriceCondition) OrderCondition.create(OrderConditionType.Price);
        p.conId(conId);
        // Use the primary exchange to avoid IBKR 398 error. If null or SMART, leave empty.
        p.exchange((exchange == null || exchange.equalsIgnoreCase("SMART")) ? "" : exchange);
        p.isMore(isMore);
        p.price(Math.round(price * 100.0) / 100.0);
        p.triggerMethod(2); // Last Price
        return p;
    }

    public static TimeCondition createGoldenRuleCondition(String timeHms) {
        TimeCondition tc = (TimeCondition) OrderCondition.create(OrderConditionType.Time);
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        tc.time(today + "-" + timeHms);
        tc.isMore(true);
        tc.conjunctionConnection(false); // OR logic
        return tc;
    }
}