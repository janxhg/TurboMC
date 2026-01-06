package com.turbomc.test.benchmark;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Benchmark for Batch Processing.
 * Tests the efficiency of batching io operations vs individual processing.
 */
public class BatchProcessingBenchmark extends TurboBenchmarkBase {

    @State(Scope.Thread)
    public static class BatchState {
        @Param({"16", "64", "256"})
        public int batchSize;

        public List<Object> items;
        public ConcurrentLinkedQueue<Object> queue;

        @Setup(Level.Trial)
        public void setup() {
            items = new ArrayList<>(batchSize);
            for (int i = 0; i < batchSize; i++) {
                items.add(new Object());
            }
            queue = new ConcurrentLinkedQueue<>(items);
        }
    }

    @Benchmark
    public void processIndividually(BatchState state, Blackhole bh) {
        // Simulate processing one by one
        for (Object item : state.items) {
            processItem(item, bh);
        }
    }

    @Benchmark
    public void processAsBatch(BatchState state, Blackhole bh) {
        // Simulate batch processing overhead + loop
        startBatch();
        for (Object item : state.items) {
            processItem(item, bh);
        }
        endBatch();
    }
    
    private void processItem(Object item, Blackhole bh) {
        // Simulate some work
        bh.consume(item.hashCode());
    }
    
    private void startBatch() {
        // Overhead of locking/setup
    }
    
    private void endBatch() {
        // Overhead of flush/commit
    }
}
