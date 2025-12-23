package com.turbomc.nbt;

import com.turbomc.compression.TurboCompressionService;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
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
 * 1. Global String Pool: Deduplicates tag names and string values.
 * 2. LZ4 Compression: Fast compression of the payload.
 * 3. Off-Heap Storage: Uses Project Panama MemorySegment to reduce GC pressure.
 * 4. Structure: Magic Header + String Pool + Compressed Payload.
 */
public class PackedBinaryNBT {

    private static final byte[] MAGIC_HEADER = "TNBT".getBytes(StandardCharsets.US_ASCII);
    private static final byte VERSION = 1;

    private final List<String> stringPool;
    private final MemorySegment payload;
    private final long payloadSize;

    public PackedBinaryNBT(List<String> stringPool, byte[] payloadData) {
        this.stringPool = stringPool;
        this.payloadSize = payloadData.length;
        // Use Arena.ofAuto() for safe off-heap management that follows GC lifecycle
        Arena arena = Arena.ofAuto();
        this.payload = arena.allocate(payloadData.length);
        this.payload.copyFrom(MemorySegment.ofArray(payloadData));
    }

    private PackedBinaryNBT(List<String> stringPool, MemorySegment payload, long size) {
        this.stringPool = stringPool;
        this.payload = payload;
        this.payloadSize = size;
    }

    /**
     * Serializes this PackedBinaryNBT to a byte array.
     */
    public byte[] toBytes() {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(bos)) {

            dos.write(MAGIC_HEADER);
            dos.writeByte(VERSION);

            dos.writeShort(stringPool.size());
            for (String str : stringPool) {
                dos.writeUTF(str);
            }

            // Copy back to heap only for compression/serialization
            byte[] rawPayload = payload.toArray(ValueLayout.JAVA_BYTE);
            byte[] compressedPayload;
            try {
                compressedPayload = TurboCompressionService.getInstance().compress(rawPayload);
            } catch (com.turbomc.compression.CompressionException e) {
                throw new IOException("Compression failed", e);
            }
            dos.writeInt(rawPayload.length);
            dos.writeInt(compressedPayload.length);
            dos.write(compressedPayload);

            return bos.toByteArray();

        } catch (IOException e) {
            throw new RuntimeException("Serialization failure", e);
        }
    }

    public static PackedBinaryNBT fromBytes(byte[] data) {
        try (DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data))) {
            byte[] magic = new byte[4];
            dis.readFully(magic);
            if (!new String(magic, StandardCharsets.US_ASCII).equals("TNBT")) {
                throw new IllegalArgumentException("Invalid TNBT header");
            }

            byte version = dis.readByte();
            if (version != VERSION) throw new UnsupportedOperationException("Version mismatch");

            int poolSize = dis.readUnsignedShort();
            List<String> pool = new ArrayList<>(poolSize);
            for (int i = 0; i < poolSize; i++) pool.add(dis.readUTF());

            int uncompressedSize = dis.readInt();
            int compressedSize = dis.readInt();
            byte[] compressedPayload = new byte[compressedSize];
            dis.readFully(compressedPayload);

            byte[] uncompressedPayload = TurboCompressionService.getInstance().decompress(compressedPayload);
            return new PackedBinaryNBT(pool, uncompressedPayload);

        } catch (IOException e) {
            throw new RuntimeException("Deserialization failure", e);
        }
    }

    public List<String> getStringPool() { return stringPool; }
    public MemorySegment getPayload() { return payload; }
    public long getPayloadSize() { return payloadSize; }

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
