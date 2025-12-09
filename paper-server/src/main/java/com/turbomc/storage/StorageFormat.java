package com.turbomc.storage;

import java.nio.file.Path;

/**
 * Supported world storage formats for region data.
 * MCA = vanilla Anvil region files (.mca)
 * LRF = TurboMC Linear Region Format (.lrf)
 */
public enum StorageFormat {

    MCA,
    LRF;

    /**
     * Parse a storage format from a case-insensitive string.
     * Falls back to MCA if the value is null or unrecognized.
     */
    public static StorageFormat fromString(String value) {
        if (value == null) {
            return MCA;
        }
        return switch (value.trim().toUpperCase()) {
            case "LRF" -> LRF;
            case "MCA" -> MCA;
            default -> MCA;
        };
    }

    /**
     * Get the default file extension for this storage format.
     */
    public String getRegionExtension() {
        return switch (this) {
            case MCA -> LRFConstants.MCA_EXTENSION;
            case LRF -> LRFConstants.LRF_EXTENSION;
        };
    }

    /**
     * Detect storage format from a region file path by extension.
     */
    public static StorageFormat detect(Path path) {
        if (path == null) {
            return MCA;
        }
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(LRFConstants.LRF_EXTENSION)) {
            return LRF;
        }
        if (name.endsWith(LRFConstants.MCA_EXTENSION)) {
            return MCA;
        }
        return MCA;
    }
}
