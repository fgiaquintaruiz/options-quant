package com.fgiaquinta.optionsquant.strategy.utils;

import com.fgiaquinta.optionsquant.strategy.Condition;
import org.slf4j.Logger;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Shared evaluation loop for strategy conditions with structured DEBUG logging.
 *
 * <p>All strategies that use a {@link Condition} list delegate to this utility to avoid
 * copy-pasting the same loop body. The log format is:
 * {@code [TAG] ticker @ time — Paso X/N "label" → value ✅|❌ STOP}
 *
 * <p>Usage:
 * <pre>{@code
 * boolean triggered = ConditionEvaluator.evaluate("[C6]", ticker, currentTime, conditions, log);
 * }</pre>
 */
public final class ConditionEvaluator {

    private ConditionEvaluator() {
        // utility class — no instances
    }

    /**
     * Evaluates {@code conditions} in order, short-circuiting on the first failure.
     *
     * <p>When DEBUG is enabled on {@code logger}, emits one line per condition:
     * <ul>
     *   <li>Pass: {@code [tag] ticker @ time — Paso X/N "label" → value ✅}</li>
     *   <li>Fail: {@code [tag] ticker @ time — Paso X/N "label" → value ❌ STOP}</li>
     * </ul>
     *
     * @param tag         strategy prefix, e.g. {@code "[C6]"}
     * @param ticker      instrument symbol
     * @param currentTime evaluation timestamp
     * @param conditions  ordered list of conditions to evaluate
     * @param logger      the strategy's own SLF4J logger (for correct class context)
     * @return {@code true} if all conditions pass; {@code false} on the first failure
     */
    public static boolean evaluate(
            String tag,
            String ticker,
            ZonedDateTime currentTime,
            List<Condition> conditions,
            Logger logger) {

        final int total = conditions.size();
        for (int step = 0; step < total; step++) {
            final Condition c = conditions.get(step);
            if (logger.isDebugEnabled()) {
                final String stepPrefix = String.format("%s %s @ %s — Paso %d/%d \"%s\" → %s",
                        tag, ticker, currentTime.toLocalTime(), step + 1, total, c.label(), c.value());
                if (!c.test()) {
                    logger.debug("{} ❌ STOP", stepPrefix);
                    return false;
                }
                logger.debug("{} ✅", stepPrefix);
            } else if (!c.test()) {
                return false;
            }
        }
        return true;
    }
}
