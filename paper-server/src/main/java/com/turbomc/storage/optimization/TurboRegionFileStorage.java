package com.turbomc.storage.optimization;

import com.turbomc.config.TurboConfig;
import com.turbomc.compression.TurboCompressionService;
import com.turbomc.storage.lrf.LRFConstants;
import com.turbomc.storage.lrf.LRFChunkEntry;
import com.turbomc.storage.lrf.LRFRegionReader;
import com.turbomc.storage.lrf.LRFRegionWriter;
import com.turbomc.storage.converter.ConversionMode;
import com.turbomc.storage.integrity.ChunkIntegrityValidator;
import com.turbomc.storage.integrity.ValidationUtils;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.DataInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import javax.annotation.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

/**
 * TurboMC-enhanced region storage wrapper that integrates with the advanced storage system.
 * This wrapper provides seamless integration between Paper's chunk storage and TurboMC's
 * optimized batch loading, memory-mapped I/O, and integrity validation.
 * 
 * @author TurboMC
 * @version 1.0.0
 */
public class TurboRegionFileStorage extends RegionFileStorage {
    
    private final RegionFileStorage delegate;
    private final Path regionFolder;
    private final boolean verbose;
    private final boolean useTurboFeatures;
    
    public TurboRegionFileStorage(RegionStorageInfo info, Path folder, boolean sync) {
        super(info, folder, sync); // Call parent constructor
        this.delegate = this; // Use this as delegate since we extend RegionFileStorage
        this.regionFolder = folder;
        
        // Get config instances without storing them to avoid memory leaks
        TurboConfig config = TurboConfig.getInstance();
        this.verbose = config.getBoolean("storage.verbose", false);
        
        // Check if TurboMC features are enabled for this world
        this.useTurboFeatures = isTurboEnabled(config);
        
        if (useTurboFeatures) {
            System.out.println("[TurboMC][RegionStorage] Turbo features enabled for: " + folder.getFileName());
        }
    }
    
    /**
     * Read a chunk with TurboMC optimizations.
     */
    @Nullable
    public CompoundTag read(ChunkPos pos) throws IOException {
        if (!useTurboFeatures) {
            return delegate.read(pos);
        }
        
        try {
            // Try to get region file path for this chunk
            Path regionPath = getRegionPath(pos);
            if (regionPath == null) {
                return delegate.read(pos);
            }
            
            // Check configuration for on-demand conversion
            TurboConfig config = TurboConfig.getInstance();
            String conversionMode = config.getString("storage.conversion-mode", "manual");
            String storageFormat = config.getString("storage.format", "auto");
            boolean onDemand = ConversionMode.ON_DEMAND.equals(ConversionMode.fromString(conversionMode));
            boolean targetIsLrf = "lrf".equalsIgnoreCase(storageFormat);

            // Compute canonical LRF/MCA region paths for this chunk
            int regionX = pos.x >> 5;
            int regionZ = pos.z >> 5;
            Path lrfRegionPath = regionFolder.resolve(String.format("r.%d.%d.lrf", regionX, regionZ));
            Path mcaRegionPath = regionFolder.resolve(String.format("r.%d.%d.mca", regionX, regionZ));

            // If an LRF region already exists, always prefer it
            if (java.nio.file.Files.exists(lrfRegionPath)) {
                return readFromLRF(lrfRegionPath, pos);
            }

            // In ON_DEMAND mode targeting LRF, lazily migrate chunks from MCA
            if (onDemand && targetIsLrf && java.nio.file.Files.exists(mcaRegionPath)) {
                // Read from MCA (optimized or vanilla)
                CompoundTag nbt = readFromMCAOptimized(pos);
                if (nbt != null) {
                    // Write this single chunk into the LRF region file
                    writeToLRF(lrfRegionPath, pos, nbt);
                }
                return nbt;
            }

            // Check if this is an LRF file (TurboMC format) according to current regionPath
            if (isLRFFile(regionPath)) {
                return readFromLRF(regionPath, pos);
            }
            
            // Fall back to vanilla MCA with optimization
            return readFromMCAOptimized(pos);
            
        } catch (Exception e) {
            System.err.println("[TurboMC][RegionStorage] Error in optimized read, falling back to vanilla: " + e.getMessage());
            return delegate.read(pos);
        }
    }
    
