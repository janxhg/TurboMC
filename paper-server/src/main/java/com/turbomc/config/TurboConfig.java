package com.turbomc.config;

import com.moandjiezana.toml.Toml;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * TurboMC configuration loader and manager.
 * Loads settings from turbo.toml configuration file.
 */
public class TurboConfig {
    private static TurboConfig instance;
    private final Toml toml;
    private final File configFile;
    
    private TurboConfig(File serverDirectory) {
        this.configFile = new File(serverDirectory, "turbo.toml");
        
        // Create default config if it doesn't exist
        if (!configFile.exists()) {
            createDefaultConfig();
        }
        
        this.toml = new Toml().read(configFile);
    }
    
    public static TurboConfig getInstance(File serverDirectory) {
        if (instance == null) {
            instance = new TurboConfig(serverDirectory);
        }
        return instance;
    }
    
    public static TurboConfig getInstance() {
        if (instance == null) {
            throw new IllegalStateException("TurboConfig not initialized! Call getInstance(File) first.");
        }
        return instance;
    }
    
    private void createDefaultConfig() {
        try {
            // Write default configuration
            String defaultConfig = """
                # TurboMC Configuration File
                # This file controls server-specific optimizations and features unique to TurboMC
                
                [compression]
                # Compression algorithm: "lz4" (faster) or "zlib" (vanilla compatible)
                algorithm = "lz4"
                
                # Compression level
                # - For LZ4: 1-17 (higher = more compression, slower)
                # - For Zlib: 1-9 (higher = more compression, slower)
                level = 6
                
                # Automatically migrate old zlib-compressed data to LZ4 format
                auto-migrate = true
                
                # Enable fallback to zlib if LZ4 decompression fails
                fallback-enabled = true
                
                [storage]
                # Region file format: "auto" (detect), "lrf" (optimized), or "mca" (vanilla)
                format = "auto"
                
                # Automatically convert MCA to LRF when loading chunks
                auto-convert = false
                
                # Conversion mode: "on-demand" (convert as chunks load), "background" (idle time), or "manual"
                conversion-mode = "on-demand"
                
                [version-control]
                # Minimum Minecraft version allowed to connect (e.g., "1.20.1")
                minimum-version = "1.20.1"
                
                # Maximum Minecraft version allowed (usually server version)
                maximum-version = "1.21.10"
                
                # List of specific versions to block (e.g., ["1.20.3", "1.20.4"])
                blocked-versions = []
                """;
            
            Files.writeString(configFile.toPath(), defaultConfig);
            System.out.println("[TurboMC] Created default configuration file: turbo.toml");
        } catch (IOException e) {
            System.err.println("[TurboMC] Failed to create default turbo.toml: " + e.getMessage());
        }
    }
    
    // === Compression Settings ===
    
    public String getCompressionAlgorithm() {
        return toml.getString("compression.algorithm", "lz4");
    }
    
    public int getCompressionLevel() {
        return toml.getLong("compression.level", 6L).intValue();
    }
    
    public boolean isAutoMigrateEnabled() {
        return toml.getBoolean("compression.auto-migrate", true);
    }
    
    public boolean isFallbackEnabled() {
        return toml.getBoolean("compression.fallback-enabled", true);
    }
    
    // === Storage Settings ===
    
    public String getStorageFormat() {
        return toml.getString("storage.format", "auto");
    }
    
    public boolean isAutoConvertEnabled() {
        return toml.getBoolean("storage.auto-convert", false);
    }
    
    public String getConversionMode() {
        return toml.getString("storage.conversion-mode", "on-demand");
    }
    
    // === Version Control Settings ===

    
    public String getMinimumVersion() {
        return toml.getString("version-control.minimum-version", "1.20.1");
    }
    
    public String getMaximumVersion() {
        return toml.getString("version-control.maximum-version", "1.21.10");
    }
    
    public List<String> getBlockedVersions() {
        return toml.getList("version-control.blocked-versions", List.of());
    }
    
    public void reload() {
        // Reload configuration from file
        Toml newToml = new Toml().read(configFile);
        // Note: To fully reload, we'd need to handle field replacement
        // For now, this is a placeholder for future hot-reload support
        System.out.println("[TurboMC] Configuration reloaded");
    }
}
