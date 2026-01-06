package com.turbomc.test.benchmark;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Queue;

/**
 * Benchmark for Buffer Pooling vs Allocation.
 * Tests the impact of reusing buffers vs allocating new ones,
 * which is critical for reducing GC pressure during high-churn chunk loading.
 */
public class BufferPoolBenchmark extends TurboBenchmarkBase {

    @State(Scope.Thread)
    public static class PoolState {
        @Param({"4096", "16384", "1048576"}) // 4KB, 16KB, 1MB
        public int bufferSize;

        public PooledByteBufAllocator nettyAllocator;
        public Queue<ByteBuffer> customPool;

        @Setup(Level.Trial)
        public void setup() {
            nettyAllocator = PooledByteBufAllocator.DEFAULT;
            customPool = new ArrayDeque<>();
            // Pre-fill custom poll
            for (int i = 0; i < 100; i++) {
                customPool.offer(ByteBuffer.allocateDirect(bufferSize));
            }
        }
    }

    @Benchmark
    public void heapAllocation(PoolState state, Blackhole bh) {
        byte[] array = new byte[state.bufferSize];
        bh.consume(array);
    }

    @Benchmark
    public void directAllocation(PoolState state, Blackhole bh) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(state.bufferSize);
        bh.consume(buffer);
    }

    @Benchmark
    public void nettyPooledAllocation(PoolState state, Blackhole bh) {
        ByteBuf buf = state.nettyAllocator.directBuffer(state.bufferSize);
        try {
            bh.consume(buf);
        } finally {
            buf.release();
        }
    }

    @Benchmark
    public void customPoolAcquireRelease(PoolState state, Blackhole bh) {
        // Simulate a simple pool acquire/release cycle
        ByteBuffer buf = state.customPool.poll();
        if (buf == null) {
            buf = ByteBuffer.allocateDirect(state.bufferSize);
        }
        
        bh.consume(buf);
        
        // Reset and return
        buf.clear();
        state.customPool.offer(buf);
    }
}
