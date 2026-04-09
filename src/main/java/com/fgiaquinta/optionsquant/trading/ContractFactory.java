package com.fgiaquinta.optionsquant.trading;

import com.ib.client.Contract;

/**
 * Creates IBKR contracts for stocks and options.
 */
public class ContractFactory {

    private ContractFactory() {}

    /**
     * Creates an option contract.
     * @param ticker Stock symbol (e.g., "AAPL")
     * @param expiration Expiration date in YYYYMMDD format
     * @param strike Strike price
     * @param right "C" for Call, "P" for Put
     */
    public static Contract createOptionContract(String ticker, String expiration, double strike, String right) {
        Contract contract = new Contract();
        String sanitizedTicker = ticker.replaceAll("[^a-zA-Z]", "").toUpperCase().trim();

        contract.symbol(sanitizedTicker);
        contract.secType("OPT");
        contract.currency("USD");
        contract.exchange("SMART");
        contract.multiplier("100");
        contract.lastTradeDateOrContractMonth(expiration.trim());
        contract.strike(strike);
        contract.right(right.toUpperCase().startsWith("C") ? "C" : "P");

        return contract;
    }

    /**
     * Creates a stock contract for underlying data requests.
     */
    public static Contract createStockContract(String ticker) {
        if (ticker == null || ticker.trim().isEmpty()) {
            throw new IllegalArgumentException("Ticker cannot be null or empty");
        }

        Contract contract = new Contract();
        String cleanTicker = ticker.replaceAll("[^a-zA-Z]", "").toUpperCase().trim();

        contract.symbol(cleanTicker);
        contract.secType("STK");
        contract.currency("USD");
        contract.exchange("SMART");

        // Special cases for NYSE-listed stocks
        if (cleanTicker.equals("SQ") || cleanTicker.equals("NVO") || cleanTicker.equals("TSM")) {
            contract.primaryExch("NYSE");
        } else {
            contract.primaryExch("ISLAND");
        }

        return contract;
    }
}
