package com.turbomc.voxel.schematic;

import com.turbomc.nbt.SimpleNBTReader;
import com.turbomc.voxel.ovf.OVFWriter;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;

public class SchematicConverter {

    /**
     * Converts a Sponge Schematic (.schem) to OVF (.ovf).
     * @param schemFile Input .schem file
     * @param ovfFile Output .ovf file
     */
    public static void convert(File schemFile, File ovfFile) throws IOException {
        System.out.println("Converting " + schemFile.getName() + " to OVF...");
        
        try (FileInputStream fis = new FileInputStream(schemFile)) {
            Object obj = SimpleNBTReader.readCompressed(fis);
            if (!(obj instanceof Map)) {
                throw new IOException("Invalid Schematic: Root is not a Compound Tag");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) obj;
            
            // 1. Read Dimensions
            short width = (short) root.get("Width");
            short height = (short) root.get("Height");
            short length = (short) root.get("Length");
            
            // 2. Read Palette
            @SuppressWarnings("unchecked")
            Map<String, Object> paletteMap = (Map<String, Object>) root.get("Palette");
            if (paletteMap == null) throw new IOException("Missing Palette");
            
            int maxId = -1;
            for (Object val : paletteMap.values()) {
                if (val instanceof Integer) {
                    maxId = Math.max(maxId, (Integer) val);
                }
            }
            
            // Build direct palette list (ID -> Name)
            String[] paletteArray = new String[maxId + 1];
            for (Map.Entry<String, Object> entry : paletteMap.entrySet()) {
                int id = (Integer) entry.getValue();
                paletteArray[id] = entry.getKey();
            }
            
            ArrayList<String> paletteList = new ArrayList<>();
            Collections.addAll(paletteList, paletteArray);
            
            // 3. Read BlockData (VarInt encoded byte array)
            byte[] rawBlockData = (byte[]) root.get("BlockData");
            if (rawBlockData == null) throw new IOException("Missing BlockData");
            
            ByteBuffer buffer = ByteBuffer.wrap(rawBlockData);
            short[] finalData = new short[width * height * length];
            
            for (int i = 0; i < finalData.length; i++) {
                int paletteId = readVarInt(buffer);
                finalData[i] = (short) paletteId;
            }
            
            // 4. Write OVF
            try (FileOutputStream fos = new FileOutputStream(ovfFile)) {
                OVFWriter.write(fos, width, height, length, paletteList, finalData);
            }
            
            System.out.println("Converted successfully! OVF Size: " + ovfFile.length());
        }
    }
    
    // Reads a VarInt from ByteBuffer
    private static int readVarInt(ByteBuffer buf) {
        int value = 0;
        int shift = 0;
        byte b;
        do {
            if (!buf.hasRemaining()) throw new RuntimeException("Unexpected end of VarInt stream");
            b = buf.get();
            value |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return value;
    }
}
