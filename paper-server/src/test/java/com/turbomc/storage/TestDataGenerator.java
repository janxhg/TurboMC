package com.turbomc.storage;

import com.turbomc.storage.lrf.LRFChunkEntry;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Random;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;
import java.io.ByteArrayOutputStream;

/**
 * Generates realistic test data for LRF testing.
 * Creates valid MCA files and chunk NBT data.
 */
public class TestDataGenerator {
    
    private static final int SECTOR_SIZE = 4096;
    private static final int HEADER_SIZE = SECTOR_SIZE * 2; // 8KB
    private static final int CHUNKS_PER_REGION = 32 * 32; // 1024
    
    private final Random random;
    
    public TestDataGenerator(long seed) {
        this.random = new Random(seed);
    }
    
    /**
     * Creates a valid MCA file with realistic chunk data.
     * 
     * @param filePath Path where to create the MCA file
     * @param chunkCount Number of chunks to populate (0-1024)
     * @throws IOException if file creation fails
     */
    public void createValidMCAFile(Path filePath, int chunkCount) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(filePath.toFile(), "rw")) {
            // Write header (8KB)
            writeMCAHeader(file, chunkCount);
            
            // Write chunk data
            writeMCAChunks(file, chunkCount);
            
            // Truncate to actual size
            long fileSize = file.getFilePointer();
            file.setLength(fileSize);
        }
    }
    
    /**
     * Writes the MCA header with chunk locations and timestamps.
     */
    private void writeMCAHeader(RandomAccessFile file, int chunkCount) throws IOException {
        // Location table (first 4KB)
        int currentOffset = 2; // Start at sector 2 (after header)
        
        for (int i = 0; i < CHUNKS_PER_REGION; i++) {
            if (i < chunkCount) {
                int sectorCount = 4; // Each chunk takes 4 sectors (16KB)
                int location = (currentOffset << 8) | sectorCount;
                file.writeInt(location);
                currentOffset += sectorCount;
            } else {
                file.writeInt(0); // Empty chunk
            }
        }
        
        // Timestamp table (second 4KB)
        for (int i = 0; i < CHUNKS_PER_REGION; i++) {
            if (i < chunkCount) {
                int timestamp = 1000 + i; // Realistic timestamps
                file.writeInt(timestamp);
            } else {
                file.writeInt(0);
            }
        }
    }
    
    /**
     * Writes realistic chunk data to MCA file.
     */
    private void writeMCAChunks(RandomAccessFile file, int chunkCount) throws IOException {
        for (int i = 0; i < chunkCount; i++) {
            // Align to sector boundary
            long position = file.getFilePointer();
            long sectorPosition = (position + SECTOR_SIZE - 1) / SECTOR_SIZE * SECTOR_SIZE;
            if (position != sectorPosition) {
                file.seek(sectorPosition);
            }
            
            // Generate realistic chunk NBT data
            byte[] nbtData = generateChunkNBT(i);
            
            // Compress with Zlib (standard Minecraft compression)
            byte[] compressedData = compressZlib(nbtData);
            
            // Write chunk header (4 bytes length + 1 byte compression type)
            file.writeInt(compressedData.length + 1); // +1 for compression type
            file.writeByte(2); // Zlib compression type
            
            // Write compressed data
            file.write(compressedData);
        }
    }
    
    /**
     * Generates realistic chunk NBT data.
     * Creates proper NBT structure with sections, entities, etc.
     */
    private byte[] generateChunkNBT(int chunkIndex) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        
        // NBT structure for a chunk
        writeNBTCompound(baos, "Data", () -> {
            try {
                writeNBTInt(baos, "DataVersion", 3465);
                writeNBTList(baos, "Sections", 10, () -> {
                    // Write 10 sections (Y levels 0-9)
                    for (int y = 0; y < 10; y++) {
                        final byte yValue = (byte) y;
                        try {
                            writeNBTCompound(baos, "", () -> {
                                try {
                                    writeNBTByte(baos, "Y", yValue);
                                    writeNBTByteArray(baos, "Blocks", generateBlockData());
                                    writeNBTByteArray(baos, "Data", generateBlockData());
                                    writeNBTByteArray(baos, "BlockLight", generateLightData());
                                    writeNBTByteArray(baos, "SkyLight", generateLightData());
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                            });
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                });
                writeNBTList(baos, "Entities", 0, () -> {}); // Empty entities list
                writeNBTList(baos, "TileEntities", 0, () -> {}); // Empty tile entities list
                writeNBTInt(baos, "xPos", chunkIndex % 32);
                writeNBTInt(baos, "zPos", chunkIndex / 32);
                writeNBTLong(baos, "LastUpdate", System.currentTimeMillis());
                writeNBTBoolean(baos, "TerrainPopulated", true);
                writeNBTBoolean(baos, "LightPopulated", true);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        
        return baos.toByteArray();
    }
    
    /**
     * Generates realistic block data for a chunk section.
     */
    private byte[] generateBlockData() {
        byte[] data = new byte[4096]; // 16x16x16 blocks
        for (int i = 0; i < data.length; i++) {
            // Generate realistic block distribution
            int blockType = random.nextInt(20); // 0-19 = common blocks
            if (blockType < 10) {
                data[i] = (byte) (blockType + 1); // Stone, dirt, grass, etc.
            } else if (blockType < 15) {
                data[i] = (byte) (blockType - 9); // Wood, leaves, etc.
            } else {
                data[i] = 0; // Air
            }
        }
        return data;
    }
    
    /**
     * Generates light data for a chunk section.
     */
    private byte[] generateLightData() {
        byte[] data = new byte[2048]; // 4 bits per block
        for (int i = 0; i < data.length; i++) {
            // Realistic light levels (0-15)
            data[i] = (byte) (random.nextInt(16) | (random.nextInt(16) << 4));
        }
        return data;
    }
    
    /**
     * Compresses data using Zlib (Minecraft standard).
     */
    private byte[] compressZlib(byte[] data) throws IOException {
        Deflater deflater = new Deflater(6); // Default compression level
        deflater.setInput(data);
        deflater.finish();
        
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        
        while (!deflater.finished()) {
            int count = deflater.deflate(buffer);
            baos.write(buffer, 0, count);
        }
        
        deflater.end();
        return baos.toByteArray();
    }
    
    // NBT writing methods
    
    private void writeNBTCompound(ByteArrayOutputStream baos, String name, Runnable writer) throws IOException {
        writeNBTString(baos, name);
        writer.run();
    }
    
    private void writeNBTList(ByteArrayOutputStream baos, String name, int size, Runnable writer) throws IOException {
        writeNBTByte(baos, name, (byte) 9); // TAG_List
        baos.write(size);
        writer.run();
    }
    
    private void writeNBTByte(ByteArrayOutputStream baos, String name, byte value) throws IOException {
        baos.write(1); // TAG_Byte
        writeNBTString(baos, name);
        baos.write(value);
    }
    
    private void writeNBTInt(ByteArrayOutputStream baos, String name, int value) throws IOException {
        baos.write(3); // TAG_Int
        writeNBTString(baos, name);
        baos.write((value >>> 24) & 0xFF);
        baos.write((value >>> 16) & 0xFF);
        baos.write((value >>> 8) & 0xFF);
        baos.write(value & 0xFF);
    }
    
    private void writeNBTLong(ByteArrayOutputStream baos, String name, long value) throws IOException {
        baos.write(4); // TAG_Long
        writeNBTString(baos, name);
        baos.write((byte)(value >>> 56));
        baos.write((byte)(value >>> 48));
        baos.write((byte)(value >>> 40));
        baos.write((byte)(value >>> 32));
        baos.write((byte)(value >>> 24));
        baos.write((byte)(value >>> 16));
        baos.write((byte)(value >>> 8));
        baos.write((byte)value);
    }
    
    private void writeNBTByteArray(ByteArrayOutputStream baos, String name, byte[] data) throws IOException {
        baos.write(7); // TAG_Byte_Array
        writeNBTString(baos, name);
        baos.write((data.length >>> 24) & 0xFF);
        baos.write((data.length >>> 16) & 0xFF);
        baos.write((data.length >>> 8) & 0xFF);
        baos.write(data.length & 0xFF);
        baos.write(data);
    }
    
    private void writeNBTBoolean(ByteArrayOutputStream baos, String name, boolean value) throws IOException {
        baos.write(1); // TAG_Byte (used for boolean)
        writeNBTString(baos, name);
        baos.write(value ? 1 : 0);
    }
    
    private void writeNBTString(ByteArrayOutputStream baos, String name) throws IOException {
        if (name.isEmpty()) {
            baos.write(0); // Empty string length
        } else {
            baos.write(name.length());
            baos.write(name.getBytes());
        }
    }
    
    /**
     * Creates a simple chunk entry with test data.
     */
    public LRFChunkEntry createTestChunk(int chunkX, int chunkZ) {
        byte[] testData = new byte[1024 + random.nextInt(2048)]; // 1-3KB
        random.nextBytes(testData);
        return new LRFChunkEntry(chunkX, chunkZ, testData);
    }
    
    /**
     * Creates multiple test chunks.
     */
    public LRFChunkEntry[] createTestChunks(int count) {
        LRFChunkEntry[] chunks = new LRFChunkEntry[count];
        for (int i = 0; i < count; i++) {
            chunks[i] = createTestChunk(i % 32, i / 32);
        }
        return chunks;
    }
}
