package com.turbomc.storage.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * Tests for TurboCacheManager - intelligent caching system.
 */
public class TurboCacheManagerTest {
    
    private Path testDir;
    private TurboCacheManager cache;
    private Random random;
    
    @BeforeEach
    void setUp() throws IOException {
        testDir = Files.createTempDirectory("turbomc_cache_test");
        cache = TurboCacheManager.getInstance();
        random = new Random(12345);
    }
    
    @AfterEach
    void tearDown() throws IOException {
        if (Files.exists(testDir)) {
            Files.walk(testDir)
                 .sorted((a, b) -> b.compareTo(a))
                 .forEach(path -> {
                     try {
                         Files.deleteIfExists(path);
                     } catch (IOException e) {
                         // Ignore cleanup errors
                     }
                 });
        }
    }
    
    @Test
    void testCacheCreation() {
        // Test cache creation
        assertNotNull(cache);
        assertNotNull(TurboCacheManager.getInstance()); // Should return same instance
    }
    
    @Test
    void testBasicCacheOperations() {
        // Test basic put/get operations
        Path regionPath = testDir.resolve("r.0.0.lrf");
        byte[] value = new byte[1024];
        random.nextBytes(value);
        
        // Put value
        assertDoesNotThrow(() -> {
            cache.put(regionPath, 0, 0, value);
        });
        
        // Get value
        byte[] retrieved = assertDoesNotThrow(() -> {
            return cache.get(regionPath, 0, 0);
        });
        
        assertArrayEquals(value, retrieved);
    }
    
    @Test
    void testMultipleChunks() {
        // Test caching multiple chunks
        Path regionPath = testDir.resolve("r.0.0.lrf");
        byte[][] values = new byte[5][];
        
        for (int i = 0; i < values.length; i++) {
            values[i] = new byte[512];
            random.nextBytes(values[i]);
            
            // Put chunk
            cache.put(regionPath, i, 0, values[i]);
        }
        
        // Get all chunks
        for (int i = 0; i < values.length; i++) {
            byte[] retrieved = cache.get(regionPath, i, 0);
            assertArrayEquals(values[i], retrieved);
        }
    }
    
    @Test
    void testMultipleRegions() {
        // Test chunks across multiple regions
        Path[] regionPaths = {
            testDir.resolve("r.0.0.lrf"),
            testDir.resolve("r.1.0.lrf"),
            testDir.resolve("r.0.1.lrf")
        };
        
        byte[] value = new byte[256];
        random.nextBytes(value);
        
        // Put chunks in different regions
        for (int i = 0; i < regionPaths.length; i++) {
            cache.put(regionPaths[i], 0, 0, value);
        }
        
        // Get chunks from all regions
        for (int i = 0; i < regionPaths.length; i++) {
            byte[] retrieved = cache.get(regionPaths[i], 0, 0);
            assertArrayEquals(value, retrieved);
        }
    }
    
    @Test
    void testCacheOverwrite() {
        // Test overwriting existing cache entries
        Path regionPath = testDir.resolve("r.0.0.lrf");
        byte[] originalValue = new byte[512];
        byte[] newValue = new byte[512];
        random.nextBytes(originalValue);
        random.nextBytes(newValue);
        
        // Put original value
        cache.put(regionPath, 0, 0, originalValue);
        byte[] retrieved = cache.get(regionPath, 0, 0);
        assertArrayEquals(originalValue, retrieved);
        
        // Overwrite with new value
        cache.put(regionPath, 0, 0, newValue);
        retrieved = cache.get(regionPath, 0, 0);
        assertArrayEquals(newValue, retrieved);
    }
    
    @Test
    void testCacheNullHandling() {
        // Test null handling
        Path regionPath = testDir.resolve("r.0.0.lrf");
        
        // Put null should not throw
        assertDoesNotThrow(() -> {
            cache.put(regionPath, 0, 0, null);
        });
        
        // Get non-existent chunk should return null
        assertNull(cache.get(regionPath, 0, 0));
    }
    
