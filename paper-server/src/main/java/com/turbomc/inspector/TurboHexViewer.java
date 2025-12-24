package com.turbomc.inspector;

import com.turbomc.storage.lrf.LRFRegionFileAdapter;
import com.turbomc.storage.lrf.LRFChunkEntry;
import com.turbomc.storage.lrf.LRFHeader;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Hexadecimal viewer for LRF regions and chunks
 * 
 * Features:
 * - Full file hex dump with addresses
 * - Chunk-specific hex dumps
 * - Highlighted LRF magic bytes
 * - Compression algorithm markers
 * - Color-coded sections
 * 
 * @author TurboMC
 * @version 1.0.0
 */
public class TurboHexViewer {
    
    private static final int BYTES_PER_LINE = 16;
    private static final int CHUNK_SIZE = 32 * 32 * 256; // Maximum chunk size
    
    // ANSI color codes for terminal output
    private static final String RESET = "\u001B[0m";
    private static final String RED = "\u001B[31m";
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String BLUE = "\u001B[34m";
    private static final String CYAN = "\u001B[36m";
    private static final String BOLD = "\u001B[1m";
    
    /**
     * Generate complete hex dump of LRF region file
     */
    public String generateHexDump(LRFRegionFileAdapter region) throws IOException {
        StringBuilder output = new StringBuilder();
        Path filePath = region.getFile();
        byte[] fileData = Files.readAllBytes(filePath);
        
        output.append(BOLD).append(CYAN);
        output.append("=== LRF Region Hex Dump ===\n");
        output.append("File: ").append(filePath.getFileName()).append("\n");
        output.append("Size: ").append(fileData.length).append(" bytes\n");
        output.append(RESET).append("\n");
        
        // Header section with highlighting
        output.append(BOLD).append(YELLOW);
        output.append("--- LRF Header ---\n");
        output.append(RESET);
        
        int headerSize = 64; // Approximate header size
        int headerEnd = Math.min(headerSize, fileData.length);
        
        for (int i = 0; i < headerEnd; i += BYTES_PER_LINE) {
            String line = formatHexLine(fileData, i, Math.min(i + BYTES_PER_LINE, headerEnd), true);
            output.append(line).append("\n");
        }
        
        // Chunk entries section
        output.append("\n");
        output.append(BOLD).append(YELLOW);
        output.append("--- Chunk Entries ---\n");
        output.append(RESET);
        
        int chunkEntriesStart = headerSize;
        int chunkEntriesEnd = Math.min(chunkEntriesStart + (1024 * 8), fileData.length); // 1024 chunks * 8 bytes each
        
        for (int i = chunkEntriesStart; i < chunkEntriesEnd; i += BYTES_PER_LINE) {
            String line = formatHexLine(fileData, i, Math.min(i + BYTES_PER_LINE, chunkEntriesEnd), false);
            output.append(line).append("\n");
        }
        
        // Data section
        output.append("\n");
        output.append(BOLD).append(YELLOW);
        output.append("--- Chunk Data ---\n");
        output.append(RESET);
        
        for (int i = chunkEntriesEnd; i < fileData.length; i += BYTES_PER_LINE) {
            String line = formatHexLine(fileData, i, Math.min(i + BYTES_PER_LINE, fileData.length), false);
            output.append(line).append("\n");
        }
        
        return output.toString();
    }
    
    /**
     * Generate hex dump for specific chunk
     */
    public String generateChunkHexDump(LRFChunkEntry chunk) throws IOException {
        StringBuilder output = new StringBuilder();
        
        output.append(BOLD).append(CYAN);
        output.append("=== LRF Chunk Hex Dump ===\n");
        output.append("Chunk: [").append(chunk.getChunkX()).append(",").append(chunk.getChunkZ()).append("]\n");
        output.append("Compression: ").append(chunk.getCompressionType()).append("\n");
        output.append("Size: ").append(chunk.getCompressedSize()).append(" bytes (compressed)\n");
        output.append("Size: ").append(chunk.getUncompressedSize()).append(" bytes (uncompressed)\n");
        output.append(RESET).append("\n");
        
        byte[] chunkData = chunk.getData();
        
        // Highlight compression markers
        output.append(BOLD).append(YELLOW);
        output.append("--- Compression Header ---\n");
        output.append(RESET);
        
        int headerSize = Math.min(16, chunkData.length);
        for (int i = 0; i < headerSize; i += BYTES_PER_LINE) {
            String line = formatHexLine(chunkData, i, Math.min(i + BYTES_PER_LINE, headerSize), true);
            output.append(line).append("\n");
        }
        
        // Data section
        output.append("\n");
        output.append(BOLD).append(YELLOW);
        output.append("--- Chunk Data ---\n");
        output.append(RESET);
        
        for (int i = headerSize; i < chunkData.length; i += BYTES_PER_LINE) {
            String line = formatHexLine(chunkData, i, Math.min(i + BYTES_PER_LINE, chunkData.length), false);
            output.append(line).append("\n");
        }
        
        return output.toString();
    }
    
