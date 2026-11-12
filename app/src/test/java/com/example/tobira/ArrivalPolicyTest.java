package com.example.tobira;

import org.junit.Test;
import static org.junit.Assert.*;

public class ArrivalPolicyTest {
    private final ArrivalPolicy policy = new ArrivalPolicy();
    private final long now = 100_000_000_000L;
    private ArrivalPolicy.Fix at(double metres, float accuracy, long timestamp) {
        return new ArrivalPolicy.Fix(Math.toDegrees(metres / 6_371_000), 0, accuracy, timestamp);
    }
    @Test public void radiusIncludesAccuracyAndRejectsOutside() {
        assertTrue(policy.check(0, 0, at(470, 20, now), now).arrived);
        assertFalse(policy.check(0, 0, at(490, 20, now), now).arrived);
        assertFalse(policy.check(0, 0, at(501, 0, now), now).arrived);
        assertTrue(policy.check(0, 0, at(0, 100, now), now).arrived);
    }
    @Test public void staleFutureAndInaccurateLocationsCannotUnlock() {
        assertFalse(policy.check(0, 0, at(0, 10, now - ArrivalPolicy.MAX_AGE_NS - 1), now).usable);
        assertFalse(policy.check(0, 0, at(0, 10, now + 1), now).usable);
        assertFalse(policy.check(0, 0, at(0, 101, now), now).usable);
        assertFalse(policy.check(0, 0, at(0, Float.NaN, now), now).usable);
        assertFalse(policy.check(0, 0, null, now).usable);
        assertFalse(policy.check(91, 0, at(0, 1, now), now).usable);
    }
}
