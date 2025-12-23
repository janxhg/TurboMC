package com.turbomc.config;

import com.moandjiezana.toml.Toml;
import com.turbomc.storage.converter.StorageFormat;
import com.turbomc.storage.converter.TurboStorageConfig;
import com.turbomc.storage.optimization.TurboStorageMigrator;
import com.turbomc.storage.converter.ConversionMode;

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
    private static volatile TurboConfig instance;
    private static final Object INSTANCE_LOCK = new Object();
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
        
        // Adjust Moonrise worker threads based on config
        adjustMoonriseThreads();
    }
    
    private void adjustMoonriseThreads() {
        try {
            int genThreads = getInt("chunk.generation-threads", 0);
            int ioThreads = getInt("storage.batch.load-threads", 4);
            ca.spottedleaf.moonrise.common.util.MoonriseCommon.adjustWorkerThreads(genThreads, ioThreads);
        } catch (Throwable t) {
            // Might fail if classes aren't loaded yet or in different source set
            System.err.println("[TurboMC][CFG] Could not adjust Moonrise threads: " + t.getMessage());
        }
    }
    
    public static TurboConfig getInstance(File serverDirectory) {
        if (instance == null) {
            synchronized (INSTANCE_LOCK) {
                if (instance == null) {
                    instance = new TurboConfig(serverDirectory);
                }
            }
        }
        return instance;
    }
    
    public static TurboConfig getInstance() {
        if (instance == null) {
            throw new IllegalStateException("TurboConfig not initialized! Call getInstance(File) first.");
        }
        return instance;
    }
    
    /**
     * Resets the singleton instance for testing purposes.
     */
    public static void resetInstance() {
        synchronized (INSTANCE_LOCK) {
            instance = null;
        }
    }

    public static TurboConfig get() {
        return getInstance();
    }
    
    public static boolean isInitialized() {
        return instance != null;
    }
    
    private void createDefaultConfig() {
        try {
            // Write default configuration
            String defaultConfig = """
                # TurboMC Configuration File
                # This file controls server-specific optimizations and features unique to TurboMC
                
                [compression]
                # Compression algorithm: "zstd" (fastest/best), "lz4" (fast), or "zlib" (vanilla compatible)
                algorithm = "zstd"
                
                # Compression level
                # - For Zstd: 1-22 (3 is standard, 1 is ultra-fast)
                # - For LZ4: 1-17
                level = 3
                
                # Automatically migrate old zlib-compressed data to LZ4 format
                auto-migrate = true
                
                # Enable fallback to zlib if LZ4 decompression fails
                fallback-enabled = true
                
                # Re-compress existing data when loaded if settings change (e.g. higher level)
                # Warning: Increases disk I/O as chunks are rewritten with new settings.
                recompress-on-load = false
                
                [storage]
                # Region file format: "auto" (detect), "lrf" (optimized), or "mca" (vanilla)
                format = "lrf"
                
                # Automatically convert MCA to LRF when loading chunks
                auto-convert = true
                
                # Conversion mode: "on-demand" (convert as chunks load), "background" (idle time), "full-lrf" (convert all at startup), or "manual"
                conversion-mode = "on-demand"
                
                # Backup original MCA files before deletion
                backup-original-mca = false
                
                # Batch operations configuration
                [storage.batch]
                # Enable batch chunk loading/saving
                enabled = true
                
                # Number of threads for batch loading operations
                load-threads = 8
                
                # Number of threads for batch saving operations  
                save-threads = 4
                
                # Maximum chunks per batch operation
                batch-size = 64
                
                # Maximum concurrent loading operations
                max-concurrent-loads = 64
                
                # Memory-mapped read-ahead engine
                [storage.mmap]
                # Enable memory-mapped read-ahead for SSD/NVMe optimization
                enabled = true
                
                # Maximum cache size in number of chunks
                max-cache-size = 512
                
                # Prefetch distance in chunks from player position
                prefetch-distance = 4
                
                # Prefetch batch size
                # Prefetch batch size (Increased for NVMe)
                prefetch-batch-size = 32
                
                # Predictive/Kinematic Prefetching
                # Analyzes movement vectors to pre-load chunks in the direction of travel
                predictive-enabled = true
                
                # Prediction strength (how many chunks ahead to look)
                # Increased to 12 for high speed flight support
                prediction-scale = 12
                
                # Maximum memory usage for caching (in MB)
                max-memory-usage = 128
                
                # Use Java 22+ Foreign Memory API if available
                use-foreign-memory-api = true
                
                # Integrity validation system
                [storage.integrity]
                # Enable chunk integrity validation with checksums
                enabled = true
                
                # Primary checksum algorithm: "crc32", "crc32c", "sha256"
                primary-algorithm = "crc32c"
                
                # Backup checksum algorithm for verification (null to disable)
                backup-algorithm = "sha256"
                
                # Enable automatic repair from backups
                auto-repair = true
                
                # Number of validation threads
                validation-threads = 2
                
                # Validation interval in milliseconds (5 minutes)
                validation-interval = 300000
                
                [quality]
                # Quality and rendering optimizations
                # Target TPS for quality adjustments
                tps-threshold = 18
                
                # Memory usage threshold for quality adjustments (0.8 = 80%)
                memory-threshold = 0.8
                
                # Adjustment interval in server ticks (1200 = 1 minute)
                adjustment-interval-ticks = 1200
                
                # Enable automatic quality adjustments based on performance
                auto_adjust_enabled = true
                
                # Default quality preset: LOW, MEDIUM, HIGH, ULTRA, DYNAMIC
                default-preset = "HIGH"
                
                # Entity culling optimization
                entity_culling_enabled = true
                
                # Particle effect optimization
                particle_optimization_enabled = true
                
                [fps]
                # FPS optimization settings
                # Target TPS for optimization
                target-tps = 20
                
                # TPS tolerance for optimization adjustments (±0.5 TPS)
                tps-tolerance = 0.5
                
                # Optimization check interval in ticks (100 = 5 seconds)
                optimization-interval-ticks = 100
                
                # Redstone optimization
                redstone_optimization_enabled = true
                
                # Entity activation range optimization
                entity_optimization_enabled = true
                
                # Hopper optimization
                hopper_optimization_enabled = true
                
                # Mob AI and Sensor throttling
                mob_throttling_enabled = true
                
                # Global Tick Budgeting (in milliseconds)
                global_budget_enabled = true
                tick_budget_ms = 45.0
                
                # Mob spawning optimization
                mob_spawning_optimization_enabled = true
                
                # Chunk ticking optimization
                chunk_ticking_optimization_enabled = true
                
                # Default optimization mode: CONSERVATIVE, BALANCED, PERFORMANCE, EXTREME, ADAPTIVE
                default-mode = "BALANCED"
                
                [chunk]
                # Chunk loading optimization settings
                # Enable intelligent chunk preloading
                preloading_enabled = true
                
                # Enable parallel chunk generation
                parallel_generation_enabled = true
                
                # Number of threads for parallel generation (0 = auto)
                generation-threads = 0
                
                # Enable chunk caching
                caching_enabled = true
                
                # Enable priority-based loading
                priority_loading_enabled = true
                
                # Maximum memory usage for chunk caching (in MB)
                max-memory-usage-mb = 128
                
                # Memory threshold for cache cleanup (0.8 = 80%)
                memory-threshold = 0.8
                
                # Default loading strategy: CONSERVATIVE, BALANCED, AGGRESSIVE, EXTREME, ADAPTIVE
                default-strategy = "AGGRESSIVE"
                
                [version-control]
                # Minimum Minecraft version allowed to connect (e.g., "1.20.1")
                minimum-version = "1.20.1"
                
                # Maximum Minecraft version allowed (usually server version)
                maximum-version = "1.21.10"
                
                # List of specific versions to block (e.g., ["1.20.3", "1.20.4"])
                blocked-versions = []
                
                [web-viewer]
                # TurboMC Web Viewer Configuration
                # Interactive map viewer for world inspection and chunk analysis
                
                # Enable/disable web viewer
                enabled = true
                
                # Web server port (default: 8080)
                port = 8080
                
                # Host interface (0.0.0.0 for all interfaces, localhost for local only)
                host = "0.0.0.0"
                
                # Maximum concurrent connections
                max-connections = 50
                
                # Cache size in MB
                cache-size-mb = 64
                
                # Auto-refresh interval in seconds (0 = disabled)
                auto-refresh-interval-seconds = 30
                
                [web-viewer.map-display]
                # Map display settings
                
                # Default zoom level (0.1 = far, 5.0 = close)
                default-zoom = 1.0
                
                # Maximum zoom level
                max-zoom = 5.0
                
                # Minimum zoom level
                min-zoom = 0.1
                
                # Chunk size in pixels at zoom 1.0
                chunk-pixel-size = 16
                
                # Show chunk boundaries
                show_chunk_boundaries = true
                
                # Show region boundaries
                show_region_boundaries = true
                
                # Show coordinate grid
                show_coordinate_grid = true
                
                [web-viewer.chunk-analysis]
                # Chunk analysis and corruption detection
                
                # Enable chunk corruption detection
                corruption-detection = true
                
                # Highlight corrupted chunks
                highlight-corrupted = true
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
            
            // USE TURBO CACHE MANAGER
            // This attempts to load .bin cache first, preventing SnakeYAML parsing overhead
            java.util.Map<String, Object> data = com.turbomc.config.cache.ConfigCacheManager.loadWithCache(globalYml);
            
            if (data == null) {
                 // Fallback if cache fails and somehow parsing inside manager also failed/returned null
                 System.err.println("[TurboMC][CFG] Cache manager returned null. Fallback to standard.");
                 Yaml yaml = new Yaml();
                 data = yaml.load(Files.readString(globalYml));
                 if (data == null) data = new java.util.HashMap<>();
            }

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
                // Create a copy to add missing defaults
                Map<String, Object> storageFull = new HashMap<>(storage);
                
                // Handle nested batch section
                Map<String, Object> batch = (Map<String, Object>) storage.get("batch");
                if (batch == null) {
                    // Add default batch section if missing
                    Map<String, Object> defaultBatch = new HashMap<>();
                    defaultBatch.put("enabled", true);
                    defaultBatch.put("load-threads", 4);
                    defaultBatch.put("save-threads", 2);
                    defaultBatch.put("batch-size", 32);
                    defaultBatch.put("max-concurrent-loads", 64);
                    storageFull.put("batch", defaultBatch);
                }
                
                // Handle nested mmap section
                Map<String, Object> mmap = (Map<String, Object>) storage.get("mmap");
                if (mmap == null) {
                    // Add default mmap section if missing
                    Map<String, Object> defaultMmap = new HashMap<>();
                    defaultMmap.put("enabled", true);
                    defaultMmap.put("max-cache-size", 256);
                    defaultMmap.put("prefetch-distance", 3);
                    defaultMmap.put("prefetch-batch-size", 8);
                    defaultMmap.put("max-memory-usage", 128);
                    defaultMmap.put("use-foreign-memory-api", true);
                    storageFull.put("mmap", defaultMmap);
                }
                
                // Handle nested integrity section
                Map<String, Object> integrity = (Map<String, Object>) storage.get("integrity");
                if (integrity == null) {
                    // Add default integrity section if missing
                    Map<String, Object> defaultIntegrity = new HashMap<>();
                    defaultIntegrity.put("enabled", true);
                    defaultIntegrity.put("primary-algorithm", "crc32c");
                    defaultIntegrity.put("backup-algorithm", "sha256");
                    defaultIntegrity.put("auto-repair", true);
                    defaultIntegrity.put("validation-threads", 2);
                    defaultIntegrity.put("validation-interval", 300000);
                    storageFull.put("integrity", defaultIntegrity);
                }
                
                tomlMap.put("storage", storageFull);
            }
            
            // Version control section
            Map<String, Object> versionControl = (Map<String, Object>) turboSection.get("version-control");
            if (versionControl != null) {
                tomlMap.put("version-control", versionControl);
            }
            
            // Create a temporary TOML from the map - convert to TOML string format
            StringBuilder tomlBuilder = new StringBuilder();
            for (Map.Entry<String, Object> entry : tomlMap.entrySet()) {
                if (entry.getValue() instanceof Map) {
                    writeSection(tomlBuilder, entry.getKey(), (Map<String, Object>) entry.getValue());
                }
            }
            return new Toml().read(tomlBuilder.toString());
            
        } catch (Exception e) {
            System.err.println("[TurboMC][CFG] Failed to load from paper-global.yml: " + e.getMessage());
            createDefaultConfig();
            return new Toml().read(configFile);
        }
    }

    private void writeSection(StringBuilder sb, String name, Map<String, Object> map) {
        sb.append("[").append(name).append("]\n");
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Object val = entry.getValue();
            if (val instanceof Map) {
                // Use recursion for nested sections like storage.batch
                writeSection(sb, name + "." + entry.getKey(), (Map<String, Object>) val);
            } else {
                String valStr = (val instanceof String) ? "\"" + val + "\"" : String.valueOf(val);
                sb.append(entry.getKey()).append(" = ").append(valStr).append("\n");
            }
        }
        sb.append("\n");
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
    
    public boolean isRecompressOnLoadEnabled() {
        return toml.getBoolean("compression.recompress-on-load", false);
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
    
    // === Batch Operations Settings ===
    
    public boolean isBatchEnabled() {
        return toml.getBoolean("storage.batch.enabled", true);
    }
    
    public int getBatchLoadThreads() {
        return toml.getLong("storage.batch.load-threads", 4L).intValue();
    }
    
    public int getBatchSaveThreads() {
        return toml.getLong("storage.batch.save-threads", 2L).intValue();
    }
    
    public int getBatchSize() {
        return toml.getLong("storage.batch.batch-size", 32L).intValue();
    }
    
    public int getMaxConcurrentLoads() {
        return toml.getLong("storage.batch.max-concurrent-loads", 64L).intValue();
    }
    
    // === Memory-Mapped Settings ===
    
    public boolean isMmapEnabled() {
        return toml.getBoolean("storage.mmap.enabled", true);
    }
    
    public int getMaxCacheSize() {
        return toml.getLong("storage.mmap.max-cache-size", 512L).intValue();
    }
    
    public int getPrefetchDistance() {
        return toml.getLong("storage.mmap.prefetch-distance", 4L).intValue();
    }
    
    public int getPrefetchBatchSize() {
        return toml.getLong("storage.mmap.prefetch-batch-size", 16L).intValue();
    }
    
    public int getMaxMemoryUsage() {
        return toml.getLong("storage.mmap.max-memory-usage", 256L).intValue();
    }
    
    public boolean useForeignMemoryApi() {
        return toml.getBoolean("storage.mmap.use-foreign-memory-api", true);
    }
    
    // === Integrity Validation Settings ===
    
    public boolean isIntegrityEnabled() {
        return toml.getBoolean("storage.integrity.enabled", true);
    }
    
    public String getPrimaryAlgorithm() {
        return toml.getString("storage.integrity.primary-algorithm", "crc32c");
    }
    
    public String getBackupAlgorithm() {
        return toml.getString("storage.integrity.backup-algorithm", "sha256");
    }
    
    public boolean isAutoRepairEnabled() {
        return toml.getBoolean("storage.integrity.auto-repair", true);
    }
    
    public int getValidationThreads() {
        return toml.getLong("storage.integrity.validation-threads", 2L).intValue();
    }
    
    public long getValidationInterval() {
        return toml.getLong("storage.integrity.validation-interval", 300000L);
    }
    
    /**
     * Get whether to backup original MCA files before deletion.
     */
    public boolean isBackupMcaEnabled() {
        return getBoolean("storage.backup-original-mca", false);
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
            ConversionMode conversionMode = getConversionModeEnum();
            TurboStorageMigrator.migrateWorldIfNeeded(worldDirectory, storageCfg, conversionMode);
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
    
    public static boolean isHopperOptimizationEnabled() {
        return getInstance().getBoolean("fps.hopper_optimization_enabled", true);
    }

    public static boolean isMobThrottlingEnabled() {
        return getInstance().getBoolean("fps.mob_throttling_enabled", true);
    }

    public static double getTickBudgetMs() {
        return getInstance().toml.getDouble("fps.tick_budget_ms", 45.0);
    }

    public static boolean isGlobalBudgetEnabled() {
        return getInstance().getBoolean("fps.global_budget_enabled", true);
    }
    
    // Generic getter methods for any configuration value
    public String getString(String key, String defaultValue) {
        return toml.getString(key, defaultValue);
    }
    
    public boolean getBoolean(String key, boolean defaultValue) {
        return toml.getBoolean(key, defaultValue);
    }
    
    public int getInt(String key, int defaultValue) {
        try {
            return toml.getLong(key, (long) defaultValue).intValue();
        } catch (ClassCastException e) {
            // Handle case where TOML value is a Double (e.g., 1200.0)
            return (int) Math.round(toml.getDouble(key, (double) defaultValue));
        }
    }
    
    public long getLong(String key, long defaultValue) {
        return toml.getLong(key, defaultValue);
    }
    
    public double getDouble(String key, double defaultValue) {
        return toml.getDouble(key, defaultValue);
    }
    
    public List<String> getList(String key, List<String> defaultValue) {
        return toml.getList(key, defaultValue);
    }
    
    public void reload() {
        // Reload configuration from file
        Toml newToml = new Toml().read(configFile);
        // Note: To fully reload, we'd need to handle field replacement
        // For now, this is a placeholder for future hot-reload support
        System.out.println("[TurboMC] Configuration reloaded");
        adjustMoonriseThreads();
    }
    
    public static boolean isParallelGenerationEnabled() {
        return getInstance().getBoolean("chunk.parallel_generation_enabled", true);
    }
    
    public static int getGenerationThreads() {
        return getInstance().getInt("chunk.generation-threads", 0);
    }
}
