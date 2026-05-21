package com.fgiaquinta.optionsquant.options;

/**
 * Abstraction over the IBKR market-data API for option greeks + bid/ask.
 *
 * <p>Isolates {@link OptionChainRecorderService} from the IBKR TWS wire protocol,
 * making the recorder fully testable without a running TWS session.
 *
 * <p>The production implementation uses {@code reqMktData} with tick type {@code "100"}
 * and waits for {@code tickOptionComputation(reqId, 13, ...)} to deliver greeks.
 */
public interface OptionChainIbkrGateway {

    /**
     * Fetches a single option contract snapshot synchronously.
     *
     * <p>Blocks until the IBKR callback fires or the timeout elapses (5 s default).
     *
     * @param ticker  underlying symbol (e.g. {@code "NVDA"})
     * @param strike  option strike price
     * @param expiry  expiry in {@code YYYYMMDD} format
     * @param right   {@code "C"} for call, {@code "P"} for put
     * @return snapshot with bid/ask and greeks; {@code null} if the callback did not fire
     *         within the timeout
     */
    OptionSnapshot fetchOptionSnapshot(String ticker, double strike, String expiry, String right);

    /**
     * One option contract data point returned by the IBKR market-data subscription.
     *
     * <p>All fields are nullable — IBKR may omit greeks for illiquid contracts.
     */
    record OptionSnapshot(
            Double bid,
            Double ask,
            Double underlyingPrice,
            Double iv,
            Double delta,
            Double gamma,
            Double theta,
            Double vega,
            Double optPrice
    ) {}
}
