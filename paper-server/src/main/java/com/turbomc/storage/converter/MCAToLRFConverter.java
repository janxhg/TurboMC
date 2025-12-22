package com.turbomc.storage.converter;

import com.turbomc.config.TurboConfig;
import com.turbomc.compression.TurboCompressionService;
import com.turbomc.storage.optimization.AnvilRegionReader;
import com.turbomc.storage.lrf.LRFChunkEntry;
import com.turbomc.storage.lrf.LRFConstants;
import com.turbomc.storage.lrf.LRFRegionWriter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

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
    
    // Performance optimizations
    private final AtomicLong conversionTime;
    private final AtomicLong compressionTime;
    private final int batchSize;
    private final ByteBuffer ioBuffer;
    
    // Streaming support
    private boolean streamingMode;
    
    // Validation and recovery
    private final ChunkDataValidator validator;
    private final ConversionRecoveryManager recoveryManager;
    
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
        
        // Initialize performance components
        this.conversionTime = new AtomicLong(0);
        this.compressionTime = new AtomicLong(0);
        this.batchSize = LRFConstants.BATCH_SIZE;
        this.ioBuffer = ByteBuffer.allocate(LRFConstants.STREAM_BUFFER_SIZE);
        this.streamingMode = false;
        
        // Initialize validation and recovery
        this.validator = new ChunkDataValidator();
        this.recoveryManager = new ConversionRecoveryManager(true, true);
    }
    
    /**
     * Create converter with default settings.
     */
    public MCAToLRFConverter() {
        this(false);
    }
    
    /**
     * Enable streaming mode for large conversions.
     * Processes chunks in batches to reduce memory usage.
     */
    public void enableStreaming() {
        this.streamingMode = true;
        if (verbose) {
            System.out.println("[TurboMC] Enabled streaming mode for MCA→LRF conversion");
        }
    }
    
    /**
     * Convert a single MCA file to LRF with streaming support.
     * 
     * @param mcaPath Path to .mca file
     * @param lrfPath Path to output .lrf file
     * @param compressionType Compression algorithm for LRF
     * @return Conversion statistics
     * @throws IOException if conversion fails
     */
    public ConversionResult convert(Path mcaPath, Path lrfPath, int compressionType) throws IOException {
        long startTime = System.currentTimeMillis();
        long conversionStart = System.nanoTime();
        
        if (!Files.exists(mcaPath)) {
            throw new IOException("Source file does not exist: " + mcaPath);
        }
        
        if (!mcaPath.toString().endsWith(LRFConstants.MCA_EXTENSION)) {
            throw new IllegalArgumentException("Source must be .mca file: " + mcaPath);
        }
        
        if (verbose) {
            System.out.println("[TurboMC] Converting: " + mcaPath.getFileName() + " → " + lrfPath.getFileName());
        }
        
        if (streamingMode) {
            return convertStreaming(mcaPath, lrfPath, compressionType, startTime, conversionStart);
        } else {
            return convertBatch(mcaPath, lrfPath, compressionType, startTime, conversionStart);
        }
    }
    /**
     * Convert in streaming mode - processes chunks in batches.
     */
    private ConversionResult convertStreaming(Path mcaPath, Path lrfPath, int compressionType,
                                           long startTime, long conversionStart) throws IOException {
        long mcaSize = Files.size(mcaPath);
        int chunksConverted = 0;
        long lrfSize = 0;
        
        // Create backup of original file
        Path backupPath = recoveryManager.createBackup(mcaPath);
        
        try (AnvilRegionReader reader = new AnvilRegionReader(mcaPath);
             LRFRegionWriter writer = new LRFRegionWriter(lrfPath, compressionType)) {
            
            writer.enableStreaming();
            
            // Reset validation for new conversion
            validator.reset();
            
            // Process chunks in batches
            List<LRFChunkEntry> batch = new ArrayList<>(batchSize);
            
            for (int x = 0; x < LRFConstants.REGION_SIZE; x++) {
                for (int z = 0; z < LRFConstants.REGION_SIZE; z++) {
                    if (reader.hasChunk(x, z)) {
                        LRFChunkEntry chunk = reader.readChunk(x, z);
                        if (chunk != null) {
                            // Validate chunk before adding to batch
                            if (validator.validateChunk(chunk)) {
                                batch.add(chunk);
                                
                                // Process batch when full
                                if (batch.size() >= batchSize) {
                                    for (LRFChunkEntry batchChunk : batch) {
                                        writer.addChunk(batchChunk);
                                    }
                                    chunksConverted += batch.size();
                                    batch.clear();
                                    
                                    // Progress indicator
                                    if (verbose && chunksConverted % 50 == 0) {
                                        System.out.println("[TurboMC] Streaming progress: " + chunksConverted + " chunks");
                                    }
                                }
                            } else {
                                System.err.println("[TurboMC] Skipping invalid chunk at " + x + "," + z);
                            }
                        }
                    }
                }
            }
            
            // Process remaining chunks
            for (LRFChunkEntry remainingChunk : batch) {
                writer.addChunk(remainingChunk);
            }
            chunksConverted += batch.size();
            
            writer.flush();
            lrfSize = Files.size(lrfPath);
        }
        
        // Validate conversion result
        boolean conversionValid = recoveryManager.validateConversion(lrfPath, RegionConverter.FormatType.LRF);
        if (!conversionValid) {
            System.err.println("[TurboMC] Conversion validation failed, attempting recovery...");
            recoveryManager.recoverFromFailure(lrfPath, mcaPath);
            throw new IOException("Conversion validation failed for " + mcaPath.getFileName());
        }
        
        // Update statistics
        totalBytesRead += mcaSize;
        totalBytesWritten += lrfSize;
        totalChunksConverted += chunksConverted;
        conversionTime.addAndGet(System.nanoTime() - conversionStart);
        
        // Handle original file cleanup
        handleOriginalFile(mcaPath, lrfSize, chunksConverted);
        
        long elapsed = System.currentTimeMillis() - startTime;
        
        ConversionResult result = new ConversionResult(
            mcaPath, lrfPath, chunksConverted, mcaSize, lrfSize, elapsed
        );
        
        if (verbose) {
            System.out.println(result);
        }
        
        return result;
    }
    
    /**
     * Convert in batch mode - original behavior.
     */
    private ConversionResult convertBatch(Path mcaPath, Path lrfPath, int compressionType,
                                       long startTime, long conversionStart) throws IOException {
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
        
        // Update statistics
        totalBytesRead += mcaSize;
        totalBytesWritten += lrfSize;
        totalChunksConverted += chunks.size();
        conversionTime.addAndGet(System.nanoTime() - conversionStart);
        
        // Handle original file cleanup
        handleOriginalFile(mcaPath, lrfSize, chunks.size());
        
        long elapsed = System.currentTimeMillis() - startTime;
        
        ConversionResult result = new ConversionResult(
            mcaPath, lrfPath, chunks.size(), mcaSize, lrfSize, elapsed
        );
        
        if (verbose) {
            System.out.println(result);
        }
        
        return result;
    }
    
    /**
     * Handle cleanup of original MCA file.
     */
    private void handleOriginalFile(Path mcaPath, long lrfSize, int chunkCount) {
        if (lrfSize > 0 && chunkCount > 0) {
            try {
                // Check if backup is enabled
                boolean shouldBackup = false;
                try {
                    TurboConfig config = TurboConfig.getInstance();
                    shouldBackup = config.isBackupMcaEnabled();
                } catch (Exception e) {
                    shouldBackup = false;
                }
                
                if (shouldBackup) {
                    // Create backup directory
                    Path backupDir = mcaPath.getParent().resolve("backup_mca");
                    Files.createDirectories(backupDir);
                    Path backupPath = backupDir.resolve(mcaPath.getFileName());
                    
                    // Move file to backup
                    Files.move(mcaPath, backupPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    if (verbose) {
                        System.out.println("[TurboMC] Backed up original: " + mcaPath.getFileName() + " → backup_mca/");
                    }
                } else {
                    // Delete directly
                    Files.delete(mcaPath);
                    if (verbose) {
                        System.out.println("[TurboMC] Removed original: " + mcaPath.getFileName());
                    }
                }
            } catch (IOException e) {
                System.err.println("[TurboMC] Warning: Failed to delete original .mca file: " + e.getMessage());
            }
        }
    }
    
    /**
     * Convert with default LZ4 compression.
     */
    public ConversionResult convert(Path mcaPath, Path lrfPath) throws IOException {
        return convert(mcaPath, lrfPath, LRFConstants.COMPRESSION_LZ4);
    }
    
    /**
     * Get conversion statistics.
     */
    public ConversionStats getConversionStats() {
        return new ConversionStats(
            totalChunksConverted,
            totalBytesRead,
            totalBytesWritten,
            conversionTime.get()
        );
    }
    
    /**
     * Conversion statistics holder.
     */
    public static class ConversionStats {
        public final int totalChunks;
        public final long totalBytesRead;
        public final long totalBytesWritten;
        public final long totalConversionTime;
        
        public ConversionStats(int chunks, long bytesRead, long bytesWritten, long time) {
            this.totalChunks = chunks;
            this.totalBytesRead = bytesRead;
            this.totalBytesWritten = bytesWritten;
            this.totalConversionTime = time;
        }
        
        public double getCompressionRatio() {
            return totalBytesRead > 0 ? (double) totalBytesWritten / totalBytesRead : 0;
        }
        
        public double getAvgConversionTime() {
            return totalChunks > 0 ? (double) totalConversionTime / totalChunks / 1_000_000 : 0;
        }
        
        @Override
        public String toString() {
            return String.format("ConversionStats{chunks=%d, read=%.1fMB, wrote=%.1fMB, ratio=%.1f%%, avgTime=%.2fms}",
                    totalChunks, 
                    totalBytesRead / 1024.0 / 1024.0,
                    totalBytesWritten / 1024.0 / 1024.0,
                    getCompressionRatio() * 100,
                    getAvgConversionTime());
        }
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
                
            } catch (Exception e) {
                System.err.println("[TurboMC] Failed to convert " + mcaPath.getFileName() + ": " + e.getMessage());
                e.printStackTrace();
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
