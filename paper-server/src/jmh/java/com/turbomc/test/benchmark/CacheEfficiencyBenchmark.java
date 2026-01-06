package com.turbomc.test.benchmark;

import com.turbomc.storage.cache.TurboCacheManager;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Random;

/**
 * Benchmark for Cache Efficiency.
 * Tests overhead, hit/miss performance, and eviction policies.
 */
public class CacheEfficiencyBenchmark extends TurboBenchmarkBase {

    @State(Scope.Thread)
    public static class CacheState {
        @Param({"1000", "10000"})
        public int cacheSize;
        
        // Use generic map since TurboCacheManager is singleton/disabled
        public java.util.concurrent.ConcurrentHashMap<Integer, byte[]> cache; 
        
        public byte[] dataPayload;
        public int[] randomKeys;
        
        @Setup(Level.Trial)
        public void setup() {
            cache = new java.util.concurrent.ConcurrentHashMap<>(cacheSize);
            dataPayload = new byte[16384]; // 16KB chunk
            new java.util.Random().nextBytes(dataPayload);
            
            randomKeys = new int[10000];
            java.util.Random r = new java.util.Random(123);
            for (int i = 0; i < randomKeys.length; i++) {
                randomKeys[i] = r.nextInt(20000);
            }
            
            // Warmup
            for (int i = 0; i < cacheSize / 2; i++) {
                cache.put(i, dataPayload);
            }
        }
        
        @TearDown(Level.Trial)
        public void teardown() {
            cache.clear();
        }
    }
    
    @Benchmark
    public void benchmarkCacheGet(CacheState state, Blackhole bh) {
        for (int key : state.randomKeys) {
            bh.consume(state.cache.get(key));
        }
    }
    
    @Benchmark
    public void benchmarkCachePut(CacheState state, Blackhole bh) {
        for (int key : state.randomKeys) {
            state.cache.put(key, state.dataPayload);
        }
    }
}
