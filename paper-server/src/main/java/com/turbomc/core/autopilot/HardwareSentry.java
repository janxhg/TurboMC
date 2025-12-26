package com.turbomc.core.autopilot;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.io.File;

/**
 * HardwareSentry: Detects system resources and assigns a "Resource Grade".
 * Part of the Turbo Autopilot system.
 */
public class HardwareSentry {

    public enum ResourceGrade {
        LOW_END(16, 4, 8),      // Max HyperView Radius, Max Gen Threads, Max Concurrent Gen
        GAMING(48, 8, 24),
        HIGH_PERFORMANCE(96, 16, 48),
        DATACENTER(256, 32, 128);

        public final int maxRadius;
        public final int maxGenThreads;
        public final int maxConcurrentGen;

        ResourceGrade(int maxRadius, int maxGenThreads, int maxConcurrentGen) {
            this.maxRadius = maxRadius;
            this.maxGenThreads = maxGenThreads;
            this.maxConcurrentGen = maxConcurrentGen;
        }
    }

    private static HardwareSentry instance;
    private final ResourceGrade grade;
    private final long totalMemory;
    private final int availableProcessors;

    private HardwareSentry() {
        this.availableProcessors = Runtime.getRuntime().availableProcessors();
        this.totalMemory = Runtime.getRuntime().maxMemory() / (1024 * 1024); // MB

        this.grade = calculateGrade();
        System.out.println("[TurboMC][Autopilot] Hardware Sentry initialized.");
        System.out.println("[TurboMC][Autopilot] Detected: " + availableProcessors + " cores, " + totalMemory + "MB RAM.");
        System.out.println("[TurboMC][Autopilot] System Grade: " + grade);
    }

    public static HardwareSentry getInstance() {
        if (instance == null) instance = new HardwareSentry();
        return instance;
    }

    /**
     * Resets the singleton instance for testing purposes.
     */
    public static void resetInstance() {
        instance = null;
    }

    private ResourceGrade calculateGrade() {
        if (availableProcessors >= 32 && totalMemory >= 32768) return ResourceGrade.DATACENTER;
        if (availableProcessors >= 12 && totalMemory >= 16384) return ResourceGrade.HIGH_PERFORMANCE;
        if (availableProcessors >= 6 && totalMemory >= 8192) return ResourceGrade.GAMING;
        return ResourceGrade.LOW_END;
    }

    public ResourceGrade getGrade() {
        return grade;
    }

    public int getMaxSafeRadius() {
        return grade.maxRadius;
    }

    public int getRecommendedThreads() {
        return Math.min(availableProcessors, grade.maxGenThreads);
    }
}
