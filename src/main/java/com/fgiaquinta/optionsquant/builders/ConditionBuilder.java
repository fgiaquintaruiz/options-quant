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

    /**
     * Crea una condición de precio basada en la acción subyacente.
     * @param underlyingConId El ID del contrato de la ACCIÓN (ej. el conId de MSFT).
     * @param exchange El exchange de la acción (usualmente "SMART").
     * @param isMore True si el precio debe ser MAYOR O IGUAL al trigger. False si debe ser MENOR O IGUAL.
     * @param triggerPrice El precio objetivo (tu TP o SL calculado).
     */
    public static PriceCondition createPriceCondition(int underlyingConId, String exchange, boolean isMore, double triggerPrice) {
        PriceCondition condition = (PriceCondition) OrderCondition.create(OrderConditionType.Price);
        condition.conId(underlyingConId);
        condition.exchange(exchange);
        condition.isMore(isMore);
        condition.price(triggerPrice);
        // condition.isDefault(true); // Opcional: Para usar la sesión regular de trading
        return condition;
    }

    public static TimeCondition createGoldenRuleCondition(String timeHms) {
        TimeCondition tc = (TimeCondition) OrderCondition.create(OrderConditionType.Time);
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        // CORRECCIÓN CRÍTICA: IBKR exige un ESPACIO entre la fecha y la hora, y la zona horaria.
        tc.time(today + " " + timeHms + " US/Eastern");

        tc.isMore(true);
        tc.conjunctionConnection(false); // Lógica OR
        return tc;
    }
}