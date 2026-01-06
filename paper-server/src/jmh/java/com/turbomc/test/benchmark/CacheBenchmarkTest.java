package com.turbomc.test.benchmark;

import com.turbomc.storage.cache.TurboCacheManager;
import com.turbomc.compression.TurboCompressionService;
import com.turbomc.config.TurboConfig;
import com.turbomc.storage.lrf.LRFRegionReader;
import com.turbomc.storage.lrf.LRFRegionWriter;
import com.turbomc.storage.lrf.LRFConstants;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Random;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Comprehensive benchmark suite to test cache performance.
 * 
 * Tests:
 * 1. Cache ON vs OFF
 * 2. Different compression levels (1, 3, 10, 20)
 * 3. Access patterns (sequential, random, hot chunks)
 * 4. Cache hit rates
 * 5. Performance degradation over time
 * 
 * Run with: ./gradlew :turbo-server:test --tests "CacheBenchmark"
 */
public class CacheBenchmarkTest {
    
    private static final int WARMUP_ITERATIONS = 100;
    private static final int BENCHMARK_ITERATIONS = 1000;
    private static final int CHUNK_SIZE = 64 * 1024; // 64KB per chunk
    
    @org.junit.jupiter.api.Test
    public void testBenchmarks() throws Exception {
        System.out.println("=".repeat(80));
        System.out.println("TurboMC Cache Performance Benchmark");
        System.out.println("=".repeat(80));
        
        // Initialize TurboMC
        initializeTurboMC();
        
        // Run all benchmarks
        System.out.println("\n[1/6] Testing Sequential Access...");
        testSequentialAccess();
        
        System.out.println("\n[2/6] Testing Random Access...");
        testRandomAccess();
        
        System.out.println("\n[3/6] Testing Hot Chunks (80/20 rule)...");
        testHotChunks();
        
        System.out.println("\n[4/6] Testing Cache vs No Cache...");
        compareCacheVsNoCache();
        
        System.out.println("\n[5/6] Testing Compression Levels...");
        testCompressionLevels();
        
        System.out.println("\n[6/6] Testing Performance Degradation...");
        testDegradation();
        
        // Summary
        printSummary();
    }
    
    private static void initializeTurboMC() {
        System.out.println("Initializing TurboMC...");
        try {
            // Reset instances for clean state
            TurboCompressionService.resetInstance();
            TurboConfig.resetInstance();
            
            // Initialize config with current directory (requires File arg)
            TurboConfig config = TurboConfig.getInstance(new java.io.File("."));
            
            // Initialize compression service
            TurboCompressionService.initialize(config);
            
            System.out.println("OK TurboMC initialized");
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize TurboMC", e);
        }
    }
    
    /**
     * Test 1: Sequential chunk access (best case for no cache)
     */
    private static void testSequentialAccess() {
        System.out.println("  Testing with cache ENABLED...");
        long withCache = benchmarkSequential(true);
        
        System.out.println("  Testing with cache DISABLED...");
        long withoutCache = benchmarkSequential(false);
        
        printComparison("Sequential Access", withCache, withoutCache);
    }
    
    private static long benchmarkSequential(boolean cacheEnabled) {
        // Create test data
        Path testFile = createTestLRFFile();
        
        long startTime = System.nanoTime();
        
        try {
            LRFRegionReader reader = new LRFRegionReader(testFile);
            
            // Read chunks sequentially
            for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
                int x = i % 32;
                int z = i / 32;
                
                if (cacheEnabled) {
                    // With cache
                    var cache = TurboCacheManager.getInstance();
                    byte[] cached = cache.get(testFile, x, z);
                    if (cached == null) {
                        var entry = reader.readChunk(x, z);
                        if (entry != null) {
                            cache.put(testFile, x, z, entry.getData());
                        }
                    }
                } else {
                    // Direct read
                    reader.readChunk(x, z);
                }
            }
            
            reader.close();
            
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
        
        long elapsed = System.nanoTime() - startTime;
        return TimeUnit.NANOSECONDS.toMillis(elapsed);
    }
    
    /**
     * Test 2: Random chunk access (realistic gameplay)
     */
    private static void testRandomAccess() {
        System.out.println("  Testing with cache ENABLED...");
        long withCache = benchmarkRandom(true);
        
        System.out.println("  Testing with cache DISABLED...");
        long withoutCache = benchmarkRandom(false);
        
        printComparison("Random Access", withCache, withoutCache);
    }
    
