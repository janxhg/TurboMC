package com.turbomc.nbt;

import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * An InputStream that reads directly from a ByteBuffer.
 * Eliminates the need for byte[] copies when reading from MemorySegment.
 */
public class ByteBufferInputStream extends InputStream {
    private final ByteBuffer buffer;

    public ByteBufferInputStream(ByteBuffer buffer) {
        this.buffer = buffer.duplicate(); // Duplicate to avoid affecting original buffer position
    }

    @Override
    public int read() {
        if (!buffer.hasRemaining()) return -1;
        return buffer.get() & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) {
        if (!buffer.hasRemaining()) return -1;
        int bytesToRead = Math.min(len, buffer.remaining());
        buffer.get(b, off, bytesToRead);
        return bytesToRead;
    }

    @Override
    public int available() {
        return buffer.remaining();
    }
}
