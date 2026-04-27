package com.fgiaquinta.optionsquant.service;

import com.fgiaquinta.optionsquant.controller.LiveModeController;
import com.fgiaquinta.optionsquant.controller.LiveModeController.ClosedTradeInfo;
import com.fgiaquinta.optionsquant.controller.LiveModeController.ExecutedTradeInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;

/**
 * Safety-net scheduler that proactively closes all open (executed) trades at 15:50 ET.
 *
 * <p>Defense in depth alongside the Golden Rule time condition on bracket orders.
 * If IBKR rejects the market-sell on the conditional order (e.g. slight clock skew,
 * price condition not met), this job ensures no position is held past market close.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EodCloseScheduler {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final String EOD_REASON = "EOD_AUTO_CLOSE";

    private final LiveModeController liveModeController;
    private final OrderExecutionService orderExecutionService;

    /**
     * Fires at 15:50 ET, Mon–Fri.
     * Iterates all entries in {@code executedTrades} that are successful and not yet in
     * {@code closedTrades}, cancels their TP/SL conditional orders via
     * {@link OrderExecutionService#closePositionViaConditions}, and marks them closed.
     */
    @Scheduled(cron = "0 50 15 * * MON-FRI", zone = "America/New_York")
    public void closeAllOpenPositions() {
        log.info("EOD auto-close triggered at {} ET — scanning for open positions",
                LocalTime.now(ET));

        Map<String, ExecutedTradeInfo> executedTrades = liveModeController.getExecutedTrades();
        Map<String, ClosedTradeInfo> closedTrades = liveModeController.getClosedTrades();

        int closed = 0;
        int skipped = 0;

        for (Map.Entry<String, ExecutedTradeInfo> entry : executedTrades.entrySet()) {
            String ticker = entry.getKey();
            ExecutedTradeInfo info = entry.getValue();

            if (!info.success()) {
                skipped++;
                continue;
            }
            if (closedTrades.containsKey(ticker)) {
                skipped++;
                continue;
            }
            if (info.orderId() == null || info.tpOrderId() == null || info.slOrderId() == null) {
                log.warn("EOD close skipped for {} — missing order IDs (orderId={}, tpId={}, slId={})",
                        ticker, info.orderId(), info.tpOrderId(), info.slOrderId());
                skipped++;
                continue;
            }

            try {
                log.info("EOD closing position: ticker={} orderId={} tpId={} slId={}",
                        ticker, info.orderId(), info.tpOrderId(), info.slOrderId());
                orderExecutionService.closePositionViaConditions(
                        info.orderId(), info.tpOrderId(), info.slOrderId());

                String closeTime = LocalTime.now(ET).toString();
                liveModeController.markClosed(ticker,
                        new ClosedTradeInfo(ticker, 0.0, closeTime, EOD_REASON));
                closed++;
                log.info("EOD closed: {} at {} ET", ticker, closeTime);
            } catch (Exception e) {
                log.error("EOD close failed for {}: {}", ticker, e.getMessage(), e);
            }
        }

        log.info("EOD auto-close complete — closed={}, skipped={}", closed, skipped);
    }
}
