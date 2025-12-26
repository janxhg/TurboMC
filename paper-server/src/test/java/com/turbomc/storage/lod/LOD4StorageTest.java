package com.turbomc.storage.lod;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class LOD4StorageTest {

    private Path testDir;
    private GlobalIndexManager manager;

    @BeforeEach
    void setUp() throws IOException {
        testDir = Files.createTempDirectory("lod4_storage_test");
        manager = GlobalIndexManager.getInstance();
        // Since GlobalIndexManager is a singleton and stays between tests, 
        // we might want a reset but it doesn't have one.
        // We'll use unique world names to avoid interference.
    }

    @AfterEach
    void tearDown() throws IOException {
        if (Files.exists(testDir)) {
            Files.walk(testDir).sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        }
    }

    @Test
    void testPackingLogic() {
        System.out.println("=== LOD4 PACKING LOGIC TEST ===");
        
        // [Generated: 1 bit] [Height: 4 bits] [BiomeCategory: 3 bits]
        
        // 1. Generated, Height 160 (160/16 = 10), Biome 5
        // Expected: (1 << 7) | (10 << 3) | 5 = 128 | 80 | 5 = 213
        byte p1 = GlobalIndexManager.pack(true, 160, 5);
        assertEquals((byte) 213, p1);
        
        // 2. Not generated, Height 32 (32/16 = 2), Biome 1
        // Expected: (0 << 7) | (2 << 3) | 1 = 16 | 1 = 17
        byte p2 = GlobalIndexManager.pack(false, 32, 1);
        assertEquals((byte) 17, p2);
        
        // 3. Clamping: Height 300 (300/16 = 18 -> 15), Biome 10 (-> 7)
        // Expected: (1 << 7) | (15 << 3) | 7 = 128 | 120 | 7 = 255
        byte p3 = GlobalIndexManager.pack(true, 300, 10);
        assertEquals((byte) 255, p3);

        System.out.println("✓ Packing logic verified");
    }

    @Test
    void testPersistence() throws IOException {
        System.out.println("=== LOD4 PERSISTENCE TEST ===");
        
        String worldName = "test_world_" + System.currentTimeMillis();
        Path worldPath = testDir.resolve(worldName);
        Files.createDirectories(worldPath);
        
        manager.initialize(worldName, worldPath);
        
        // Update some chunks
        manager.updateChunkInfo(worldName, 0, 0, GlobalIndexManager.pack(true, 64, 1));
        manager.updateChunkInfo(worldName, 10, -5, GlobalIndexManager.pack(true, 128, 2));
        
        // Save
        manager.saveIndex(worldName);
        
        Path indexPath = worldPath.resolve("turbo_index.twi");
        assertTrue(Files.exists(indexPath), "Index file should be created");
        assertTrue(Files.size(indexPath) > 12, "Index file should contain data");
        
        // We can't easily reset the singleton manager's internal map without a reset method,
        // but we can test that it reports the same info.
        // To really test loading, we would need to initialize a NEW manager instance or have a reset.
        
        assertEquals(GlobalIndexManager.pack(true, 64, 1), manager.getChunkInfo(worldName, 0, 0));
        assertEquals(GlobalIndexManager.pack(true, 128, 2), manager.getChunkInfo(worldName, 10, -5));
        
        System.out.println("✓ Persistence verified");
    }

    @Test
    void testReloading() throws Exception {
        System.out.println("=== LOD4 RELOADING TEST ===");
        
        String worldName = "reload_world";
        Path worldPath = testDir.resolve(worldName);
        Files.createDirectories(worldPath);
        
        // Manually write a mock .twi file to test loading
        Path indexPath = worldPath.resolve("turbo_index.twi");
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(12 + 9 * 2);
        buffer.putInt(0x54574900); // Magic
        buffer.putInt(1);          // Version
        buffer.putInt(2);          // Count
        
        // Entry 1: (0,0) -> 0xAA
        buffer.putInt(0);
        buffer.putInt(0);
        buffer.put((byte) 0xAA);
        
        // Entry 2: (5,5) -> 0xBB
        buffer.putInt(5);
        buffer.putInt(5);
        buffer.put((byte) 0xBB);
        
        buffer.flip();
        Files.write(indexPath, buffer.array());
        
        // Initialize manager - should load our mock file
        manager.initialize(worldName, worldPath);
        
        assertEquals((byte) 0xAA, manager.getChunkInfo(worldName, 0, 0), "Reloaded data mismatch for 0,0");
        assertEquals((byte) 0xBB, manager.getChunkInfo(worldName, 5, 5), "Reloaded data mismatch for 5,5");
        
        System.out.println("✓ Reloading verified");
    }
}
