package com.turbomc.nbt;

import java.lang.foreign.ValueLayout;
import net.minecraft.nbt.*;
import java.io.*;
import java.util.List;
import java.util.Set;

/**
 * Converter between standard vanilla NBT and the optimized PackedBinary format.
 */
public class NBTConverter {

    private static final ThreadLocal<ByteArrayOutputStream> RECYCLABLE_BOS = 
        ThreadLocal.withInitial(() -> new ByteArrayOutputStream(65536));

    /**
     * Converts a standard CompoundTag to PackedBinaryNBT.
     */
    public static PackedBinaryNBT toPackedBinary(CompoundTag root) {
        PackedBinaryNBT.Builder builder = new PackedBinaryNBT.Builder();
        ByteArrayOutputStream bos = RECYCLABLE_BOS.get();
        bos.reset();
        
        try (DataOutputStream dos = new DataOutputStream(bos)) {
            writeTag(root, dos, builder);
            dos.flush();
        } catch (IOException e) {
            throw new RuntimeException("Error converting NBT to PackedBinary", e);
        }

        return builder.build(bos.toByteArray());
    }

    /**
     * Converts PackedBinaryNBT back to a standard CompoundTag.
     */
    public static CompoundTag fromPackedBinary(PackedBinaryNBT packed) {
        java.nio.ByteBuffer buffer = packed.getPayload().asByteBuffer();
        try (DataInputStream dis = new DataInputStream(new com.turbomc.nbt.ByteBufferInputStream(buffer))) {
            Tag tag = readTag(dis, packed.getStringPool());
            if (tag instanceof CompoundTag) {
                return (CompoundTag) tag;
            } else {
                throw new IllegalStateException("Root tag must be CompoundTag, got: " + tag.getClass().getSimpleName());
            }
        } catch (IOException e) {
            throw new RuntimeException("Error converting PackedBinary to NBT", e);
        }
    }

    // === Writing internal logic ===

    private static void writeTag(Tag tag, DataOutputStream dos, PackedBinaryNBT.Builder builder) throws IOException {
        dos.writeByte(tag.getId()); // Type ID

        if (tag instanceof CompoundTag compound) {
            Set<String> keys = compound.keySet();
            dos.writeInt(keys.size()); // Number of entries
            for (String key : keys) {
                dos.writeInt(builder.getStringId(key)); // Key ID (from pool)
                writeTag(compound.get(key), dos, builder); // Value
            }
        } else if (tag instanceof ListTag list) {
            dos.writeByte(list.identifyRawElementType()); // List Type
            dos.writeInt(list.size()); // Size
            for (Tag element : list) {
                writeTag(element, dos, builder);
            }
        } else if (tag instanceof StringTag stringTag) {
            dos.writeInt(builder.getStringId(stringTag.value()));
        } else if (tag instanceof IntTag intTag) {
            dos.writeInt(intTag.value());
        } else if (tag instanceof ByteTag byteTag) {
            dos.writeByte(byteTag.value());
        } else if (tag instanceof ShortTag shortTag) {
            dos.writeShort(shortTag.value());
        } else if (tag instanceof LongTag longTag) {
            dos.writeLong(longTag.value());
        } else if (tag instanceof FloatTag floatTag) {
            dos.writeFloat(floatTag.value());
        } else if (tag instanceof DoubleTag doubleTag) {
            dos.writeDouble(doubleTag.value());
        } else if (tag instanceof ByteArrayTag byteArrayTag) {
            byte[] bytes = byteArrayTag.getAsByteArray();
            dos.writeInt(bytes.length);
            dos.write(bytes);
        } else if (tag instanceof IntArrayTag intArrayTag) {
            int[] ints = intArrayTag.getAsIntArray();
            dos.writeInt(ints.length);
            for (int i : ints) dos.writeInt(i);
        } else if (tag instanceof LongArrayTag longArrayTag) {
            long[] longs = longArrayTag.getAsLongArray();
            dos.writeInt(longs.length);
            for (long l : longs) dos.writeLong(l);
        }
        // EndTag is implied by structure, no explicit write needed for simpler types
    }

