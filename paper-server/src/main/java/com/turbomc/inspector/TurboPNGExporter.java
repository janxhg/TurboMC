package com.turbomc.inspector;

import com.turbomc.storage.lrf.LRFRegionFileAdapter;
import com.turbomc.storage.lrf.LRFChunkEntry;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * PNG exporter for LRF region top-down views
 * 
 * Features:
 * - Top-down region visualization
 * - Block color mapping
 * - Different scaling options
 * - Chunk boundary overlay
 * - Coordinate grid overlay
 * - Statistics overlay
 * 
 * @author TurboMC
 * @version 1.0.0
 */
public class TurboPNGExporter {
    
    // Block color mappings for visualization
    private static final Map<String, Color> BLOCK_COLORS = new HashMap<>();
    
    static {
        // Natural blocks
        BLOCK_COLORS.put("minecraft:air", new Color(0, 0, 0, 0)); // Transparent
        BLOCK_COLORS.put("minecraft:stone", new Color(128, 128, 128));
        BLOCK_COLORS.put("minecraft:dirt", new Color(139, 90, 43));
        BLOCK_COLORS.put("minecraft:grass_block", new Color(124, 169, 97));
        BLOCK_COLORS.put("minecraft:sand", new Color(238, 203, 173));
        BLOCK_COLORS.put("minecraft:gravel", new Color(136, 140, 141));
        BLOCK_COLORS.put("minecraft:water", new Color(64, 164, 223, 128));
        BLOCK_COLORS.put("minecraft:lava", new Color(255, 100, 0, 180));
        
        // Wood blocks
        BLOCK_COLORS.put("minecraft:oak_log", new Color(139, 90, 43));
        BLOCK_COLORS.put("minecraft:oak_leaves", new Color(34, 139, 34, 200));
        BLOCK_COLORS.put("minecraft:birch_log", new Color(222, 184, 135));
        BLOCK_COLORS.put("minecraft:birch_leaves", new Color(144, 238, 144, 200));
        BLOCK_COLORS.put("minecraft:spruce_log", new Color(101, 67, 33));
        BLOCK_COLORS.put("minecraft:spruce_leaves", new Color(85, 107, 47, 200));
        
        // Ore blocks
        BLOCK_COLORS.put("minecraft:coal_ore", new Color(64, 64, 64));
        BLOCK_COLORS.put("minecraft:iron_ore", new Color(192, 192, 192));
        BLOCK_COLORS.put("minecraft:gold_ore", new Color(255, 215, 0));
        BLOCK_COLORS.put("minecraft:diamond_ore", new Color(185, 242, 255));
        BLOCK_COLORS.put("minecraft:emerald_ore", new Color(80, 217, 123));
        
        // Special blocks
        BLOCK_COLORS.put("minecraft:bedrock", new Color(64, 64, 64));
        BLOCK_COLORS.put("minecraft:cobblestone", new Color(105, 105, 105));
        BLOCK_COLORS.put("minecraft:obsidian", new Color(47, 27, 69));
        
        // Default color for unknown blocks
        BLOCK_COLORS.put("default", new Color(255, 0, 255));
    }
    
