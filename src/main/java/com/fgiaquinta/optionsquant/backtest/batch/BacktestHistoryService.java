package com.fgiaquinta.optionsquant.backtest.batch;

import com.fgiaquinta.optionsquant.backtest.dto.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only query service for historical backtest runs.
 *
 * <p>All queries execute against the {@code candlesReadDs} DataSource (read pool, WAL mode).
 * No INSERT/UPDATE/DELETE operations are performed here.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "candles.store", havingValue = "sqlite", matchIfMissing = true)
public class BacktestHistoryService {

    // -------------------------------------------------------------------------
    // SQL queries
    // -------------------------------------------------------------------------

    private static final String LIST_RUNS = """
            SELECT p.run_id,
                   p.total_tickers,
                   p.completed_tickers,
                   COALESCE(t.total_trades, 0) as total_trades,
                   COALESCE(t.total_pnl, 0) as total_pnl
            FROM (
                SELECT run_id,
                       COUNT(DISTINCT ticker) as total_tickers,
                       COUNT(DISTINCT CASE WHEN status = 'COMPLETE' THEN ticker END) as completed_tickers
                FROM backtest_progress
                GROUP BY run_id
            ) p
            LEFT JOIN (
                SELECT run_id,
                       COUNT(*) as total_trades,
                       SUM(pnl) as total_pnl
                FROM backtest_trades
                GROUP BY run_id
            ) t ON p.run_id = t.run_id
            ORDER BY p.run_id DESC
            """;

    private static final String STRATEGY_BREAKDOWN = """
            SELECT strategy,
                   COUNT(*) as trades,
                   ROUND(100.0 * SUM(win) / COUNT(*), 1) as win_rate,
                   ROUND(SUM(pnl), 2) as total_pnl,
                   ROUND(AVG(CASE WHEN win=1 THEN pnl END), 2) as avg_win,
                   ROUND(AVG(CASE WHEN win=0 THEN pnl END), 2) as avg_loss
            FROM backtest_trades
            WHERE run_id = ?
            GROUP BY strategy
            ORDER BY total_pnl DESC
            """;

    private static final String SIGNAL_TYPE_BREAKDOWN = """
            SELECT signal_type,
                   COUNT(*) as trades,
                   ROUND(100.0 * SUM(win) / COUNT(*), 1) as win_rate,
                   ROUND(SUM(pnl), 2) as total_pnl
            FROM backtest_trades
            WHERE run_id = ?
            GROUP BY signal_type
            ORDER BY total_pnl DESC
            """;

    private static final String TOTAL_STATS = """
            SELECT COUNT(*) as total_trades,
                   ROUND(COALESCE(SUM(pnl), 0), 2) as total_pnl,
                   ROUND(100.0 * SUM(win) / NULLIF(COUNT(*), 0), 1) as win_rate
            FROM backtest_trades
            WHERE run_id = ?
            """;

    private static final String LOSSES_BY_HOUR = """
            SELECT substr(signal_date, 12, 2) as hour,
                   COUNT(*) as count,
                   ROUND(AVG(pnl), 2) as avg_loss
            FROM backtest_trades
            WHERE run_id = ? AND win = 0
            GROUP BY hour
            ORDER BY hour
            """;

    private static final String LOSSES_BY_PATTERN = """
            SELECT COALESCE(pattern, '') as pattern,
                   COUNT(*) as count,
                   ROUND(AVG(pnl), 2) as avg_loss
            FROM backtest_trades
            WHERE run_id = ? AND win = 0 AND pattern IS NOT NULL AND pattern != ''
            GROUP BY pattern
            ORDER BY avg_loss ASC
            """;

    private static final String LOSSES_BY_TICKER = """
            SELECT ticker,
                   COUNT(*) as count,
                   ROUND(SUM(pnl), 2) as total_loss
            FROM backtest_trades
            WHERE run_id = ? AND win = 0
            GROUP BY ticker
            ORDER BY total_loss ASC
            """;

