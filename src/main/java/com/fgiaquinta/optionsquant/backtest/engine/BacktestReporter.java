package com.fgiaquinta.optionsquant.backtest.engine;

import com.fgiaquinta.optionsquant.backtest.domain.BacktestReport;
import com.fgiaquinta.optionsquant.backtest.domain.TradeRecord;

import java.time.ZonedDateTime;

/**
 * Records backtest results and generates reports.
 */
public interface BacktestReporter {

    void onStart(ZonedDateTime startTime, double initialCapital);

    void onTrade(TradeRecord trade);

    void onEquityUpdate(ZonedDateTime timestamp, double equity);

    BacktestReport onFinish(long elapsedMs);
}