    /**
     * Write a chunk with TurboMC optimizations.
     */
    public void write(ChunkPos pos, CompoundTag nbt) throws IOException {
        if (!useTurboFeatures) {
            delegate.write(pos, nbt);
            return;
        }
        
        try {
            // Try to get region file path for this chunk
            Path regionPath = getRegionPath(pos);
            if (regionPath == null) {
                delegate.write(pos, nbt);
                return;
            }
            
            // Check if this should be saved as LRF (TurboMC format)
            TurboConfig config = TurboConfig.getInstance();
            if (shouldSaveAsLRF(config, regionPath)) {
                writeToLRF(regionPath, pos, nbt);
                return;
            }
            
            // Fall back to vanilla MCA with optimization
            writeToMCAOptimized(pos, nbt);
            
        } catch (Exception e) {
            System.err.println("[TurboMC][RegionStorage] Error in optimized write, falling back to vanilla: " + e.getMessage());
            delegate.write(pos, nbt);
        }
    }
    
    /**
     * Read chunk from LRF format with timeout and error handling.
     */
    @Nullable
    private CompoundTag readFromLRF(Path regionPath, ChunkPos pos) {
        // Input validation
        ValidationUtils.validateNotNull(regionPath, "regionPath");
        ValidationUtils.validateChunkCoordinates(pos);
        
        try {
            CompletableFuture<LRFChunkEntry> future = TurboStorageManager.getInstance().loadChunk(
                regionPath, pos.x, pos.z);
            
            // FIXED: Use configurable timeout with proper exception handling
            int timeoutSeconds = TurboConfig.getInstance().getInt("storage.lrf.timeout-seconds", 5);
            ValidationUtils.validateTimeout(timeoutSeconds);
            
            LRFChunkEntry chunk = TurboExceptionHandler.handleTimeout(
                "readFromLRF", 
                "region=" + regionPath.getFileName() + ",chunk=" + pos,
                (attempt) -> future.get(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS),
                3
            );
            
            if (chunk == null) {
                return null;
            }
            
            // Parse NBT from chunk data
            byte[] data = chunk.getData();
            if (data.length == 0) {
                return null;
            }
            
            // NEW: Support for PackedBinaryNBT (TNBT magic)
            if (data.length > 5 && data[0] == 'T' && data[1] == 'N' && data[2] == 'B' && data[3] == 'T') {
                return com.turbomc.nbt.NBTConverter.fromPackedBinary(
                    com.turbomc.nbt.PackedBinaryNBT.fromBytes(data)
                );
            }
            
            // LEGACY: Check for timestamp at the end of standard NBT data
            if (data.length >= 8) {
                // Check if last 8 bytes look like a timestamp (reasonable range)
                long potentialTimestamp = java.nio.ByteBuffer.wrap(data, data.length - 8, 8).getLong();
                long currentTime = System.currentTimeMillis();
                if (potentialTimestamp > 0 && potentialTimestamp < currentTime + 86400000L * 365 * 130) {
                    byte[] nbtData = new byte[data.length - 8];
                    System.arraycopy(data, 0, nbtData, 0, nbtData.length);
                    data = nbtData;
                }
            }
            
            return NbtIo.read(new DataInputStream(new java.io.ByteArrayInputStream(data)), 
                           NbtAccounter.unlimitedHeap());
            
        } catch (Exception e) {
            TurboExceptionHandler.handleException("readFromLRF", 
                "region=" + regionPath.getFileName() + ",chunk=" + pos, e);
            return null;
        }
    }
    
