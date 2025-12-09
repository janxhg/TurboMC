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

### Integration Status

**Server Initialization:**
- ✅ Added `TurboCompressionService.initialize()` hook in `MinecraftServer.initPostWorld()`
- ✅ Compression system loads before plugins
- ✅ Automatic configuration loading from `turbo.toml`
- ✅ Startup logging shows active algorithm and compression level

**Chunk Storage:**
- ✅ Modified `RegionFileVersion.getCompressionFormat()` to query `TurboCompressionService`
- ✅ Chunks automatically use LZ4 or Zlib based on `turbo.toml` configuration
- ✅ Seamless integration with Paper's RegionFile infrastructure
- ✅ Backward compatible - reads both LZ4 and Zlib compressed chunks

**NBT Serialization:**
- 📝 Placeholder comments added to `NbtIo.java` for future integration
- ⏭️ Full NBT compression deferred (requires custom stream wrappers)

**Network Packets:**
- ⏭️ Not integrated - Paper's Velocity natives already optimized

### Next Steps (v1.4.0+)
- [ ] Complete NBT compression with custom stream wrappers
- [ ] Implement migration tools (`/turbo compression migrate`)
- [ ] Add admin commands (`/turbo compression stats`, `/turbo compression reload`)
- [ ] Resolve ServerHandshakePacketListenerImpl patch compatibility
- [ ] Add TurboVersionControl TOML-based version range validation

---

## v1.3.0 - ViaVersion Multi-Version Support (December 2025)

### Summary
Enabled ViaVersion multi-version protocol support, allowing clients from Minecraft 1.8+ to connect to the 1.21.10 TurboMC server. This includes ViaVersion and ViaBackwards integration for comprehensive backwards compatibility.

### Technical Implementation

**Dependencies Added:**
- `viaversion-common:5.1.1` - Core ViaVersion protocol translation
- `viabackwards-common:5.1.1` - Backwards compatibility for legacy clients

**Integration Components:**
- `TurboViaPlatform.java` - ViaVersion platform bridge implementation
- `TurboViaConfig.java` - Configuration wrapper for ViaVersion settings
- `TurboViaInjector.java` - Netty pipeline injection handler
- `TurboViaLoader.java` - Bootstrap initialization and channel injection

**Server Hooks:**
- `MinecraftServer.java` - ViaVersion initialization during server startup
- `ServerConnectionListener.java` - Protocol handler injection into Netty pipeline

### Supported Client Versions

| Minecraft Version | Protocol | Status |
|---|---|---|
| 1.8.x | 47 | ✅ Supported via ViaVersion |
| 1.9-1.12.x | 107-340 | ✅ Supported via ViaBackwards |
| 1.13-1.16.x | 393-754 | ✅ Supported via ViaBackwards |
| 1.17-1.20.x | 755-765 | ✅ Supported via ViaVersion |
| 1.21.x | 766-769 | ✅ Native support |

### Configuration

ViaVersion auto-generates `viaversion.yml` on first startup with sensible defaults.

**Optional TurboMC config (`turbo.toml`):**
```toml
[version-control]
enabled = true
minimum-protocol = 47      # Minecraft 1.8
maximum-protocol = 769     # Minecraft 1.21.10
```

### Benefits

**Multi-Version Access:**
- Players on older Minecraft versions can connect
- No need for multiple server instances
- Simplified network infrastructure

**Future Compatibility:**
- Easy integration of new Minecraft versions
- ViaVersion handles protocol changes automatically
- Reduced maintenance burden

### Build Status
- ✅ Build successful: 900 patches applied
- ✅ ViaVersion dependencies resolved
- ✅ All TurboVia integration classes compiled
- ✅ Compatible with Paper 1.21.10

### Known Limitations

**ServerHandshakePacketListenerImpl:**
- Patch remains disabled (not required for ViaVersion)
- ViaVersion injects at Netty level, before handshake
- Custom TurboMC protocol features (from broken patch) not active
- Can be re-implemented separately if needed

### Migration Notes

**Upgrading from v1.2.0:**
1. No action required - ViaVersion auto-initializes
2. `viaversion.yml` generated on first startup
3. Existing clients maintain compatibility

**Testing:**
1. Start server with ViaVersion enabled
2. Check logs for "ViaVersion initialized" message
3. Connect with clients from different Minecraft versions
4. Verify protocol translation works correctly
