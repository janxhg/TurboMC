# TurboMC 🚀

| TurboMC Version | Minecraft Version | Upstream            |
|:---------------:|:-----------------:|:-------------------:|
| **v1.2.0**      | **1.21.10**        | Fork of **PaperMC** |

**TurboMC** is a high-performance fork of [Paper](https://github.com/PaperMC/Paper), designed for extreme scalability and optimized specifically for high-density entity scenarios and proxy-based setups.

## Key Features

### ⚡ SIMD Collision Optimization
Utilizes Java's **Incubator Vector API** to parallelize AABB collision checks.
- **Batched Physics**: Processes entity collisions in parallel groups using hardware acceleration (AVX/AVX2/AVX-512).
- **Massive Scalability**: Tested with **20,000+ entities** in a single block space without server crash.

### 🚄 Native LZ4 Compression
Replaces standard Zlib compression with **LZ4** for proxy connections.
- **Ultra-Low Latency**: Drastically reduces CPU time spent on packet compression/decompression.
- **Velocity Support**: Custom integration with Velocity proxy for seamless high-speed data transfer.

## Requirements
- **Java 21** or higher.
- **Startup Flag**: You **MUST** add `--add-modules=jdk.incubator.vector` to your startup flags to enable SIMD optimizations.

## Getting Started

1.  Download the latest `TurboMC` build (Paperclip jar).
2.  Add the required flag to your startup script:
    ```bash
    java -Xms4G -Xmx4G --add-modules=jdk.incubator.vector -jar turbomc.jar
    ```

## Development
This project is based on the Paper ecosystem.
- Clone the repo.
- Run `gradlew applyPatches`.
- Build with `gradlew build`.

## License
TurboMC is licensed under GPLv3, same as Paper.
