package com.turbomc.performance.insight;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight passive profiler for detecting expensive entity ticks.
 */
public class TurboInsight {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ConcurrentHashMap<String, HotPathData> SAMPLES = new ConcurrentHashMap<>();
    private static final java.util.Random RANDOM = new java.util.Random();
    
    private static class HotPathData {
        final AtomicLong totalTime = new AtomicLong(0);
        final AtomicInteger count = new AtomicInteger(0);
    }

    public static void sample(LivingEntity entity, long durationNs) {
        if (durationNs < 1_000_000) return; // Ignore < 1ms

        String entityName = entity.getType().getDescriptionId();
        HotPathData data = SAMPLES.computeIfAbsent(entityName, k -> new HotPathData());
        data.totalTime.addAndGet(durationNs);
        int currentCount = data.count.incrementAndGet();

        // Every 100 samples of a "hot" entity type, log a warning
        if (currentCount % 100 == 0) {
            double avgMs = (data.totalTime.get() / (double)currentCount) / 1_000_000.0;
            LOGGER.warn("[TurboInsight] Dense path detected: {} (Avg: {}ms over {} samples)", 
                entityName, String.format("%.2f", avgMs), currentCount);
        }
    }

    public static boolean shouldSample() {
        // Sample 1 in 1000 ticks to minimize overhead
        return java.util.concurrent.ThreadLocalRandom.current().nextInt(1000) == 0;
    }
}
