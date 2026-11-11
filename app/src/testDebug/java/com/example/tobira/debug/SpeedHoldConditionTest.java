package com.example.tobira.debug;

import org.junit.Test;
import static org.junit.Assert.*;

public class SpeedHoldConditionTest {
    private static final long SECOND = 1_000_000_000L;
    private static final float TARGET = 10f / 3.6f;

    @Test public void requiresNewChangeAndTwoSecondsOfFreshSamples() {
        SpeedHoldCondition condition = new SpeedHoldCondition(SECOND);
        assertFalse(condition.accept(TARGET, SECOND, SECOND));
        assertFalse(condition.accept(TARGET, 2 * SECOND, 2 * SECOND));
        assertFalse(condition.accept(0, 3 * SECOND, 3 * SECOND));
        assertFalse(condition.accept(TARGET, 4 * SECOND, 4 * SECOND));
        assertFalse(condition.accept(TARGET, 5 * SECOND, 5 * SECOND));
        assertEquals(50, condition.progressPercent());
        assertTrue(condition.accept(TARGET, 6 * SECOND, 6 * SECOND));
        assertEquals(100, condition.progressPercent());
    }

    @Test public void staleFutureInvalidAndPreStartSamplesCannotComplete() {
        SpeedHoldCondition condition = new SpeedHoldCondition(10 * SECOND);
        assertFalse(condition.accept(0, SECOND, 10 * SECOND));
        assertFalse(condition.accept(TARGET, 11 * SECOND, 10 * SECOND));
        assertFalse(condition.accept(Float.NaN, 11 * SECOND, 11 * SECOND));
        assertFalse(condition.accept(0, 11 * SECOND, 13 * SECOND));
        assertFalse(condition.accept(TARGET, 13 * SECOND, 13 * SECOND));
        assertFalse(condition.accept(TARGET, 14 * SECOND, 14 * SECOND));
        assertFalse(condition.accept(TARGET, 15 * SECOND, 15 * SECOND));
        assertEquals(0, condition.progressPercent());
    }

    @Test public void gapsAndDisconnectsRequireNewAction() {
        SpeedHoldCondition condition = new SpeedHoldCondition(0);
        condition.accept(0, 0, 0);
        condition.accept(TARGET, SECOND, SECOND);
        condition.accept(TARGET, 2 * SECOND, 2 * SECOND);
        assertFalse(condition.accept(TARGET, 4 * SECOND, 4 * SECOND));
        assertEquals(0, condition.progressPercent());
        condition.accept(0, 5 * SECOND, 5 * SECOND);
        condition.accept(TARGET, 6 * SECOND, 6 * SECOND);
        condition.resetHold();
        assertFalse(condition.accept(TARGET, 7 * SECOND, 7 * SECOND));
        assertEquals(0, condition.progressPercent());
    }

    @Test public void duplicateOrOutOfOrderSamplesDoNotAdvanceTimer() {
        SpeedHoldCondition condition = new SpeedHoldCondition(0);
        condition.accept(0, 0, 0);
        condition.accept(TARGET, SECOND, SECOND);
        condition.accept(TARGET, 2 * SECOND, 2 * SECOND);
        assertFalse(condition.accept(TARGET, 2 * SECOND, 2 * SECOND));
        assertFalse(condition.accept(TARGET, SECOND, 2 * SECOND));
        assertEquals(50, condition.progressPercent());
    }

    @Test public void reverseSpeedDoesNotCountAsForwardTarget() {
        SpeedHoldCondition condition = new SpeedHoldCondition(0);
        condition.accept(0, 0, 0);
        for (int second = 1; second <= 4; second++) {
            assertFalse(condition.accept(-TARGET, second * SECOND, second * SECOND));
        }
        assertEquals(0, condition.progressPercent());
    }

    @Test public void leavingToleranceResetsHold() {
        SpeedHoldCondition condition = new SpeedHoldCondition(0);
        condition.accept(0, 0, 0);
        condition.accept(TARGET, SECOND, SECOND);
        condition.accept(TARGET, 2 * SECOND, 2 * SECOND);
        assertFalse(condition.accept(12f / 3.6f, 3 * SECOND, 3 * SECOND));
        assertEquals(0, condition.progressPercent());
        assertFalse(condition.accept(TARGET, 4 * SECOND, 4 * SECOND));
    }
}
