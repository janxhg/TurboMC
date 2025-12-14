# TurboMC v1.5.0 - LRF Stability & Performance Update

## 🚀 Critical Fixes (v1.5.0)
- **LRF Offset Alignment**: Enforced 256-byte alignment in `LRFRegionFileAdapter` to match LRF Header specification. Resolved `Invalid Magic Byte` and corruption issues.
- **IO Performance**: Removed blocking `channel.force()` calls on every chunk write. Writes now leverage OS page cache, eliminating IO starvation and server hangs.
- **Cache Logic**: Fixed `TurboCacheManager` memory leak where eviction size wasn't tracked, causing cache thrashing. Now correctly releases memory quota on eviction.
- **LZ4 Compatibility**: Implemented robust transcoding for Paper's Moonrise system, supporting seamless Zlib/GZip/LZ4 input handling without crashes (`Invalid tag id`).

## ⚡ Performance Optimizations
- **Non-blocking IO**: Writes are sequential and buffered by OS.
- **Smart Caching**: 256MB L1 Cache now works as intended, reducing disk reads significantly.
- **Universal Compression**: Automatically handles Paper's internal compression formats while enforcing LRF's configured format (LZ4) on disk.
- **Math Intrinsics**: Validated Java 21 `Math.clamp` for physics acceleration.

## 🛠 Features (v1.5.0)
- **Full LRF Storage**: Native linear format (`.lrf`) with compact headers.
- **Auto-Conversion**: `/turbo storage convert` and startup migration.
- **Monitoring**: Detailed timestamps and conversion logs.
- **Stability**: Tested and verified against strict `NbtIo` checks.

## 📊 Performance Results (Observed)
- **Stability**: Server startups and chunk loads are consistent without "Invalid Tag" crashes.
- **Throughput**: Chunk saving no longer blocks the main thread or IO workers.
- **Memory**: Cache usage respects 256MB limit properly.

## Recommended Configuration
```toml
[storage]
format = "lrf"
conversion-mode = "full-lrf"

[compression]
algorithm = "lz4"
level = 3
```