    private static final String WORST_TRADES = """
            SELECT id, run_id, ticker, strategy, timeframe, signal_date, signal_type,
                   entry_price, exit_price, pnl, win, pattern
            FROM backtest_trades
            WHERE run_id = ? AND win = 0
            ORDER BY pnl ASC
            LIMIT 20
            """;

    // -------------------------------------------------------------------------
    // RowMappers
    // -------------------------------------------------------------------------

    private static final RowMapper<RunSummaryDto> RUN_SUMMARY_MAPPER = (rs, rowNum) ->
            new RunSummaryDto(
                    rs.getString("run_id"),
                    rs.getInt("total_tickers"),
                    rs.getInt("completed_tickers"),
                    rs.getLong("total_trades"),
                    rs.getDouble("total_pnl")
            );

    private static final RowMapper<StrategyBreakdownDto> STRATEGY_BREAKDOWN_MAPPER = (rs, rowNum) ->
            new StrategyBreakdownDto(
                    rs.getString("strategy"),
                    rs.getLong("trades"),
                    rs.getDouble("win_rate"),
                    rs.getDouble("total_pnl"),
                    rs.getDouble("avg_win"),
                    rs.getDouble("avg_loss")
            );

    private static final RowMapper<SignalTypeBreakdownDto> SIGNAL_TYPE_MAPPER = (rs, rowNum) ->
            new SignalTypeBreakdownDto(
                    rs.getString("signal_type"),
                    rs.getLong("trades"),
                    rs.getDouble("win_rate"),
                    rs.getDouble("total_pnl")
            );

    private static final RowMapper<TradeRecordDto> TRADE_RECORD_MAPPER = (rs, rowNum) ->
            new TradeRecordDto(
                    rs.getLong("id"),
                    rs.getString("run_id"),
                    rs.getString("ticker"),
                    rs.getString("strategy"),
                    rs.getString("timeframe"),
                    rs.getString("signal_date"),
                    rs.getString("signal_type"),
                    rs.getDouble("entry_price"),
                    rs.getDouble("exit_price"),
                    rs.getDouble("pnl"),
                    rs.getInt("win"),
                    rs.getString("pattern")
            );

    private static final RowMapper<HourlyLossDto> HOURLY_LOSS_MAPPER = (rs, rowNum) ->
            new HourlyLossDto(
                    rs.getString("hour"),
                    rs.getLong("count"),
                    rs.getDouble("avg_loss")
            );

    private static final RowMapper<PatternLossDto> PATTERN_LOSS_MAPPER = (rs, rowNum) ->
            new PatternLossDto(
                    rs.getString("pattern"),
                    rs.getLong("count"),
                    rs.getDouble("avg_loss")
            );

    private static final RowMapper<TickerLossDto> TICKER_LOSS_MAPPER = (rs, rowNum) ->
            new TickerLossDto(
                    rs.getString("ticker"),
                    rs.getLong("count"),
                    rs.getDouble("total_loss")
            );

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------

    private final JdbcTemplate readJdbc;

