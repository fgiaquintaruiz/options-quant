package com.fgiaquinta.optionsquant.candle.sqlite;

import com.fgiaquinta.optionsquant.candle.RepositoryException;
import com.fgiaquinta.optionsquant.domain.Candle;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Spliterators;
import java.util.function.Consumer;

/**
 * A lazy {@link java.util.Spliterator} that reads {@link Candle} objects from an open
 * JDBC {@link ResultSet} on demand.
 *
 * <p>Callers are responsible for closing the ResultSet (and its owning Connection /
 * PreparedStatement) via the stream's {@code onClose()} handler. This spliterator does
 * NOT close any JDBC resources itself.
 *
 * <p>Characteristics: {@code ORDERED | IMMUTABLE | NONNULL}.
 */
public class CandleResultSetSpliterator extends Spliterators.AbstractSpliterator<Candle> {

    private final ResultSet rs;
    private final CandleRowMapper mapper;
    private int rowNum = 0;

    public CandleResultSetSpliterator(ResultSet rs, CandleRowMapper mapper) {
        super(Long.MAX_VALUE, ORDERED | IMMUTABLE | NONNULL);
        this.rs = rs;
        this.mapper = mapper;
    }

    /**
     * Advances to the next row and calls {@code action} with the mapped candle.
     *
     * @return {@code true} if a row was read; {@code false} when the ResultSet is exhausted.
     * @throws RepositoryException if a {@link SQLException} is thrown while advancing.
     */
    @Override
    public boolean tryAdvance(Consumer<? super Candle> action) {
        try {
            if (!rs.next()) {
                return false;
            }
            action.accept(mapper.mapRow(rs, ++rowNum));
            return true;
        } catch (SQLException e) {
            throw new RepositoryException("Error reading candle from ResultSet", e);
        }
    }
}
