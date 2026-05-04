package com.fgiaquinta.optionsquant.candle.sqlite;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Base class for SQLite-backed tests.
 *
 * <p>Sets up an in-memory SQLite database with the candle schema before each test,
 * and tears it down (closes the pool) after each test.
 *
 * <p>Uses a single connection pool (pool size = 1) which is sufficient for all
 * write+read scenarios in tests. The same DataSource is shared for reads and writes.
 */
public abstract class SqliteTestBase {

    protected DataSource ds;
    protected JdbcTemplate jdbc;

    @BeforeEach
    void setupDb() throws Exception {
        HikariConfig cfg = new HikariConfig();
        cfg.setDriverClassName("org.sqlite.JDBC");
        cfg.setJdbcUrl("jdbc:sqlite::memory:");
        cfg.setMaximumPoolSize(1);
        cfg.setConnectionInitSql("PRAGMA busy_timeout=5000;");
        ds = new HikariDataSource(cfg);
        jdbc = new JdbcTemplate(ds);
        SchemaInitializer.forTesting(jdbc).afterPropertiesSet();
    }

    @AfterEach
    void tearDown() {
        if (ds instanceof HikariDataSource hikari) {
            hikari.close();
        }
    }
}
