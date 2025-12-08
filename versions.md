# TurboMC Changelog

## v1.0.0 - LZ4 Compression Support

### Summary
Added native LZ4 compression support to the server networking pipeline to handle connections from the optimized Velocity proxy. This reduces CPU overhead compared to standard Zlib compression.

### Technical Details
- **Network Pipeline**: Integrated LZ4 encoder (`Lz4PacketEncoder`) and decoder (`Lz4PacketDecoder`) into the Netty pipeline.
- **Connection Handshake**: Modified packet handling to recognize LZ4 compression signals from the proxy.
- **Compatibility**: Retains full support for standard Zlib compression for vanilla clients or other proxies.

### Configuration
Requires `paper-global.yml` settings:
```yaml
proxies:
  velocity:
    enabled: true
    # secret must match proxy
```

## v1.1.0 - SIMD Collision Optimization

### Summary
Implemented Single Instruction, Multiple Data (SIMD) optimization for AABB collision detection. This allows the server to process entity physical interactions in parallel batches, significantly improving performance in high-density scenarios (mob farms, entity cramming).

### Technical Details
- **Vector API**: Utilized Java's Incubator Vector API (`jdk.incubator.vector`) for hardware-accelerated calculations.
- **Batched Processing**:
  - `anyIntersects`: Checks intersection against a list of AABBs in parallel.
  - `collide`: Calculates maximum movement offset against a list of AABBs in parallel.
- **Fallbacks**: Retained scalar logic for small datasets or legacy hardware.

### Performance Verification
- **Stress Test**: Sustained ~21,000 active collision entities (zombies in 1x1 space) without server crash.
- **Requirements**: Server launch script must include `--add-modules=jdk.incubator.vector`.

## v1.2.0 - Configurable Compression System

### Summary
Implemented a configurable, dual-algorithm compression system supporting both LZ4 (speed-optimized) and Zlib (vanilla-compatible) compression. The system features automatic format detection, graceful fallback, and TOML-based configuration for maximum flexibility.

### Technical Details
**Core Compression Framework:**
- **TurboConfig**: TOML configuration loader (using `toml4j`) with automatic defaults
- **Compressor Interface**: Standardized API for compression algorithms
- **LZ4CompressorImpl**: Fast compression using `lz4-java` library (levels 1-17)
- **ZlibCompressor**: Vanilla-compatible compression using Java Deflater/Inflater (levels 1-9)
- **TurboCompressionService**: Central service with auto-detection, fallback, and statistics
- **CompressionStats**: Real-time compression metrics and performance tracking

**Format Specification:**
- **Magic Bytes**: Auto-detection via header (0x01 = Zlib, 0x02 = LZ4)
- **Data Format**: `[magic_byte][original_size_4_bytes][compressed_data]`
- **Fallback**: Automatic retry with alternative algorithm if decompression fails

### Configuration (`turbo.toml`)
```toml
[compression]
algorithm = "lz4"           # "lz4" or "zlib"
level = 6                   # 1-17 for LZ4, 1-9 for Zlib
auto-migrate = true         # Auto-convert old formats
fallback-enabled = true     # Enable graceful fallback

[version-control]
minimum-version = "1.20.1"  # Placeholder for future ViaVersion integration
maximum-version = "1.21.10"
```

### Known Issues & Limitations
**ServerHandshakePacketListenerImpl Patch:**
- ❌ Cannot apply patch due to Minecraft version incompatibility (1.16 → 1.21.10)
- 📦 Patch expects code structure without TRANSFER intention case (added in MC 1.19+)
- 🔄 Features lost: Connection throttling, PlayerHandshakeEvent, BungeeCord proxy support
- 💾 Patch preserved in `ServerHandshakePacket_BROKEN.patch` for future manual recreation
- ✅ Server builds and runs successfully without this patch

**ViaVersion Integration:**
- 🚧 Initially planned for v1.2.0 but deferred due to patch conflicts
- 📋 Dependencies added but integration code disabled
- 🔮 Planned for future release once patch issues resolved

### Files Created
**Configuration:**
- `turbo.toml` - Main configuration file

**Core Classes:**
- `com.turbomc.config.TurboConfig` - Configuration loader
- `com.turbomc.compression.Compressor` - Interface
- `com.turbomc.compression.LZ4CompressorImpl` - LZ4 implementation
- `com.turbomc.compression.ZlibCompressor` - Zlib implementation
- `com.turbomc.compression.TurboCompressionService` - Central service
- `com.turbomc.compression.CompressionStats` - Statistics tracking
- `com.turbomc.compression.CompressionException` - Error handling

### Build System
- ✅ Build successful: 900 patches applied
- 📦 Dependencies: `lz4-java:1.8.0`, `toml4j:0.7.2`
- ⚠️ ViaVersion dependencies temporarily disabled

### Next Steps (v1.2.1+)
- [ ] Integrate compression into network packet layer
- [ ] Integrate compression into chunk storage
- [ ] Integrate compression into NBT serialization
- [ ] Implement migration tools for existing worlds
- [ ] Add `/turbo compression` admin commands
- [ ] Resolve ServerHandshakePacketListenerImpl patch compatibility
- [ ] Re-enable ViaVersion multi-version support