    private static long benchmarkRandom(boolean cacheEnabled) {
        Path testFile = createTestLRFFile();
        Random random = new Random(42); // Fixed seed for reproducibility
        
        long startTime = System.nanoTime();
        
        try {
            LRFRegionReader reader = new LRFRegionReader(testFile);
            
            for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
                int x = random.nextInt(32);
                int z = random.nextInt(32);
                
                if (cacheEnabled) {
                    var cache = TurboCacheManager.getInstance();
                    byte[] cached = cache.get(testFile, x, z);
                    if (cached == null) {
                        var entry = reader.readChunk(x, z);
                        if (entry != null) {
                            cache.put(testFile, x, z, entry.getData());
                        }
                    }
                } else {
                    reader.readChunk(x, z);
                }
            }
            
            reader.close();
            
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
        
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
    }
    
    /**
     * Test 3: Hot chunks (80/20 rule - 20% of chunks accessed 80% of time)
     */
    private static void testHotChunks() {
        System.out.println("  Testing with cache ENABLED...");
        long withCache = benchmarkHotChunks(true);
        
        System.out.println("  Testing with cache DISABLED...");
        long withoutCache = benchmarkHotChunks(false);
        
        printComparison("Hot Chunks (80/20)", withCache, withoutCache);
    }
    
