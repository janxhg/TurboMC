package com.turbomc.storage.optimization;

import com.turbomc.config.TurboConfig;
import com.turbomc.compression.TurboCompressionService;
import com.turbomc.storage.lrf.LRFChunkEntry;
import com.turbomc.storage.lrf.LRFRegionReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Enhanced tests for TurboStorageManager - central storage operations manager.
 * Tests real storage flows, batch operations, and data integrity.
 */
public class TurboStorageManagerTest {
    
    private Path testDir;
    private com.turbomc.storage.optimization.TurboStorageManager manager;
    private Random random;
    
    @BeforeEach
    void setUp() throws IOException {
        testDir = Files.createTempDirectory("turbomc_storage_test");
        random = new Random(42);
        
        // Initialize config with real values for testing
        Path configFile = testDir.resolve("turbo.toml");
        String configContent = """
            [storage.mmap]
              enabled = false
            [storage.batch]
              enabled = true
              load-threads = 2
              save-threads = 2
            [storage.integrity]
              enabled = true
              auto-repair = true
            """;
        Files.writeString(configFile, configContent);
        
        // RESET and RE-INITIALIZE all singletons
        com.turbomc.config.TurboConfig.resetInstance();
        com.turbomc.compression.TurboCompressionService.resetInstance();
        com.turbomc.storage.optimization.TurboStorageManager.resetInstance();
        
        TurboConfig config = TurboConfig.getInstance(testDir.toFile());
        TurboCompressionService.initialize(config);
        
        manager = com.turbomc.storage.optimization.TurboStorageManager.getInstance();
        manager.initialize();
    }
    
    @AfterEach
    void tearDown() throws IOException {
        if (manager != null) {
            try {
                manager.close();
            } catch (Exception e) {
                // Ignore cleanup errors
            }
        }
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
    void testSaveAndLoadCycle() throws Exception {
        System.out.println("=== STORAGE MANAGER SAVE/LOAD CYCLE TEST ===");
        
        Path regionPath = testDir.resolve("world/region/r.0.0.lrf");
        Files.createDirectories(regionPath.getParent());
        
        byte[] testData = new byte[1024 * 10]; // 10KB
        random.nextBytes(testData);
        
        // 1. Save Chunk
        CompletableFuture<Void> saveFuture = manager.saveChunk(regionPath, 5, 5, testData);
        saveFuture.get(5, TimeUnit.SECONDS);
        
        // 2. Flush and Verify File Existence
        manager.closeRegion(regionPath);
        assertTrue(Files.exists(regionPath), "LRF region file should be created");
        
        // 3. Load Chunk
        CompletableFuture<LRFChunkEntry> loadFuture = manager.loadChunk(regionPath, 5, 5);
        LRFChunkEntry entry = loadFuture.get(5, TimeUnit.SECONDS);
        
        assertNotNull(entry, "Loaded chunk should not be null");
        assertArrayEquals(testData, entry.getData(), "Loaded data must match saved data");
        
        System.out.println("✓ Save and load cycle completed successfully");
    }
    
    @Test
    void testBatchLoadingPerformance() throws Exception {
        System.out.println("=== STORAGE MANAGER BATCH LOADING TEST ===");
        
        Path regionPath = testDir.resolve("world/region/r.batch.lrf");
        Files.createDirectories(regionPath.getParent());
        
        // Prepare 10 chunks
        for (int i = 0; i < 10; i++) {
            byte[] data = ("Batch data " + i).getBytes();
            manager.saveChunk(regionPath, i, 0, data).get();
        }
        
        // Load in batch
        java.util.List<int[]> coords = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) coords.add(new int[]{i, 0});
        
        CompletableFuture<java.util.List<LRFChunkEntry>> batchFuture = manager.loadChunks(regionPath, coords);
        java.util.List<LRFChunkEntry> results = batchFuture.get(10, TimeUnit.SECONDS);
        
        assertEquals(10, results.size(), "Should load all 10 chunks in batch");
        for (int i = 0; i < 10; i++) {
            final int idx = i;
            assertTrue(results.stream().anyMatch(e -> e.getChunkX() == idx && new String(e.getData()).equals("Batch data " + idx)),
                "Chunk " + i + " data mismatch in batch load");
        }
        
        System.out.println("✓ Batch loading successful");
    }
    
    @Test
    void testStatisticsCollection() throws Exception {
        System.out.println("=== STORAGE MANAGER STATS TEST ===");
        
        Path regionPath = testDir.resolve("world/region/r.stats.lrf");
        Files.createDirectories(regionPath.getParent());
        
        // Perform some operations
        manager.saveChunk(regionPath, 1, 1, new byte[1024]).get();
        manager.loadChunk(regionPath, 1, 1).get();
        
        com.turbomc.storage.optimization.TurboStorageManager.StorageManagerStats stats = manager.getStats();
        assertNotNull(stats);
        assertTrue(stats.getTotalLoaded() >= 1, "Stats should reflect at least 1 chunk loaded");
        
        System.out.println("✓ Stats report: " + stats);
    }
    
    @Test
    void testIntegrityValidationFlow() throws Exception {
        System.out.println("=== STORAGE MANAGER INTEGRITY FLOW TEST ===");
        
        Path regionPath = testDir.resolve("world/region/r.integrity.lrf");
        Files.createDirectories(regionPath.getParent());
        
        byte[] validData = new byte[1024];
        random.nextBytes(validData);
        
        // Save valid chunk
        manager.saveChunk(regionPath, 1, 1, validData).get();
        
        // Load and verify
        LRFChunkEntry entry = manager.loadChunk(regionPath, 1, 1).get();
        assertNotNull(entry);
        
        // Note: Manual corruption and testing auto-repair would belong to a more specific IntegrityTest,
        // but here we verify the happy path through the manager.
        
        System.out.println("✓ Integrity flow verified (happy path)");
    }
}
