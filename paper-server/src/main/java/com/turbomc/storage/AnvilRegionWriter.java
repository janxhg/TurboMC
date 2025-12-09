package com.turbomc.storage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Writes Minecraft Anvil (.mca) region files.
 * Used for reverse conversion from LRF to MCA.
 * 
 * @author TurboMC
 * @version 1.0.0
 */
public class AnvilRegionWriter implements AutoCloseable {
    
    private static final int SECTOR_SIZE = 4096;
    private static final int HEADER_SECTORS = 2;
    private static final int HEADER_SIZE = SECTOR_SIZE * HEADER_SECTORS; // 8KB
    private static final byte COMPRESSION_ZLIB = 2;
    
    private final Path filePath;
    private final RandomAccessFile file;
    private final List<LRFChunkEntry> chunks;
    private final int[] locations;
    private final int[] timestamps;
    private boolean headerWritten;
    
    /**
     * Create a new Anvil region file for writing.
     * 
     * @param filePath Path to .mca file
     * @throws IOException if file cannot be created
     */
    public AnvilRegionWriter(Path filePath) throws IOException {
        this.filePath = filePath;
        this.file = new RandomAccessFile(filePath.toFile(), "rw");
        this.chunks = new ArrayList<>();
        this.locations = new int[LRFConstants.CHUNKS_PER_REGION];
        this.timestamps = new int[LRFConstants.CHUNKS_PER_REGION];
        this.headerWritten = false;
        
        // Reserve space for header
        file.setLength(HEADER_SIZE);
        
        System.out.println("[TurboMC] Creating Anvil region: " + filePath.getFileName());
    }
    
    /**
     * Add a chunk to be written.
     * 
     * @param chunk Chunk entry with uncompressed NBT data
     */
    public void addChunk(LRFChunkEntry chunk) {
        if (headerWritten) {
            throw new IllegalStateException("Cannot add chunks after header is written");
        }
        chunks.add(chunk);
    }
    
    /**
     * Write all buffered chunks to file.
     * 
     * @throws IOException if write fails
     */
    public void flush() throws IOException {
        if (headerWritten) {
            return;
        }
        
        int currentSector = HEADER_SECTORS; // Start after header
        
        for (LRFChunkEntry chunk : chunks) {
            // Compress chunk data with Zlib
            byte[] compressedData = compressChunk(chunk.getData());
            
            // Calculate chunk size (4 byte length + 1 byte compression + data)
            int chunkSize = 4 + 1 + compressedData.length;
            int sectorsNeeded = (chunkSize + SECTOR_SIZE - 1) / SECTOR_SIZE;
            
            // Seek to current sector
            file.seek(currentSector * SECTOR_SIZE);
            
            // Write chunk header
            file.writeInt(compressedData.length + 1); // +1 for compression type
            file.writeByte(COMPRESSION_ZLIB);
            
            // Write compressed data
            file.write(compressedData);
            
            // Pad to sector boundary
            int padding = (sectorsNeeded * SECTOR_SIZE) - chunkSize;
            if (padding > 0) {
                file.write(new byte[padding]);
            }
            
            // Update location and timestamp tables
            int index = LRFConstants.getChunkIndex(chunk.getChunkX(), chunk.getChunkZ());
            locations[index] = (currentSector << 8) | (sectorsNeeded & 0xFF);
            timestamps[index] = (int) chunk.getTimestamp();
            
            currentSector += sectorsNeeded;
        }
        
        // Write header
        writeHeader();
        
        // Truncate to actual size
        file.setLength(currentSector * SECTOR_SIZE);
        
        headerWritten = true;
        
        System.out.println("[TurboMC] Wrote " + chunks.size() + " chunks to " + 
                         filePath.getFileName() + " (" + (currentSector * SECTOR_SIZE) + " bytes)");
    }
    
    /**
     * Compress chunk data with Zlib.
     */
    private byte[] compressChunk(byte[] data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        
        try (DeflaterOutputStream dos = new DeflaterOutputStream(baos, deflater)) {
            dos.write(data);
        }
        
        return baos.toByteArray();
    }
    
    /**
     * Write the 8KB Anvil header.
     */
    private void writeHeader() throws IOException {
        ByteBuffer header = ByteBuffer.allocate(HEADER_SIZE);
        
        // Write locations (first 4KB)
        for (int location : locations) {
            header.putInt(location);
        }
        
        // Write timestamps (second 4KB)
        for (int timestamp : timestamps) {
            header.putInt(timestamp);
        }
        
        // Write header to file
        file.seek(0);
        file.write(header.array());
    }
    
    /**
     * Get number of chunks buffered.
     * 
     * @return Chunk count
     */
    public int getChunkCount() {
        return chunks.size();
    }
    
    /**
     * Get file path.
     * 
     * @return Path to MCA file
     */
    public Path getFilePath() {
        return filePath;
    }
    
    @Override
    public void close() throws IOException {
        try {
            if (!headerWritten && !chunks.isEmpty()) {
                flush();
            }
        } finally {
            if (file != null) {
                file.close();
            }
        }
    }
    
    @Override
    public String toString() {
        return "AnvilRegionWriter{" +
               "file=" + filePath.getFileName() +
               ", chunks=" + chunks.size() +
               ", written=" + headerWritten +
               '}';
    }
}
