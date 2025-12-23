package com.turbomc.inspector;

import com.turbomc.storage.lrf.LRFRegionFileAdapter;
import com.turbomc.storage.lrf.LRFChunkEntry;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Compression statistics analyzer for LRF regions
 * 
 * Features:
 * - Compression ratio analysis per algorithm
 * - Performance metrics for LZ4/ZLIB/ZSTD
 * - Size savings calculations
 * - Algorithm efficiency comparisons
 * - Compression recommendations
 * 
 * @author TurboMC
 * @version 1.0.0
 */
public class TurboCompressionStats {
    
    // ANSI color codes for terminal output
    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String BLUE = "\u001B[34m";
    private static final String CYAN = "\u001B[36m";
    private static final String RED = "\u001B[31m";
    private static final String BOLD = "\u001B[1m";
    
    // Compression algorithm information
    private static final Map<String, AlgorithmInfo> ALGORITHM_INFO = Map.of(
        "LZ4", new AlgorithmInfo("LZ4", "Fast compression, good ratio", 1.0, 2.5),
        "ZLIB", new AlgorithmInfo("ZLIB", "Balanced speed/ratio", 2.0, 3.5),
        "ZSTD", new AlgorithmInfo("ZSTD", "Best ratio, slower", 3.0, 4.5)
    );
    
    /**
     * Calculate compression ratio for region
     */
    public double calculateCompressionRatio(LRFRegionFileAdapter region) throws IOException {
        long totalCompressed = 0;
        long totalUncompressed = 0;
        
        for (int chunkX = 0; chunkX < 32; chunkX++) {
            for (int chunkZ = 0; chunkZ < 32; chunkZ++) {
                LRFChunkEntry chunk = region.getChunk(chunkX, chunkZ);
                if (chunk != null) {
                    totalCompressed += chunk.getCompressedSize();
                    totalUncompressed += chunk.getUncompressedSize();
                }
            }
        }
        
        return totalUncompressed > 0 ? (double) totalCompressed / totalUncompressed : 0.0;
    }
    
    /**
     * Generate detailed compression statistics
     */
    public CompressionStatistics generateDetailedStats(LRFRegionFileAdapter region) throws IOException {
        CompressionStatistics stats = new CompressionStatistics();
        
        Map<String, List<ChunkCompressionData>> algorithmChunks = new HashMap<>();
        long totalCompressed = 0;
        long totalUncompressed = 0;
        int totalChunks = 0;
        
        // Collect data from all chunks
        for (int chunkX = 0; chunkX < 32; chunkX++) {
            for (int chunkZ = 0; chunkZ < 32; chunkZ++) {
                LRFChunkEntry chunk = region.getChunk(chunkX, chunkZ);
                if (chunk != null) {
                    totalChunks++;
                    int compressedSize = chunk.getCompressedSize();
                    int uncompressedSize = chunk.getUncompressedSize();
                    
                    totalCompressed += compressedSize;
                    totalUncompressed += uncompressedSize;
                    
                    String algorithm = chunk.getCompressionType();
                    ChunkCompressionData data = new ChunkCompressionData(
                        chunkX, chunkZ, algorithm, compressedSize, uncompressedSize
                    );
                    
                    algorithmChunks.computeIfAbsent(algorithm, k -> new ArrayList<>()).add(data);
                }
            }
        }
        
        // Calculate overall statistics
        stats.overallRatio = totalUncompressed > 0 ? (double) totalCompressed / totalUncompressed : 0.0;
        stats.totalChunks = totalChunks;
        stats.totalCompressedSize = totalCompressed;
        stats.totalUncompressedSize = totalUncompressed;
        stats.totalSavings = totalUncompressed - totalCompressed;
        
        // Calculate per-algorithm statistics
        stats.algorithmStats = new HashMap<>();
        for (Map.Entry<String, List<ChunkCompressionData>> entry : algorithmChunks.entrySet()) {
            String algorithm = entry.getKey();
            List<ChunkCompressionData> chunks = entry.getValue();
            
            AlgorithmStatistics algoStats = new AlgorithmStatistics();
            algoStats.algorithm = algorithm;
            algoStats.chunkCount = chunks.size();
            algoStats.totalCompressed = chunks.stream().mapToInt(c -> c.compressedSize).sum();
            algoStats.totalUncompressed = chunks.stream().mapToInt(c -> c.uncompressedSize).sum();
            algoStats.averageRatio = algoStats.totalUncompressed > 0 ? 
                (double) algoStats.totalCompressed / algoStats.totalUncompressed : 0.0;
            algoStats.bestRatio = chunks.stream()
                .mapToDouble(c -> c.getRatio())
                .min().orElse(0.0);
            algoStats.worstRatio = chunks.stream()
                .mapToDouble(c -> c.getRatio())
                .max().orElse(0.0);
            
            stats.algorithmStats.put(algorithm, algoStats);
        }
        
        // Generate recommendations
        stats.recommendations = generateRecommendations(stats);
        
        return stats;
    }
    
