package com.example.tobira.debug;

/** Pure evaluator. Only fresh forward-speed samples, following a new change, can complete it. */
public final class SpeedHoldCondition {
    public static final float TARGET_KMH = 10f;
    public static final float TOLERANCE_KMH = 1f;
    public static final long HOLD_NS = 2_000_000_000L;
    public static final long MAX_GAP_NS = 1_000_000_000L;
    private final long startedNs;
    private long lastNs = -1;
    private long holdStartNs = -1;
    private boolean outsideSeen;
    private boolean complete;

    public SpeedHoldCondition(long startedNs) { this.startedNs = startedNs; }

    public boolean accept(float metersPerSecond, long timestampNs, long nowNs) {
        if (complete) return true;
        if (!Float.isFinite(metersPerSecond) || timestampNs < startedNs
                || timestampNs > nowNs || nowNs - timestampNs > MAX_GAP_NS) {
            resetHold();
            return false;
        }
        if (timestampNs <= lastNs) return false;
        if (lastNs >= 0 && timestampNs - lastNs > MAX_GAP_NS) resetHold();
        lastNs = timestampNs;
        float kmh = metersPerSecond * 3.6f;
        if (Math.abs(kmh - TARGET_KMH) > TOLERANCE_KMH) {
            outsideSeen = true;
            holdStartNs = -1;
        } else if (outsideSeen) {
            if (holdStartNs < 0) holdStartNs = timestampNs;
            complete = timestampNs - holdStartNs >= HOLD_NS;
        }
        return complete;
    }

    public void resetHold() {
        holdStartNs = -1;
        outsideSeen = false;
    }

    public int progressPercent() {
        if (complete) return 100;
        return holdStartNs < 0 ? 0 : (int) Math.min(100, (lastNs - holdStartNs) * 100 / HOLD_NS);
    }
}
