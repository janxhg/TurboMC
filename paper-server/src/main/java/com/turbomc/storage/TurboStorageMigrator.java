package com.turbomc.storage;

import com.turbomc.storage.converter.RegionConverter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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

        RegionConverter converter = new RegionConverter(true);
        // Convert in-place: source and target are the same directory
        converter.convertRegionDirectory(regionDir, regionDir, targetFormat);

        System.out.println("[TurboMC][LRF] Region auto-migration complete.");
    }
}
