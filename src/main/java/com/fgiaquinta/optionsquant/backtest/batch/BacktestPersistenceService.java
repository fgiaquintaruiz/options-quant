package com.fgiaquinta.optionsquant.backtest.batch;

import com.fgiaquinta.optionsquant.backtest.domain.TradeRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Persistence service for backtest runs.
 *
 * <p>Writes to {@code backtest_trades} and {@code backtest_progress} tables in candles.db.
 * All writes for a single ticker are performed in one atomic JDBC transaction via
 * {@link TransactionTemplate}.
 *
 * <p>Uses the {@code candlesWriteDs} DataSource (pool size = 1, WAL mode).
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "candles.store", havingValue = "sqlite", matchIfMissing = true)
public class BacktestPersistenceService {

    private static final String CREATE_TRADES_TABLE = """
            CREATE TABLE IF NOT EXISTS backtest_trades (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                run_id TEXT NOT NULL,
                ticker TEXT NOT NULL,
                strategy TEXT NOT NULL,
                timeframe TEXT NOT NULL,
                signal_date TEXT NOT NULL,
                signal_type TEXT NOT NULL,
                entry_price REAL NOT NULL,
                exit_price REAL,
                pnl REAL,
                win INTEGER,
                pattern TEXT,
                created_at INTEGER NOT NULL
            )
            """;

    private static final String CREATE_PROGRESS_TABLE = """
            CREATE TABLE IF NOT EXISTS backtest_progress (
                run_id TEXT NOT NULL,
                ticker TEXT NOT NULL,
                status TEXT NOT NULL,
                trade_count INTEGER DEFAULT 0,
                pnl REAL DEFAULT 0,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (run_id, ticker)
            )
            """;

