package com.fgiaquinta.optionsquant.candle.backfill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link BackfillStatus} enum values.
 */
class BackfillStatusTest {

    @Test
    void skippedPermanentEnumValueExists() {
        // Must not throw — SKIPPED_PERMANENT must be a valid enum constant
        BackfillStatus status = BackfillStatus.valueOf("SKIPPED_PERMANENT");
        assertEquals(BackfillStatus.SKIPPED_PERMANENT, status);
    }
}
