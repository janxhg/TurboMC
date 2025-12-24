package com.turbomc.storage.optimization;

import com.turbomc.storage.converter.RegionConverter;
import com.turbomc.storage.converter.MCAToLRFConverter;
import com.turbomc.storage.converter.StorageFormat;
import com.turbomc.storage.converter.ConversionMode;
import com.turbomc.storage.converter.TurboStorageConfig;

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
     * Enhanced with parallel processing and better error handling.
     * 
     * @param regionDir Region directory
     * @param targetFormat Target format (should be LRF)
     * @throws IOException if conversion fails
     */
    private static void migrateFullLRF(Path regionDir, StorageFormat targetFormat) throws IOException {
        System.out.println("[TurboMC][LRF] Starting FULL LRF conversion with enhanced processing...");
        
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
        
        // Enhanced converter with parallel processing
        RegionConverter converter = new RegionConverter(true);
        long startTime = System.currentTimeMillis();
        
        try {
            // Convert with optimized batch processing
            var result = converter.convertRegionDirectory(regionDir, regionDir, targetFormat);
            
            long endTime = System.currentTimeMillis();
            double durationSeconds = (endTime - startTime) / 1000.0;
            
            System.out.println("[TurboMC][LRF] FULL LRF conversion completed successfully!");
            System.out.println("[TurboMC][LRF] Converted " + totalMcaFiles + " files in " + 
                             String.format("%.2f", durationSeconds) + " seconds");
            System.out.println("[TurboMC][LRF] Average: " + 
                             String.format("%.2f", durationSeconds / totalMcaFiles) + " seconds per file");
            System.out.println("[TurboMC][LRF] Result: " + result.toString());
            
            // Verify conversion was successful
            verifyConversion(regionDir, totalMcaFiles);
            
        } catch (Exception e) {
            System.err.println("[TurboMC][LRF] FULL LRF conversion failed: " + e.getMessage());
            System.err.println("[TurboMC][LRF] Total MCA files to convert: " + totalMcaFiles);
            throw e;
        }
    }
    
    /**
     * Verify that conversion was successful by checking file counts.
     */
    private static void verifyConversion(Path regionDir, long expectedOriginalCount) throws IOException {
        long lrfCount;
        long mcaCount;

        try (Stream<Path> files = Files.list(regionDir)) {
            lrfCount = files
                .filter(Files::isRegularFile)
                .filter(file -> file.getFileName().toString().endsWith(".lrf"))
                .count();
        }

        try (Stream<Path> files = Files.list(regionDir)) {
            mcaCount = files
                .filter(Files::isRegularFile)
                .filter(file -> file.getFileName().toString().endsWith(".mca"))
                .count();
        }

        System.out.println("[TurboMC][LRF] Verification: " + lrfCount + " LRF files, " + 
                         mcaCount + " remaining MCA files");

        if (lrfCount < expectedOriginalCount) {
            System.err.println("[TurboMC][LRF] WARNING: Not all files were converted. " +
                             "Expected: " + expectedOriginalCount + ", Got: " + lrfCount);
        }

        if (mcaCount > 0) {
            System.err.println("[TurboMC][LRF] ERROR: MCA/LRF coexistence detected after FULL_LRF conversion. " +
                             "Remaining MCA files: " + mcaCount);
            throw new IOException("FULL_LRF conversion incomplete: " + mcaCount + " MCA files remain in " + regionDir);
        }
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
        
        try {
            // Use intelligent background scheduler
            BackgroundConversionScheduler scheduler = new BackgroundConversionScheduler(regionDir, targetFormat);
            scheduler.start();
            
            // Let the scheduler run in the background
            // It will automatically stop when all regions are converted
            System.out.println("[TurboMC][LRF] Background scheduler started. Conversion will proceed during idle time.");
            
            // Note: In a real implementation, you'd keep a reference to the scheduler
            // and stop it when the server shuts down
            
        } catch (Exception e) {
            System.err.println("[TurboMC][LRF] Failed to start background conversion: " + e.getMessage());
            throw e;
        }
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
        System.out.println("[TurboMC][LRF] ON-DEMAND mode active: regions will be converted lazily as chunks are accessed.");
        System.out.println("[TurboMC][LRF] Note: runtime per-chunk conversion is handled by storage hooks; no bulk conversion will be performed at startup.");
    }
}
