package com.fgiaquinta.optionsquant.trading;

import com.ib.client.Decimal;
import com.ib.client.Order;
import com.ib.client.TagValue;

import java.util.ArrayList;
import java.util.List;

/**
 * Creates IBKR bracket orders for options (entry + TP + SL).
 */
public class OrderFactory {

    private OrderFactory() {}

    /**
     * Creates a 3-leg bracket order for options.
     *
     * @param pId Parent order ID (entry)
     * @param tpId Take profit order ID
     * @param slId Stop loss order ID
     * @param qty Number of contracts
     * @param underlyingConId The conId of the underlying stock
     * @param primaryExch The exchange of the underlying stock (for price triggers)
     * @param entry Entry price (for reference)
     * @param tp Take profit price level (triggers when underlying hits this)
     * @param sl Stop loss price level (triggers when underlying hits this)
     * @param isCall True for CALL contracts, false for PUT
     * @return List of 3 orders: [entry, takeProfit, stopLoss]
     */
    public static List<Order> createOptionBracket(int pId, int tpId, int slId, int qty,
                                                   int underlyingConId, String primaryExch,
                                                   double entry, double tp, double sl,
                                                   boolean isCall) {
        List<Order> orders = new ArrayList<>();
        String oca = "OCA_" + pId;

        // 1. Parent Order: Entry (transmit=false, sends as group)
        Order parent = new Order();
        parent.orderId(pId);
        parent.action("BUY");
        parent.orderType("MKT");
        parent.totalQuantity(Decimal.get(qty));
        parent.transmit(false);
        parent.algoStrategy("Adaptive");
        parent.algoParams(new ArrayList<>());
        parent.algoParams().add(new TagValue("adaptivePriority", "Normal"));

        // 2. Take Profit (transmit=false)
        Order takeProfit = createExitOrder(tpId, pId, qty, oca, underlyingConId, primaryExch, tp, isCall);

        // 3. Stop Loss (transmit=true to send the whole group)
        Order stopLoss = createExitOrder(slId, pId, qty, oca, underlyingConId, primaryExch, sl, !isCall);
        stopLoss.transmit(true);

        orders.add(parent);
        orders.add(takeProfit);
        orders.add(stopLoss);
        return orders;
    }

    /**
     * Creates an exit order (TP or SL) with price condition + golden rule time guard.
     *
     * @param id Order ID
     * @param parentId Parent order ID
     * @param qty Number of contracts
     * @param oca OCA group name
     * @param conId Underlying stock conId (for price trigger)
     * @param primaryExch Underlying stock exchange
     * @param price Trigger price on the underlying
     * @param isMore True for TP on calls / SL on puts (price must be >= trigger)
     *               False for SL on calls / TP on puts (price must be <= trigger)
     */
    private static Order createExitOrder(int id, int parentId, int qty, String oca,
                                          int conId, String primaryExch, double price, boolean isMore) {
        Order o = new Order();
        o.orderId(id);
        o.parentId(parentId);
        o.action("SELL");
        o.orderType("MKT");
        o.totalQuantity(Decimal.get(qty));
        o.ocaGroup(oca);
        o.ocaType(1); // First cancellation: if TP or SL fires, cancel the other
        o.transmit(false);

        // Price trigger: when underlying hits the price level
        o.conditions().add(ConditionBuilder.createPriceCondition(conId, primaryExch, isMore, price));
        // Golden Rule: don't execute after 21:55 ET (no overnight options)
        o.conditions().add(ConditionBuilder.createGoldenRuleCondition("21:55:00"));

        return o;
    }

    /**
     * Creates a simple market order.
     *
     * @param orderId The order ID
     * @param action "BUY" or "SELL"
     * @param qty Number of contracts
     * @return A market order
     */
    public static Order createMarketOrder(int orderId, String action, int qty) {
        Order o = new Order();
        o.orderId(orderId);
        o.action(action);
        o.orderType("MKT");
        o.totalQuantity(Decimal.get(qty));
        return o;
    }
}
