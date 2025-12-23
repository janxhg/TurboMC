package com.turbomc.voxel.ovf;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class OVFReader {

    public static class OVFContainer {
        public final OVFFormat.Header header;
        public final List<String> palette;
        public final short[] blockData;

        public OVFContainer(OVFFormat.Header header, List<String> palette, short[] blockData) {
            this.header = header;
            this.palette = palette;
            this.blockData = blockData;
        }
    }

    public static OVFContainer read(InputStream in) throws IOException {
        DataInputStream dis = new DataInputStream(in);

        // 1. Read Header
        byte[] headerBytes = new byte[OVFFormat.HEADER_SIZE];
        dis.readFully(headerBytes);
        OVFFormat.Header header = OVFFormat.Header.read(ByteBuffer.wrap(headerBytes));

        // 2. Read Palette
        List<String> palette = new ArrayList<>(header.paletteCount);
        for (int i = 0; i < header.paletteCount; i++) {
            palette.add(dis.readUTF());
        }

        // 3. Read Data (RLE)
        int totalVoxels = header.width * header.height * header.length;
        short[] data = new short[totalVoxels];
        
        int currentIndex = 0;
        while (currentIndex < totalVoxels) {
            int count = dis.readUnsignedByte();
            if (count == 0xFF) {
                count = dis.readInt();
            }
            
            short val = dis.readShort();
            
            // Fill run
            // Arrays.fill is faster but requires splitting if run wraps? 
            // 1D array implies contiguous fill. Arrays.fill is perfect.
            int end = currentIndex + count;
            if (end > totalVoxels) {
                throw new IOException("OVF Data Corruption: Run exceeds bounds");
            }
            
            /*
             * Optimization: Arrays.fill for large runs
             */
            java.util.Arrays.fill(data, currentIndex, end, val);
            
            currentIndex = end;
        }

        return new OVFContainer(header, palette, data);
    }
}
