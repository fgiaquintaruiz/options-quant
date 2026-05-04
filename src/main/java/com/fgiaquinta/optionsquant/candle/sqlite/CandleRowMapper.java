package com.fgiaquinta.optionsquant.candle.sqlite;

import com.fgiaquinta.optionsquant.domain.Candle;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Maps a JDBC ResultSet row from the {@code candles} table to a {@link Candle} record.
 *
 * <p>Column contract:
 * <ul>
 *   <li>{@code ts_epoch} — UTC seconds since Unix epoch (INTEGER)</li>
 *   <li>{@code open}, {@code high}, {@code low}, {@code close} — REAL</li>
 *   <li>{@code volume} — INTEGER</li>
 * </ul>
 */
public class CandleRowMapper implements RowMapper<Candle> {

    @Override
    public Candle mapRow(ResultSet rs, int rowNum) throws SQLException {
        long tsEpoch = rs.getLong("ts_epoch");
        ZonedDateTime timestamp = Instant.ofEpochSecond(tsEpoch).atZone(ZoneOffset.UTC);

        double open  = rs.getDouble("open");
        double high  = rs.getDouble("high");
        double low   = rs.getDouble("low");
        double close = rs.getDouble("close");
        long   volume = rs.getLong("volume");

        return new Candle(timestamp, open, high, low, close, volume);
    }
}
