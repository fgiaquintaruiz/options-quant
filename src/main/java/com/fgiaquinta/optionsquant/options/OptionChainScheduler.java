package com.fgiaquinta.optionsquant.options;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Scheduled triggers for option chain recording.
 *
 * <ul>
 *   <li><b>15-minute cadence</b>: every 15 min from 9:30 to 15:45 ET (Mon–Fri).</li>
 *   <li><b>5-minute opening window</b>: 9:30, 9:35, and 9:40 ET only (Mon–Fri).</li>
 * </ul>
 *
 * All recordings are fire-and-forget — the scheduler never blocks on IBKR I/O.
 */
@Slf4j
@Component
public class OptionChainScheduler {

    static final List<String> TICKERS = List.of(
            "NVDA", "AMD", "AMDL", "TSLA", "META", "AVGO",
            "COIN", "MSTR", "AMZN", "SPY", "AAPL", "URA",
            "MU", "SMH", "OXY", "GLD"
    );

    private static final ZoneId ET = ZoneId.of("America/New_York");

    private final OptionChainRecorderService recorderService;

    public OptionChainScheduler(OptionChainRecorderService recorderService) {
        this.recorderService = recorderService;
    }

    /**
     * Fires every 15 minutes starting at 9:30 ET through 9:45 ET, Mon–Fri.
     * Cron: {@code 0 30/15 9 * * MON-FRI} — covers the :30 and :45 slots of hour 9.
     *
     * <p>Combined with {@link #scheduledSnapshot15mFullHours}, this achieves coverage
     * from 9:30 ET through 15:45 ET with no gaps.
     */
    @Scheduled(cron = "0 30/15 9 * * MON-FRI", zone = "America/New_York")
    public void scheduledSnapshot15m() {
        log.info("Option chain 15m snapshot triggered (9:30–9:45 ET)");
        runSnapshotForAllTickers("SCHEDULED");
    }

    /**
     * Fires every 15 minutes from 10:00 ET through 15:45 ET, Mon–Fri.
     * Cron: {@code 0 0/15 10-15 * * MON-FRI} — covers all :00/:15/:30/:45 slots in hours 10–15.
     *
     * <p>Combined with {@link #scheduledSnapshot15m}, this achieves full coverage
     * from 9:30 ET through 15:45 ET.
     */
    @Scheduled(cron = "0 0/15 10-15 * * MON-FRI", zone = "America/New_York")
    public void scheduledSnapshot15mFullHours() {
        log.info("Option chain 15m snapshot triggered (10:00–15:45 ET)");
        runSnapshotForAllTickers("SCHEDULED");
    }

    /**
     * Fires every 5 minutes starting at 9:30 ET, Mon–Fri.
     * Only runs for the 9:30–9:40 ET opening window; calls at 9:45+ are silently skipped.
     */
    @Scheduled(cron = "0 30/5 9 * * MON-FRI", zone = "America/New_York")
    public void scheduledSnapshot5mOpening() {
        runSnapshot5mOpeningAt(ZonedDateTime.now(ET));
    }

    /**
     * Testable helper: accepts the current time so tests can inject any clock value.
     *
     * @param now the current time in any zone (converted internally to ET)
     */
    public void runSnapshot5mOpeningAt(ZonedDateTime now) {
        ZonedDateTime nowEt = now.withZoneSameInstant(ET);
        int hour = nowEt.getHour();
        int minute = nowEt.getMinute();

        if (hour != 9 || minute > 40) {
            log.debug("Option chain 5m opening: skipping at {}:{} ET (outside 9:30–9:40 window)",
                    hour, minute);
            return;
        }

        log.info("Option chain 5m opening snapshot triggered at {}:{} ET", hour, minute);
        runSnapshotForAllTickers("SCHEDULED_OPENING");
    }

    /**
     * Fires one {@code snapshotAsync} call per ticker. Uses a shared batch UUID so all 16
     * tickers in the same run can be correlated in the database.
     *
     * @param triggerType trigger label stored in each snapshot row
     */
    public void runSnapshotForAllTickers(String triggerType) {
        String batchId = UUID.randomUUID().toString();
        log.debug("Option chain batch snapshot: trigger={}, batchId={}, tickers={}",
                triggerType, batchId, TICKERS.size());
        TICKERS.forEach(ticker ->
                recorderService.snapshotAsync(ticker, batchId, null, "BOTH", triggerType));
    }
}
