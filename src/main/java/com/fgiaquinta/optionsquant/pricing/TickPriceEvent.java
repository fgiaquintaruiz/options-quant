package com.fgiaquinta.optionsquant.pricing;

/**
 * IBKR tickPrice callback event. Mirrors the {@code EWrapper#tickPrice} signature
 * minus the {@code TickAttrib} argument (callers don't need attribs for last-price work).
 *
 * @param reqId request id that the tick refers to
 * @param field IBKR tick type ({@code 4} = LAST trade price)
 * @param price price reported by TWS; can be {@code -1.0} when no quote available
 */
public record TickPriceEvent(int reqId, int field, double price) {
}
