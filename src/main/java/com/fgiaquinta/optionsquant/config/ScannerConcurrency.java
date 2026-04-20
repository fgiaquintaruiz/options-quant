package com.fgiaquinta.optionsquant.config;

/**
 * Computes safe parallel scan width for laptops/workstations.
 */
public final class ScannerConcurrency {

    private ScannerConcurrency() {
    }

    /**
     * Effective parallelism: available logical processors minus a reserve for OS/IDE, clamped.
     */
    public static int computeAutoMaxConcurrent(int availableProcessors, int reserveLogical, int min, int cap) {
        int n = availableProcessors - Math.max(0, reserveLogical);
        return Math.max(min, Math.min(cap, n));
    }
}
