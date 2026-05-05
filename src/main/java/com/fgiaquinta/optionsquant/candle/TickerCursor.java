package com.fgiaquinta.optionsquant.candle;

import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;

import java.util.Iterator;
import java.util.stream.Stream;

/**
 * Wraps a lazy Stream<Candle> for a single ticker+timeframe.
 *
 * Designed for use in a PriorityQueue-based merge of multiple ticker streams:
 * cursors are ordered by the timestamp of the next (peeked) candle.
 * Exhausted cursors sort after all live cursors.
 */
public final class TickerCursor implements AutoCloseable, Comparable<TickerCursor> {

    private final String ticker;
    private final TimeFrame timeframe;
    private final Stream<Candle> stream;
    private final Iterator<Candle> iterator;

    private Candle peeked;

    public TickerCursor(String ticker, TimeFrame timeframe, Stream<Candle> stream) {
        this.ticker = ticker;
        this.timeframe = timeframe;
        this.stream = stream;
        this.iterator = stream.iterator();
        this.peeked = iterator.hasNext() ? iterator.next() : null;
    }

    /** Returns the next candle without consuming it, or null if exhausted. */
    public Candle peek() {
        return peeked;
    }

    /** Consumes and returns the next candle, then pre-fetches the one after it. */
    public Candle next() {
        Candle current = peeked;
        peeked = iterator.hasNext() ? iterator.next() : null;
        return current;
    }

    /** Returns true when no more candles are available. */
    public boolean isExhausted() {
        return peeked == null;
    }

    public String ticker() {
        return ticker;
    }

    public TimeFrame timeframe() {
        return timeframe;
    }

    /**
     * Orders by next candle timestamp ascending.
     * Exhausted cursors (no next candle) sort after all live cursors.
     */
    @Override
    public int compareTo(TickerCursor other) {
        if (this.peeked == null && other.peeked == null) return 0;
        if (this.peeked == null) return 1;
        if (other.peeked == null) return -1;
        return this.peeked.timestamp().compareTo(other.peeked.timestamp());
    }

    @Override
    public void close() {
        stream.close();
    }
}
