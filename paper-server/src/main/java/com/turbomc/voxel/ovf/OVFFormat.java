package com.turbomc.voxel.ovf;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Standard definitions for Optimized Voxel Format (.ovf).
 * 
 * Header Structure (32 bytes):
 * [0-8]   Magic "TURBO_OVF"
 * [9]     Version (1)
 * [10-11] Width (X)
 * [12-13] Height (Y)
 * [14-15] Length (Z)
 * [16-19] Palette Count
 * [20-23] Data Offset (Start of RLE data)
 * [24-31] Reserved
 */
public class OVFFormat {
    public static final byte[] MAGIC = "TURBO_OVF".getBytes(StandardCharsets.US_ASCII);
    public static final int VERSION = 1;
    public static final int HEADER_SIZE = 32;

    public static class Header {
        public final int width;
        public final int height;
        public final int length;
        public final int paletteCount;
        public final int dataOffset;

        public Header(int width, int height, int length, int paletteCount, int dataOffset) {
            this.width = width;
            this.height = height;
            this.length = length;
            this.paletteCount = paletteCount;
            this.dataOffset = dataOffset;
        }

        public static Header read(ByteBuffer buffer) {
            if (buffer.remaining() < HEADER_SIZE) throw new IllegalArgumentException("Invalid OVF Header size");
            
            byte[] magic = new byte[MAGIC.length];
            buffer.get(magic);
            if (!Arrays.equals(magic, MAGIC)) throw new IllegalArgumentException("Invalid OVF Magic");

            int ver = buffer.get();
            if (ver != VERSION) throw new IllegalArgumentException("Unsupported OVF Version: " + ver);

            int w = Short.toUnsignedInt(buffer.getShort());
            int h = Short.toUnsignedInt(buffer.getShort());
            int l = Short.toUnsignedInt(buffer.getShort());
            int pc = buffer.getInt();
            int doff = buffer.getInt();
            
            // Skip reserved
            buffer.position(buffer.position() + 8);

            return new Header(w, h, l, pc, doff);
        }

        public void write(ByteBuffer buffer) {
            buffer.put(MAGIC);
            buffer.put((byte) VERSION);
            buffer.putShort((short) width);
            buffer.putShort((short) height);
            buffer.putShort((short) length);
            buffer.putInt(paletteCount);
            buffer.putInt(dataOffset);
            buffer.putLong(0L); // Reserved
        }
    }
}
