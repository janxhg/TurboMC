package com.turbomc.storage.integrity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * Tests for ValidationUtils - validation utilities for storage operations.
 */
public class ChunkIntegrityTest {
    
    private Path testDir;
    private Random random;
    
    @BeforeEach
    void setUp() throws IOException {
        testDir = Files.createTempDirectory("turbomc_integrity_test");
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
    void testValidationUtilsClass() {
        // Test ValidationUtils is a utility class
        assertDoesNotThrow(() -> {
            // Should be able to call static methods
            ValidationUtils.validateChunkCoordinates(0, 0);
        });
    }
    
    @Test
    void testChunkCoordinatesValidation() {
        // Test valid chunk coordinates
        assertDoesNotThrow(() -> {
            ValidationUtils.validateChunkCoordinates(0, 0);
            ValidationUtils.validateChunkCoordinates(100, -100);
            ValidationUtils.validateChunkCoordinates(-5000, 5000);
        });
        
        // Test invalid chunk coordinates (too large)
        assertThrows(IllegalArgumentException.class, () -> {
            ValidationUtils.validateChunkCoordinates(30_000_001, 0);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            ValidationUtils.validateChunkCoordinates(0, -30_000_001);
        });
    }
    
    @Test
    void testRegionCoordinatesValidation() {
        // Test valid region coordinates
        assertDoesNotThrow(() -> {
            ValidationUtils.validateRegionCoordinates(0, 0);
            ValidationUtils.validateRegionCoordinates(10, -10);
            ValidationUtils.validateRegionCoordinates(-100, 100);
        });
        
        // Test invalid region coordinates (too large)
        assertThrows(IllegalArgumentException.class, () -> {
            ValidationUtils.validateRegionCoordinates(3_000_001, 0);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            ValidationUtils.validateRegionCoordinates(0, -3_000_001);
        });
    }
    
    @Test
    void testFilePathValidation() {
        // Test valid file paths
        assertDoesNotThrow(() -> {
            ValidationUtils.validateFilePath(testDir.resolve("r.0.0.mca"));
            ValidationUtils.validateFilePath(testDir.resolve("r.1.-1.lrf"));
        });
        
        // Test invalid file paths
        assertThrows(IllegalArgumentException.class, () -> {
            ValidationUtils.validateFilePath(null);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            ValidationUtils.validateFilePath(testDir.resolve("../invalid.txt"));
        });
    }
    
    @Test
    void testArrayValidation() {
        // Test valid arrays
        assertDoesNotThrow(() -> {
            byte[] validArray = new byte[1024];
            ValidationUtils.validateByteArray(validArray, 1024);
        });
        
        // Test invalid arrays
        assertThrows(IllegalArgumentException.class, () -> {
            ValidationUtils.validateByteArray(null, 1024);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            byte[] smallArray = new byte[512];
            ValidationUtils.validateByteArray(smallArray, 1024);
        });
    }
    
    @Test
    void testStringLengthValidation() {
        // Test valid strings
        assertDoesNotThrow(() -> {
            ValidationUtils.validateStringLength("valid", 1, 10);
            ValidationUtils.validateStringLength("", 0, 10);
        });
        
        // Test invalid strings
        assertThrows(IllegalArgumentException.class, () -> {
            ValidationUtils.validateStringLength(null, 1, 10);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            ValidationUtils.validateStringLength("toolong", 1, 5);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            ValidationUtils.validateStringLength("short", 10, 20);
        });
    }
    
    @Test
    void testRangeValidation() {
        // Test valid ranges
        assertDoesNotThrow(() -> {
            ValidationUtils.validateRange(5, 0, 10);
            ValidationUtils.validateRange(0, 0, 10);
            ValidationUtils.validateRange(10, 0, 10);
        });
        
        // Test invalid ranges
        assertThrows(IllegalArgumentException.class, () -> {
            ValidationUtils.validateRange(-1, 0, 10);
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            ValidationUtils.validateRange(11, 0, 10);
        });
    }
}
