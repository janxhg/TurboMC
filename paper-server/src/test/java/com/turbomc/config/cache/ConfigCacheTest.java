package com.turbomc.config.cache;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class ConfigCacheTest {

    @TempDir
    Path tempDir;

    @Test
    public void testCacheCreationAndLoading() throws IOException {
        Path configFile = tempDir.resolve("paper-global.yml");
        Path cacheFile = tempDir.resolve("paper-global.yml.bin");
        
        // 1. Create source YAML
        String yamlContent = """
            turbo:
              storage:
                format: "lrf"
                auto-convert: true
              compression:
                algorithm: "zstd"
            """;
        Files.writeString(configFile, yamlContent);
        
        // 2. First Load (Should create cache)
        long start1 = System.nanoTime();
        Map<String, Object> data1 = ConfigCacheManager.loadWithCache(configFile);
        long time1 = System.nanoTime() - start1;
        
        Assertions.assertNotNull(data1);
        Assertions.assertTrue(Files.exists(cacheFile), "Cache file should be created");
        
        // Check data content
        Map<String, Object> turbo = (Map<String, Object>) data1.get("turbo");
        Map<String, Object> storage = (Map<String, Object>) turbo.get("storage");
        Assertions.assertEquals("lrf", storage.get("format"));
        
        // 3. Second Load (Should use cache)
        long start2 = System.nanoTime();
        Map<String, Object> data2 = ConfigCacheManager.loadWithCache(configFile);
        long time2 = System.nanoTime() - start2;
        
        Assertions.assertEquals(data1, data2);
        
        System.out.println("First Load Time (YAML+CacheWrite): " + time1 / 1000 + "us");
        System.out.println("Second Load Time (BinRead):       " + time2 / 1000 + "us");
        // Usually faster, not strictly asserting time due to JIT variance in small tests
    }
    
    @Test
    public void testCacheInvalidation() throws IOException {
        Path configFile = tempDir.resolve("config.yml");
        Path cacheFile = tempDir.resolve("config.yml.bin");
        
        // 1. Initial Content
        Files.writeString(configFile, "key: value1");
        ConfigCacheManager.loadWithCache(configFile);
        
        // 2. Modify Content
        Files.writeString(configFile, "key: value2");
        
        // 3. Load (Should detect change and reload from YAML, updating cache)
        Map<String, Object> data = ConfigCacheManager.loadWithCache(configFile);
        
        Assertions.assertEquals("value2", data.get("key"), "Should load updated value");
    }
}