    /**
     * Generate compression recommendations
     */
    private List<String> generateRecommendations(CompressionStatistics stats) {
        List<String> recommendations = new ArrayList<>();
        
        // Overall compression analysis
        if (stats.overallRatio > 0.7) {
            recommendations.add("Consider using ZSTD for better compression ratios");
        } else if (stats.overallRatio < 0.3) {
            recommendations.add("Excellent compression achieved. Current algorithm is optimal");
        }
        
        // Algorithm-specific recommendations
        for (AlgorithmStatistics algo : stats.algorithmStats.values()) {
            if (algo.averageRatio > 0.8 && algo.algorithm.equals("LZ4")) {
                recommendations.add(algo.algorithm + " chunks have poor compression, consider ZSTD");
            }
            
            if (algo.chunkCount < stats.totalChunks * 0.1 && algo.averageRatio < 0.4) {
                recommendations.add(algo.algorithm + " performs well but is underutilized");
            }
        }
        
        // Size-based recommendations
        if (stats.totalSavings > 100_000_000) { // 100MB+
            recommendations.add("Significant space savings achieved (>100MB)");
        } else if (stats.totalSavings < 10_000_000) { // <10MB
            recommendations.add("Consider optimizing chunk structure for better compression");
        }
        
        return recommendations;
    }
    
    /**
     * Generate formatted compression report
     */
    public String generateCompressionReport(CompressionStatistics stats) {
        StringBuilder output = new StringBuilder();
        
        output.append(BOLD).append(CYAN);
        output.append("=== Compression Statistics Report ===\n");
        output.append(RESET);
        
        // Overall statistics
        output.append(BOLD).append(YELLOW);
        output.append("Overall Statistics:\n");
        output.append(RESET);
        output.append(String.format("Total Chunks: %,d\n", stats.totalChunks));
        output.append(String.format("Compressed Size: %,d bytes (%.1f MB)\n", 
            stats.totalCompressedSize, stats.totalCompressedSize / 1_048_576.0));
        output.append(String.format("Uncompressed Size: %,d bytes (%.1f MB)\n", 
            stats.totalUncompressedSize, stats.totalUncompressedSize / 1_048_576.0));
        output.append(String.format("Space Savings: %,d bytes (%.1f MB)\n", 
            stats.totalSavings, stats.totalSavings / 1_048_576.0));
        output.append(String.format("Overall Ratio: %.2f%%\n\n", stats.overallRatio * 100));
        
        // Per-algorithm statistics
        output.append(BOLD).append(YELLOW);
        output.append("Algorithm Performance:\n");
        output.append(RESET);
        
        for (AlgorithmStatistics algo : stats.algorithmStats.values()) {
            String color = getAlgorithmColor(algo.algorithm);
            output.append(String.format("%s%s%s:\n", color, algo.algorithm, RESET));
            output.append(String.format("  Chunks: %d (%.1f%% of total)\n", 
                algo.chunkCount, (double) algo.chunkCount / stats.totalChunks * 100));
            output.append(String.format("  Average Ratio: %.2f%%\n", algo.averageRatio * 100));
            output.append(String.format("  Best Ratio: %.2f%%\n", algo.bestRatio * 100));
            output.append(String.format("  Worst Ratio: %.2f%%\n", algo.worstRatio * 100));
            output.append(String.format("  Size: %,d -> %,d bytes\n", 
                algo.totalUncompressed, algo.totalCompressed));
            output.append("\n");
        }
        
        // Recommendations
        if (!stats.recommendations.isEmpty()) {
            output.append(BOLD).append(YELLOW);
            output.append("Recommendations:\n");
            output.append(RESET);
            for (int i = 0; i < stats.recommendations.size(); i++) {
                output.append(String.format("%d. %s\n", i + 1, stats.recommendations.get(i)));
            }
        }
        
        return output.toString();
    }
    
    /**
     * Get color for compression algorithm
     */
    private String getAlgorithmColor(String algorithm) {
        switch (algorithm) {
            case "LZ4": return GREEN;
            case "ZLIB": return YELLOW;
            case "ZSTD": return CYAN;
            default: return RESET;
        }
    }
    
    /**
     * Algorithm information container
     */
    private static class AlgorithmInfo {
        final String name;
        final String description;
        final double speedRating;
        final double compressionRating;
        
        AlgorithmInfo(String name, String description, double speedRating, double compressionRating) {
            this.name = name;
            this.description = description;
            this.speedRating = speedRating;
            this.compressionRating = compressionRating;
        }
    }
    
    /**
     * Chunk compression data container
     */
    private static class ChunkCompressionData {
        final int chunkX;
        final int chunkZ;
        final String algorithm;
        final int compressedSize;
        final int uncompressedSize;
        
        ChunkCompressionData(int chunkX, int chunkZ, String algorithm, 
                           int compressedSize, int uncompressedSize) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.algorithm = algorithm;
            this.compressedSize = compressedSize;
            this.uncompressedSize = uncompressedSize;
        }
        
        double getRatio() {
            return uncompressedSize > 0 ? (double) compressedSize / uncompressedSize : 0.0;
        }
    }
    
    /**
     * Compression statistics container
     */
    public static class CompressionStatistics {
        public double overallRatio;
        public int totalChunks;
        public long totalCompressedSize;
        public long totalUncompressedSize;
        public long totalSavings;
        public Map<String, AlgorithmStatistics> algorithmStats;
        public List<String> recommendations;
        
        public String getSummary() {
            return String.format(
                "Compression Summary:\n" +
                "Overall Ratio: %.2f%%\n" +
                "Total Savings: %.1f MB\n" +
                "Algorithms: %s\n",
                overallRatio * 100, totalSavings / 1_048_576.0,
                String.join(", ", algorithmStats.keySet())
            );
        }
    }
    
    /**
     * Algorithm-specific statistics
     */
    public static class AlgorithmStatistics {
        public String algorithm;
        public int chunkCount;
        public long totalCompressed;
        public long totalUncompressed;
        public double averageRatio;
        public double bestRatio;
        public double worstRatio;
    }
}
