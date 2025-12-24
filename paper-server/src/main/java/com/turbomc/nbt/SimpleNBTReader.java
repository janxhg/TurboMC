package com.turbomc.nbt;

import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * Lightweight NBT Reader to avoid NMS dependencies.
 * Supports reading standard Named Binary Tag format (Java Edition).
 */
public class SimpleNBTReader {

    public static Object readCompressed(InputStream in) throws IOException {
        try (DataInputStream dis = new DataInputStream(new GZIPInputStream(in))) {
            return readNamedTag(dis);
        }
    }

    private static Object readNamedTag(DataInputStream dis) throws IOException {
        int typeId = dis.readByte();
        if (typeId == 0) return null; // End
        
        // Name (we assume parsing full file usually starts with Compound)
        // Standard NBT files usually have a root name (often empty string).
        String name = dis.readUTF();
        
        return readPayload(dis, typeId);
    }

    private static Object readPayload(DataInputStream dis, int typeId) throws IOException {
        switch (typeId) {
            case 0: return null;
            case 1: return dis.readByte();
            case 2: return dis.readShort();
            case 3: return dis.readInt();
            case 4: return dis.readLong();
            case 5: return dis.readFloat();
            case 6: return dis.readDouble();
            case 7: // Byte Array
                int len7 = dis.readInt();
                byte[] b7 = new byte[len7];
                dis.readFully(b7);
                return b7;
            case 8: return dis.readUTF();
            case 9: // List
                int listType = dis.readByte();
                int listLen = dis.readInt();
                List<Object> list = new ArrayList<>(listLen);
                for (int i = 0; i < listLen; i++) {
                    list.add(readPayload(dis, listType));
                }
                return list;
            case 10: // Compound
                Map<String, Object> map = new HashMap<>();
                while (true) {
                    int subType = dis.readByte();
                    if (subType == 0) break;
                    String subName = dis.readUTF();
                    Object val = readPayload(dis, subType);
                    map.put(subName, val);
                }
                return map;
            case 11: // Int Array
                int len11 = dis.readInt();
                int[] i11 = new int[len11];
                for(int k=0; k<len11; k++) i11[k] = dis.readInt();
                return i11;
            case 12: // Long Array
                int len12 = dis.readInt();
                long[] l12 = new long[len12];
                for(int k=0; k<len12; k++) l12[k] = dis.readLong();
                return l12;
            default:
                throw new IOException("Unknown NBT Tag Type: " + typeId);
        }
    }
}
