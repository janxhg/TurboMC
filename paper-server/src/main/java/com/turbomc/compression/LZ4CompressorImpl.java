package com.turbomc.compression;

import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;

import java.nio.ByteBuffer;

/**
 * LZ4 implementation of the Compressor interface.
 * Uses LZ4 block compression for fast compression/decompression.
 */
public class LZ4CompressorImpl implements Compressor {
    private static final byte MAGIC_BYTE = 0x4C; // FIXED: Match service expectation
    
    private final LZ4Compressor compressor;
    private final LZ4FastDecompressor decompressor;
    private final int level;
    
    public LZ4CompressorImpl(int level) {
        this.level = Math.max(1, Math.min(17, level));
        LZ4Factory factory = LZ4Factory.fastestInstance();
        
        // Use fast compressor for levels 1-6, high compressor for 7+
        if (this.level <= 6) {
            this.compressor = factory.fastCompressor();
        } else {
            this.compressor = factory.highCompressor(this.level);
        }
        
        this.decompressor = factory.fastDecompressor();
    }
    
    @Override
    public byte[] compress(byte[] data) throws CompressionException {
        if (data == null || data.length == 0) {
            return new byte[]{MAGIC_BYTE, 0, 0, 0, 0};
        }
        
        try {
            int maxCompressedLength = compressor.maxCompressedLength(data.length);
            byte[] compressed = new byte[maxCompressedLength];
            
            int compressedLength = compressor.compress(data, 0, data.length, compressed, 0, maxCompressedLength);
            
            // Format: [magic byte (1)] [original size (4)] [compressed data]
            ByteBuffer buffer = ByteBuffer.allocate(1 + 4 + compressedLength);
            buffer.put(MAGIC_BYTE);
            buffer.putInt(data.length);
            buffer.put(compressed, 0, compressedLength);
            
            return buffer.array();
        } catch (Exception e) {
            throw new CompressionException("LZ4 compression failed", e);
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
                throw new CompressionException("Invalid magic byte for LZ4: " + magic);
            }
            
            // Read original size
            int originalSize = buffer.getInt();
            
            if (originalSize == 0) {
                return new byte[0];
            }
            
            // Decompress
            byte[] compressedData = new byte[buffer.remaining()];
            buffer.get(compressedData);
            
            byte[] decompressed = new byte[originalSize];
            decompressor.decompress(compressedData, 0, decompressed, 0, originalSize);
            
            return decompressed;
        } catch (Exception e) {
            throw new CompressionException("LZ4 decompression failed", e);
        }
    }
    
    @Override
    public String getName() {
        return "LZ4";
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
