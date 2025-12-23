package com.turbomc.nbt;

import com.turbomc.compression.TurboCompressionService;

import net.minecraft.nbt.CompoundTag;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents NBT data in a highly optimized "Packed-Binary" format.
 * <p>
 * Optimizations:
 * 1. Global String Pool: Deduplicates tag names and string values (replaces them with 1-4 byte var-ints).
 * 2. LZ4 Compression: Fast compression of the payload.
 * 3. Structure: Magic Header + String Pool + Compressed Payload.
 */
public class PackedBinaryNBT {

    private static final byte[] MAGIC_HEADER = "TNBT".getBytes(StandardCharsets.US_ASCII); // TurboNBT
    private static final byte VERSION = 1;

    private final List<String> stringPool;
    private final byte[] payload; // The actual NBT structure referencing pool IDs

    public PackedBinaryNBT(List<String> stringPool, byte[] payload) {
        this.stringPool = stringPool;
        this.payload = payload;
    }

    /**
     * Serializes this PackedBinaryNBT to a byte array.
     * Use this for storage or network transmission.
     */
    public byte[] toBytes() {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(bos)) {

            // 1. Write Header
            dos.write(MAGIC_HEADER);
            dos.writeByte(VERSION);

            // 2. Write String Pool
            dos.writeShort(stringPool.size());
            for (String str : stringPool) {
                dos.writeUTF(str);
            }

            // 3. Compress Payload (using configured primary algorithm)
            byte[] compressedPayload;
            try {
                compressedPayload = TurboCompressionService.getInstance().compress(payload);
            } catch (com.turbomc.compression.CompressionException e) {
                throw new IOException("Compression failed during NBT serialization", e);
            }
            dos.writeInt(payload.length); // Uncompressed size
            dos.writeInt(compressedPayload.length); // Compressed size
            dos.write(compressedPayload);

            return bos.toByteArray();

        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize PackedBinaryNBT", e);
        }
    }

    /**
     * Reconstructs a PackedBinaryNBT from raw bytes.
     */
    public static PackedBinaryNBT fromBytes(byte[] data) {
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data))) {

            // 1. Verify Header
            byte[] magic = new byte[4];
            dis.readFully(magic);
            if (!new String(magic, StandardCharsets.US_ASCII).equals("TNBT")) {
                throw new IllegalArgumentException("Invalid Magic Header for PackedBinaryNBT");
            }

            byte version = dis.readByte();
            if (version != VERSION) {
                throw new UnsupportedOperationException("Unsupported PackedBinaryNBT version: " + version);
            }

            // 2. Read String Pool
            int poolSize = dis.readUnsignedShort();
            List<String> pool = new ArrayList<>(poolSize);
            for (int i = 0; i < poolSize; i++) {
                pool.add(dis.readUTF());
            }

            // 3. Decompress Payload
            int uncompressedSize = dis.readInt();
            int compressedSize = dis.readInt();
            byte[] compressedPayload = new byte[compressedSize];
            dis.readFully(compressedPayload);

            byte[] uncompressedPayload = TurboCompressionService.getInstance().decompress(compressedPayload);

            return new PackedBinaryNBT(pool, uncompressedPayload);

        } catch (IOException e) {
            throw new RuntimeException("Failed to deserialize PackedBinaryNBT", e);
        }
    }

    public List<String> getStringPool() {
        return stringPool;
    }

    public byte[] getPayload() {
        return payload;
    }

    /**
     * Builder for creating PackedBinaryNBT from scratch (used by NBTConverter).
     */
    public static class Builder {
        private final List<String> stringPool = new ArrayList<>();
        private final Map<String, Integer> stringToIndex = new HashMap<>();

        public int getStringId(String str) {
            return stringToIndex.computeIfAbsent(str, k -> {
                int index = stringPool.size();
                stringPool.add(k);
                return index;
            });
        }
        
        public PackedBinaryNBT build(byte[] payload) {
            return new PackedBinaryNBT(stringPool, payload);
        }
    }
}
