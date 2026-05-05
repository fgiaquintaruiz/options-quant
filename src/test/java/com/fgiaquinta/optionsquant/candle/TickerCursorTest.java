package com.fgiaquinta.optionsquant.candle;

import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T19 — TickerCursor wraps a Stream<Candle> from CandleRepository.stream()
 * and supports PriorityQueue ordering by next candle timestamp.
 */
class TickerCursorTest {

    private static final ZonedDateTime T0 = ZonedDateTime.of(2024, 1, 2, 9, 30, 0, 0, ZoneOffset.UTC);

    private static Candle candle(ZonedDateTime ts) {
        return new Candle(ts, 100.0, 105.0, 99.0, 103.0, 1000L);
    }

    @Test
    @DisplayName("T19: cursor advances through candles in stream order")
    void cursor_advancesThroughCandles() {
        ZonedDateTime t1 = T0;
        ZonedDateTime t2 = T0.plusMinutes(5);
        ZonedDateTime t3 = T0.plusMinutes(10);
        Stream<Candle> stream = Stream.of(candle(t1), candle(t2), candle(t3));

        TickerCursor cursor = new TickerCursor("SPY", TimeFrame.MIN_5, stream);

        assertThat(cursor.peek()).isNotNull();
        assertThat(cursor.peek().timestamp()).isEqualTo(t1);

        Candle first = cursor.next();
        assertThat(first.timestamp()).isEqualTo(t1);

        assertThat(cursor.peek().timestamp()).isEqualTo(t2);

        Candle second = cursor.next();
        assertThat(second.timestamp()).isEqualTo(t2);

        Candle third = cursor.next();
        assertThat(third.timestamp()).isEqualTo(t3);

        assertThat(cursor.isExhausted()).isTrue();
        assertThat(cursor.peek()).isNull();
    }

    @Test
    @DisplayName("T19: compareTo orders cursors by next candle timestamp ascending")
    void cursor_compareToOrdersByTimestamp() {
        ZonedDateTime earlier = T0;
        ZonedDateTime later = T0.plusMinutes(15);

        TickerCursor a = new TickerCursor("AAPL", TimeFrame.MIN_5, Stream.of(candle(earlier)));
        TickerCursor b = new TickerCursor("MSFT", TimeFrame.MIN_5, Stream.of(candle(later)));

        assertThat(a.compareTo(b)).isNegative();
        assertThat(b.compareTo(a)).isPositive();
        assertThat(a.compareTo(a)).isZero();
    }

    @Test
    @DisplayName("T19: exhausted cursor sorts after non-exhausted cursor")
    void cursor_exhausted_sortsLast() {
        TickerCursor live = new TickerCursor("SPY", TimeFrame.MIN_5, Stream.of(candle(T0)));
        TickerCursor exhausted = new TickerCursor("QQQ", TimeFrame.MIN_5, Stream.empty());

        assertThat(exhausted.isExhausted()).isTrue();
        assertThat(live.compareTo(exhausted)).isNegative();
        assertThat(exhausted.compareTo(live)).isPositive();
    }

    @Test
    @DisplayName("T19: two exhausted cursors compare equal")
    void cursor_twoExhausted_compareEqual() {
        TickerCursor a = new TickerCursor("SPY", TimeFrame.MIN_5, Stream.empty());
        TickerCursor b = new TickerCursor("QQQ", TimeFrame.MIN_5, Stream.empty());

        assertThat(a.compareTo(b)).isZero();
        assertThat(b.compareTo(a)).isZero();
    }

    @Test
    @DisplayName("T19: close() closes the underlying stream")
    @SuppressWarnings("unchecked")
    void cursor_close_closesUnderlyingStream() throws Exception {
        Stream<Candle> mockStream = mock(Stream.class);
        when(mockStream.iterator()).thenReturn(List.<Candle>of().iterator());

        TickerCursor cursor = new TickerCursor("SPY", TimeFrame.MIN_5, mockStream);
        cursor.close();

        verify(mockStream).close();
    }

    @Test
    @DisplayName("T19: cursor is usable in try-with-resources without exception")
    void cursor_tryWithResources_noException() {
        Stream<Candle> stream = Stream.of(candle(T0), candle(T0.plusMinutes(5)));

        try (TickerCursor cursor = new TickerCursor("SPY", TimeFrame.MIN_5, stream)) {
            assertThat(cursor.next()).isNotNull();
            assertThat(cursor.next()).isNotNull();
            assertThat(cursor.isExhausted()).isTrue();
        }
    }
}