    /**
     * Format a single line of hex dump
     */
    private String formatHexLine(byte[] data, int start, int end, boolean highlightImportant) {
        StringBuilder line = new StringBuilder();
        
        // Address
        line.append(String.format("%08X: ", start));
        
        // Hex bytes
        for (int i = start; i < end; i++) {
            byte b = data[i];
            String hex = String.format("%02X", b & 0xFF);
            
            // Color coding for important bytes
            if (highlightImportant && isImportantByte(start + i, b)) {
                line.append(BOLD).append(getByteColor(b)).append(hex).append(RESET);
            } else {
                line.append(hex);
            }
            
            line.append(" ");
            
            // Add space after 8 bytes for readability
            if ((i - start + 1) % 8 == 0) {
                line.append(" ");
            }
        }
        
        // Padding for incomplete lines
        for (int i = end; i < start + BYTES_PER_LINE; i++) {
            line.append("   ");
            if ((i - start + 1) % 8 == 0) {
                line.append(" ");
            }
        }
        
        // ASCII representation
        line.append(" |");
        for (int i = start; i < end; i++) {
            char c = (char) (data[i] & 0xFF);
            if (c >= 32 && c <= 126) {
                line.append(c);
            } else {
                line.append(".");
            }
        }
        line.append("|");
        
        return line.toString();
    }
    
    /**
     * Check if byte is important (magic bytes, markers, etc.)
     */
    private boolean isImportantByte(int offset, byte b) {
        // LRF magic bytes: "LRF\0"
        if (offset < 4) {
            byte[] lrfMagic = {0x4C, 0x52, 0x46, 0x00}; // LRF\0
            return offset < lrfMagic.length && b == lrfMagic[offset];
        }
        
        // Compression algorithm markers
        if (offset == 8) { // Compression type field
            return true;
        }
        
        // Chunk entry headers
        if (offset >= 64 && offset < 64 + (1024 * 8)) {
            int chunkOffset = offset - 64;
            if (chunkOffset % 8 < 4) { // Chunk position fields
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Get color for specific byte type
     */
    private String getByteColor(byte b) {
        // Magic bytes - bright green
        if (b == 0x4C || b == 0x52 || b == 0x46) { // L, R, F
            return GREEN;
        }
        
        // Null terminator - red
        if (b == 0x00) {
            return RED;
        }
        
        // Compression types - different colors
        if (b == 0x01) return BLUE;   // LZ4
        if (b == 0x02) return YELLOW; // ZLIB
        if (b == 0x03) return CYAN;   // ZSTD
        
        return RESET;
    }
    
    /**
     * Generate simplified hex dump for CLI output
     */
    public String generateSimpleHexDump(byte[] data, int maxLines) {
        StringBuilder output = new StringBuilder();
        
        int linesToShow = Math.min(maxLines, (data.length + BYTES_PER_LINE - 1) / BYTES_PER_LINE);
        
        for (int i = 0; i < linesToShow; i++) {
            int start = i * BYTES_PER_LINE;
            int end = Math.min(start + BYTES_PER_LINE, data.length);
            
            String line = formatHexLine(data, start, end, false);
            output.append(line).append("\n");
        }
        
        if (linesToShow < (data.length + BYTES_PER_LINE - 1) / BYTES_PER_LINE) {
            output.append("... (").append(data.length - linesToShow * BYTES_PER_LINE).append(" more bytes)\n");
        }
        
        return output.toString();
    }
    
    /**
     * Search for specific byte pattern in hex dump
     */
    public List<Integer> searchPattern(byte[] data, byte[] pattern) {
        List<Integer> matches = new ArrayList<>();
        
        for (int i = 0; i <= data.length - pattern.length; i++) {
            boolean match = true;
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                matches.add(i);
            }
        }
        
        return matches;
    }
    
    /**
     * Generate statistics about byte distribution
     */
    public Map<String, Integer> analyzeByteDistribution(byte[] data) {
        Map<String, Integer> distribution = new HashMap<>();
        
        for (byte b : data) {
            int value = b & 0xFF;
            String category;
            
            if (value == 0x00) {
                category = "NULL";
            } else if (value >= 0x20 && value <= 0x7E) {
                category = "ASCII";
            } else if (value >= 0x80 && value <= 0xFF) {
                category = "Extended";
            } else {
                category = "Control";
            }
            
            distribution.put(category, distribution.getOrDefault(category, 0) + 1);
        }
        
        return distribution;
    }
}
