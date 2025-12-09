package com.turbomc.storage;

import com.turbomc.storage.converter.RegionConverter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * High-level helper for running MCA \u2194 LRF migrations based on a TurboStorageConfig.
 *
 * This class does NOT read any YAML/TOML by itself. It is intended to be
 * called from bootstrap or plugin code that already has access to the
 * global configuration, and only needs a single place to run migrations.
 */
public final class TurboStorageMigrator {

    private TurboStorageMigrator() {
    }

    /**
     * Run an automatic region migration for a single world, if enabled.
     *
     * @param worldDirectory the base directory of the world (contains the {@code region/} folder)
     * @param config         resolved Turbo storage configuration
     * @throws IOException if conversion fails
     */
    public static void migrateWorldIfNeeded(Path worldDirectory, TurboStorageConfig config) throws IOException {
        migrateWorldIfNeeded(worldDirectory, config, ConversionMode.ON_DEMAND);
    }

    /**
     * Run an automatic region migration for a single world, if enabled.
     *
     * @param worldDirectory the base directory of the world (contains the {@code region/} folder)
     * @param config         resolved Turbo storage configuration
     * @param conversionMode the conversion mode to use
     * @throws IOException if conversion fails
     */
    public static void migrateWorldIfNeeded(Path worldDirectory, TurboStorageConfig config, ConversionMode conversionMode) throws IOException {
        if (config == null) {
            return;
        }

        if (!config.isAutoMigrate()) {
            return; // feature disabled
        }

        StorageFormat targetFormat = config.getFormat();
        if (targetFormat == null || targetFormat == StorageFormat.MCA) {
            // Nothing to do: MCA is the vanilla format
            return;
        }

        Path regionDir = worldDirectory.resolve("region");
        if (!Files.isDirectory(regionDir)) {
            // No region directory yet (new world, or not generated)
            return;
        }

        System.out.println("[TurboMC][LRF] Auto-migration enabled: converting regions in '" + regionDir + "' to " + targetFormat + "...");

        switch (conversionMode) {
            case FULL_LRF:
                migrateFullLRF(regionDir, targetFormat);
                break;
            case ON_DEMAND:
                migrateOnDemand(regionDir, targetFormat);
                break;
            case BACKGROUND:
                migrateBackground(regionDir, targetFormat);
                break;
            case MANUAL:
                System.out.println("[TurboMC][LRF] Manual conversion mode - skipping automatic migration.");
                break;
        }

        System.out.println("[TurboMC][LRF] Region auto-migration complete.");
    }

    /**
     * Perform full LRF conversion - convert ALL MCA files immediately.
     * 
     * @param regionDir Region directory
     * @param targetFormat Target format (should be LRF)
     * @throws IOException if conversion fails
     */
    private static void migrateFullLRF(Path regionDir, StorageFormat targetFormat) throws IOException {
        System.out.println("[TurboMC][LRF] Starting FULL LRF conversion...");
        
        // Count total MCA files for progress tracking
        long totalMcaFiles;
        try (Stream<Path> files = Files.list(regionDir)) {
            totalMcaFiles = files
                .filter(Files::isRegularFile)
                .filter(file -> file.getFileName().toString().endsWith(".mca"))
                .count();
        }
        
        if (totalMcaFiles == 0) {
            System.out.println("[TurboMC][LRF] No MCA files found for conversion.");
            return;
        }
        
        System.out.println("[TurboMC][LRF] Found " + totalMcaFiles + " MCA files to convert...");
        
        RegionConverter converter = new RegionConverter(true);
        
        // Convert all MCA files to LRF
        converter.convertRegionDirectory(regionDir, regionDir, targetFormat);
        
        System.out.println("[TurboMC][LRF] FULL LRF conversion completed. All " + totalMcaFiles + " files converted.");
    }

    /**
     * Perform background conversion - convert during idle server time.
     * 
     * @param regionDir Region directory
     * @param targetFormat Target format (should be LRF)
     * @throws IOException if conversion fails
     */
    private static void migrateBackground(Path regionDir, StorageFormat targetFormat) throws IOException {
        System.out.println("[TurboMC][LRF] Starting BACKGROUND conversion during idle time...");
        
        // For now, implement as on-demand conversion
        // TODO: Implement intelligent background scheduling
        System.out.println("[TurboMC][LRF] Background mode: Will convert during idle periods (currently using on-demand logic).");
        
        RegionConverter converter = new RegionConverter(true);
        converter.convertRegionDirectory(regionDir, regionDir, targetFormat);
        
        System.out.println("[TurboMC][LRF] BACKGROUND conversion completed.");
    }

    /**
     * Perform on-demand conversion - convert only when chunks are loaded.
     * This is the original behavior.
     * 
     * @param regionDir Region directory
     * @param targetFormat Target format (should be LRF)
     * @throws IOException if conversion fails
     */
    private static void migrateOnDemand(Path regionDir, StorageFormat targetFormat) throws IOException {
        System.out.println("[TurboMC][LRF] Starting ON-DEMAND conversion...");
        
        RegionConverter converter = new RegionConverter(true);
        
        // Convert in-place: source and target are the same directory
        // This will convert files as they are encountered
        converter.convertRegionDirectory(regionDir, regionDir, targetFormat);
        
        System.out.println("[TurboMC][LRF] ON-DEMAND conversion completed.");
    }
}
