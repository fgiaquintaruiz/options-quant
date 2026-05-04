package com.fgiaquinta.optionsquant.candle.sqlite;

import com.fgiaquinta.optionsquant.domain.Candle;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for CandleRowMapper.
 * Uses a mocked ResultSet to verify epoch → ZonedDateTime and OHLCV mapping.
 */
class CandleRowMapperTest {

    private final CandleRowMapper mapper = new CandleRowMapper();

    @Test
    void mapRow_knownEpoch_returnsCorrectZonedDateTimeUTC() throws SQLException {
        // 1514901000L = 2018-01-02T13:50:00Z  (verified: Instant.ofEpochSecond(1514901000))
        ResultSet rs = mockRs(1514901000L, 1.0, 2.0, 0.5, 1.5, 100L);

        Candle candle = mapper.mapRow(rs, 1);

        ZonedDateTime expected = ZonedDateTime.of(2018, 1, 2, 13, 50, 0, 0, ZoneOffset.UTC);
        assertEquals(expected, candle.timestamp(), "Epoch should map to 2018-01-02T13:50:00Z");
        assertEquals(ZoneOffset.UTC, candle.timestamp().getZone(), "Zone must be UTC");
    }

    @Test
    void mapRow_zeroEpoch_returnsUnixEpochUTC() throws SQLException {
        ResultSet rs = mockRs(0L, 1.0, 2.0, 0.5, 1.5, 100L);

        Candle candle = mapper.mapRow(rs, 1);

        ZonedDateTime expected = ZonedDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        assertEquals(expected, candle.timestamp(), "Zero epoch should map to 1970-01-01T00:00:00Z");
    }

    @Test
    void mapRow_ohlcvMappedCorrectly() throws SQLException {
        ResultSet rs = mockRs(1514901000L, 100.25, 105.75, 99.50, 103.00, 500_000L);

        Candle candle = mapper.mapRow(rs, 1);

        assertEquals(100.25, candle.open(),   0.0001, "open must match");
        assertEquals(105.75, candle.high(),   0.0001, "high must match");
        assertEquals(99.50,  candle.low(),    0.0001, "low must match");
        assertEquals(103.00, candle.close(),  0.0001, "close must match");
        assertEquals(500_000L, candle.volume(), "volume must match");
    }

    @Test
    void mapRow_largeEpoch_mapsCorrectly() throws SQLException {
        // 2030-06-15T12:00:00Z = 1908676800
        long epoch = 1908676800L;
        ResultSet rs = mockRs(epoch, 50.0, 55.0, 48.0, 52.0, 999L);

        Candle candle = mapper.mapRow(rs, 1);

        assertEquals(epoch, candle.timestamp().toEpochSecond(), "Round-trip epoch must match");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static ResultSet mockRs(long tsEpoch, double open, double high,
                                    double low, double close, long volume)
            throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("ts_epoch")).thenReturn(tsEpoch);
        when(rs.getDouble("open")).thenReturn(open);
        when(rs.getDouble("high")).thenReturn(high);
        when(rs.getDouble("low")).thenReturn(low);
        when(rs.getDouble("close")).thenReturn(close);
        when(rs.getLong("volume")).thenReturn(volume);
        return rs;
    }
}
