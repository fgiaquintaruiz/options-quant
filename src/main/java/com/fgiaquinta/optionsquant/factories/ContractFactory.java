package com.fgiaquinta.optionsquant.factories;

import com.ib.client.Contract;

public class ContractFactory {
    private ContractFactory() {
        /* This utility class should not be instantiated */
    }

    public static com.ib.client.Contract createOptionContract(String ticker, String expiration, double strike, String right) {
        com.ib.client.Contract contract = new com.ib.client.Contract();

        // 👉 1. CLEAN THE TICKER: IBKR will reject " AAPL" or "ARKK]"
        String sanitizedTicker = ticker.replaceAll("[^a-zA-Z]", "").toUpperCase().trim();

        contract.symbol(sanitizedTicker);
        contract.secType("OPT");
        contract.currency("USD");
        contract.exchange("SMART"); // SMART is usually best for routing
        contract.multiplier("100");  // Standard for US Options

        // 👉 2. FORMATTING: Ensure expiration is a clean string (YYYYMMDD)
        contract.lastTradeDateOrContractMonth(expiration.trim());

        // 👉 3. THE STRIKE: This must match a real strike from tickerToValidStrikes
        contract.strike(strike);

        // 👉 4. THE RIGHT: Must be "CALL"/"PUT" or "C"/"P"
        contract.right(right.toUpperCase().startsWith("C") ? "C" : "P");

        return contract;
    }

    public static com.ib.client.Contract createStockDefinition(String ticker) {
        if (ticker == null || ticker.trim().isEmpty()) {
            throw new IllegalArgumentException("Ticker cannot be null or empty");
        }

        com.ib.client.Contract contract = new com.ib.client.Contract();

        // 👉 Extreme Clean: Remove everything except A-Z
        String cleanTicker = ticker.replaceAll("[^a-zA-Z]", "").toUpperCase().trim();

        contract.symbol(cleanTicker);
        contract.secType("STK");
        contract.currency("USD");
        contract.exchange("SMART");

        if (cleanTicker.equals("SQ") || cleanTicker.equals("NVO") || cleanTicker.equals("TSM")) {
            contract.primaryExch("NYSE");
        } else {
            contract.primaryExch("ISLAND");
        }

        return contract;
    }
}