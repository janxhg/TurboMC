package com.turbomc.storage.converter;

/**
 * Simple in-memory representation of TurboMC storage configuration.
 *
 * This class does not read files by itself; it is intended to be
 * populated from Paper/TurboMC configuration (YAML/TOML) and then
 * passed to components that need to know the desired storage format.
 */
public final class TurboStorageConfig {

    private final StorageFormat format;
    private final boolean autoMigrate;

    public TurboStorageConfig(StorageFormat format, boolean autoMigrate) {
        this.format = format == null ? StorageFormat.MCA : format;
        this.autoMigrate = autoMigrate;
    }

    public StorageFormat getFormat() {
        return this.format;
    }

    public boolean isAutoMigrate() {
        return this.autoMigrate;
    }

    /**
     * Convenience factory from raw config strings/flags.
     *
     * @param formatString value like "MCA" or "LRF" (case-insensitive)
     * @param autoMigrate whether automatic migration should run on startup
     */
    public static TurboStorageConfig fromRaw(String formatString, boolean autoMigrate) {
        StorageFormat format = StorageFormat.fromString(formatString);
        return new TurboStorageConfig(format, autoMigrate);
    }
}
