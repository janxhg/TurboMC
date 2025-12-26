package com.turbomc.performance.event;

import com.turbomc.core.autopilot.HealthMonitor;
import com.turbomc.core.autopilot.HealthMonitor.HealthSnapshot;

/**
 * EventThrottle: Provides dynamic thresholds for frequently called events.
 * Throttles movement events to reduce core overhead during high MSPT.
 */
public class EventThrottle {

    private static final EventThrottle instance = new EventThrottle();

    private EventThrottle() {}

    public static EventThrottle getInstance() {
        return instance;
    }

    /**
     * Movement distance threshold (squared) for PlayerMoveEvent.
     */
    public double getPlayerMoveThresholdSq(HealthSnapshot status) {
        if (status.isCritical()) return 0.015625; // 1/8 block squared (0.125m)
        if (status.isStruggling()) return 0.00694444; // 1/12 block squared (~0.083m)
        return 0.00390625; // 1/16 block squared (Default Paper)
    }

    /**
     * Rotation threshold (degrees) for PlayerMoveEvent.
     */
    public float getPlayerRotationThreshold(HealthSnapshot status) {
        if (status.isCritical()) return 30.0f;
        if (status.isStruggling()) return 20.0f;
        return 10.0f; // Default Paper
    }

    /**
     * Movement distance threshold (squared) for EntityMoveEvent (Non-players).
     * Usually more aggressive than player movement.
     */
    public double getEntityMoveThresholdSq(HealthSnapshot status) {
        if (status.isCritical()) return 0.0625; // 1/4 block squared (0.25m)
        if (status.isStruggling()) return 0.015625; // 1/8 block squared (0.125m)
        return 0.00390625; // 1/16 block squared (Default)
    }
    
    /**
     * Rotation threshold (degrees) for EntityMoveEvent (Non-players).
     */
    public float getEntityRotationThreshold(HealthSnapshot status) {
        if (status.isCritical()) return 60.0f;
        if (status.isStruggling()) return 30.0f;
        return 15.0f;
    }
}
