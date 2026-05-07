package com.fgiaquinta.optionsquant.candle.sqlite;

import com.fgiaquinta.optionsquant.domain.TickerInfo;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Maps a {@code tickers} result-set row to a {@link TickerInfo} record.
 *
 * <p>CRITICAL: {@code rs.wasNull()} MUST be called immediately after each
 * {@code rs.getDouble()} / {@code rs.getLong()} — any intervening call resets the flag.
 */
public class TickerRowMapper implements RowMapper<TickerInfo> {

    @Override
    public TickerInfo mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new TickerInfo(
                rs.getString("ticker"),
                rs.getString("company_name"),
                rs.getString("sector"),
                nullableLong(rs, "market_cap_billion"),
                nullableDouble(rs, "pe_ratio"),
                nullableDouble(rs, "dividend_yield"),
                nullableDouble(rs, "beta"),
                nullableDouble(rs, "eps_growth"),
                nullableDouble(rs, "revenue_growth"),
                nullableDouble(rs, "debt_to_equity"),
                nullableDouble(rs, "roic"),
                rs.getString("notes")
        );
    }

    private static Double nullableDouble(ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }

    private static Long nullableLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }
}
