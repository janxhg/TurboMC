package com.turbomc.storage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Reads Minecraft Anvil (.mca) region files.
 * Supports both GZIP and Zlib compression.
 * 
 * Anvil Format Structure:
 * - Header: 8KB (4KB locations + 4KB timestamps)
 * - Chunks: Variable size, 4KB aligned
 * 
 * @author TurboMC
 * @version 1.0.0
 */
public class AnvilRegionReader implements AutoCloseable {
    
    private static final int SECTOR_SIZE = 4096;
    private static final int HEADER_SECTORS = 2;
    private static final int HEADER_SIZE = SECTOR_SIZE * HEADER_SECTORS; // 8KB
    
    // Compression types
    private static final byte COMPRESSION_GZIP = 1;
    private static final byte COMPRESSION_ZLIB = 2;
    private static final byte COMPRESSION_NONE = 3;  // Uncompressed
    private static final byte COMPRESSION_LZ4 = 4;    // LZ4 compression
    
    private final Path filePath;
    private final RandomAccessFile file;
    private final int[] locations;   // Offset and sector count for each chunk
    private final int[] timestamps;  // Last modification time for each chunk
    
    /**
     * Open an Anvil region file for reading.
     * 
     * @param filePath Path to .mca file
     * @throws IOException if file cannot be opened
     */
    public AnvilRegionReader(Path filePath) throws IOException {
        this.filePath = filePath;
        this.file = new RandomAccessFile(filePath.toFile(), "r");
        this.locations = new int[LRFConstants.CHUNKS_PER_REGION];
        this.timestamps = new int[LRFConstants.CHUNKS_PER_REGION];
        
        // Read header
        readHeader();
        
        int chunkCount = countChunks();
        System.out.println("[TurboMC] Opened Anvil region: " + filePath.getFileName() + 
                         " (" + chunkCount + " chunks)");
    }
    
    /**
     * Read the 8KB Anvil header.
     */
    private void readHeader() throws IOException {
        byte[] headerData = new byte[HEADER_SIZE];
        file.seek(0);
        file.readFully(headerData);
        
        ByteBuffer buffer = ByteBuffer.wrap(headerData);
        
        // Read locations (first 4KB)
        for (int i = 0; i < LRFConstants.CHUNKS_PER_REGION; i++) {
            locations[i] = buffer.getInt();
        }
        
        // Read timestamps (second 4KB)
        for (int i = 0; i < LRFConstants.CHUNKS_PER_REGION; i++) {
            timestamps[i] = buffer.getInt();
        }
    }
    
    /**
     * Count non-empty chunks.
     */
    private int countChunks() {
        int count = 0;
        for (int location : locations) {
            if (location != 0) {
                count++;
            }
        }
        return count;
    }
    
    /**
     * Read a specific chunk.
     * 
     * @param chunkX Chunk X coordinate (local to region, 0-31)
     * @param chunkZ Chunk Z coordinate (local to region, 0-31)
     * @return Chunk entry, or null if chunk doesn't exist
     * @throws IOException if read fails
     */
    public LRFChunkEntry readChunk(int chunkX, int chunkZ) throws IOException {
        int index = LRFConstants.getChunkIndex(chunkX, chunkZ);
        int location = locations[index];
        
        if (location == 0) {
            return null; // Chunk doesn't exist
        }
        
        // Parse location (3 bytes offset + 1 byte sector count)
        int offset = (location >>> 8) * SECTOR_SIZE;
        int sectorCount = location & 0xFF;
        
        if (sectorCount == 0) {
            return null;
        }
        
        // Read chunk header (5 bytes: 4 byte length + 1 byte compression type)
        file.seek(offset);
        int length = file.readInt();
        byte compressionType = file.readByte();
        
        if (length <= 0 || length > sectorCount * SECTOR_SIZE) {
            throw new IOException("Invalid chunk length: " + length);
        }
        
        // Read compressed data
        byte[] compressedData = new byte[length - 1]; // -1 for compression type byte
        file.readFully(compressedData);
        
        // Decompress
        byte[] decompressedData = decompressChunk(compressedData, compressionType);
        
        // Get timestamp
        long timestamp = timestamps[index] & 0xFFFFFFFFL; // Unsigned int to long
        
        return new LRFChunkEntry(chunkX, chunkZ, decompressedData, timestamp);
    }
    
