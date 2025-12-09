package com.turbomc.storage;

import com.turbomc.compression.TurboCompressionService;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads LRF (Linear Region Format) files.
 * Provides efficient sequential and random access to chunks.
 * 
 * @author TurboMC
 * @version 1.0.0
 */
public class LRFRegionReader implements AutoCloseable {
    
    private final Path filePath;
    private final RandomAccessFile file;
    private final FileChannel channel;
    private final LRFHeader header;
    
    /**
     * Open an LRF file for reading.
     * 
     * @param filePath Path to .lrf file
     * @throws IOException if file cannot be opened or is invalid
     */
    public LRFRegionReader(Path filePath) throws IOException {
        this.filePath = filePath;
        this.file = new RandomAccessFile(filePath.toFile(), "r");
        this.channel = file.getChannel();
        
        // Read and validate header
        ByteBuffer headerBuffer = ByteBuffer.allocate(LRFConstants.HEADER_SIZE);
        channel.read(headerBuffer, 0);
        headerBuffer.flip();
        
        try {
            this.header = LRFHeader.read(headerBuffer);
        } catch (IllegalArgumentException e) {
            close();
            throw new IOException("Invalid LRF file: " + filePath, e);
        }
        
        System.out.println("[TurboMC] Opened LRF region: " + filePath.getFileName() + 
                         " (" + header.getChunkCount() + " chunks, " + 
                         LRFConstants.getCompressionName(header.getCompressionType()) + ")");
    }
    
    /**
     * Read a specific chunk.
     * 
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate
     * @return Chunk entry, or null if chunk doesn't exist
     * @throws IOException if read fails
     */
    public LRFChunkEntry readChunk(int chunkX, int chunkZ) throws IOException {
        if (!header.hasChunk(chunkX, chunkZ)) {
            return null;
        }
        
        int offset = header.getChunkOffset(chunkX, chunkZ);
        int size = header.getChunkSize(chunkX, chunkZ);
        
        if (size <= 0 || size > LRFConstants.MAX_CHUNK_SIZE) {
            throw new IOException("Invalid chunk size: " + size + " bytes");
        }
        
        // Read compressed chunk data
        ByteBuffer buffer = ByteBuffer.allocate(size);
        int bytesRead = channel.read(buffer, offset);
        
        if (bytesRead != size) {
            throw new IOException("Failed to read chunk: expected " + size + 
                                " bytes, got " + bytesRead);
        }
        
        buffer.flip();
        byte[] compressedData = new byte[size];
        buffer.get(compressedData);
        
        // Decompress if needed
        byte[] data;
        if (header.getCompressionType() == LRFConstants.COMPRESSION_NONE) {
            data = compressedData;
        } else {
            data = TurboCompressionService.getInstance().decompress(compressedData);
        }
        
        // Timestamp is stored at the end of each chunk (8 bytes)
        long timestamp = 0;
        if (data.length >= 8) {
            ByteBuffer tsBuffer = ByteBuffer.wrap(data, data.length - 8, 8);
            timestamp = tsBuffer.getLong();
        }
        
        return new LRFChunkEntry(chunkX, chunkZ, data, timestamp);
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
                if (header.hasChunk(x, z)) {
                    LRFChunkEntry chunk = readChunk(x, z);
                    if (chunk != null) {
                        chunks.add(chunk);
                    }
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
        return header.hasChunk(chunkX, chunkZ);
    }
    
    /**
     * Get the file header.
     * 
     * @return LRF header
     */
    public LRFHeader getHeader() {
        return header;
    }
    
    /**
     * Get file path.
     * 
     * @return Path to LRF file
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
        return channel.size();
    }
    
    @Override
    public void close() throws IOException {
        if (channel != null && channel.isOpen()) {
            channel.close();
        }
        if (file != null) {
            file.close();
        }
    }
    
    @Override
    public String toString() {
        return "LRFRegionReader{" +
               "file=" + filePath.getFileName() +
               ", chunks=" + header.getChunkCount() +
               ", compression=" + LRFConstants.getCompressionName(header.getCompressionType()) +
               '}';
    }
}
