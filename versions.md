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