    /**
     * Decompress chunk data based on compression type.
     */
    private byte[] decompressChunk(byte[] compressedData, byte compressionType) throws IOException {
        try {
            if (compressionType == COMPRESSION_GZIP) {
                return readAllBytes(new GZIPInputStream(new ByteArrayInputStream(compressedData)));
            } else if (compressionType == COMPRESSION_ZLIB) {
                return readAllBytes(new InflaterInputStream(new ByteArrayInputStream(compressedData)));
            } else if (compressionType == COMPRESSION_NONE) {
                // Uncompressed data
                return compressedData;
            } else if (compressionType == COMPRESSION_LZ4) {
                // LZ4 compression - for now, skip these chunks
                System.err.println("[TurboMC] Warning: LZ4 compression not yet supported, skipping chunk");
                throw new IOException("LZ4 compression not supported");
            } else {
                throw new IOException("Unknown compression type: " + compressionType);
            }
        } catch (Exception e) {
            System.err.println("[TurboMC] Warning: Failed to decompress chunk, skipping: " + e.getMessage());
            throw new IOException("Failed to decompress chunk", e);
        }
    }
    
    /**
     * Read all bytes from an input stream.
     */
    private byte[] readAllBytes(java.io.InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int bytesRead;
        
        while ((bytesRead = in.read(buffer)) != -1) {
            out.write(buffer, 0, bytesRead);
        }
        
        in.close();
        return out.toByteArray();
    }
    
    /**
     * Read all chunks in the region.
     * 
     * @return List of all chunk entries
     * @throws IOException if read fails
     */
    public List<LRFChunkEntry> readAllChunks() throws IOException {
        List<LRFChunkEntry> chunks = new ArrayList<>();
        
        for (int x = 0; x < LRFConstants.REGION_SIZE; x++) {
            for (int z = 0; z < LRFConstants.REGION_SIZE; z++) {
                try {
                    LRFChunkEntry chunk = readChunk(x, z);
                    if (chunk != null) {
                        chunks.add(chunk);
                    }
                } catch (Exception e) {
                    // Skip corrupt chunks but continue reading others
                    System.err.println("[TurboMC] Warning: Skipping corrupt chunk at [" + x + "," + z + "]: " + e.getMessage());
                }
            }
        }
        
        return chunks;
    }
    
    /**
     * Check if chunk exists.
     * 
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return True if chunk exists
     */
    public boolean hasChunk(int chunkX, int chunkZ) {
        int index = LRFConstants.getChunkIndex(chunkX, chunkZ);
        return locations[index] != 0;
    }
    
    /**
     * Get file path.
     * 
     * @return Path to MCA file
     */
    public Path getFilePath() {
        return filePath;
    }
    
    /**
     * Get file size in bytes.
     * 
     * @return File size
     * @throws IOException if size cannot be determined
     */
    public long getFileSize() throws IOException {
        return file.length();
    }
    
    @Override
    public void close() throws IOException {
        if (file != null) {
            file.close();
        }
    }
    
    @Override
    public String toString() {
        return "AnvilRegionReader{" +
               "file=" + filePath.getFileName() +
               ", chunks=" + countChunks() +
               '}';
    }
    
    /**
     * Helper class for ByteArrayOutputStream (since we can't use Java 9+ readAllBytes).
     */
    private static class ByteArrayOutputStream extends java.io.ByteArrayOutputStream {
        public byte[] toByteArray() {
            return super.toByteArray();
        }
    }
}