    /**
     * Production constructor — wired by Spring with the read DataSource.
     */
    public BacktestHistoryService(@Qualifier("candlesReadDs") DataSource readDs) {
        this.readJdbc = new JdbcTemplate(readDs);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns a summary list of all backtest runs, ordered by run_id descending.
     */
    public List<RunSummaryDto> listRuns() {
        try {
            return readJdbc.query(LIST_RUNS, RUN_SUMMARY_MAPPER);
        } catch (Exception e) {
            log.warn("[backtest-history] listRuns failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Returns detailed statistics for a single run. Returns an empty detail object
     * (no 404) when the run_id is unknown, consistent with read-only query semantics.
     */
    public RunDetailDto getRunSummary(String runId) {
        try {
            List<StrategyBreakdownDto> byStrategy = readJdbc.query(STRATEGY_BREAKDOWN, STRATEGY_BREAKDOWN_MAPPER, runId);
            List<SignalTypeBreakdownDto> bySignalType = readJdbc.query(SIGNAL_TYPE_BREAKDOWN, SIGNAL_TYPE_MAPPER, runId);

            long totalTrades = 0;
            double totalPnl = 0.0;
            double winRate = 0.0;

            List<double[]> stats = readJdbc.query(TOTAL_STATS, (rs, rowNum) -> new double[]{
                    rs.getDouble("total_trades"),
                    rs.getDouble("total_pnl"),
                    rs.getDouble("win_rate")
            }, runId);

            if (!stats.isEmpty()) {
                totalTrades = (long) stats.get(0)[0];
                totalPnl = stats.get(0)[1];
                winRate = stats.get(0)[2];
            }

            return new RunDetailDto(runId, totalTrades, totalPnl, winRate, byStrategy, bySignalType);
        } catch (Exception e) {
            log.warn("[backtest-history] getRunSummary failed for run {}: {}", runId, e.getMessage());
            return new RunDetailDto(runId, 0, 0.0, 0.0, List.of(), List.of());
        }
    }

    /**
     * Returns a paginated, filterable list of trades for a given run.
     * All filters are optional (null = no filter applied).
     *
     * @param runId      required run identifier
     * @param strategy   optional strategy filter
     * @param signalType optional signal_type filter
     * @param win        optional win filter (1 = wins, 0 = losses)
     * @param limit      maximum number of rows to return
     * @param offset     zero-based row offset for pagination
     */
    public TradesPageDto getTrades(String runId, String strategy, String signalType,
                                   Integer win, int limit, int offset) {
        try {
            StringBuilder where = new StringBuilder("WHERE run_id = ?");
            List<Object> params = new ArrayList<>();
            params.add(runId);

            if (strategy != null) {
                where.append(" AND strategy = ?");
                params.add(strategy);
            }
            if (signalType != null) {
                where.append(" AND signal_type = ?");
                params.add(signalType);
            }
            if (win != null) {
                where.append(" AND win = ?");
                params.add(win);
            }

            String countSql = "SELECT COUNT(*) FROM backtest_trades " + where;
            String dataSql = "SELECT id, run_id, ticker, strategy, timeframe, signal_date, signal_type, " +
                             "entry_price, exit_price, pnl, win, pattern " +
                             "FROM backtest_trades " + where +
                             " ORDER BY pnl ASC LIMIT ? OFFSET ?";

            Long total = readJdbc.queryForObject(countSql, Long.class, params.toArray());

            List<Object> dataParams = new ArrayList<>(params);
            dataParams.add(limit);
            dataParams.add(offset);

            List<TradeRecordDto> trades = readJdbc.query(dataSql, TRADE_RECORD_MAPPER, dataParams.toArray());

            return new TradesPageDto(trades, total != null ? total : 0L);
        } catch (Exception e) {
            log.warn("[backtest-history] getTrades failed for run {}: {}", runId, e.getMessage());
            return new TradesPageDto(List.of(), 0L);
        }
    }

    /**
     * Returns multi-dimensional loss analysis: by hour, pattern, ticker, and worst 20 trades.
     */
    public LossesAnalysisDto getLossesAnalysis(String runId) {
        try {
            List<HourlyLossDto> byHour = readJdbc.query(LOSSES_BY_HOUR, HOURLY_LOSS_MAPPER, runId);
            List<PatternLossDto> byPattern = readJdbc.query(LOSSES_BY_PATTERN, PATTERN_LOSS_MAPPER, runId);
            List<TickerLossDto> byTicker = readJdbc.query(LOSSES_BY_TICKER, TICKER_LOSS_MAPPER, runId);
            List<TradeRecordDto> worstTrades = readJdbc.query(WORST_TRADES, TRADE_RECORD_MAPPER, runId);

            return new LossesAnalysisDto(byHour, byPattern, byTicker, worstTrades);
        } catch (Exception e) {
            log.warn("[backtest-history] getLossesAnalysis failed for run {}: {}", runId, e.getMessage());
            return new LossesAnalysisDto(List.of(), List.of(), List.of(), List.of());
        }
    }
}
