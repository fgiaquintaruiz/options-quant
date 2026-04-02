package com.fgiaquinta.optionsquant.factories;

import com.ib.client.Decimal;
import com.ib.client.Order;
import com.fgiaquinta.optionsquant.builders.ConditionBuilder;
import java.util.ArrayList;
import java.util.List;

public class OrderFactory {

    private OrderFactory() {
        // This utility class should not be instantiated
    }

    public static List<Order> createOptionBracket(int pId, int tpId, int slId, int qty,
                                                  int subConId, String primaryExch, double entry, double tp, double sl,
                                                  boolean isCall) {
        List<Order> orders = new ArrayList<>();
        String oca = "OCA_" + pId;

        // 1. Parent Order: Entry (transmit false)
        Order parent = new Order();
        parent.orderId(pId);
        parent.action("BUY");
        parent.orderType("LMT");
        parent.lmtPrice(entry);
        parent.totalQuantity(Decimal.get(qty));
        parent.transmit(false);
        // 👉 CORRECCIÓN ERROR 135: Hemos eliminado parent.conditions().add(...)
        // Las órdenes MKT de opciones no deben llevar condiciones de precio en la entrada.

        // 2. Take Profit (transmit false)
        Order takeProfit = createExitOrder(tpId, pId, qty, oca, subConId, primaryExch, tp, isCall);

        // 3. Stop Loss (transmit true to send the whole group)
        Order stopLoss = createExitOrder(slId, pId, qty, oca, subConId, primaryExch, sl, !isCall);
        stopLoss.transmit(true);

        orders.add(parent);
        orders.add(takeProfit);
        orders.add(stopLoss);
        return orders;
    }

    private static Order createExitOrder(int id, int parentId, int qty, String oca, int conId, String primaryExch, double price, boolean isMore) {
        Order o = new Order();
        o.orderId(id);
        o.parentId(parentId);
        o.action("SELL");
        o.orderType("MKT");
        o.totalQuantity(Decimal.get(qty));
        o.ocaGroup(oca);
        o.ocaType(1);
        o.transmit(false);

        // Price trigger and Golden Rule (21:55)
        o.conditions().add(ConditionBuilder.createPriceCondition(conId, primaryExch, price, isMore));
        o.conditions().add(ConditionBuilder.createGoldenRuleCondition("21:55:00"));

        return o;
    }
}