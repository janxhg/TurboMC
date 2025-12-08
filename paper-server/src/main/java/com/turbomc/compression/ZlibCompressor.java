package com.turbomc.compression;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Zlib implementation of the Compressor interface.
 * Compatible with vanilla Minecraft compression format.
 */
public class ZlibCompressor implements Compressor {
    private static final byte MAGIC_BYTE = 0x01;
    
    private final int level;
    
    public ZlibCompressor(int level) {
        this.level = Math.max(1, Math.min(9, level));
    }
    
    @Override
    public byte[] compress(byte[] data) throws CompressionException {
        if (data == null || data.length == 0) {
            return new byte[]{MAGIC_BYTE, 0, 0, 0, 0};
        }
        
        try {
            Deflater deflater = new Deflater(level);
            deflater.setInput(data);
            deflater.finish();
            
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream(data.length);
            byte[] buffer = new byte[1024];
            
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                outputStream.write(buffer, 0, count);
            }
            
            deflater.end();
            byte[] compressed = outputStream.toByteArray();
            
            // Format: [magic byte (1)] [original size (4)] [compressed data]
            ByteBuffer result = ByteBuffer.allocate(1 + 4 + compressed.length);
            result.put(MAGIC_BYTE);
            result.putInt(data.length);
            result.put(compressed);
            
            return result.array();
        } catch (Exception e) {
            throw new CompressionException("Zlib compression failed", e);
        }
    }
    
    @Override
    public byte[] decompress(byte[] compressed) throws CompressionException {
        if (compressed == null || compressed.length < 5) {
            throw new CompressionException("Invalid compressed data: too short");
        }
        
        try {
            ByteBuffer buffer = ByteBuffer.wrap(compressed);
            
            // Read magic byte
            byte magic = buffer.get();
            if (magic != MAGIC_BYTE) {
                throw new CompressionException("Invalid magic byte for Zlib: " + magic);
            }
            
            // Read original size
            int originalSize = buffer.getInt();
            
            if (originalSize == 0) {
                return new byte[0];
            }
            
            // Decompress
            byte[] compressedData = new byte[buffer.remaining()];
            buffer.get(compressedData);
            
            Inflater inflater = new Inflater();
            inflater.setInput(compressedData);
            
            byte[] decompressed = new byte[originalSize];
            int resultLength = inflater.inflate(decompressed);
            inflater.end();
            
            if (resultLength != originalSize) {
                throw new CompressionException("Decompressed size mismatch: expected " + originalSize + ", got " + resultLength);
            }
            
            return decompressed;
        } catch (Exception e) {
            throw new CompressionException("Zlib decompression failed", e);
        }
    }
    
    @Override
    public String getName() {
        return "Zlib";
    }
    
    @Override
    public int getCompressionLevel() {
        return level;
    }
    
    @Override
    public byte getMagicByte() {
        return MAGIC_BYTE;
    }
}
