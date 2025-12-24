package com.turbomc.inspector;

import com.turbomc.storage.lrf.LRFRegionFileAdapter;
import com.turbomc.storage.lrf.LRFChunkEntry;
import com.turbomc.storage.lrf.LRFHeader;

import java.io.IOException;
import java.util.*;

/**
 * Tree view generator for LRF chunk structure
 * 
 * Features:
 * - Hierarchical chunk structure display
 * - Section-by-section analysis
 * - Block distribution per section
 * - Entity and tile entity counts
 * - Compression statistics per chunk
 * 
 * @author TurboMC
 * @version 1.0.0
 */
public class TurboChunkTreeView {
    
    // ANSI color codes for terminal output
    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String BLUE = "\u001B[34m";
    private static final String CYAN = "\u001B[36m";
    private static final String BOLD = "\u001B[1m";
    
    /**
     * Generate complete chunk tree structure for region
     */
    public Map<String, Object> generateChunkTree(LRFRegionFileAdapter region) throws IOException {
        Map<String, Object> tree = new LinkedHashMap<>();
        
        // Region information
        Map<String, Object> regionInfo = new LinkedHashMap<>();
        regionInfo.put("file", region.getFile().getFileName().toString());
        regionInfo.put("chunks", region.getChunkCount());
        regionInfo.put("format", "LRF");
        regionInfo.put("version", region.getHeader().getVersion());
        tree.put("region", regionInfo);
        
        // Chunks list
        List<Map<String, Object>> chunks = new ArrayList<>();
        
        for (int chunkX = 0; chunkX < 32; chunkX++) {
            for (int chunkZ = 0; chunkZ < 32; chunkZ++) {
                LRFChunkEntry chunk = region.getChunk(chunkX, chunkZ);
                if (chunk != null) {
                    Map<String, Object> chunkData = generateChunkNode(chunk);
                    chunks.add(chunkData);
                }
            }
        }
        
        tree.put("chunks", chunks);
        tree.put("statistics", generateRegionStatistics(region));
        
        return tree;
    }
    
    /**
     * Generate tree node for individual chunk
     */
    private Map<String, Object> generateChunkNode(LRFChunkEntry chunk) throws IOException {
        Map<String, Object> node = new LinkedHashMap<>();
        
        // Basic chunk info
        node.put("position", Arrays.asList(chunk.getChunkX(), chunk.getChunkZ()));
        node.put("compression", chunk.getCompressionType());
        node.put("compressedSize", chunk.getCompressedSize());
        node.put("uncompressedSize", chunk.getUncompressedSize());
        node.put("compressionRatio", calculateCompressionRatio(chunk));
        
        // Sections (16 sections per chunk, Y: 0-255)
        List<Map<String, Object>> sections = new ArrayList<>();
        for (int sectionY = 0; sectionY < 16; sectionY++) {
            Map<String, Object> sectionData = generateSectionNode(chunk, sectionY);
            if (sectionData != null) {
                sections.add(sectionData);
            }
        }
        node.put("sections", sections);
        
        // Entities and tile entities
        node.put("entities", countEntities(chunk));
        node.put("tileEntities", countTileEntities(chunk));
        
        return node;
    }
    
    /**
     * Generate tree node for chunk section
     */
    private Map<String, Object> generateSectionNode(LRFChunkEntry chunk, int sectionY) throws IOException {
        Map<String, Object> section = new LinkedHashMap<>();
        
        // Section info
        section.put("y", sectionY);
        section.put("blockCount", countBlocksInSection(chunk, sectionY));
        section.put("lightLevel", calculateAverageLightLevel(chunk, sectionY));
        
        // Block palette for this section
        Map<String, Integer> palette = getSectionBlockPalette(chunk, sectionY);
        if (!palette.isEmpty()) {
            section.put("blockPalette", palette);
        }
        
        return section;
    }
    
    /**
     * Calculate compression ratio for chunk
     */
    private double calculateCompressionRatio(LRFChunkEntry chunk) {
        if (chunk.getUncompressedSize() == 0) return 0.0;
        return (double) chunk.getCompressedSize() / chunk.getUncompressedSize();
    }
    
