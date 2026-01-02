package com.turbomc.core.autopilot;

import net.minecraft.server.MinecraftServer;
import java.util.concurrent.atomic.AtomicReference;

/**
 * HealthMonitor: Tracks real-time server performance (MSPT/TPS).
 * Part of the Turbo Autopilot system.
 */
public class HealthMonitor {

    private static HealthMonitor instance;
    private final AtomicReference<HealthSnapshot> lastSnapshot = new AtomicReference<>(new HealthSnapshot(0, 20.0));

    private HealthMonitor() {
        System.out.println("[TurboMC][Autopilot] Health Monitor initialized.");
    }

    public static HealthMonitor getInstance() {
        if (instance == null) instance = new HealthMonitor();
        return instance;
    }

    /**
     * Resets the singleton instance for testing purposes.
     */
    public static void resetInstance() {
        instance = null;
    }

    private static final long CAPTURE_CACHE_TTL_MS = 500; // Cache for 500ms
    private volatile long lastCaptureTime = 0;

    /**
     * Captures current server health (cached for performance).
     */
    public HealthSnapshot capture() {
        long now = System.currentTimeMillis();
        if (now - lastCaptureTime < CAPTURE_CACHE_TTL_MS) {
            return lastSnapshot.get();
        }
        
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) return lastSnapshot.get();

        double mspt = 0;
        // Access Paper-specific MSPT data if available
        try {
            var msptData = server.getMSPTData5s();
            if (msptData != null) {
                mspt = msptData.avg(); 
            }
        } catch (Throwable ignored) {}

        double tps = server.getTPS()[0]; // 1m average
        
        HealthSnapshot snapshot = new HealthSnapshot(mspt, tps);
        lastSnapshot.set(snapshot);
        lastCaptureTime = now;
        return snapshot;
    }

    public HealthSnapshot getLastSnapshot() {
        return lastSnapshot.get();
    }

    public boolean isOverloaded() {
        return capture().isCritical();
    }

    public boolean isUnderPressure() {
        return capture().isStruggling();
    }

    public static record HealthSnapshot(double mspt, double tps) {
        public boolean isHealthy() {
            return mspt < 45.0 && tps > 18.0;
        }

        public boolean isStruggling() {
            return mspt > 50.0 || tps < 15.0;
        }

        public boolean isCritical() {
            return mspt > 100.0 || tps < 10.0;
        }
    }
}
