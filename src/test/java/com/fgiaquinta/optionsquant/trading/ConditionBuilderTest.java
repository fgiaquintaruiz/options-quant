package com.fgiaquinta.optionsquant.trading;

import com.ib.client.TimeCondition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ConditionBuilder}.
 */
class ConditionBuilderTest {

    // -----------------------------------------------------------------------
    // TASK 4.1 — createClose1450Condition
    // -----------------------------------------------------------------------

    @Test
    void createClose1450Condition_returnsTimeConditionWithCorrectTime() {
        // WHEN
        TimeCondition condition = ConditionBuilder.createClose1450Condition();

        // THEN time must contain the 14:50:00 sentinel
        assertNotNull(condition, "Condition must not be null");
        assertNotNull(condition.time(), "time() must not be null");
        assertTrue(condition.time().contains("14:50:00"),
                "time() must contain '14:50:00' but was: " + condition.time());
    }

    @Test
    void createClose1450Condition_isMoreIsFalse() {
        // WHEN
        TimeCondition condition = ConditionBuilder.createClose1450Condition();

        // THEN isMore must be false (fires AT or BEFORE 14:50, not after)
        // NOTE: isMore(false) semantics require paper-test verification — see Phase 6.6.
        assertFalse(condition.isMore(),
                "isMore must be false per design; if condition fires too early or not at all in paper, flip this boolean.");
    }

    @Test
    void createClose1450Condition_returnsDistinctInstancesOnEachCall() {
        // WHEN called twice
        TimeCondition c1 = ConditionBuilder.createClose1450Condition();
        TimeCondition c2 = ConditionBuilder.createClose1450Condition();

        // THEN each call must return a fresh instance (no hidden singleton)
        assertNotSame(c1, c2, "Each call must produce a fresh TimeCondition instance");
    }
}
