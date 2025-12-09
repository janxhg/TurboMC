package com.turbomc.storage.converter;

import com.turbomc.storage.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Converts Minecraft Anvil (.mca) region files to Linear Region Format (.lrf).
 * 
 * @author TurboMC
 * @version 1.0.0
 */
public class MCAToLRFConverter {
    
    private final boolean verbose;
    private long totalBytesRead;
    private long totalBytesWritten;
    private int totalChunksConverted;
    
    /**
     * Create a new MCA to LRF converter.
     * 
     * @param verbose Enable verbose logging
     */
    public MCAToLRFConverter(boolean verbose) {
        this.verbose = verbose;
        this.totalBytesRead = 0;
        this.totalBytesWritten = 0;
        this.totalChunksConverted = 0;
    }
    
    /**
     * Create converter with default settings.
     */
    public MCAToLRFConverter() {
        this(false);
    }
    
    /**
     * Convert a single MCA file to LRF.
     * 
     * @param mcaPath Path to .mca file
     * @param lrfPath Path to output .lrf file
     * @param compressionType Compression algorithm for LRF
     * @return Conversion statistics
     * @throws IOException if conversion fails
     */
    public ConversionResult convert(Path mcaPath, Path lrfPath, int compressionType) throws IOException {
        if (!Files.exists(mcaPath)) {
            throw new IOException("Source file does not exist: " + mcaPath);
        }
        
        if (!mcaPath.toString().endsWith(LRFConstants.MCA_EXTENSION)) {
            throw new IllegalArgumentException("Source must be .mca file: " + mcaPath);
        }
        
        long startTime = System.currentTimeMillis();
        
        if (verbose) {
            System.out.println("[TurboMC] Converting: " + mcaPath.getFileName() + " → " + lrfPath.getFileName());
        }
        
        // Read all chunks from MCA
        List<LRFChunkEntry> chunks;
        long mcaSize;
        
        try (AnvilRegionReader reader = new AnvilRegionReader(mcaPath)) {
            chunks = reader.readAllChunks();
            mcaSize = reader.getFileSize();
        }
        
        if (chunks.isEmpty()) {
            if (verbose) {
                System.out.println("[TurboMC] Skipping empty region: " + mcaPath.getFileName());
            }
            return new ConversionResult(mcaPath, lrfPath, 0, 0, 0, 0);
        }
        
        // Write chunks to LRF
        long lrfSize;
        
        try (LRFRegionWriter writer = new LRFRegionWriter(lrfPath, compressionType)) {
            for (LRFChunkEntry chunk : chunks) {
                writer.addChunk(chunk);
            }
            writer.flush();
            lrfSize = Files.size(lrfPath);
        }
        
        long elapsed = System.currentTimeMillis() - startTime;
        
        // Update totals
        totalBytesRead += mcaSize;
        totalBytesWritten += lrfSize;
        totalChunksConverted += chunks.size();
        
        ConversionResult result = new ConversionResult(
            mcaPath, lrfPath, chunks.size(), mcaSize, lrfSize, elapsed
        );
        
        if (verbose) {
            System.out.println(result);
        }
        
        return result;
    }
    
    /**
     * Convert with default LZ4 compression.
     */
    public ConversionResult convert(Path mcaPath, Path lrfPath) throws IOException {
        return convert(mcaPath, lrfPath, LRFConstants.COMPRESSION_LZ4);
    }
    
    /**
     * Convert a directory of MCA files to LRF.
     * 
     * @param sourceDir Directory containing .mca files
     * @param targetDir Directory for output .lrf files
     * @param compressionType Compression algorithm
     * @return Summary of all conversions
     * @throws IOException if conversion fails
     */
    public BatchConversionResult convertDirectory(Path sourceDir, Path targetDir, int compressionType) throws IOException {
        if (!Files.isDirectory(sourceDir)) {
            throw new IllegalArgumentException("Source must be a directory: " + sourceDir);
        }
        
        Files.createDirectories(targetDir);
        
        List<Path> mcaFiles = Files.walk(sourceDir)
            .filter(p -> p.toString().endsWith(LRFConstants.MCA_EXTENSION))
            .toList();
        
        if (mcaFiles.isEmpty()) {
            System.out.println("[TurboMC] No .mca files found in " + sourceDir);
            return new BatchConversionResult(0, 0, 0, 0L, 0L, 0L);
        }
        
        System.out.println("[TurboMC] Converting " + mcaFiles.size() + " region files...");
        
        long startTime = System.currentTimeMillis();
        int successful = 0;
        int failed = 0;
        
        for (Path mcaPath : mcaFiles) {
            try {
                String fileName = mcaPath.getFileName().toString();
                String lrfFileName = fileName.replace(LRFConstants.MCA_EXTENSION, LRFConstants.LRF_EXTENSION);
                Path lrfPath = targetDir.resolve(lrfFileName);
                
                convert(mcaPath, lrfPath, compressionType);
                successful++;
                
                // Progress indicator
                if (successful % 10 == 0) {
                    System.out.println("[TurboMC] Progress: " + successful + "/" + mcaFiles.size() + " files");
                }
                
            } catch (IOException e) {
                System.err.println("[TurboMC] Failed to convert " + mcaPath.getFileName() + ": " + e.getMessage());
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
     * Convert directory with default LZ4 compression.
     */
    public BatchConversionResult convertDirectory(Path sourceDir, Path targetDir) throws IOException {
        return convertDirectory(sourceDir, targetDir, LRFConstants.COMPRESSION_LZ4);
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
        
        public double getCompressionRatio() {
            return sourceSize > 0 ? (double) targetSize / sourceSize : 0;
        }
        
        public long getSavedBytes() {
            return sourceSize - targetSize;
        }
        
        @Override
        public String toString() {
            return String.format(
                "  %s → %s: %d chunks, %.2f KB → %.2f KB (%.1f%% size, saved %.2f KB) in %d ms",
                sourcePath.getFileName(),
                targetPath.getFileName(),
                chunkCount,
                sourceSize / 1024.0,
                targetSize / 1024.0,
                getCompressionRatio() * 100,
                getSavedBytes() / 1024.0,
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
        
        public double getCompressionRatio() {
            return totalBytesRead > 0 ? (double) totalBytesWritten / totalBytesRead : 0;
        }
        
        @Override
        public String toString() {
            return String.format(
                "  Converted: %d files (%d failed)\n" +
                "  Chunks: %d\n" +
                "  Size: %.2f MB → %.2f MB (%.1f%% of original)\n" +
                "  Saved: %.2f MB\n" +
                "  Time: %.2f seconds",
                successCount, failedCount,
                totalChunks,
                totalBytesRead / 1024.0 / 1024.0,
                totalBytesWritten / 1024.0 / 1024.0,
                getCompressionRatio() * 100,
                (totalBytesRead - totalBytesWritten) / 1024.0 / 1024.0,
                elapsedMs / 1000.0
            );
        }
    }
}