    private static final String INSERT_TRADE = """
            INSERT INTO backtest_trades
                (run_id, ticker, strategy, timeframe, signal_date, signal_type,
                 entry_price, exit_price, pnl, win, pattern, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String UPSERT_PROGRESS = """
            INSERT OR REPLACE INTO backtest_progress
                (run_id, ticker, status, trade_count, pnl, updated_at)
            VALUES (?, ?, 'COMPLETE', ?, ?, ?)
            """;

    private static final String FIND_INCOMPLETE_RUN = """
            SELECT bp.run_id
            FROM backtest_progress bp
            WHERE EXISTS (
                SELECT 1 FROM backtest_trades bt WHERE bt.run_id = bp.run_id
            )
            GROUP BY bp.run_id
            HAVING COUNT(CASE WHEN bp.status = 'COMPLETE' THEN 1 END) < ?
            ORDER BY MAX(bp.updated_at) DESC
            LIMIT 1
            """;

    private static final String GET_COMPLETED_TICKERS = """
            SELECT ticker
            FROM backtest_progress
            WHERE run_id = ? AND status = 'COMPLETE'
            """;

    private final JdbcTemplate writeJdbc;
    private final TransactionTemplate transactionTemplate;

    /**
     * Production constructor — wired by Spring with the write DataSource.
     */
    public BacktestPersistenceService(
            @Qualifier("candlesWriteDs") DataSource writeDs) {
        this.writeJdbc = new JdbcTemplate(writeDs);
        DataSourceTransactionManager txManager = new DataSourceTransactionManager(writeDs);
        this.transactionTemplate = new TransactionTemplate(txManager);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Creates the backtest tables if they don't exist yet.
     * Idempotent (uses CREATE TABLE IF NOT EXISTS).
     */
    public void initSchema() {
        writeJdbc.execute(CREATE_TRADES_TABLE);
        writeJdbc.execute(CREATE_PROGRESS_TABLE);
        log.debug("[backtest-persistence] Schema initialized (tables: backtest_trades, backtest_progress)");
    }

    /**
     * Finds the most recent incomplete run — i.e. a run where fewer than
     * {@code expectedTickerCount} tickers have COMPLETE status.
     *
     * @param expectedTickerCount total number of tickers for this batch
     * @return the run_id if an incomplete run exists, empty otherwise
     */
    public Optional<String> findIncompleteRun(int expectedTickerCount) {
        try {
            List<String> results = writeJdbc.queryForList(
                    FIND_INCOMPLETE_RUN, String.class, expectedTickerCount);
            return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
        } catch (Exception e) {
            log.warn("[backtest-persistence] Could not query for incomplete runs: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Returns the set of tickers with COMPLETE status for the given run.
     *
     * @param runId the run identifier
     * @return set of completed ticker symbols (may be empty)
     */
    public Set<String> getCompletedTickers(String runId) {
        try {
            List<String> tickers = writeJdbc.queryForList(GET_COMPLETED_TICKERS, String.class, runId);
            return new HashSet<>(tickers);
        } catch (Exception e) {
            log.warn("[backtest-persistence] Could not query completed tickers for run {}: {}", runId, e.getMessage());
            return Set.of();
        }
    }

    /**
     * Counts and then deletes all rows from backtest_trades and backtest_progress,
     * then runs VACUUM to reclaim disk space.
     *
     * <p>Row counts are queried BEFORE the deletes so the log line reflects real data.
     * If a table doesn't exist the operation is skipped for that table (logs a warning).
     * VACUUM failure is non-fatal — logged as a warning and execution continues.
     *
     * @return a {@link DeleteResult} with the row counts deleted from each table
     * @throws RuntimeException if either DELETE fails (caller should abort the backtest)
     */
    public DeleteResult deleteAllData() {
        int trades = 0;
        int progress = 0;

        try {
            Integer t = writeJdbc.queryForObject("SELECT COUNT(*) FROM backtest_trades", Integer.class);
            trades = t != null ? t : 0;
        } catch (Exception e) {
            log.warn("[backtest-persistence] backtest_trades not found, skipping delete");
            return new DeleteResult(0, 0);
        }

        try {
            Integer p = writeJdbc.queryForObject("SELECT COUNT(*) FROM backtest_progress", Integer.class);
            progress = p != null ? p : 0;
        } catch (Exception e) {
            log.warn("[backtest-persistence] backtest_progress not found, skipping delete");
        }

        writeJdbc.execute("DELETE FROM backtest_trades");
        writeJdbc.execute("DELETE FROM backtest_progress");

        try {
            writeJdbc.execute("VACUUM");
            log.info("[backtest] VACUUM completed");
        } catch (Exception e) {
            log.warn("[backtest] VACUUM failed: {} — continuing", e.getMessage());
        }

        return new DeleteResult(trades, progress);
    }

    /**
     * Row counts from a {@link #deleteAllData()} operation.
     *
     * @param trades   rows deleted from backtest_trades
     * @param progress rows deleted from backtest_progress
     */
    public record DeleteResult(int trades, int progress) {}

    /**
     * Persists all trades for one ticker + marks its progress as COMPLETE — in a single transaction.
     *
     * <p>If the transaction fails, no partial data is committed.
     *
     * @param runId     the run identifier
     * @param ticker    the ticker symbol
     * @param timeframe the execution timeframe (e.g. "MIN_15")
     * @param trades    all trades generated for this ticker in this run
     */
    public void persistTickerResult(String runId, String ticker, String timeframe, List<TradeRecord> trades) {
        try {
            transactionTemplate.execute(status -> {
                long now = Instant.now().getEpochSecond();
                double totalPnl = 0.0;

                for (TradeRecord trade : trades) {
                    String signalDate = trade.entryTime() != null
                            ? trade.entryTime().toInstant().toString()
                            : "";
                    int win = trade.netPnl() > 0 ? 1 : 0;
                    totalPnl += trade.netPnl();

                    writeJdbc.update(INSERT_TRADE,
                            runId,
                            trade.ticker(),
                            trade.strategy(),
                            timeframe,
                            signalDate,
                            trade.direction(),
                            trade.entryPrice(),
                            trade.exitPrice(),
                            trade.netPnl(),
                            win,
                            trade.candlestickPattern(),
                            now
                    );
                }

                writeJdbc.update(UPSERT_PROGRESS,
                        runId, ticker, trades.size(), totalPnl, now);

                return null;
            });
        } catch (Exception e) {
            log.error("[backtest-persistence] Failed to persist ticker {} for run {}: {}",
                    ticker, runId, e.getMessage(), e);
        }
    }
}
