package com.turbomc.storage.converter;

import com.turbomc.storage.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Converts Linear Region Format (.lrf) files back to Minecraft Anvil (.mca).
 * Used for rollback or compatibility purposes.
 * 
 * @author TurboMC
 * @version 1.0.0
 */
public class LRFToMCAConverter {
    
    private final boolean verbose;
    private long totalBytesRead;
    private long totalBytesWritten;
    private int totalChunksConverted;
    
    /**
     * Create a new LRF to MCA converter.
     * 
     * @param verbose Enable verbose logging
     */
    public LRFToMCAConverter(boolean verbose) {
        this.verbose = verbose;
        this.totalBytesRead = 0;
        this.totalBytesWritten = 0;
        this.totalChunksConverted = 0;
    }
    
    /**
     * Create converter with default settings.
     */
    public LRFToMCAConverter() {
        this(false);
    }
    
    /**
     * Convert a single LRF file to MCA.
     * 
     * @param lrfPath Path to .lrf file
     * @param mcaPath Path to output .mca file
     * @return Conversion statistics
     * @throws IOException if conversion fails
     */
    public ConversionResult convert(Path lrfPath, Path mcaPath) throws IOException {
        if (!Files.exists(lrfPath)) {
            throw new IOException("Source file does not exist: " + lrfPath);
        }
        
        if (!lrfPath.toString().endsWith(LRFConstants.LRF_EXTENSION)) {
            throw new IllegalArgumentException("Source must be .lrf file: " + lrfPath);
        }
        
        long startTime = System.currentTimeMillis();
        
        if (verbose) {
            System.out.println("[TurboMC] Converting: " + lrfPath.getFileName() + " → " + mcaPath.getFileName());
        }
        
        // Read all chunks from LRF
        List<LRFChunkEntry> chunks;
        long lrfSize;
        
        try (LRFRegionReader reader = new LRFRegionReader(lrfPath)) {
            chunks = reader.readAllChunks();
            lrfSize = reader.getFileSize();
        }
        
        if (chunks.isEmpty()) {
            if (verbose) {
                System.out.println("[TurboMC] Skipping empty region: " + lrfPath.getFileName());
            }
            return new ConversionResult(lrfPath, mcaPath, 0, 0, 0, 0);
        }
        
        // Write chunks to MCA
        long mcaSize;
        
        try (AnvilRegionWriter writer = new AnvilRegionWriter(mcaPath)) {
            for (LRFChunkEntry chunk : chunks) {
                writer.addChunk(chunk);
            }
            writer.flush();
            mcaSize = Files.size(mcaPath);
        }
        
        long elapsed = System.currentTimeMillis() - startTime;
        
        // Update totals
        totalBytesRead += lrfSize;
        totalBytesWritten += mcaSize;
        totalChunksConverted += chunks.size();
        
        ConversionResult result = new ConversionResult(
            lrfPath, mcaPath, chunks.size(), lrfSize, mcaSize, elapsed
        );
        
        if (verbose) {
            System.out.println(result);
        }
        
        return result;
    }
    
    /**
     * Convert a directory of LRF files to MCA.
     * 
     * @param sourceDir Directory containing .lrf files
     * @param targetDir Directory for output .mca files
     * @return Summary of all conversions
     * @throws IOException if conversion fails
     */
    public BatchConversionResult convertDirectory(Path sourceDir, Path targetDir) throws IOException {
        if (!Files.isDirectory(sourceDir)) {
            throw new IllegalArgumentException("Source must be a directory: " + sourceDir);
        }
        
        Files.createDirectories(targetDir);
        
        List<Path> lrfFiles = Files.walk(sourceDir)
            .filter(p -> p.toString().endsWith(LRFConstants.LRF_EXTENSION))
            .toList();
        
        if (lrfFiles.isEmpty()) {
            System.out.println("[TurboMC] No .lrf files found in " + sourceDir);
            return new BatchConversionResult(0, 0, 0, 0, 0, 0);
        }
        
        System.out.println("[TurboMC] Converting " + lrfFiles.size() + " region files...");
        
        long startTime = System.currentTimeMillis();
        int successful = 0;
        int failed = 0;
        
        for (Path lrfPath : lrfFiles) {
            try {
                String fileName = lrfPath.getFileName().toString();
                String mcaFileName = fileName.replace(LRFConstants.LRF_EXTENSION, LRFConstants.MCA_EXTENSION);
                Path mcaPath = targetDir.resolve(mcaFileName);
                
                convert(lrfPath, mcaPath);
                successful++;
                
                // Progress indicator
                if (successful % 10 == 0) {
                    System.out.println("[TurboMC] Progress: " + successful + "/" + lrfFiles.size() + " files");
                }
                
            } catch (IOException e) {
                System.err.println("[TurboMC] Failed to convert " + lrfPath.getFileName() + ": " + e.getMessage());
                failed++;
            }
        }
        
        long elapsed = System.currentTimeMillis() - startTime;
        
        BatchConversionResult result = new BatchConversionResult(
            successful, failed, totalChunksConverted, totalBytesRead, totalBytesWritten, elapsed
        );
        
        System.out.println("[TurboMC] Batch conversion complete:");
        System.out.println(result);
        
        return result;
    }
    
    /**
     * Result of a single file conversion.
     */
    public static class ConversionResult {
        public final Path sourcePath;
        public final Path targetPath;
        public final int chunkCount;
        public final long sourceSize;
        public final long targetSize;
        public final long elapsedMs;
        
        public ConversionResult(Path sourcePath, Path targetPath, int chunkCount,
                               long sourceSize, long targetSize, long elapsedMs) {
            this.sourcePath = sourcePath;
            this.targetPath = targetPath;
            this.chunkCount = chunkCount;
            this.sourceSize = sourceSize;
            this.targetSize = targetSize;
            this.elapsedMs = elapsedMs;
        }
        
        public double getSizeRatio() {
            return sourceSize > 0 ? (double) targetSize / sourceSize : 0;
        }
        
        @Override
        public String toString() {
            return String.format(
                "  %s → %s: %d chunks, %.2f KB → %.2f KB (%.1f%% size) in %d ms",
                sourcePath.getFileName(),
                targetPath.getFileName(),
                chunkCount,
                sourceSize / 1024.0,
                targetSize / 1024.0,
                getSizeRatio() * 100,
                elapsedMs
            );
        }
    }
    
    /**
     * Result of batch directory conversion.
     */
    public static class BatchConversionResult {
        public final int successCount;
        public final int failedCount;
        public final int totalChunks;
        public final long totalBytesRead;
        public final long totalBytesWritten;
        public final long elapsedMs;
        
        public BatchConversionResult(int successCount, int failedCount, int totalChunks,
                                    long totalBytesRead, long totalBytesWritten, long elapsedMs) {
            this.successCount = successCount;
            this.failedCount = failedCount;
            this.totalChunks = totalChunks;
            this.totalBytesRead = totalBytesRead;
            this.totalBytesWritten = totalBytesWritten;
            this.elapsedMs = elapsedMs;
        }
        
        @Override
        public String toString() {
            return String.format(
                "  Converted: %d files (%d failed)\n" +
                "  Chunks: %d\n" +
                "  Size: %.2f MB → %.2f MB\n" +
                "  Time: %.2f seconds",
                successCount, failedCount,
                totalChunks,
                totalBytesRead / 1024.0 / 1024.0,
                totalBytesWritten / 1024.0 / 1024.0,
                elapsedMs / 1000.0
            );
        }
    }
}