    @Test
    void testConcurrentAccess() throws InterruptedException {
        // Test concurrent cache access
        int threadCount = 10;
        Thread[] threads = new Thread[threadCount];
        Path regionPath = testDir.resolve("r.0.0.lrf");
        
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                byte[] value = new byte[256];
                random.nextBytes(value);
                
                // Put value
                cache.put(regionPath, threadId, 0, value);
                
                // Get value
                byte[] retrieved = cache.get(regionPath, threadId, 0);
                assertArrayEquals(value, retrieved);
            });
        }
        
        // Start all threads
        for (Thread thread : threads) {
            thread.start();
        }
        
        // Wait for completion
        for (Thread thread : threads) {
            thread.join();
        }
        
        // Verify all chunks exist
        for (int i = 0; i < threadCount; i++) {
            byte[] retrieved = cache.get(regionPath, i, 0);
            assertNotNull(retrieved);
            assertEquals(256, retrieved.length);
        }
    }
    
    @Test
    void testCacheMemoryManagement() {
        // Test cache memory management
        Path regionPath = testDir.resolve("r.0.0.lrf");
        
        // Add large chunks to test eviction
        byte[] largeValue = new byte[1024 * 1024]; // 1MB
        random.nextBytes(largeValue);
        
        // Add multiple large chunks (should trigger eviction)
        for (int i = 0; i < 300; i++) { // 300MB total, should exceed default 256MB limit
            cache.put(regionPath, i, 0, largeValue);
        }
        
        // Cache should still work (old entries may be evicted)
        byte[] retrieved = cache.get(regionPath, 299, 0); // Last entry should still be there
        assertArrayEquals(largeValue, retrieved);
    }
    
    @Test
    void testCachePerformance() {
        // Test cache performance
        Path regionPath = testDir.resolve("r.0.0.lrf");
        byte[] value = new byte[1024];
        random.nextBytes(value);
        
        // Benchmark put operations
        long startTime = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            cache.put(regionPath, i, 0, value);
        }
        long putTime = System.nanoTime() - startTime;
        
        // Benchmark get operations
        startTime = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            cache.get(regionPath, i, 0);
        }
        long getTime = System.nanoTime() - startTime;
        
        // Performance should be reasonable
        assertTrue(putTime < 1_000_000_000L, "Put operations should complete in under 1 second");
        assertTrue(getTime < 500_000_000L, "Get operations should complete in under 500ms");
    }
    
    @Test
    void testCacheWithDifferentSizes() {
        // Test cache with different chunk sizes
        Path regionPath = testDir.resolve("r.0.0.lrf");
        
        int[] sizes = {256, 512, 1024, 2048, 4096};
        
        for (int i = 0; i < sizes.length; i++) {
            byte[] value = new byte[sizes[i]];
            random.nextBytes(value);
            
            cache.put(regionPath, i, 0, value);
            byte[] retrieved = cache.get(regionPath, i, 0);
            
            assertArrayEquals(value, retrieved);
            assertEquals(sizes[i], retrieved.length);
        }
    }
    
    @Test
    void testCacheConsistency() {
        // Test cache consistency across operations
        Path regionPath = testDir.resolve("r.0.0.lrf");
        byte[] value = new byte[1024];
        random.nextBytes(value);
        
        // Put value
        cache.put(regionPath, 0, 0, value);
        
        // Get value multiple times
        byte[] retrieved1 = cache.get(regionPath, 0, 0);
        byte[] retrieved2 = cache.get(regionPath, 0, 0);
        byte[] retrieved3 = cache.get(regionPath, 0, 0);
        
        // All should be identical
        assertArrayEquals(value, retrieved1);
        assertArrayEquals(retrieved1, retrieved2);
        assertArrayEquals(retrieved2, retrieved3);
    }
}