    // === Reading internal logic ===

    private static Tag readTag(DataInputStream dis, List<String> stringPool) throws IOException {
        byte typeId = dis.readByte();

        switch (typeId) {
            case Tag.TAG_COMPOUND:
                CompoundTag compound = new CompoundTag();
                int size = dis.readInt();
                for (int i = 0; i < size; i++) {
                    int keyId = dis.readInt();
                    String key = stringPool.get(keyId);
                    Tag value = readTag(dis, stringPool);
                    compound.put(key, value);
                }
                return compound;

            case Tag.TAG_LIST:
                ListTag list = new ListTag();
                // We need to read the type but ListTag usually infers or sets it.
                // In vanilla ListTag, you add elements.
                // However, standard read usually reads type then size.
                // Packed format wrote: type, size, [elements (with their own type header? NO)]
                // Recursive writeTag writes ID for EVERY tag.
                // But for ListTag, vanilla format is: id, size, payloads. Elements DON'T have ids.
                // MY WRITE LOGIC IS WRONG FOR LISTS.
                // Wait, writeTag writes "dos.writeByte(tag.getId())".
                // So inside a loop for ListTag, I am writing the ID again for each element?
                // Standard NBT ListTag: [Type] [Size] [Payload 1] [Payload 2]... (Payloads do NOT have IDs)
                // My logic: for (Tag element : list) writeTag(element...) -> Writes ID + Payload.
                // This is slightly redundant but safe.
                // Let's implement read to match write.
                
                // WAIT. Vanilla ListTag enforcement: All elements must be same type.
                // My write logic respects that by iterating. But it writes the type byte for EACH element.
                // That consumes 1 extra byte per list item. Acceptable overhead for simplicity?
                // Probably better to optimize later if needed. For now let's stick to the symmetric read.
                
                // Actually, let's look at readTag logic.
                byte listType = dis.readByte(); // Read the type declared for the list
                int listSize = dis.readInt();
                
                // Ideally we should create a ListTag of that type.
                // But since I wrote explicit tags inside, I just read them.
                // The 'listType' byte I read above is just metadata from the parent write.
                // The elements follow.
                
                for (int i = 0; i < listSize; i++) {
                    // Since my writeTag writes the ID, readTag will read the ID.
                    list.add(readTag(dis, stringPool));
                }
                return list;

            case Tag.TAG_STRING:
                return StringTag.valueOf(stringPool.get(dis.readInt()));
            case Tag.TAG_INT:
                return IntTag.valueOf(dis.readInt());
            case Tag.TAG_BYTE:
                return ByteTag.valueOf(dis.readByte());
            case Tag.TAG_SHORT:
                return ShortTag.valueOf(dis.readShort());
            case Tag.TAG_LONG:
                return LongTag.valueOf(dis.readLong());
            case Tag.TAG_FLOAT:
                return FloatTag.valueOf(dis.readFloat());
            case Tag.TAG_DOUBLE:
                return DoubleTag.valueOf(dis.readDouble());
            case Tag.TAG_BYTE_ARRAY:
                int baLength = dis.readInt();
                byte[] ba = new byte[baLength];
                dis.readFully(ba);
                return new ByteArrayTag(ba);
            case Tag.TAG_INT_ARRAY:
                int iaLength = dis.readInt();
                int[] ia = new int[iaLength];
                for(int i=0; i<iaLength; i++) ia[i] = dis.readInt();
                return new IntArrayTag(ia);
            case Tag.TAG_LONG_ARRAY:
                int laLength = dis.readInt();
                long[] la = new long[laLength];
                for(int i=0; i<laLength; i++) la[i] = dis.readLong();
                return new LongArrayTag(la);
            default:
                throw new IllegalArgumentException("Unknown Tag ID in PackedBinary: " + typeId);
        }
    }
}