    private static long benchmarkHotChunks(boolean cacheEnabled) {
        Path testFile = createTestLRFFile();
        Random random = new Random(42);
        
        // Define hot chunks (20% of total)
        List<int[]> hotChunks = new ArrayList<>();
        for (int i = 0; i < 200; i++) { // 200 out of 1024 chunks
            hotChunks.add(new int[]{i % 32, i / 32});
        }
        
        long startTime = System.nanoTime();
        
        try {
            LRFRegionReader reader = new LRFRegionReader(testFile);
            
            for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
                int x, z;
                
                // 80% chance to access hot chunk
                if (random.nextDouble() < 0.8) {
                    int[] chunk = hotChunks.get(random.nextInt(hotChunks.size()));
                    x = chunk[0];
                    z = chunk[1];
                } else {
                    x = random.nextInt(32);
                    z = random.nextInt(32);
                }
                
                if (cacheEnabled) {
                    var cache = TurboCacheManager.getInstance();
                    byte[] cached = cache.get(testFile, x, z);
                    if (cached == null) {
                        var entry = reader.readChunk(x, z);
                        if (entry != null) {
                            cache.put(testFile, x, z, entry.getData());
                        }
                    }
                } else {
                    reader.readChunk(x, z);
                }
            }
            
            reader.close();
            
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
        
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);
    }
    
    /**
     * Test 4: Direct comparison with detailed metrics
     */
    private static void compareCacheVsNoCache() {
        System.out.println("\n  Detailed Metrics:");
        System.out.println("  " + "-".repeat(70));
        System.out.printf("  %-30s %15s %15s %10s%n", "Metric", "With Cache", "Without Cache", "Diff");
        System.out.println("  " + "-".repeat(70));
        
        // Throughput
        long cacheTime = benchmarkRandom(true);
        long noCacheTime = benchmarkRandom(false);
        
        double cacheThroughput = (double) BENCHMARK_ITERATIONS / cacheTime * 1000;
        double noCacheThroughput = (double) BENCHMARK_ITERATIONS / noCacheTime * 1000;
        
        System.out.printf("  %-30s %15.2f %15.2f %9.1f%%%n", 
            "Throughput (chunks/sec)", cacheThroughput, noCacheThroughput,
            ((cacheThroughput - noCacheThroughput) / noCacheThroughput * 100));
        
        // Average latency
        double cacheLatency = (double) cacheTime / BENCHMARK_ITERATIONS;
        double noCacheLatency = (double) noCacheTime / BENCHMARK_ITERATIONS;
        
        System.out.printf("  %-30s %15.2f %15.2f %9.1f%%%n",
            "Avg Latency (ms)", cacheLatency, noCacheLatency,
            ((cacheLatency - noCacheLatency) / noCacheLatency * 100));
        
        System.out.println("  " + "-".repeat(70));
    }
    
    /**
     * Test 5: Test different compression levels
     */
    private static void testCompressionLevels() {
        int[] levels = {1, 3, 10, 20};
        
        System.out.println("\n  Testing compression levels:");
        System.out.println("  " + "-".repeat(70));
        System.out.printf("  %-10s %15s %15s %15s%n", "Level", "With Cache", "Without Cache", "Difference");
        System.out.println("  " + "-".repeat(70));
        
        for (int level : levels) {
            setCompressionLevel(level);
            
            long withCache = benchmarkRandom(true);
            long withoutCache = benchmarkRandom(false);
            long diff = withCache - withoutCache;
            
            System.out.printf("  %-10d %15dms %15dms %15dms (%s)%n",
                level, withCache, withoutCache, Math.abs(diff),
                diff > 0 ? "SLOWER" : "FASTER");
        }
        
        System.out.println("  " + "-".repeat(70));
    }
    
    /**
     * Test 6: Performance degradation over time
     */
    private static void testDegradation() {
        System.out.println("\n  Measuring degradation (cache filling up):");
        System.out.println("  " + "-".repeat(50));
        System.out.printf("  %-15s %15s %15s%n", "Iteration", "Time (ms)", "Degradation");
        System.out.println("  " + "-".repeat(50));
        
        Path testFile = createTestLRFFile();
        Random random = new Random(42);
        
        long baselineTime = 0;
        
        try {
            LRFRegionReader reader = new LRFRegionReader(testFile);
            var cache = TurboCacheManager.getInstance();
            
            // Measure in batches of 100
            for (int batch = 0; batch < 10; batch++) {
                long batchStart = System.nanoTime();
                
                for (int i = 0; i < 100; i++) {
                    int x = random.nextInt(32);
                    int z = random.nextInt(32);
                    
                    byte[] cached = cache.get(testFile, x, z);
                    if (cached == null) {
                        var entry = reader.readChunk(x, z);
                        if (entry != null) {
                            cache.put(testFile, x, z, entry.getData());
                        }
                    }
                }
                
                long batchTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - batchStart);
                
                if (batch == 0) {
                    baselineTime = batchTime;
                    System.out.printf("  %-15d %15dms %15s%n", batch * 100, batchTime, "baseline");
                } else {
                    double degradation = ((double) batchTime / baselineTime - 1.0) * 100;
                    System.out.printf("  %-15d %15dms %14.1f%%%n", batch * 100, batchTime, degradation);
                }
            }
            
            reader.close();
            
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
        }
        
        System.out.println("  " + "-".repeat(50));
    }
    
    // Helper methods
    
    private static Path createTestLRFFile() {
        try {
            Path tempFile = Files.createTempFile("turbomc_bench_", ".lrf");
            
            // Create and populate with test data
            LRFRegionWriter writer = new LRFRegionWriter(tempFile, LRFConstants.COMPRESSION_LZ4);
            
            // Write 1024 chunks (32x32 region)
            byte[] chunkData = new byte[CHUNK_SIZE];
            new Random(42).nextBytes(chunkData);
            
            for (int x = 0; x < 32; x++) {
                for (int z = 0; z < 32; z++) {
                    com.turbomc.storage.lrf.LRFChunkEntry entry = 
                        new com.turbomc.storage.lrf.LRFChunkEntry(x, z, chunkData);
                    writer.writeChunkStreaming(entry);
                }
            }
            
            writer.flush();
            writer.close();
            
            return tempFile;
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to create test file", e);
        }
    }
    
    private static void setCompressionLevel(int level) {
        // This would need to be implemented in TurboCompressionService
        System.setProperty("turbomc.compression.level", String.valueOf(level));
    }
    
    private static void printComparison(String testName, long withCache, long withoutCache) {
        long diff = withCache - withoutCache;
        double percentDiff = ((double) diff / withoutCache) * 100;
        
        System.out.printf("  %-20s: Cache=%5dms  NoCache=%5dms  Diff=%+5dms (%+.1f%%)  %s%n",
            testName, withCache, withoutCache, diff, percentDiff,
            diff > 0 ? "X CACHE SLOWER" : "OK CACHE FASTER");
    }
    
    private static void printSummary() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("SUMMARY");
        System.out.println("=".repeat(80));
        System.out.println("Recommendations:");
        System.out.println("  - If cache is consistently SLOWER: Disable cache in config");
        System.out.println("  - If degradation > 20%: Implement better eviction strategy");
        System.out.println("  - If compression level 20: Reduce to 3 or use LZ4");
        System.out.println("  - If NVMe storage: Cache overhead likely exceeds benefit");
        System.out.println("=".repeat(80));
    }
}
