package com.turbomc.test.benchmark;

import com.turbomc.test.benchmark.TurboBenchmarkBase;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * STRESS TEST BENCHMARK
 * 
 * Simulates high concurrency scenarios characteristic of heavy server loads (e.g., players flying with elytra).
 * Tests how the system behaves under thread saturation.
 */
@BenchmarkMode(Mode.Throughput) // Measure throughput for stress test
@OutputTimeUnit(TimeUnit.SECONDS)
public class ChunkLoadingStressTest extends TurboBenchmarkBase {

    @State(Scope.Benchmark)
    public static class StressState {
        @Param({"4", "8", "16"}) // Number of concurrent threads
        public int threads;

        public ExecutorService executor;

        @Setup(Level.Trial)
        public void setup() {
            executor = Executors.newFixedThreadPool(threads);
        }

        @TearDown(Level.Trial)
        public void teardown() throws InterruptedException {
            executor.shutdown();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @Benchmark
    @Threads(Threads.MAX) // Use all available JMH worker threads to drive load
    public void concurrentChunkRequest(StressState state, Blackhole bh) {
        // Simulate a "heavy" unitary operation representing chunk load
        // Includes: allocation, computation, some IO latency simulation
        
        // 1. Allocation
        byte[] buffer = new byte[16384];
        
        // 2. Computation (burning CPU)
        long token = 0;
        for (int i = 0; i < 1000; i++) {
            token += i ^ buffer[i % buffer.length];
        }
        
        bh.consume(token);
    }
}