    /**
     * Export top-down view of region to PNG
     */
    public void exportTopDownView(LRFRegionFileAdapter region, String outputPath, int scale) throws IOException {
        // Calculate image dimensions
        int regionWidth = 32 * 16 * scale; // 32 chunks * 16 blocks per chunk
        int regionHeight = 32 * 16 * scale;
        
        BufferedImage image = new BufferedImage(regionWidth, regionHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = image.createGraphics();
        
        // Enable anti-aliasing for better quality
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        
        // Fill background with black
        g2d.setColor(Color.BLACK);
        g2d.fillRect(0, 0, regionWidth, regionHeight);
        
        // Draw chunks
        for (int chunkX = 0; chunkX < 32; chunkX++) {
            for (int chunkZ = 0; chunkZ < 32; chunkZ++) {
                LRFChunkEntry chunk = region.getChunk(chunkX, chunkZ);
                if (chunk != null) {
                    drawChunk(g2d, chunk, chunkX, chunkZ, scale);
                }
            }
        }
        
        // Draw chunk boundaries
        drawChunkBoundaries(g2d, scale);
        
        // Draw coordinate grid
        drawCoordinateGrid(g2d, scale);
        
        // Draw statistics overlay
        drawStatisticsOverlay(g2d, region, scale);
        
        // Save image
        File outputFile = new File(outputPath);
        ImageIO.write(image, "PNG", outputFile);
        
        g2d.dispose();
    }
    
    /**
     * Draw individual chunk
     */
    private void drawChunk(Graphics2D g2d, LRFChunkEntry chunk, int chunkX, int chunkZ, int scale) {
        // Implementation would parse actual chunk data
        // For now, generate sample visualization based on chunk position
        
        int chunkPixelX = chunkX * 16 * scale;
        int chunkPixelZ = chunkZ * 16 * scale;
        
        // Generate sample block data for visualization
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                // Sample top block at this position
                String blockType = getSampleTopBlock(chunkX * 16 + x, chunkZ * 16 + z, chunk.getY());
                Color blockColor = BLOCK_COLORS.getOrDefault(blockType, BLOCK_COLORS.get("default"));
                
                // Draw block
                g2d.setColor(blockColor);
                g2d.fillRect(
                    chunkPixelX + x * scale,
                    chunkPixelZ + z * scale,
                    scale,
                    scale
                );
            }
        }
    }
    
    /**
     * Get sample top block for visualization
     */
    private String getSampleTopBlock(int worldX, int worldZ, int chunkY) {
        // Simple terrain generation for visualization
        long seed = worldX * 31L + worldZ;
        double noise = (Math.sin(seed * 0.1) + Math.cos(seed * 0.05)) * 0.5 + 0.5;
        
        if (noise < 0.1) return "minecraft:water";
        if (noise < 0.2) return "minecraft:sand";
        if (noise < 0.3) return "minecraft:grass_block";
        if (noise < 0.5) return "minecraft:dirt";
        if (noise < 0.7) return "minecraft:stone";
        if (noise < 0.8) return "minecraft:coal_ore";
        if (noise < 0.9) return "minecraft:iron_ore";
        if (noise < 0.95) return "minecraft:gold_ore";
        if (noise < 0.98) return "minecraft:diamond_ore";
        
        return "minecraft:bedrock";
    }
    
    /**
     * Draw chunk boundaries
     */
    private void drawChunkBoundaries(Graphics2D g2d, int scale) {
        g2d.setColor(new Color(255, 255, 255, 64));
        g2d.setStroke(new BasicStroke(1));
        
        // Draw vertical lines
        for (int chunkX = 0; chunkX <= 32; chunkX++) {
            int x = chunkX * 16 * scale;
            g2d.drawLine(x, 0, x, 32 * 16 * scale);
        }
        
        // Draw horizontal lines
        for (int chunkZ = 0; chunkZ <= 32; chunkZ++) {
            int z = chunkZ * 16 * scale;
            g2d.drawLine(0, z, 32 * 16 * scale, z);
        }
    }
    
    /**
     * Draw coordinate grid
     */
    private void drawCoordinateGrid(Graphics2D g2d, int scale) {
        g2d.setColor(new Color(255, 255, 0, 128));
        g2d.setFont(new Font("Arial", Font.PLAIN, 10));
        
        // Draw chunk coordinates every 4 chunks
        for (int chunkX = 0; chunkX < 32; chunkX += 4) {
            for (int chunkZ = 0; chunkZ < 32; chunkZ += 4) {
                int x = chunkX * 16 * scale + 2;
                int z = chunkZ * 16 * scale + 12;
                
                String label = String.format("[%d,%d]", chunkX, chunkZ);
                g2d.drawString(label, x, z);
            }
        }
    }
    
    /**
     * Draw statistics overlay
     */
    private void drawStatisticsOverlay(Graphics2D g2d, LRFRegionFileAdapter region, int scale) {
        // Create semi-transparent overlay for statistics
        int overlayWidth = 300;
        int overlayHeight = 120;
        int overlayX = 10;
        int overlayY = 10;
        
        // Background
        g2d.setColor(new Color(0, 0, 0, 180));
        g2d.fillRect(overlayX, overlayY, overlayWidth, overlayHeight);
        
        // Border
        g2d.setColor(new Color(255, 255, 255, 200));
        g2d.drawRect(overlayX, overlayY, overlayWidth, overlayHeight);
        
        // Text
        g2d.setColor(Color.WHITE);
        g2d.setFont(new Font("Arial", Font.BOLD, 12));
        
        int textY = overlayY + 20;
        g2d.drawString("TurboMC Region Inspector", overlayX + 10, textY);
        
        g2d.setFont(new Font("Arial", Font.PLAIN, 10));
        textY += 15;
        g2d.drawString("Region: " + region.getFile().getFileName().toString(), overlayX + 10, textY);
        
        textY += 12;
        g2d.drawString("Scale: 1:" + scale, overlayX + 10, textY);
        
        textY += 12;
        g2d.drawString("Chunks: " + region.getChunkCount() + "/1024", overlayX + 10, textY);
        
        textY += 12;
        g2d.drawString("Size: " + (32 * 16) + "x" + (32 * 16) + " blocks", overlayX + 10, textY);
        
        textY += 12;
        g2d.drawString("Format: LRF v" + region.getHeader().getVersion(), overlayX + 10, textY);
        
        // Legend
        textY += 20;
        g2d.setFont(new Font("Arial", Font.BOLD, 10));
        g2d.drawString("Legend:", overlayX + 10, textY);
        
        textY += 12;
        g2d.setFont(new Font("Arial", Font.PLAIN, 9));
        
        String[] legendBlocks = {"grass_block", "water", "stone", "dirt", "sand"};
        String[] legendNames = {"Grass", "Water", "Stone", "Dirt", "Sand"};
        
        for (int i = 0; i < legendBlocks.length && i < 5; i++) {
            Color color = BLOCK_COLORS.getOrDefault("minecraft:" + legendBlocks[i], Color.GRAY);
            g2d.setColor(color);
            g2d.fillRect(overlayX + 10 + i * 50, textY - 8, 8, 8);
            
            g2d.setColor(Color.WHITE);
            g2d.drawString(legendNames[i], overlayX + 22 + i * 50, textY);
        }
    }
    
    /**
     * Export minimap view (smaller version)
     */
    public void exportMinimap(LRFRegionFileAdapter region, String outputPath, int size) throws IOException {
        // Create a smaller version for minimap
        BufferedImage minimap = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = minimap.createGraphics();
        
        // Enable anti-aliasing
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        
        // Draw simplified region view
        int blockSize = Math.max(1, size / (32 * 16));
        
        for (int chunkX = 0; chunkX < 32; chunkX++) {
            for (int chunkZ = 0; chunkZ < 32; chunkZ++) {
                LRFChunkEntry chunk = region.getChunk(chunkX, chunkZ);
                if (chunk != null) {
                    // Draw chunk as a single colored block
                    Color chunkColor = getChunkColor(chunk);
                    g2d.setColor(chunkColor);
                    g2d.fillRect(
                        chunkX * 16 * blockSize,
                        chunkZ * 16 * blockSize,
                        16 * blockSize,
                        16 * blockSize
                    );
                }
            }
        }
        
        // Draw grid
        g2d.setColor(new Color(255, 255, 255, 100));
        g2d.setStroke(new BasicStroke(1));
        for (int i = 0; i <= 32; i++) {
            int pos = i * 16 * blockSize;
            g2d.drawLine(pos, 0, pos, size);
            g2d.drawLine(0, pos, size, pos);
        }
        
        // Save minimap
        ImageIO.write(minimap, "PNG", new File(outputPath));
        g2d.dispose();
    }
    
    /**
     * Get color for chunk based on content
     */
    private Color getChunkColor(LRFChunkEntry chunk) {
        // Generate color based on chunk compression and position
        String algorithm = chunk.getCompressionType();
        double ratio = (double) chunk.getCompressedSize() / chunk.getUncompressedSize();
        
        // Base color on algorithm
        Color baseColor;
        switch (algorithm) {
            case "LZ4": baseColor = new Color(0, 255, 0, 200); break;
            case "ZLIB": baseColor = new Color(255, 255, 0, 200); break;
            case "ZSTD": baseColor = new Color(0, 255, 255, 200); break;
            default: baseColor = new Color(255, 0, 255, 200); break;
        }
        
        // Adjust brightness based on compression ratio
        float brightness = (float) (1.0 - ratio); // Better compression = brighter
        return new Color(
            Math.min(255, (int) (baseColor.getRed() * brightness + 128 * (1 - brightness))),
            Math.min(255, (int) (baseColor.getGreen() * brightness + 128 * (1 - brightness))),
            Math.min(255, (int) (baseColor.getBlue() * brightness + 128 * (1 - brightness))),
            baseColor.getAlpha()
        );
    }
}
