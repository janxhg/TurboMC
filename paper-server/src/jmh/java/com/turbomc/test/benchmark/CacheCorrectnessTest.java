package com.turbomc.test.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;

import com.turbomc.storage.cache.TurboCacheManager;
import java.nio.file.Path;
import java.nio.file.Files;

/**
 * Unit tests for cache correctness.
 */
public class CacheCorrectnessTest {
    
    private TurboCacheManager cache;
    private Path testPath;
    
    @BeforeEach
    void setUp() throws Exception {
        cache = TurboCacheManager.getInstance();
        testPath = Files.createTempFile("test", ".lrf");
    }
    
    @AfterEach
    void tearDown() throws Exception {
        if (testPath != null) {
            Files.deleteIfExists(testPath);
        }
    }
    
    @Test
    void testBasicPutGet() {
        byte[] data = "test data".getBytes();
        cache.put(testPath, 0, 0, data);
        
        byte[] retrieved = cache.get(testPath, 0, 0);
        assertNotNull(retrieved);
        assertArrayEquals(data, retrieved);
    }
    
    @Test
    void testCacheMiss() {
        byte[] retrieved = cache.get(testPath, 99, 99);
        assertNull(retrieved);
    }
    
    @Test
    void testEviction() {
        // Fill cache beyond capacity
        int size = 1024 * 1024; // 1MB
        int count = 300; // Should overflow 256MB cache
        
        for (int i = 0; i < count; i++) {
            byte[] data = new byte[size];
            cache.put(testPath, i, 0, data);
        }
        
        // First entries should be evicted
        byte[] first = cache.get(testPath, 0, 0);
        assertNull(first, "Oldest entry should be evicted");
        
        // Recent entries should still be cached
        byte[] recent = cache.get(testPath, count - 1, 0);
        assertNotNull(recent, "Recent entry should be cached");
    }
    
    @Test
    void testInvalidation() {
        byte[] data = "test data".getBytes();
        cache.put(testPath, 5, 5, data);
        
        cache.invalidate(testPath, 5, 5);
        
        byte[] retrieved = cache.get(testPath, 5, 5);
        assertNull(retrieved, "Invalidated entry should not be retrievable");
    }
    
    @Test
    void testConcurrentAccess() throws Exception {
        int threadCount = 10;
        Thread[] threads = new Thread[threadCount];
        
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 100; j++) {
                    byte[] data = ("thread" + threadId + "_" + j).getBytes();
                    cache.put(testPath, threadId, j, data);
                    
                    byte[] retrieved = cache.get(testPath, threadId, j);
                    assertNotNull(retrieved);
                }
            });
            threads[i].start();
        }
        
        for (Thread thread : threads) {
            thread.join();
        }
        
        // No assertion needed - test passes if no exceptions thrown
    }
}
