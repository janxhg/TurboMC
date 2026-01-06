package com.turbomc.test.benchmark;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;
import java.util.HashMap;
import java.util.Map;

/**
 * Base class for all TurboMC benchmarks.
 * Provides common infrastructure for metrics collection and reporting.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@State(Scope.Benchmark)
public abstract class TurboBenchmarkBase {
    
    protected static final int SMALL_BATCH = 16;
    protected static final int MEDIUM_BATCH = 64;
    protected static final int LARGE_BATCH = 256;
    protected static final int HUGE_BATCH = 1024;
    
    // Metrics collection
    protected Map<String, Long> timings = new HashMap<>();
    protected Map<String, Long> counters = new HashMap<>();
    protected Map<String, Double> ratios = new HashMap<>();
    
    /**
     * Records timing for a specific operation
     */
    protected void recordTiming(String operation, long nanos) {
        timings.merge(operation, nanos, Long::sum);
    }
    
    /**
     * Increments a counter
     */
    protected void incrementCounter(String counter) {
        counters.merge(counter, 1L, Long::sum);
    }
    
    /**
     * Records a ratio metric
     */
    protected void recordRatio(String metric, double value) {
        ratios.put(metric, value);
    }
    
    /**
     * Gets average timing for an operation
     */
    protected long getAverageTiming(String operation) {
        Long total = timings.get(operation);
        Long count = counters.get(operation + "_count");
        if (total == null || count == null || count == 0) return 0;
        return total / count;
    }
    
    /**
     * Prints benchmark summary
     */
    @TearDown(Level.Trial)
    public void printSummary() {
        System.out.println("\n=== " + this.getClass().getSimpleName() + " Summary ===");
        
        if (!timings.isEmpty()) {
            System.out.println("\nTimings (μs):");
            timings.forEach((op, time) -> 
                System.out.printf("  %s: %.2f\n", op, time / 1000.0));
        }
        
        if (!counters.isEmpty()) {
            System.out.println("\nCounters:");
            counters.forEach((name, count) -> 
                System.out.printf("  %s: %d\n", name, count));
        }
        
        if (!ratios.isEmpty()) {
            System.out.println("\nRatios:");
            ratios.forEach((name, ratio) -> 
                System.out.printf("  %s: %.2f%%\n", name, ratio * 100));
        }
        
        System.out.println("=====================================\n");
    }
    
    /**
     * Measures execution time of a runnable
     */
    protected long measureNanos(Runnable operation) {
        long start = System.nanoTime();
        operation.run();
        return System.nanoTime() - start;
    }
    
    /**
     * Prevents JIT from optimizing away the result
     */
    protected void consume(Blackhole bh, Object... objects) {
        for (Object obj : objects) {
            bh.consume(obj);
        }
    }
}
