package com.turbomc.voxel.ovf;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class OVFWriter {

    /**
     * Writes voxel data to an OVF stream.
     * @param out Output stream
     * @param width Dimensions X
     * @param height Dimensions Y
     * @param length Dimensions Z
     * @param palette List of block state strings (e.g. "minecraft:stone")
     * @param blockData Array of palette indices (size = width*height*length) in YZX or XYZ order? 
     *                  Convention: Sponge Schematics use YZX (Y varies fastest? No, usually X varies fastest, then Z, then Y).
     *                  Optimized format prefers standard linear index: index = (y * length + z) * width + x
     */
    public static void write(OutputStream out, int width, int height, int length, List<String> palette, short[] blockData) throws IOException {
        DataOutputStream dos = new DataOutputStream(out);

        // 1. Calculate Palette Size in bytes (approx) to compute Data Offset
        // But wait, header needs fixed Data Offset.
        // We write header placeholder, then palette, then data, then seek back?
        // If stream is not seekable (e.g. Network), we must buffer options.
        // Let's assume FileOutputStream or ByteArrayOutputStream.
        
        // Actually, we can just write Header -> Palette -> Data.
        // We need to know Data Offset.
        // Data Offset = 32 + Palette Bytes.
        
        // Calculate palette bytes
        int paletteBytes = 0;
        for (String s : palette) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            paletteBytes += 2 + b.length; // Short length + string bytes
        }
        
        int dataOffset = OVFFormat.HEADER_SIZE + paletteBytes;
        
        // 2. Write Header
        ByteBuffer headerBuf = ByteBuffer.allocate(OVFFormat.HEADER_SIZE);
        new OVFFormat.Header(width, height, length, palette.size(), dataOffset).write(headerBuf);
        dos.write(headerBuf.array());
        
        // 3. Write Palette
        for (String s : palette) {
            dos.writeUTF(s);
        }
        
        // 4. Write Data with RLE
        // RLE Format: 
        // [Count (1 or 5 bytes)] [PaletteIdx (2 bytes)]
        
        int totalVoxels = width * height * length;
        if (blockData.length != totalVoxels) {
            throw new IllegalArgumentException("Block data size mismatch");
        }
        
        int currentRun = 0;
        short currentVal = -1;
        
        if (totalVoxels > 0) {
            currentVal = blockData[0];
            currentRun = 1;
        }
        
        for (int i = 1; i < totalVoxels; i++) {
            short val = blockData[i];
            if (val == currentVal) {
                currentRun++;
            } else {
                writeRun(dos, currentRun, currentVal);
                currentVal = val;
                currentRun = 1;
            }
        }
        
        if (totalVoxels > 0) {
            writeRun(dos, currentRun, currentVal);
        }
        
        dos.flush();
    }
    
    private static void writeRun(DataOutputStream dos, int count, short val) throws IOException {
        if (count < 255) {
            dos.writeByte(count);
        } else {
            dos.writeByte(0xFF);
            dos.writeInt(count);
        }
        dos.writeShort(val);
    }
}
