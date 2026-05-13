package com.fgiaquinta.optionsquant.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import javax.sql.DataSource;

/**
 * Spring configuration for candle-store DataSource beans backed by SQLite + HikariCP.
 *
 * <p>Two pools are created:
 * <ul>
 *   <li>{@code candlesWriteDs} — single-connection writer with WAL pragmas applied on init.</li>
 *   <li>{@code candlesReadDs} — up-to-8-connection read-only pool with shared cache.</li>
 * </ul>
 *
 * Both beans are gated on {@code candles.store=sqlite} (default enabled).
 */
@Configuration
public class CandlesDataSourceConfig {

    // -------------------------------------------------------------------------
    // Write pool
    // -------------------------------------------------------------------------

    @Bean("candlesWriteDs")
    @Primary
    @ConditionalOnProperty(name = "candles.store", havingValue = "sqlite", matchIfMissing = true)
    public DataSource candlesWriteDataSource(
            @Value("${candles.sqlite.path:data/candles.db}") String dbPath) {

        HikariConfig cfg = new HikariConfig();
        cfg.setDriverClassName("org.sqlite.JDBC");
        // busy_timeout in the URL is applied by the JDBC driver at connection-open time,
        // BEFORE any PRAGMA runs. This prevents SQLITE_BUSY during concurrent pool init
        // (e.g. read pool WAL switch racing with write pool DDL on startup).
        cfg.setJdbcUrl("jdbc:sqlite:" + dbPath + "?busy_timeout=30000");
        cfg.setMaximumPoolSize(1);
        cfg.setMinimumIdle(1);
        cfg.setPoolName("candles-write");
        // Also apply via PRAGMA so the timeout is set on every warm connection from the pool
        cfg.setConnectionInitSql(
            "PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL; PRAGMA busy_timeout=30000;");

        return new HikariDataSource(cfg);
    }

    // -------------------------------------------------------------------------
    // Read pool
    // -------------------------------------------------------------------------

    @Bean("candlesReadDs")
    @ConditionalOnProperty(name = "candles.store", havingValue = "sqlite", matchIfMissing = true)
    public DataSource candlesReadDataSource(
            @Value("${candles.sqlite.path:data/candles.db}") String dbPath) {

        // NOTE: SQLite JDBC (3.x) rejects Connection.setReadOnly() after connection creation.
        // HikariCP's setReadOnly(true) triggers this call and will fail. Instead, we use a
        // larger pool for read-heavy workloads without enforcing JDBC-level read-only.
        // In WAL mode, concurrent readers don't block writers; read-only discipline
        // is enforced at the SQL level (only SELECT queries on this pool).
        //
        // Pool sizing rationale for --backtest-all:
        //   BacktestEngine opens one JDBC connection per TimeFrame (4) per worker thread.
        //   default-max-concurrent-scans = 4 workers → 4 workers × 4 TimeFrames = 16
        //   simultaneous open connections in the worst case. Pool = 16 + 4 buffer = 20.
        //   Previous value of 8 caused exhaustion: active=8, waiting=3 under full load.
        HikariConfig cfg = new HikariConfig();
        cfg.setDriverClassName("org.sqlite.JDBC");
        // busy_timeout in the URL is applied by the JDBC driver at connection-open time,
        // BEFORE any PRAGMA runs. This prevents SQLITE_BUSY during concurrent pool init.
        cfg.setJdbcUrl("jdbc:sqlite:" + dbPath + "?busy_timeout=30000");
        cfg.setMaximumPoolSize(20);
        cfg.setMinimumIdle(1);
        cfg.setPoolName("candles-read");
        cfg.setConnectionInitSql("PRAGMA journal_mode=WAL; PRAGMA busy_timeout=30000;");

        return new HikariDataSource(cfg);
    }
}
