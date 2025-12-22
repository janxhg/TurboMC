package com.turbomc.storage.converter;

import com.turbomc.storage.lrf.LRFConstants;
import com.turbomc.storage.converter.StorageFormat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * High-level region converter that auto-detects format and uses appropriate converter.
 * Supports both MCA→LRF and LRF→MCA conversions.
 * 
 * @author TurboMC
 * @version 1.0.0
 */
public class RegionConverter {
    
    private final MCAToLRFConverter mcaToLrf;
    private final LRFToMCAConverter lrfToMca;
    
    /**
     * Create a new region converter.
     * 
     * @param verbose Enable verbose logging
     */
    public RegionConverter(boolean verbose) {
        this.mcaToLrf = new MCAToLRFConverter(verbose);
        this.lrfToMca = new LRFToMCAConverter(verbose);
    }
    
    /**
     * Create converter with default settings.
     */
    public RegionConverter() {
        this(true);
    }
    
    /**
     * Convert a region file, auto-detecting the format.
     * 
     * @param sourcePath Source file (.mca or .lrf)
     * @param targetPath Target file (.lrf or .mca)
     * @return Conversion result
     * @throws IOException if conversion fails
     */
    public Object convert(Path sourcePath, Path targetPath) throws IOException {
        FormatType sourceFormat = detectFormat(sourcePath);
        FormatType targetFormat = detectFormat(targetPath);
        
        if (sourceFormat == FormatType.UNKNOWN) {
            throw new IllegalArgumentException("Unknown source format: " + sourcePath);
        }
        
        if (targetFormat == FormatType.UNKNOWN) {
            throw new IllegalArgumentException("Unknown target format: " + targetPath);
        }
        
        if (sourceFormat == targetFormat) {
            throw new IllegalArgumentException("Source and target formats are the same");
        }
        
        if (sourceFormat == FormatType.MCA && targetFormat == FormatType.LRF) {
            return mcaToLrf.convert(sourcePath, targetPath);
        } else if (sourceFormat == FormatType.LRF && targetFormat == FormatType.MCA) {
            return lrfToMca.convert(sourcePath, targetPath);
        } else {
            throw new IllegalArgumentException("Unsupported conversion");
        }
    }
    
    /**
     * Convert a directory of region files.
     * 
     * @param sourceDir Source directory
     * @param targetDir Target directory
     * @param targetFormat Target format
     * @return Batch conversion result
     * @throws IOException if conversion fails
     */
    public Object convertDirectory(Path sourceDir, Path targetDir, FormatType targetFormat) throws IOException {
        if (targetFormat == FormatType.LRF) {
            return mcaToLrf.convertDirectory(sourceDir, targetDir);
        } else if (targetFormat == FormatType.MCA) {
            return lrfToMca.convertDirectory(sourceDir, targetDir);
        } else {
            throw new IllegalArgumentException("Target format must be MCA or LRF");
        }
    }
    
    /**
     * Detect region file format from extension.
     * 
     * @param path File path
     * @return Format type
     */
    public static FormatType detectFormat(Path path) {
        String fileName = path.getFileName().toString().toLowerCase();
        
        if (fileName.endsWith(LRFConstants.MCA_EXTENSION)) {
            return FormatType.MCA;
        } else if (fileName.endsWith(LRFConstants.LRF_EXTENSION)) {
            return FormatType.LRF;
        } else {
            return FormatType.UNKNOWN;
        }
    }
    
    /**
     * Check if file is a region file (MCA or LRF).
     * 
     * @param path File path
     * @return True if file is a region file
     */
    public static boolean isRegionFile(Path path) {
        return detectFormat(path) != FormatType.UNKNOWN;
    }

    /**
     * Convert all region files inside a given directory to the desired storage format.
     * <p>
     * This is a convenience wrapper that translates the public {@link StorageFormat}
     * into the internal {@link FormatType} enum used by the low-level converters.
     * </p>
     *
     * @param sourceDir Existing directory containing .mca or .lrf region files
     * @param targetDir Directory where converted regions will be written
     * @param targetFormat Desired storage format (MCA or LRF)
     * @return Batch conversion result from the underlying converter
     * @throws IOException if conversion fails
     */
    public Object convertRegionDirectory(Path sourceDir, Path targetDir, StorageFormat targetFormat) throws IOException {
        if (targetFormat == null) {
            targetFormat = StorageFormat.MCA;
        }

        FormatType internalTarget = switch (targetFormat) {
            case MCA -> FormatType.MCA;
            case LRF -> FormatType.LRF;
        };

        return convertDirectory(sourceDir, targetDir, internalTarget);
    }
    
    /**
     * Validate that conversion is possible.
     * 
     * @param sourcePath Source file
     * @param targetPath Target file
     * @throws IOException if validation fails
     */
    public static void validateConversion(Path sourcePath, Path targetPath) throws IOException {
        if (!Files.exists(sourcePath)) {
            throw new IOException("Source file does not exist: " + sourcePath);
        }
        
        if (!Files.isRegularFile(sourcePath)) {
            throw new IOException("Source is not a file: " + sourcePath);
        }
        
        FormatType sourceFormat = detectFormat(sourcePath);
        FormatType targetFormat = detectFormat(targetPath);
        
        if (sourceFormat == FormatType.UNKNOWN) {
            throw new IllegalArgumentException("Unknown source format: " + sourcePath);
        }
        
        if (targetFormat == FormatType.UNKNOWN) {
            throw new IllegalArgumentException("Unknown target format: " + targetPath);
        }
        
        if (sourceFormat == targetFormat) {
            throw new IllegalArgumentException("Source and target formats must be different");
        }
    }
    
    /**
     * Region file format types.
     */
    public enum FormatType {
        MCA,     // Minecraft Anvil format
        LRF,     // Linear Region Format
        UNKNOWN  // Unknown or unsupported
    }
}
