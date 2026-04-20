package com.fgiaquinta.optionsquant.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScannerConcurrencyTest {

    @Test
    void computeAuto_respectsReserveAndCap() {
        assertEquals(6, ScannerConcurrency.computeAutoMaxConcurrent(12, 4, 1, 6));
        assertEquals(1, ScannerConcurrency.computeAutoMaxConcurrent(4, 4, 1, 6));
        assertEquals(6, ScannerConcurrency.computeAutoMaxConcurrent(24, 4, 1, 6));
    }
}