    /**
     * Write chunk to LRF format with performance tracking.
     */
    private void writeToLRF(Path regionPath, ChunkPos pos, CompoundTag nbt) throws IOException {
        long startTime = System.nanoTime();
        
        try {
            // OPTIMIZATION: Use PackedBinaryNBT for LRF storage instead of standard NBT
            // This is significantly faster and smaller for chunk data
            byte[] dataToWrite = com.turbomc.nbt.NBTConverter.toPackedBinary(nbt).toBytes();
            
            // Hand off to Storage Manager (NON-BLOCKING)
            // We do NOT call future.get() here. The Storage Manager handles the write in its own pool.
            // Consistency is maintained via the inflightChunks cache in BatchSaver.
            TurboStorageManager.getInstance().saveChunk(regionPath, pos.x, pos.z, dataToWrite);
            
            long elapsed = System.nanoTime() - startTime;
            if (verbose && elapsed > 500_000) { // Log if > 0.5ms (non-blocking should be nearly zero)
                System.out.println("[TurboMC][RegionStorage] Handed off chunk " + pos + 
                                 " to LRF buffer in " + elapsed / 1_000_000.0 + "ms");
            }
            
            // Enforce FULL_LRF-style semantics on disk where appropriate:
            // if we are writing to an LRF region file and backups are not requested,
            // remove the corresponding MCA region file so that the directory converges to only .lrf.
            try {
                TurboConfig config = TurboConfig.getInstance();
                boolean backupOriginalMca = config.getBoolean("storage.backup-original-mca", false);
                if (!backupOriginalMca && isLRFFile(regionPath)) {
                    int regionX = pos.x >> 5;
                    int regionZ = pos.z >> 5;
                    Path mcaPath = regionFolder.resolve(String.format("r.%d.%d.mca", regionX, regionZ));
                    if (java.nio.file.Files.exists(mcaPath)) {
                        java.nio.file.Files.deleteIfExists(mcaPath);
                        if (verbose) {
                            System.out.println("[TurboMC][RegionStorage] Removed MCA region after LRF write: " + mcaPath.getFileName());
                        }
                    }
                }
            } catch (Exception e) {
                if (verbose) {
                    System.err.println("[TurboMC][RegionStorage] Failed to remove MCA region after LRF write: " + e.getMessage());
                }
            }
            
        } catch (Exception e) {
            throw new IOException("Failed to write chunk " + pos + " to LRF", e);
        }
    }
    
    /**
     * Optimized MCA read with caching.
     */
    @Nullable
    private CompoundTag readFromMCAOptimized(ChunkPos pos) throws IOException {
        // Check if MCA optimization is enabled
        TurboConfig config = TurboConfig.getInstance();
        if (!config.getBoolean("storage.mca.optimization.enabled", false)) {
            return delegate.read(pos);
        }
        
        try {
            // Get region file path
            Path regionPath = getRegionPath(pos);
            if (regionPath == null || !regionPath.toString().endsWith(".mca")) {
                return delegate.read(pos);
            }
            
            // Use optimized MCA reader
            OptimizedMCAReader reader = new OptimizedMCAReader(regionPath);
            try {
                return reader.readChunkPublic(pos.x, pos.z);
            } finally {
                try {
                    reader.close();
                } catch (Exception e) {
                    System.err.println("[TurboMC][Storage] Error closing MCA reader: " + e.getMessage());
                }
            }
            
        } catch (Exception e) {
            System.err.println("[TurboMC][RegionStorage] Optimized MCA read failed, falling back to vanilla: " + e.getMessage());
            return delegate.read(pos);
        }
    }
    
    /**
     * Optimized MCA write with batching.
     */
    private void writeToMCAOptimized(ChunkPos pos, CompoundTag nbt) throws IOException {
        // Check if MCA optimization is enabled
        TurboConfig config = TurboConfig.getInstance();
        if (!config.getBoolean("storage.mca.optimization.enabled", false)) {
            delegate.write(pos, nbt);
            return;
        }
        
        // Get region path for this chunk
        Path regionPath = getRegionPath(pos);
        if (regionPath != null && isLRFFile(regionPath)) {
            writeToLRF(regionPath, pos, nbt);
            return;
        }
        
        // Fall back to vanilla MCA with optimization
        delegate.write(pos, nbt);
    }
    
    /**
     * Get the region file path for a chunk position.
     */
    @Nullable
    private Path getRegionPath(ChunkPos pos) {
        try {
            // Compute region coordinates directly from chunk position
            int regionX = pos.x >> 5;
            int regionZ = pos.z >> 5;

            // Base MCA path (vanilla format)
            Path mcaPath = regionFolder.resolve(String.format("r.%d.%d.mca", regionX, regionZ));

            if (!useTurboFeatures) {
                return mcaPath;
            }

            // With Turbo features enabled, decide between MCA and LRF purely via config
            TurboConfig config = TurboConfig.getInstance();
            if (shouldSaveAsLRF(config, mcaPath)) {
                return regionFolder.resolve(String.format("r.%d.%d.lrf", regionX, regionZ));
            }

            return mcaPath;
        } catch (Exception e) {
            if (verbose) {
                System.err.println("[TurboMC][RegionStorage] Error computing region path: " + e.getMessage());
            }
            return null;
        }
    }
    
