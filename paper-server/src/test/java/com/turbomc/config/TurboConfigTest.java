package com.turbomc.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tests for TurboConfig configuration system.
 */
public class TurboConfigTest {
    
    private Path testDir;
    
    @BeforeEach
    void setUp() throws IOException {
        testDir = Files.createTempDirectory("turbomc_config_test");
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
    void testTurboConfigCreation() {
        // Test that TurboConfig can be created
        assertDoesNotThrow(() -> {
            TurboConfig config = TurboConfig.getInstance(testDir.toFile());
            assertNotNull(config);
        });
    }
    
    @Test
    void testTurboConfigSingleton() {
        // Test singleton pattern
        TurboConfig config1 = TurboConfig.getInstance(testDir.toFile());
        TurboConfig config2 = TurboConfig.getInstance(testDir.toFile());
        
        assertNotNull(config1);
        assertSame(config1, config2);
    }
    
    @Test
    void testTurboConfigStaticMethods() {
        // Test static methods
        assertFalse(TurboConfig.isInitialized());
        
        // Initialize
        TurboConfig config = TurboConfig.getInstance(testDir.toFile());
        assertTrue(TurboConfig.isInitialized());
        
        // Test get() method
        TurboConfig config2 = TurboConfig.get();
        assertSame(config, config2);
    }
    
    @Test
    void testTurboConfigWithoutConfigFile() {
        // Test behavior when no config file exists
        assertDoesNotThrow(() -> {
            TurboConfig config = TurboConfig.getInstance(testDir.toFile());
            assertNotNull(config);
            // Should not throw even without config file
        });
    }
}
