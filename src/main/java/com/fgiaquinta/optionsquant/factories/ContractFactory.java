package com.fgiaquinta.optionsquant.factories;

import com.ib.client.Contract;

public class ContractFactory {
    private ContractFactory() {
        /* This utility class should not be instantiated */
    }

    public static Contract createOptionContract(String ticker, String expiration, double strike, String right) {
        Contract contract = new Contract();
        contract.symbol(ticker);
        contract.secType("OPT");
        contract.currency("USD");
        contract.exchange("SMART");
        contract.multiplier("100");
        contract.lastTradeDateOrContractMonth(expiration);
        contract.strike(strike);
        contract.right(right);
        return contract;
    }

    public static Contract createStockDefinition(String ticker) {
        Contract contract = new Contract();
        contract.symbol(ticker);
        contract.secType("STK");
        contract.exchange("SMART");
        contract.currency("USD");
        return contract;
    }
}