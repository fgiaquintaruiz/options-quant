package com.fgiaquinta.optionsquant.strategy;

/**
 * Represents a named, evaluable trading condition used in strategy evaluation pipelines.
 *
 * <p>Each {@code Condition} encapsulates one discrete check within a strategy's logic,
 * exposing both a human-readable label and the computed value that drove the evaluation.
 * This enables structured debug logging of individual evaluation steps without scattering
 * conditional logic across the strategy body.
 */
public interface Condition {

    /**
     * Evaluates this condition against the pre-captured context.
     *
     * @return {@code true} if the condition is satisfied; {@code false} otherwise
     */
    boolean test();

    /**
     * Returns a short human-readable name for this condition (used in log output).
     *
     * @return non-null, non-empty label string
     */
    String label();

    /**
     * Returns a formatted string describing the concrete value(s) evaluated by this condition.
     * Typically includes the computed metric and the threshold it was tested against.
     *
     * @return non-null description of the evaluated value
     */
    String value();
}
