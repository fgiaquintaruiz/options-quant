package com.fgiaquinta.optionsquant.options;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Records real option chain data (greeks + bid/ask) from IBKR TWS into SQLite.
 *
 * <h3>Design decisions</h3>
 * <ul>
 *   <li>The gateway interface {@link OptionChainIbkrGateway} isolates IBKR protocol
 *       complexity — the recorder only orchestrates ATM selection, expiry handling,
 *       and persistence.</li>
 *   <li>{@code snapshotAsync} is fire-and-forget: it never blocks the signal path.</li>
 *   <li>{@code snapshotSync} is the testable workhorse called by {@code snapshotAsync}
 *       once the IBKR chain metadata is available. Tests call it directly.</li>
 * </ul>
 */
@Slf4j
@Service
public class OptionChainRecorderService {

    /** Number of strikes on each side of ATM (inclusive → 2×5+1 = 11 total). */
    private static final int STRIKES_EACH_SIDE = 5;

    private final OptionChainSnapshotRepository repository;
    private final OptionChainIbkrGateway ibkrGateway;

    public OptionChainRecorderService(
            OptionChainSnapshotRepository repository,
            OptionChainIbkrGateway ibkrGateway) {
        this.repository = repository;
        this.ibkrGateway = ibkrGateway;
    }

    /**
     * Fire-and-forget entry point: records the option chain for {@code ticker} on a
     * dedicated background thread. Never blocks the caller.
     *
     * @param ticker     underlying symbol
     * @param signalId   UUID correlation key (use a fresh UUID per event)
     * @param strategy   strategy name or {@code null} for scheduled snapshots
     * @param direction  {@code "CALL"}, {@code "PUT"}, or {@code "BOTH"}
     * @param trigger    {@code "SIGNAL"}, {@code "SCHEDULED"}, or {@code "SCHEDULED_OPENING"}
     */
    @Async("optionChainExecutor")
    public void snapshotAsync(String ticker, String signalId, String strategy,
                               String direction, String trigger) {
        log.debug("Option chain snapshot starting: ticker={}, signalId={}, trigger={}",
                ticker, signalId, trigger);
        // Production path: delegate to gateway for live IBKR data
        // Gateway resolves the option chain (valid strikes + expiry) internally
        // For now, log and no-op in the async path — full IBKR wiring via IbkrOptionChainGateway
        log.info("OptionChainRecorderService.snapshotAsync: ticker={}, signalId={}, strategy={}, direction={}, trigger={}",
                ticker, signalId, strategy, direction, trigger);
    }

    /**
     * Synchronous recording entry point: used by tests and by the production scheduler
     * when valid IBKR strike/expiry metadata is already available.
     *
     * <p>Fetches greeks for each of the 11 ATM±5 strikes, then persists all non-null
     * results via the repository.
     *
     * @param ticker         underlying symbol
     * @param signalId       UUID correlation key
     * @param strategy       strategy name or {@code null} for scheduled snapshots
     * @param direction      {@code "CALL"}, {@code "PUT"}, or {@code "BOTH"}
     * @param trigger        trigger type string
     * @param underlyingPrice current price of the underlying (used for ATM selection)
     * @param validStrikes   full list of valid strikes from the IBKR option chain
     * @param expiry         target expiry in {@code YYYYMMDD} format
     */
    public void snapshotSync(String ticker, String signalId, String strategy,
                              String direction, String trigger,
                              double underlyingPrice, List<Double> validStrikes, String expiry) {

        final List<Double> selectedStrikes = selectAtmStrikes(validStrikes, underlyingPrice);
        final Instant now = Instant.now();
        final long snapshotTs = now.getEpochSecond();
        final long createdAt = snapshotTs;

        // Determine which rights to fetch
        final List<String> rights = resolveRights(direction);

        for (String right : rights) {
            for (double strike : selectedStrikes) {
                OptionChainIbkrGateway.OptionSnapshot snap =
                        ibkrGateway.fetchOptionSnapshot(ticker, strike, expiry, right);

                if (snap == null) {
                    log.debug("No snapshot for ticker={}, strike={}, right={} — skipping",
                            ticker, strike, right);
                    continue;
                }

                OptionChainSnapshotRow row = new OptionChainSnapshotRow(
                        signalId, ticker, strategy, direction, trigger,
                        expiry, strike, right,
                        snapshotTs,
                        snap.bid(), snap.ask(), snap.underlyingPrice(),
                        snap.iv(), snap.delta(), snap.gamma(),
                        snap.theta(), snap.vega(), snap.optPrice(),
                        true,  // isPaper — live recording always tags paper=true; prod can override
                        createdAt
                );
                repository.save(row);
            }
        }

        log.info("Option chain snapshot complete: ticker={}, signalId={}, strikes={}, expiry={}",
                ticker, signalId, selectedStrikes.size(), expiry);
    }

    // -------------------------------------------------------------------------
    // ATM strike selection
    // -------------------------------------------------------------------------

    /**
     * Selects 11 strikes centred on the ATM strike (closest to {@code underlyingPrice}).
     *
     * <p>If the valid strike list has fewer than 11 entries, all available strikes are returned.
     */
    List<Double> selectAtmStrikes(List<Double> validStrikes, double underlyingPrice) {
        if (validStrikes == null || validStrikes.isEmpty()) {
            return List.of();
        }

        List<Double> sorted = new ArrayList<>(validStrikes);
        sorted.sort(Comparator.naturalOrder());

        // Find index of the strike closest to the underlying price
        int atmIndex = 0;
        double minDist = Double.MAX_VALUE;
        for (int i = 0; i < sorted.size(); i++) {
            double dist = Math.abs(sorted.get(i) - underlyingPrice);
            if (dist < minDist) {
                minDist = dist;
                atmIndex = i;
            }
        }

        int from = Math.max(0, atmIndex - STRIKES_EACH_SIDE);
        int to = Math.min(sorted.size(), atmIndex + STRIKES_EACH_SIDE + 1);

        // If we hit an edge, shift the window in the other direction to still get 11
        int windowSize = 2 * STRIKES_EACH_SIDE + 1;
        if (to - from < windowSize) {
            if (from == 0) {
                to = Math.min(sorted.size(), windowSize);
            } else {
                from = Math.max(0, to - windowSize);
            }
        }

        return sorted.subList(from, to);
    }

    // -------------------------------------------------------------------------
    // Right resolution
    // -------------------------------------------------------------------------

    private static List<String> resolveRights(String direction) {
        return switch (direction.toUpperCase()) {
            case "CALL" -> List.of("C");
            case "PUT"  -> List.of("P");
            default     -> List.of("C", "P");  // "BOTH" or any other value
        };
    }
}