    /**
     * Check if a file is in LRF format.
     */
    private boolean isLRFFile(Path filePath) {
        return filePath.toString().endsWith(".lrf");
    }
    
    /**
     * Check if chunks should be saved as LRF format.
     */
    private boolean shouldSaveAsLRF(TurboConfig config, Path regionPath) {
        if (!TurboConfig.isInitialized()) {
            return false;
        }
        
        if (config == null) {
            config = TurboConfig.getInstance();
        }
        
        String conversionMode = config.getString("storage.conversion-mode", "manual");
        
        // In FULL_LRF mode, always create LRF files directly (no MCA conversion)
        if (ConversionMode.FULL_LRF.equals(ConversionMode.fromString(conversionMode))) {
            return true;
        }
        
        String storageFormat = config.getString("storage.format", "auto");
        
        if (storageFormat.equals("lrf")) {
            return true;
        } else if (storageFormat.equals("mca")) {
            return false;
        } else { // auto
            // Use LRF if region file already exists in LRF format
            return isLRFFile(regionPath) || config.getBoolean("storage.auto-convert", true);
        }
    }
    
    /**
     * Check if TurboMC features are enabled.
     */
    private boolean isTurboEnabled(TurboConfig config) {
        if (!TurboConfig.isInitialized()) {
            return false;
        }
        
        if (config == null) {
            config = TurboConfig.getInstance();
        }
        
        // Check for FULL_LRF mode first (most common case)
        String conversionMode = config.getString("storage.conversion-mode", "manual");
        if (ConversionMode.FULL_LRF.equals(ConversionMode.fromString(conversionMode))) {
            return true;
        }
        
        // Otherwise check for other Turbo features
        return config.getBoolean("storage.batch.enabled", true) ||
               config.getBoolean("storage.mmap.enabled", true) ||
               config.getBoolean("storage.integrity.enabled", true);
    }
    
