package com.turbomc.storage;

import com.turbomc.config.TurboConfig;
import com.turbomc.compression.TurboCompressionService;
import com.turbomc.storage.optimization.TurboLRFBootstrap;
import com.turbomc.storage.lrf.LRFRegionReader;
import com.turbomc.storage.lrf.LRFRegionWriter;
import com.turbomc.storage.lrf.LRFChunkEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * Integration tests for LRF storage system.
 * Tests real-world scenarios and migration processes using actual TurboMC services.
 */
public class LRFIntegrationTest {
    
    private Path testDir;
    private Path worldDir;
    private TestDataGenerator dataGenerator;
    private Random random;
    private TurboCompressionService compressionService;
    
    @BeforeEach
    void setUp() throws IOException {
        testDir = Files.createTempDirectory("turbomc_integration_test");
        worldDir = testDir.resolve("world");
        Files.createDirectories(worldDir);
        dataGenerator = new TestDataGenerator(54321);
        random = new Random(54321);
        
        // Initialize Config and Compression Service with MMap DISABLED
        Path configFile = testDir.resolve("turbo.toml");
        String configContent = """
            [storage.mmap]
              enabled = false
            """;
        Files.writeString(configFile, configContent);
        
        com.turbomc.config.TurboConfig.resetInstance();
        TurboConfig config = TurboConfig.getInstance(testDir.toFile());
        TurboCompressionService.initialize(config);
        compressionService = TurboCompressionService.getInstance();
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
    void testTurboConfigLoading() throws IOException {
        System.out.println("=== TURBO CONFIG LOADING TEST ===");
        
        // Reset configuration singleton
        TurboConfig.resetInstance();
        
        // TurboLRFBootstrap.initialize() in setUp() creates turbo.toml
        // We MUST delete it to test the YAML fallback mechanism
        Files.deleteIfExists(testDir.resolve("turbo.toml"));
        
        Path configDir = testDir.resolve("config");
        Files.createDirectories(configDir);
        Path globalYml = configDir.resolve("paper-global.yml");
        
        String yamlContent = """
            turbo:
              compression:
                algorithm: zstd
                level: 3
              storage:
                auto-convert: true
                format: lrf
            """;
        
        Files.writeString(globalYml, yamlContent);
        
        // Load config - should now fall back to YAML
        TurboConfig config = TurboConfig.getInstance(testDir.toFile());
        
        assertEquals("zstd", config.getCompressionAlgorithm(), "Should load ZSTD algorithm from YAML");
        assertEquals(3, config.getCompressionLevel(), "Should load level 3 from YAML");
        
        System.out.println("✓ Configuration loaded successfully from YAML");
    }
    
    @Test
    @org.junit.jupiter.api.Disabled("TestDataGenerator creates empty MCA files - needs fix")
    void testWorldMigrationIntegrity() throws IOException {
        System.out.println("=== WORLD MIGRATION INTEGRITY TEST ===");
        
        // 1. Create realistic MCA data
        Path regionDir = worldDir.resolve("region");
        Files.createDirectories(regionDir);
        Path mcaFile = regionDir.resolve("r.0.0.mca");
        dataGenerator.createValidMCAFile(mcaFile, 10); // Create 10 valid chunks
        
        // 2. Initialize TurboMC with full-lrf conversion mode for the test
        Path configFile = testDir.resolve("turbo.toml");
        String configContent = """
            [storage]
              auto-convert = true
              format = "lrf"
              conversion-mode = "full-lrf"
            [storage.mmap]
              enabled = false
            """;
        Files.writeString(configFile, configContent);
        com.turbomc.config.TurboConfig.resetInstance();
        
        TurboLRFBootstrap.initialize(testDir);
        
        // 3. Perform Migration
        TurboLRFBootstrap.migrateWorldIfNeeded(worldDir);
        
        // 4. Verify LRF exists and is valid
        Path lrfFile = regionDir.resolve("r.0.0.lrf");
        assertTrue(Files.exists(lrfFile), "LRF file should exist after migration");
        
        // 5. Verify Data Integrity using LRFRegionReader
        try (LRFRegionReader reader = new LRFRegionReader(lrfFile)) {
            for (int i = 0; i < 10; i++) {
                int cx = i % 32;
                int cz = i / 32;
                assertTrue(reader.hasChunk(cx, cz), "Chunk " + cx + "," + cz + " should exist in LRF");
                
                LRFChunkEntry entry = reader.readChunk(cx, cz);
                assertNotNull(entry, "Chunk data should not be null");
                assertTrue(entry.getData().length > 0, "Chunk data should not be empty");
            }
        }
        
        System.out.println("✓ World migration integrity verified");
    }
    
    @Test
    void testCompressionServiceFallback() throws Exception {
        System.out.println("=== COMPRESSION SERVICE FALLBACK TEST ===");
        
        byte[] testData = new byte[1024 * 50]; // 50KB
        random.nextBytes(testData);
        
        // Test primary compression
        byte[] compressed = compressionService.compress(testData);
        assertNotNull(compressed);
        assertTrue(compressed.length > 0);
        
        // Test decompression
        byte[] decompressed = compressionService.decompress(compressed);
        assertArrayEquals(testData, decompressed, "Decompressed data must match original");
        
        System.out.println("✓ Compression and decompression working with real service");
    }
    
    @Test
    void testConcurrentLRFAccess() throws InterruptedException, IOException {
        System.out.println("=== CONCURRENT LRF ACCESS TEST ===");
        
        Path regionFile = worldDir.resolve("region").resolve("r.concurrent.lrf");
        Files.createDirectories(regionFile.getParent());
        
        // Prepare some data
        try (LRFRegionWriter writer = new LRFRegionWriter(regionFile)) {
            for (int i = 0; i < 20; i++) {
                writer.addChunk(new LRFChunkEntry(i, 0, ("Thread safe data " + i).getBytes()));
            }
        }
        
        int threadCount = 10;
        Thread[] threads = new Thread[threadCount];
        final boolean[] failed = {false};
        
        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                try {
                    // Each thread has its own reader to simulate multiple accesses
                    // (Though LRFRegionReader should ideally be used per-thread if it's not thread-safe)
                    try (LRFRegionReader reader = new LRFRegionReader(regionFile)) {
                        for (int j = 0; j < 100; j++) {
                            int chunkIdx = random.nextInt(20);
                            LRFChunkEntry entry = reader.readChunk(chunkIdx, 0);
                            if (entry == null || !new String(entry.getData()).startsWith("Thread safe data")) {
                                failed[0] = true;
                            }
                        }
                    }
                } catch (IOException e) {
                    failed[0] = true;
                }
            });
        }
        
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();
        
        assertFalse(failed[0], "Concurrent access should not fail or return corrupted data");
        System.out.println("✓ Concurrent LRF access successful");
    }
}
