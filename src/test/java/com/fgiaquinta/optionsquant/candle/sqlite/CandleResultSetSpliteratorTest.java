package com.fgiaquinta.optionsquant.candle.sqlite;

import com.fgiaquinta.optionsquant.domain.Candle;
import com.fgiaquinta.optionsquant.domain.TimeFrame;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CandleResultSetSpliterator using a real in-memory SQLite database.
 */
class CandleResultSetSpliteratorTest extends SqliteTestBase {

    private static final String TICKER = "AAPL";
    private static final TimeFrame TF = TimeFrame.MIN_5;

    private static final ZonedDateTime T0 = ZonedDateTime.of(2024, 1, 2, 9, 30, 0, 0, ZoneOffset.UTC);

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    void emptyResultSet_streamHasZeroElements() throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(selectSql());
             ResultSet rs = executeQuery(ps)) {

            Stream<Candle> stream = toStream(rs);
            List<Candle> result = stream.toList();

            assertTrue(result.isEmpty(), "Empty table should yield empty stream");
        }
    }

    @Test
    void threeCandles_streamYieldsAllThreeInOrder() throws Exception {
        insertCandles(T0, T0.plusMinutes(5), T0.plusMinutes(10));

        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(selectSql());
             ResultSet rs = executeQuery(ps)) {

            List<Candle> result = toStream(rs).toList();

            assertEquals(3, result.size(), "Should yield exactly 3 candles");
            assertEquals(T0,                  result.get(0).timestamp(), "First candle at T0");
            assertEquals(T0.plusMinutes(5),  result.get(1).timestamp(), "Second candle at T0+5m");
            assertEquals(T0.plusMinutes(10), result.get(2).timestamp(), "Third candle at T0+10m");
        }
    }

    @Test
    void streamIsLazy_resultSetNotExhaustedUntilConsumed() throws Exception {
        insertCandles(T0, T0.plusMinutes(5), T0.plusMinutes(10));

        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(selectSql());
             ResultSet rs = executeQuery(ps)) {

            CandleRowMapper rowMapper = new CandleRowMapper();
            CandleResultSetSpliterator spliterator =
                    new CandleResultSetSpliterator(rs, rowMapper);

            // Do NOT consume yet — ResultSet should not be exhausted
            assertFalse(rs.isAfterLast(), "ResultSet should not be exhausted before consumption");

            // Consume one element
            spliterator.tryAdvance(c -> {});
            // Still more elements available
            assertFalse(rs.isAfterLast(), "ResultSet should not be fully exhausted after first element");
        }
    }

    @Test
    void streamOnClose_resourceCleanupIsCalled() throws Exception {
        insertCandles(T0);

        AtomicBoolean closeCalled = new AtomicBoolean(false);

        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(selectSql());
             ResultSet rs = executeQuery(ps)) {

            CandleRowMapper rowMapper = new CandleRowMapper();
            Stream<Candle> stream = StreamSupport
                    .stream(new CandleResultSetSpliterator(rs, rowMapper), false)
                    .onClose(() -> closeCalled.set(true));

            stream.close();
        }

        assertTrue(closeCalled.get(), "onClose handler must be called when stream is closed");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void insertCandles(ZonedDateTime... timestamps) {
        String sql = """
            INSERT INTO candles(ticker,timeframe,ts_epoch,open,high,low,close,volume)
            VALUES(?,?,?,?,?,?,?,?)
            """;
        for (ZonedDateTime ts : timestamps) {
            jdbc.update(sql, TICKER, TF.name(), ts.toEpochSecond(),
                    100.0, 105.0, 99.0, 103.0, 1000L);
        }
    }

    private String selectSql() {
        return "SELECT ts_epoch,open,high,low,close,volume FROM candles " +
               "WHERE ticker=? AND timeframe=? ORDER BY ts_epoch ASC";
    }

    private ResultSet executeQuery(PreparedStatement ps) throws Exception {
        ps.setString(1, TICKER);
        ps.setString(2, TF.name());
        return ps.executeQuery();
    }

    private Stream<Candle> toStream(ResultSet rs) {
        return StreamSupport.stream(
                new CandleResultSetSpliterator(rs, new CandleRowMapper()), false);
    }
}