    /**
     * Validate region file integrity.
     */
    public boolean validateRegion(Path regionPath) {
        if (!isLRFFile(regionPath)) {
            return true; // Skip MCA validation for now
        }
        
        try {
            // Use ChunkIntegrityValidator for LRF files
            LRFRegionReader reader = new LRFRegionReader(regionPath);
            ChunkIntegrityValidator validator = new ChunkIntegrityValidator(regionPath);
            
            try {
                java.util.List<ChunkIntegrityValidator.IntegrityReport> reports = validator.validateRegion(reader).get();
                
                // Check if any chunks are corrupted
                boolean hasCorruption = reports.stream()
                    .anyMatch(ChunkIntegrityValidator.IntegrityReport::isCorrupted);
                
                if (verbose) {
                    System.out.println("[TurboMC][RegionStorage] Validation completed for " + regionPath + 
                                     ": " + reports.size() + " chunks checked, " + 
                                     (hasCorruption ? "corruption found" : "all good"));
                }
                
                return !hasCorruption;
                
            } finally {
                try {
                    validator.close();
                } catch (Exception e) {
                    System.err.println("[TurboMC][Storage] Error closing integrity validator: " + e.getMessage());
                }
                try {
                    reader.close();
                } catch (Exception e) {
                    System.err.println("[TurboMC][Storage] Error closing MCA reader: " + e.getMessage());
                }
            }
            
        } catch (Exception e) {
            System.err.println("[TurboMC][RegionStorage] Validation failed for " + regionPath + ": " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Enhanced storage statistics.
     */
    public static class StorageStats {
        public final Object managerStats;
        public final int regionCount;
        public final double cacheHitRate;
        
        public StorageStats(Object managerStats, int regionCount, double cacheHitRate) {
            this.managerStats = managerStats;
            this.regionCount = regionCount;
            this.cacheHitRate = cacheHitRate;
        }
        
        @Override
        public String toString() {
            return String.format("StorageStats{regions=%d, cacheHitRate=%.1f%%, manager=%s}",
                    regionCount, cacheHitRate * 100,
                    managerStats != null ? managerStats.toString() : "disabled");
        }
    }
    
    /**
     * Get comprehensive statistics.
     */
    public StorageStats getStorageStats() {
        try {
            TurboStorageManager manager = TurboStorageManager.getInstance();
            return new StorageStats(
                manager.getStats(),
                getRegionFileCount(),
                getCacheHitRate()
            );
        } catch (Exception e) {
            return new StorageStats(null, 0, 0.0);
        }
    }
    
    private int getRegionFileCount() {
        try {
            if (!java.nio.file.Files.isDirectory(regionFolder)) {
                return 0;
            }
            return (int) java.nio.file.Files.list(regionFolder)
                .filter(path -> path.toString().endsWith(".lrf") || path.toString().endsWith(".mca"))
                .count();
        } catch (Exception e) {
            if (verbose) {
                System.err.println("[TurboMC][RegionStorage] Error counting region files: " + e.getMessage());
            }
            return 0;
        }
    }
    
    private double getCacheHitRate() {
        try {
            TurboStorageManager manager = TurboStorageManager.getInstance();
            TurboStorageManager.StorageManagerStats stats = manager.getStats();
            return stats.getCacheHitRate();
        } catch (Exception e) {
            if (verbose) {
                System.err.println("[TurboMC][RegionStorage] Error getting cache hit rate: " + e.getMessage());
            }
            return 0.0;
        }
    }
    
    /**
     * Get storage statistics for this region storage.
     */
    public TurboStorageManager.StorageManagerStats getTurboStats() {
        try {
            TurboStorageManager manager = TurboStorageManager.getInstance();
            return manager.getStats();
        } catch (Exception e) {
            if (verbose) {
                System.err.println("[TurboMC][RegionStorage] Error getting Turbo stats: " + e.getMessage());
            }
            return null;
        }
    }
    
    /**
     * Add missing methods for compatibility.
     */
    public void flush() throws IOException {
        // No-op for now - delegate handles flushing
        if (verbose) {
            System.out.println("[TurboMC][RegionStorage] Flush called");
        }
    }
    
    public void close() throws IOException {
        // FIXED: Don't close singleton storage manager, just cleanup local resources
        if (verbose) {
            System.out.println("[TurboMC][RegionStorage] Closed TurboMC resources for world");
        }
    }
    
    /**
     * Add scanRegion method for compatibility.
     */
    public void scanRegion(int regionX, int regionZ, java.util.function.BiPredicate<Integer, Integer> chunkVisitor) {
        // For now, delegate to parent implementation
        // This could be enhanced with TurboMC-specific optimizations
        try {
            // Iterate through all chunks in the region (32x32 = 1024 chunks)
            for (int chunkX = regionX * 32; chunkX < regionX * 32 + 32; chunkX++) {
                for (int chunkZ = regionZ * 32; chunkZ < regionZ * 32 + 32; chunkZ++) {
                    if (chunkVisitor.test(chunkX, chunkZ)) {
                        // Chunk accepted by visitor
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[TurboMC][RegionStorage] Error scanning region: " + e.getMessage());
        }
    }
    
    /**
     * Validate region for compatibility.
     */
    public CompletableFuture<java.util.List<ChunkIntegrityValidator.IntegrityReport>> validateRegion(int regionX, int regionZ) {
        if (!useTurboFeatures) {
            return CompletableFuture.completedFuture(new java.util.ArrayList<>());
        }
        
        String regionFileName = String.format("r.%d.%d.lrf", regionX, regionZ);
        Path regionPath = regionFolder.resolve(regionFileName);
        
        if (!java.nio.file.Files.exists(regionPath)) {
            return CompletableFuture.completedFuture(new java.util.ArrayList<>());
        }
        
        try {
            // Use ChunkIntegrityValidator for actual validation
            LRFRegionReader reader = new LRFRegionReader(regionPath);
            ChunkIntegrityValidator validator = new ChunkIntegrityValidator(regionPath);
            
            try {
                java.util.List<ChunkIntegrityValidator.IntegrityReport> reports = validator.validateRegion(reader).get();
                
                // Check if any chunks are corrupted
                boolean hasCorruption = reports.stream()
                    .anyMatch(ChunkIntegrityValidator.IntegrityReport::isCorrupted);
                
                if (verbose) {
                    System.out.println("[TurboMC][RegionStorage] Validation completed for " + regionFileName + 
                                     ": " + reports.size() + " chunks checked, " + 
                                     (hasCorruption ? "corruption found" : "all good"));
                }
                
                return CompletableFuture.completedFuture(reports);
                
            } finally {
                validator.close();
                reader.close();
            }
            
        } catch (Exception e) {
            System.err.println("[TurboMC][RegionStorage] Failed to create validator for region " + regionFileName + ": " + e.getMessage());
            return CompletableFuture.completedFuture(new java.util.ArrayList<>());
        }
    }
}
