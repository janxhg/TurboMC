package com.turbomc.config;

import com.moandjiezana.toml.Toml;
import com.turbomc.storage.StorageFormat;
import com.turbomc.storage.TurboStorageConfig;
import com.turbomc.storage.TurboStorageMigrator;
import com.turbomc.storage.ConversionMode;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import org.yaml.snakeyaml.Yaml;

/**
 * TurboMC configuration loader and manager.
 * Loads settings from turbo.toml configuration file.
 */
public class TurboConfig {
    private static TurboConfig instance;
    private final Toml toml;
    private final File configFile;
    private final File serverDirectory;
    
    private TurboConfig(File serverDirectory) {
        this.configFile = new File(serverDirectory, "turbo.toml");
        this.serverDirectory = serverDirectory;
        
        // Try to load from turbo.toml first, fallback to paper-global.yml
        if (configFile.exists()) {
            this.toml = new Toml().read(configFile);
            System.out.println("[TurboMC][CFG] Loaded configuration from turbo.toml");
        } else {
            // Fallback to YAML
            this.toml = loadFromYaml();
            System.out.println("[TurboMC][CFG] Loaded configuration from paper-global.yml (turbo.toml not found)");
        }
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
                format = "lrf"
                
                # Automatically convert MCA to LRF when loading chunks
                auto-convert = true
                
                # Conversion mode: "on-demand" (convert as chunks load), "background" (idle time), "full-lrf" (convert all at startup), or "manual"
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
            System.out.println("[TurboMC][CFG] Created default turbo.toml with production defaults.");
        } catch (IOException e) {
            System.err.println("[TurboMC] Failed to create default turbo.toml: " + e.getMessage());
        }
    }

    private Toml loadFromYaml() {
        try {
            Path configDir = serverDirectory.toPath().resolve("config");
            Path globalYml = configDir.resolve("paper-global.yml");
            
            if (!Files.exists(globalYml)) {
                // Create default TOML since YAML doesn't exist either
                createDefaultConfig();
                return new Toml().read(configFile);
            }
            
            // Read YAML and extract turbo section
            Yaml yaml = new Yaml();
            Map<String, Object> data = yaml.load(Files.readString(globalYml));
            Map<String, Object> turboSection = (Map<String, Object>) data.get("turbo");
            
            if (turboSection == null) {
                System.err.println("[TurboMC][CFG] No 'turbo' section found in paper-global.yml. Using defaults.");
                createDefaultConfig();
                return new Toml().read(configFile);
            }
            
            // Convert YAML structure to TOML-like map
            Map<String, Object> tomlMap = new HashMap<>();
            
            // Compression section
            Map<String, Object> compression = (Map<String, Object>) turboSection.get("compression");
            if (compression != null) {
                tomlMap.put("compression", compression);
            }
            
            // Storage section
            Map<String, Object> storage = (Map<String, Object>) turboSection.get("storage");
            if (storage != null) {
                tomlMap.put("storage", storage);
            }
            
            // Version control section
            Map<String, Object> versionControl = (Map<String, Object>) turboSection.get("version-control");
            if (versionControl != null) {
                tomlMap.put("version-control", versionControl);
            }
            
            // Create a temporary TOML from the map - convert to TOML string format
            StringBuilder tomlBuilder = new StringBuilder();
            if (tomlMap.containsKey("compression")) {
                Map<String, Object> comp = (Map<String, Object>) tomlMap.get("compression");
                tomlBuilder.append("[compression]\n");
                for (Map.Entry<String, Object> entry : comp.entrySet()) {
                    tomlBuilder.append(entry.getKey()).append(" = ").append(entry.getValue()).append("\n");
                }
            }
            if (tomlMap.containsKey("storage")) {
                Map<String, Object> storageSection = (Map<String, Object>) tomlMap.get("storage");
                tomlBuilder.append("[storage]\n");
                for (Map.Entry<String, Object> entry : storageSection.entrySet()) {
                    tomlBuilder.append(entry.getKey()).append(" = ").append(entry.getValue()).append("\n");
                }
            }
            if (tomlMap.containsKey("version-control")) {
                Map<String, Object> version = (Map<String, Object>) tomlMap.get("version-control");
                tomlBuilder.append("[version-control]\n");
                for (Map.Entry<String, Object> entry : version.entrySet()) {
                    tomlBuilder.append(entry.getKey()).append(" = ").append(entry.getValue()).append("\n");
                }
            }
            return new Toml().read(tomlBuilder.toString());
            
        } catch (Exception e) {
            System.err.println("[TurboMC][CFG] Failed to load from paper-global.yml: " + e.getMessage());
            createDefaultConfig();
            return new Toml().read(configFile);
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
    
    /**
     * Get the conversion mode as a ConversionMode enum.
     * 
     * @return Conversion mode enum (defaults to ON_DEMAND)
     */
    public ConversionMode getConversionModeEnum() {
        String modeStr = getConversionMode();
        return ConversionMode.fromString(modeStr);
    }
    
    /**
     * Get the storage configuration as TurboStorageConfig.
     * Maps turbo.toml [storage] section to TurboStorageConfig.
     */
    public TurboStorageConfig getStorageConfig() {
        String formatStr = getStorageFormat();
        boolean autoMigrate = isAutoConvertEnabled();
        
        // Map "auto" to MCA for now (can be enhanced to detect actual format)
        StorageFormat format = "auto".equalsIgnoreCase(formatStr) ? StorageFormat.MCA : 
                              StorageFormat.fromString(formatStr);
        
        return new TurboStorageConfig(format, autoMigrate);
    }
    
    /**
     * Run world region migration if auto-convert is enabled.
     * Should be called during server startup before worlds are loaded.
     */
    public void migrateWorldRegionsIfNeeded(Path worldDirectory) {
        try {
            TurboStorageConfig storageCfg = getStorageConfig();
            TurboStorageMigrator.migrateWorldIfNeeded(worldDirectory, storageCfg);
        } catch (IOException e) {
            System.err.println("[TurboMC] Failed to migrate world regions: " + e.getMessage());
            e.printStackTrace();
        }
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
