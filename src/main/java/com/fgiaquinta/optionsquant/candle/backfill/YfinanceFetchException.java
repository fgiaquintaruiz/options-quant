package com.fgiaquinta.optionsquant.candle.backfill;

public class YfinanceFetchException extends RuntimeException {

    public YfinanceFetchException(String message) {
        super(message);
    }

    public YfinanceFetchException(String message, Throwable cause) {
        super(message, cause);
    }
}
