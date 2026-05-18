package com.fgiaquinta.optionsquant.strategy.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

@Repository
public class StrategyConfigRepository {

    private final JdbcTemplate readJdbc;
    private final JdbcTemplate writeJdbc;

    public StrategyConfigRepository(
            @Qualifier("candlesReadDs") DataSource readDs,
            @Qualifier("candlesWriteDs") DataSource writeDs) {
        this.readJdbc = new JdbcTemplate(readDs);
        this.writeJdbc = new JdbcTemplate(writeDs);
    }

    private static final RowMapper<StrategyConfig> MAPPER = (rs, rowNum) -> StrategyConfig.builder()
            .strategyName(rs.getString("strategy_name"))
            .enabledBacktest(rs.getInt("enabled_backtest") == 1)
            .enabledLive(rs.getInt("enabled_live") == 1)
            .notes(rs.getString("notes"))
            .updatedAt(rs.getLong("updated_at"))
            .updatedBy(rs.getString("updated_by"))
            .build();

    public List<StrategyConfig> findAll() {
        return readJdbc.query("SELECT * FROM strategy_config ORDER BY strategy_name", MAPPER);
    }

    public List<String> findActiveLive() {
        return readJdbc.queryForList(
                "SELECT strategy_name FROM strategy_config WHERE enabled_live=1 ORDER BY strategy_name",
                String.class);
    }

    public List<String> findActiveBacktest() {
        return readJdbc.queryForList(
                "SELECT strategy_name FROM strategy_config WHERE enabled_backtest=1 ORDER BY strategy_name",
                String.class);
    }

    public Optional<StrategyConfig> findByName(String name) {
        List<StrategyConfig> rows = readJdbc.query(
                "SELECT * FROM strategy_config WHERE strategy_name=?", MAPPER, name);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public int update(String name, boolean enabledBacktest, boolean enabledLive, String notes, String updatedBy) {
        return writeJdbc.update(
                "UPDATE strategy_config SET enabled_backtest=?, enabled_live=?, notes=?, updated_at=strftime('%s','now'), updated_by=? WHERE strategy_name=?",
                enabledBacktest ? 1 : 0, enabledLive ? 1 : 0, notes, updatedBy, name);
    }
}
