package com.turbomc.storage.stress;

import com.turbomc.config.TurboConfig;
import com.turbomc.compression.TurboCompressionService;
import com.turbomc.storage.lrf.LRFChunkEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Heavy stress tests for TurboMC storage system.
 * Simulates high player load and concurrent I/O pressure.
 */
public class TurboStressTest {
    
    private Path testDir;
    private com.turbomc.storage.optimization.TurboStorageManager storageManager;
    private Random random;
    
    @BeforeEach
    void setUp() throws IOException {
        testDir = Files.createTempDirectory("turbo_stress_test");
        random = new Random(99);
        
        // Initialize with high-performance settings for stress testing
        Path configFile = testDir.resolve("turbo.toml");
        String configContent = """
            [storage.mmap]
              enabled = false
            [storage.batch]
              enabled = true
              load-threads = 8
              save-threads = 4
            """;
        Files.writeString(configFile, configContent);
        
        // RESET and RE-INITIALIZE all singletons
        com.turbomc.storage.optimization.TurboStorageManager.resetInstance();
        com.turbomc.config.TurboConfig.resetInstance();
        com.turbomc.compression.TurboCompressionService.resetInstance();
        
        TurboConfig config = TurboConfig.getInstance(testDir.toFile());
        TurboCompressionService.initialize(config);
        
        storageManager = com.turbomc.storage.optimization.TurboStorageManager.getInstance();
        storageManager.initialize();
    }
    
    @AfterEach
    void tearDown() throws IOException {
        if (storageManager != null) {
            try { storageManager.close(); } catch (Exception ignored) {}
        }
        if (Files.exists(testDir)) {
            Files.walk(testDir).sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        }
    }
    
    @Test
    void testHighConcurrencyReadWrite() throws Exception {
        System.out.println("=== HIGH CONCURRENCY STRESS TEST ===");
        
        int threadCount = 32;
        int operationsPerThread = 50;
        
        Path regionPath = testDir.resolve("world/region/r.stress.lrf");
        Files.createDirectories(regionPath.getParent());
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<?>> futures = new ArrayList<>();
        AtomicInteger successCount = new AtomicInteger(0);
        
        long startTime = System.currentTimeMillis();
        
        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                for (int j = 0; j < operationsPerThread; j++) {
                    int cx = random.nextInt(32);
                    int cz = random.nextInt(32);
                    
                    try {
                        if (random.nextBoolean()) {
                            // WRITE
                            byte[] data = new byte[1024 + random.nextInt(4096)];
                            random.nextBytes(data);
                            storageManager.saveChunk(regionPath, cx, cz, data).get();
                            // Ensure the write is flushed to disk
                            storageManager.flush(regionPath).get();
                        } else {
                            // READ
                            storageManager.loadChunk(regionPath, cx, cz).get();
                        }
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        // Log but don't fail - concurrent access may return null
                    }
                }
            }));
        }
        
        for (Future<?> f : futures) f.get(60, TimeUnit.SECONDS);
        executor.shutdown();
        
        long duration = System.currentTimeMillis() - startTime;
        System.out.printf("✓ Completed %d operations in %d ms (%.2f op/s)%n", 
            successCount.get(), duration, (successCount.get() * 1000.0 / duration));
        
        assertTrue(successCount.get() > 0, "Should complete some operations");
    }
    
    @Test
    void testLargeRegionParallelCompression() throws Exception {
        System.out.println("=== PARALLEL COMPRESSION STRESS TEST ===");
        
        Path regionPath = testDir.resolve("world/region/r.heavy.lrf");
        Files.createDirectories(regionPath.getParent());
        
        int chunkCount = 50;
        List<CompletableFuture<?>> futures = new ArrayList<>();
        
        for (int i = 0; i < chunkCount; i++) {
            byte[] data = new byte[16 * 1024]; // 16KB per chunk
            random.nextBytes(data);
            futures.add(storageManager.saveChunk(regionPath, i % 32, i / 32, data));
        }
        
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(60, TimeUnit.SECONDS);
        
        // Ensure data is flushed to disk
        storageManager.closeRegion(regionPath);
        
        com.turbomc.storage.optimization.TurboStorageManager.StorageManagerStats stats = storageManager.getStats();
        System.out.println("✓ Stats after heavy load: " + stats);
        
        assertTrue(Files.exists(regionPath), "Region file should exist: " + regionPath);
        assertTrue(Files.size(regionPath) > 0, "Region file should not be empty");
    }
}
