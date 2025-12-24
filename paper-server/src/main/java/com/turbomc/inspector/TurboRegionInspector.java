package com.turbomc.inspector;

import com.turbomc.storage.lrf.LRFRegionFileAdapter;
import com.turbomc.storage.lrf.LRFChunkEntry;
import com.turbomc.storage.lrf.LRFHeader;
import com.turbomc.inspector.TurboCompressionStats.CompressionStatistics;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * TurboMC Region Inspector - GUI/TUI for LRF region analysis
 * 
 * Features:
 * - Hexadecimal viewer of LRF regions
 * - Tree view of chunk structure  
 * - Block palette visualizer
 * - Compression ratio statistics
 * - PNG export (top-down view)
 * 
 * @author TurboMC
 * @version 1.0.0
 */
public class TurboRegionInspector {
    
    private static volatile TurboRegionInspector instance;
    private static final Object INSTANCE_LOCK = new Object();
    
    // Inspector components
    private final TurboHexViewer hexViewer;
    private final TurboChunkTreeView chunkTreeView;
    private final TurboBlockPaletteVisualizer paletteVisualizer;
    private final TurboCompressionStats compressionStats;
    private final TurboPNGExporter pngExporter;
    
    // Current inspection session
    private LRFRegionFileAdapter currentRegion;
    private String currentWorldName;
    private Path currentFilePath;
    
    private TurboRegionInspector() {
        this.hexViewer = new TurboHexViewer();
        this.chunkTreeView = new TurboChunkTreeView();
        this.paletteVisualizer = new TurboBlockPaletteVisualizer();
        this.compressionStats = new TurboCompressionStats();
        this.pngExporter = new TurboPNGExporter();
    }
    
    /**
     * Get singleton instance
     */
    public static TurboRegionInspector getInstance() {
        if (instance == null) {
            synchronized (INSTANCE_LOCK) {
                if (instance == null) {
                    instance = new TurboRegionInspector();
                }
            }
        }
        return instance;
    }
    
    /**
     * Load and inspect LRF region file
     */
    public CompletableFuture<InspectionResult> inspectRegion(String filePath) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path path = Path.of(filePath);
                if (!Files.exists(path)) {
                    throw new IOException("Region file not found: " + filePath);
                }
                
                currentFilePath = path;
                currentRegion = new LRFRegionFileAdapter(null, path);
                
                // Perform comprehensive inspection
                InspectionResult result = new InspectionResult();
                result.filePath = filePath;
                result.header = currentRegion.getHeader();
                result.chunkCount = currentRegion.getChunkCount();
                result.fileSize = Files.size(path);
                result.compressionRatio = compressionStats.calculateCompressionRatio(currentRegion);
                result.hexDump = hexViewer.generateHexDump(currentRegion);
                result.chunkTree = chunkTreeView.generateChunkTree(currentRegion);
                result.blockPalette = paletteVisualizer.analyzeBlockPalette(currentRegion);
                
