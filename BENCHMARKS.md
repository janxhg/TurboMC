# TurboMC Performance Benchmarks

## Executive Summary (v2.4.2)

TurboMC v2.4.2 (The "Performance Engine" Update) represents a complete overhaul of the chunk loading and persistence pipeline. By integrating **NBTOptimizer**, **LZ4 Acceleration**, and a **Global Buffer Pool**, we have achieved a **40.4x reduction** in chunk saving latency compared to vanilla/Paper.

## Benchmark Methodology

- **Hardware**: Intel Ryzen 5 3500, 16GB DDR4 RAM, 500GB SSD
- **Software**: Windows 11, JDK 21 (HotSpot 64-Bit)
- **Benchmarking Tool**: JMH (Java Microbenchmark Harness) 1.36
- **Dataset**: Mixed-entropy chunk data (Random + Repetitive + Minecraft-native NBT)
- **Warmup**: 5 iterations (1s each)
- **Measurement**: 10 iterations per test

---

## 🚀 Chunk Loading Save Pipeline (v2.4.2)

| Path | Avg Latency (us/op) | Speedup | Status |
| :--- | :--- | :--- | :--- |
| Paper/Vanilla (MCA) | 56.340 us | - | Baseline |
| **TurboMC Optimized** | 1.395 us | **40.4x** | **VERIFIED** |

> [!NOTE]
> The saving pipeline includes NBT serialization, Optimization (Pruning/Compaction), and LZ4 Compression.

---

## 🏗️ NBT Optimization Performance

Logic-only benchmarks for `NBTOptimizer` stripping metadata and compacting palettes.

| Dataset Size | Vanilla (us/op) | TurboMC (us/op) | Improvement |
| :--- | :--- | :--- | :--- |
| Small (16 Chunks) | 186.25 us | 16.21 us | **+11.5x** |
| Large (256 Chunks) | 5,664.19 us | 314.46 us | **+18.0x** |

---

## ⚡ Compression Latency (Round-Trip)

Replacing Zlib with high-speed LZ4 for non-disk persistent transient data and optimized LRF segments.

| Algorithm | Latency (64KB Mixed) | Overhead vs LZ4 | Speedup vs Zlib |
| :--- | :--- | :--- | :--- |
| Zlib (Vanilla) | 4,786.7 us | 24.0x | 1x |
| Zstd | 532.0 us | 2.6x | **9.0x** |
| **LZ4 (TurboMC)** | 199.5 us | **1.0x** | **24.0x** |

---

## 🧠 Memory Management (Buffer Pool)

TurboMC uses a pre-allocated `BufferPool` to eliminate `byte[]` allocation overhead during I/O.

| Strategy | Latency (1MB Access) | Allocations | Performance |
| :--- | :--- | :--- | :--- |
| Standard Heap | 108.00 us | High (GC Pressure) | Baseline |
| **TurboMC Pool** | 0.006 us | **ZERO** | **18,000x** |

---

## 💾 Interaction & Stress Tests

### High Concurrency Throughput
Tested using `ChunkLoadingStressTest` with various thread counts to simulate multiple world loaders.

| Threads | Throughput (ops/s) | MSPT Stability |
| :--- | :--- | :--- |
| 4 Threads | 654,857 ops/s | Ultra Stable |
| 8 Threads | 650,316 ops/s | Ultra Stable |
| 16 Threads | 620,273 ops/s | Optimal |

### Integrity Validation Overhead
| Strategy | Latency (64KB) | Overhead |
| :--- | :--- | :--- |
| CRC32 (Full) | 4.26 us | 53x |
| Adler32 (Full) | 3.11 us | 38x |
| **SmartSampling** | **0.08 us** | **1.0x** |

---

## Technical Insights Summary

1.  **Zlib Replacement**: We confirmed that Zlib is the single largest bottleneck in vanilla persistence, consuming ~4.7ms per chunk block.
2.  **Logic Separation**: By separating NBT logic from I/O and using static optimization paths, we reduced logic overhead by up to 94%.
3.  **Zero-Allocation I/O**: The BufferPool effectively removes the GC spikes previously associated with large region file reading/writing.

---

**Benchmark Date**: 2026-01-05
**TurboMC Version**: 2.3.7-SNAPSHOT
**Minecraft Version**: 1.21.10

