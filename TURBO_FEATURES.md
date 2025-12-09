# 🚀 TurboMC Full Feature List & Changelog

This document details all modifications, improvements, and features added to **TurboMC** (Fork of Paper) up to version 1.2.0.

## 🛠️ Performance & Core

### ⚡ SIMD Collision Optimization
TurboMC utilizes Java's **Incubator Vector API** to parallelize AABB collision checks, significantly boosting performance in high-density areas.
*   **Batched Physics**: Processes entity collisions in parallel groups using hardware acceleration (AVX/AVX2/AVX-512).
*   **Scalability**: Tested sustaining **20,000+ entities** in a single block space without crashing the server thread.
*   **Requirements**: Requires `--add-modules=jdk.incubator.vector` flag on startup.

### �️ Configurable Compression System (v1.2.0+)
Dynamic compression system supporting both **LZ4** (fast) and **Zlib** (compatible) algorithms, configurable via `turbo.toml`.
*   **Dual-Algorithm Support**: Choose between LZ4 (speed-optimized) or Zlib (vanilla-compatible) compression.
*   **Auto-Detection**: Automatically detects compression format via magic bytes (0x01=Zlib, 0x02=LZ4).
*   **Fallback**: Gracefully falls back to alternative algorithm if decompression fails.
*   **Statistics Tracking**: Real-time compression metrics and performance monitoring.
*   **TOML Configuration**: User-friendly configuration in `turbo.toml` with hot-reload support.

### �🚄 Native LZ4 Compression (v1.0.0)
Replaces the standard Zlib compression for proxy connections with **LZ4**, designed for extreme speed.
*   **Ultra-Low Latency**: Drastically reduces CPU time spent on packet compression/decompression.
*   **Integration**: specifically tuned to work with **TurboProxy** (formerly Velocity) for seamless high-speed data transfer.

### ☕ Java Platform
*   **Native Java 21**: Fully optimized for **JDK 21**, ensuring compatibility with the latest Generational ZGC and Vector API features.



## ⚙️ Configuration

### 🔧 `paper-global.yml`
TurboMC introduces specific optimzation toggles within the standard Paper configuration:

```yaml
proxies:
  velocity:
    enabled: true
    # secret must match proxy
```
*Note: The native LZ4 compression is automatically negotiated when connecting via TurboProxy.*



## 📝 Version Summary

| Version | Codename | Main Changes |
| :--- | :--- | :--- |
| **1.2.0** | *Compression Complete* | **Configurable Compression System** (LZ4/Zlib) with full chunk storage integration.
*Protocol Bridge* | **ViaVersion Multi-Version Support**: Clients 1.8+ can now connect. |
| **1.1.0** | *Vector Speed* | SIMD Collision Optimization (Vector API). |
| **1.0.0** | *Genesis* | Initial fork, Native LZ4 Compression (replacing Zlib). |
