package com.fgiaquinta.optionsquant.candle.sqlite;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * One-off prod runner for {@link ProgressBackfillReconciler}.
 *
 * <p>Wires HikariCP directly to {@code data/candles.db} (file-backed, NOT in-memory)
 * and invokes {@code reconcileWithStats()} once. Used for FASE 5 of DEL4 — recalc +
 * overwrite pass against the production database without booting Spring.
 *
 * <p>Gated by the system property {@code reconcileProd=true} so it never runs as
 * part of the normal CI test suite. Run with:
 * <pre>{@code
 * ./gradlew test --tests "*ReconcileProdRunner*" -DreconcileProd=true
 * }</pre>
 */
class ReconcileProdRunner {

    @Test
    @EnabledIfSystemProperty(named = "reconcileProd", matches = "true")
    void runReconcilerAgainstProd() throws Exception {
        Path db = Paths.get("data", "candles.db").toAbsolutePath();
        if (!Files.exists(db)) {
            throw new IllegalStateException("data/candles.db not found at " + db);
        }

        HikariConfig cfg = new HikariConfig();
        cfg.setDriverClassName("org.sqlite.JDBC");
        cfg.setJdbcUrl("jdbc:sqlite:" + db);
        cfg.setMaximumPoolSize(1);
        cfg.setConnectionInitSql("PRAGMA busy_timeout=30000; PRAGMA journal_mode=WAL;");

        try (HikariDataSource ds = new HikariDataSource(cfg)) {
            ProgressBackfillReconciler reconciler = new ProgressBackfillReconciler(ds);
            ProgressBackfillReconciler.ReconcileResult result = reconciler.reconcileWithStats();
            System.out.printf("[ReconcileProdRunner] inserted=%d updated=%d preserved=%d%n",
                    result.inserted(), result.updated(), result.preserved());
        }
    }
}