                return result;
                
            } catch (Exception e) {
                throw new RuntimeException("Failed to inspect region: " + e.getMessage(), e);
            }
        });
    }
    
    /**
     * Inspect specific chunk within region
     */
    public CompletableFuture<ChunkInspectionResult> inspectChunk(String filePath, int chunkX, int chunkZ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (currentRegion == null || !currentFilePath.toString().equals(filePath)) {
                    // Load region if not already loaded
                    inspectRegion(filePath).get();
                }
                
                // Implementation would get chunk from adapter
                LRFChunkEntry chunk = null; // Placeholder
                if (chunk == null) {
                    throw new IOException("Chunk not found: " + chunkX + "," + chunkZ);
                }
                
                ChunkInspectionResult result = new ChunkInspectionResult();
                result.chunkX = chunkX;
                result.chunkZ = chunkZ;
                result.chunkData = chunk;
                result.blockCount = chunk.getBlockCount();
                result.compressionType = chunk.getCompressionType();
                result.uncompressedSize = chunk.getUncompressedSize();
                result.compressedSize = chunk.getCompressedSize();
                result.blockPalette = paletteVisualizer.analyzeChunkBlockPalette(chunk);
                result.hexDump = hexViewer.generateChunkHexDump(chunk);
                
                return result;
                
            } catch (Exception e) {
                throw new RuntimeException("Failed to inspect chunk: " + e.getMessage(), e);
            }
        });
    }
    
    /**
     * Export region as PNG (top-down view)
     */
    public CompletableFuture<ExportResult> exportToPNG(String filePath, int scale) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (currentRegion == null || !currentFilePath.toString().equals(filePath)) {
                    // Load region if not already loaded
                    inspectRegion(filePath).get();
                }
                
                String outputPath = filePath.replace(".lrf", "_topdown.png");
                pngExporter.exportTopDownView(currentRegion, outputPath, scale);
                
                ExportResult result = new ExportResult();
                result.success = true;
                result.outputPath = outputPath;
                result.fileSize = Files.size(Path.of(outputPath));
                result.exportType = "PNG";
                
                return result;
                
            } catch (Exception e) {
                ExportResult result = new ExportResult();
                result.success = false;
                result.error = e.getMessage();
                return result;
            }
        });
    }
    
    /**
     * Get compression statistics for region
     */
    public TurboCompressionStats.CompressionStatistics getCompressionStatistics(String filePath) {
        try {
            if (currentRegion == null || !currentFilePath.toString().equals(filePath)) {
                inspectRegion(filePath).get();
            }
            
            return compressionStats.generateDetailedStats(currentRegion);
            
        } catch (Exception e) {
            throw new RuntimeException("Failed to get compression statistics: " + e.getMessage(), e);
        }
    }
    
    /**
     * Close current inspection session
     */
    public void closeInspection() {
        try {
            if (currentRegion != null) {
                currentRegion.close();
            }
            currentRegion = null;
            currentFilePath = null;
            currentWorldName = null;
        } catch (IOException e) {
            System.err.println("Warning: Failed to close region file: " + e.getMessage());
        }
    }
    
    /**
     * Inspection result container
     */
    public static class InspectionResult {
        public String filePath;
        public LRFHeader header;
        public int chunkCount;
        public long fileSize;
        public double compressionRatio;
        public String hexDump;
        public Map<String, Object> chunkTree;
        public Map<String, Integer> blockPalette;
        
        public String getSummary() {
            return String.format(
                "Region: %s\nChunks: %d\nSize: %d bytes\nCompression: %.2f%%\n",
                filePath, chunkCount, fileSize, compressionRatio * 100
            );
        }
    }
    
    /**
     * Chunk inspection result container
     */
    public static class ChunkInspectionResult {
        public int chunkX;
        public int chunkZ;
        public LRFChunkEntry chunkData;
        public int blockCount;
        public String compressionType;
        public int uncompressedSize;
        public int compressedSize;
        public Map<String, Integer> blockPalette;
        public String hexDump;
        
        public double getCompressionRatio() {
            if (uncompressedSize == 0) return 0.0;
            return (double) compressedSize / uncompressedSize;
        }
        
        public String getSummary() {
            return String.format(
                "Chunk [%d,%d]\nBlocks: %d\nCompression: %s\nRatio: %.2f%%\n",
                chunkX, chunkZ, blockCount, compressionType, getCompressionRatio() * 100
            );
        }
    }
    
    /**
     * Export result container
     */
    public static class ExportResult {
        public boolean success;
        public String outputPath;
        public long fileSize;
        public String exportType;
        public String error;
        
        public String getSummary() {
            if (success) {
                return String.format(
                    "Export successful: %s (%d bytes)\n",
                    outputPath, fileSize
                );
            } else {
                return "Export failed: " + error;
            }
        }
    }
    
    /**
     * Compression statistics container
     */
    public static class CompressionStatistics {
        public double overallRatio;
        public Map<String, Double> algorithmRatios;
        public int totalChunks;
        public int compressedChunks;
        public long totalUncompressedSize;
        public long totalCompressedSize;
        
        public String getSummary() {
            return String.format(
                "Compression Statistics:\nOverall Ratio: %.2f%%\nChunks: %d/%d compressed\nTotal Size: %d -> %d bytes\n",
                overallRatio * 100, compressedChunks, totalChunks,
                totalUncompressedSize, totalCompressedSize
            );
        }
    }
}