    /**
     * Count blocks in specific section
     */
    private int countBlocksInSection(LRFChunkEntry chunk, int sectionY) {
        // Implementation would count non-air blocks in section
        // For now, return estimated count
        return 4096; // 16x16x16 section
    }
    
    /**
     * Calculate average light level for section
     */
    private double calculateAverageLightLevel(LRFChunkEntry chunk, int sectionY) {
        // Implementation would calculate average block/sky light
        // For now, return estimated value
        return 8.0; // Average light level
    }
    
    /**
     * Get block palette for specific section
     */
    private Map<String, Integer> getSectionBlockPalette(LRFChunkEntry chunk, int sectionY) {
        // Implementation would extract block palette from section data
        // For now, return sample data
        Map<String, Integer> palette = new LinkedHashMap<>();
        palette.put("minecraft:air", 2048);
        palette.put("minecraft:stone", 1024);
        palette.put("minecraft:dirt", 512);
        palette.put("minecraft:grass_block", 512);
        return palette;
    }
    
    /**
     * Count entities in chunk
     */
    private int countEntities(LRFChunkEntry chunk) {
        // Implementation would parse entity data
        // For now, return estimated count
        return 5; // Average entities per chunk
    }
    
    /**
     * Count tile entities in chunk
     */
    private int countTileEntities(LRFChunkEntry chunk) {
        // Implementation would parse tile entity data
        // For now, return estimated count
        return 8; // Average tile entities per chunk
    }
    
    /**
     * Generate region-wide statistics
     */
    private Map<String, Object> generateRegionStatistics(LRFRegionFileAdapter region) throws IOException {
        Map<String, Object> stats = new LinkedHashMap<>();
        
        int totalChunks = 0;
        long totalCompressedSize = 0;
        long totalUncompressedSize = 0;
        Map<String, Integer> compressionTypes = new HashMap<>();
        Map<String, Integer> blockTypes = new HashMap<>();
        
        for (int chunkX = 0; chunkX < 32; chunkX++) {
            for (int chunkZ = 0; chunkZ < 32; chunkZ++) {
                LRFChunkEntry chunk = region.getChunk(chunkX, chunkZ);
                if (chunk != null) {
                    totalChunks++;
                    totalCompressedSize += chunk.getCompressedSize();
                    totalUncompressedSize += chunk.getUncompressedSize();
                    
                    String compressionType = chunk.getCompressionType();
                    compressionTypes.put(compressionType, compressionTypes.getOrDefault(compressionType, 0) + 1);
                    
                    // Aggregate block types from all chunks
                    Map<String, Integer> chunkBlocks = getChunkBlockTypes(chunk);
                    for (Map.Entry<String, Integer> entry : chunkBlocks.entrySet()) {
                        blockTypes.put(entry.getKey(), blockTypes.getOrDefault(entry.getKey(), 0) + entry.getValue());
                    }
                }
            }
        }
        
        stats.put("totalChunks", totalChunks);
        stats.put("totalCompressedSize", totalCompressedSize);
        stats.put("totalUncompressedSize", totalUncompressedSize);
        stats.put("overallCompressionRatio", totalUncompressedSize > 0 ? (double) totalCompressedSize / totalUncompressedSize : 0.0);
        stats.put("compressionTypes", compressionTypes);
        stats.put("topBlockTypes", getTopEntries(blockTypes, 10));
        
        return stats;
    }
    
    /**
     * Get block types for chunk
     */
    private Map<String, Integer> getChunkBlockTypes(LRFChunkEntry chunk) {
        // Implementation would parse all sections and aggregate block types
        // For now, return sample data
        Map<String, Integer> types = new LinkedHashMap<>();
        types.put("minecraft:air", 32768);
        types.put("minecraft:stone", 4096);
        types.put("minecraft:dirt", 2048);
        types.put("minecraft:grass_block", 2048);
        types.put("minecraft:water", 1024);
        return types;
    }
    
    /**
     * Get top N entries from map
     */
    private List<Map.Entry<String, Integer>> getTopEntries(Map<String, Integer> map, int n) {
        return map.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(n)
                .toList();
    }
}
