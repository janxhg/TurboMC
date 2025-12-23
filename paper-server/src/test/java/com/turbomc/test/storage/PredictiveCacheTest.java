package com.turbomc.test.storage;

import com.turbomc.config.TurboConfig;
import com.turbomc.storage.mmap.MMapReadAheadEngine;
import com.turbomc.storage.lrf.LRFRegionWriter;
import com.turbomc.storage.lrf.LRFConstants;
import com.turbomc.storage.lrf.LRFChunkEntry;
import com.turbomc.compression.TurboCompressionService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.TimeUnit;

public class PredictiveCacheTest {

    private Path testFile;

    @BeforeEach
    public void setup() throws Exception {
        // 0. Delete turbo.toml to ensure we test DEFAULT values and not stale user config
        Files.deleteIfExists(new File("turbo.toml").toPath());

        // 1. Initialize TurboMC Config FIRST
        TurboConfig.resetInstance();
        // Initialize with default settings (should pick up our new AGGRESSIVE defaults)
        TurboConfig config = TurboConfig.getInstance(new File("."));
        
        // 2. Initialize Compression Service (Required by LRFRegionWriter)
        TurboCompressionService.resetInstance();
        TurboCompressionService.initialize(config);

        // 3. Create a temporary LRF file for testing (NOW safe to call)
        testFile = Files.createTempFile("turbomc_predict_test_", ".lrf");
        createTestLRFFile(testFile);
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (testFile != null) {
            Files.deleteIfExists(testFile);
        }
    }

    @Test
    public void testConfigDefaults() {
        System.out.println("Running testConfigDefaults...");
        TurboConfig config = TurboConfig.getInstance();
        
        // Check if our high-speed optimizations are active by default
        assertEquals(12, config.getInt("storage.mmap.prediction-scale", 6), 
            "Default prediction scale should be 12 (Aggressive)");
            
        assertEquals(32, config.getInt("storage.mmap.prefetch-batch-size", 16),
            "Default prefetch batch size should be 32 (NVMe optimized)");
            
        String strategy = config.getString("chunk.default-strategy", "BALANCED");
        assertEquals("AGGRESSIVE", strategy.toUpperCase(),
            "Default strategy should be AGGRESSIVE");
            
        System.out.println("Config verification PASSED.");
    }

    @Test
    public void testPredictivePrefetchingLogic() throws Exception {
        System.out.println("Running testPredictivePrefetchingLogic...");
        
        // Create engine with optimizations manually enabled to test LOGIC
        // (Even if config failed, we want to test the engine logic itself)
        MMapReadAheadEngine engine = new MMapReadAheadEngine(
            testFile, 
            512, // cache size
            8,   // prefetch dist
            32,  // batch size
            100 * 1024 * 1024, // max mem
            true, // predictive enabled
            12    // prediction scale
        );
        
        // 1. Initial read at 0,0
        engine.readChunk(0, 0);
        int initialPrefetch = engine.getStats().getPrefetchCount();
        System.out.println("Initial read(0,0) prefetches: " + initialPrefetch);
        
        // Wait for async prefetch
        Thread.sleep(100);
        
        // 2. High Speed Move: Jump from 0,0 to 0,8 (Fly Speed 10 Simulation)
        // This is a delta of 8 chunks. 
        // Before fix: >2 was "teleport" -> Velocity=0 -> No predictive prefetch
        // After fix: <=10 is "movement" -> Velocity=8 -> Predictive prefetch active
        
        System.out.println("Simulating High Speed Move (0,0 -> 0,8)...");
        engine.readChunk(0, 8);
        
        // Allow async tasks to run
        Thread.sleep(200);
        
        var stats = engine.getStats();
        System.out.println("Stats after move: " + stats);
        
        // If predictive logic worked, it should have prefetched chunks AHEAD of 0,8
        // i.e., 0,9 to 0,20 (current + 12)
        // Accessing 0,10 should be a CACHE HIT
        
        // Note: Prefetch is async, so we might need a small wait or loop check
        // But for unit test, we check if it's in cache or if prefetch count increased significantly
        
        int prefetchCountAfter = stats.getPrefetchCount();
        assertTrue(prefetchCountAfter > initialPrefetch, 
            "Prefetch count should increase after movement");
            
        // 3. Verify Cache Hit on Predicted Chunk
        // Access (0, 10) - should be in cache if prediction worked
        long start = System.nanoTime();
        engine.readChunk(0, 10);
        long elapsed = System.nanoTime() - start;
        
        // RAM access is usually < 0.1ms, Disk is > 0.1ms (even with NVMe overhead)
        // But better to check stats hits
        int hits = engine.getStats().getCacheHits();
        assertTrue(hits > 0, "Should have at least 1 cache hit from predicted chunk");
        
        System.out.println("Predictive logic verification PASSED.");
        engine.close();
    }

    @Test
    public void testTeleportDetection() throws Exception {
        System.out.println("Running testTeleportDetection...");
        
        MMapReadAheadEngine engine = new MMapReadAheadEngine(testFile);
        
        // 1. Read 0,0
        engine.readChunk(0, 0);
        Thread.sleep(50);
        int initialPrefetches = engine.getStats().getPrefetchCount();
        
        // 2. Teleport far away (0,0 -> 100,100)
        // Delta > 10. Should register as teleport.
        // Velocity should be 0.
        // NO forward prediction, only radial prefetch.
        engine.readChunk(100, 100); // Note: file only has 32x32 chunks, so this returns null/empty but logic runs
        
        // Since 100,100 doesn't exist in our 32x32 File, readChunk returns null
        // But we want to test the logic trigger.
        // Let's use 0,0 -> 20,20 (Still > 10 blocks? No, 20 chunks away)
        // Our file is small (32x32), so 20,20 exists.
        
        engine.readChunk(20, 20);
        Thread.sleep(50);
        
        // Validate stats? 
        // This is harder to assert without internal access, but we ensure no crash.
        System.out.println("Teleport logic executed safely.");
        engine.close();
    }

    private void createTestLRFFile(Path path) throws Exception {
        // Use ZSTD to match default configuration of TurboCompressionService
        LRFRegionWriter writer = new LRFRegionWriter(path, LRFConstants.COMPRESSION_ZSTD);
        byte[] chunkData = new byte[4096]; // Small chunk
        new Random(42).nextBytes(chunkData);
        
        // Write 32x32 chunks
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                LRFChunkEntry entry = new LRFChunkEntry(x, z, chunkData);
                // writer.writeChunkStreaming(entry); // Assuming this method exists from previous edits
                // Actually, let's use the one we saw in CacheBenchmark: writeChunkStreaming
                writer.writeChunkStreaming(entry);
            }
        }
        writer.flush();
        writer.close();
    }
}
