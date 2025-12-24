# TurboMC Configuration Guide

## turbo.toml - Complete Configuration Reference

### Storage Configuration

```toml
[storage]
# Storage format: "lrf" (Linear Region Format), "mca" (vanilla), "auto" (auto-detect)
format = "lrf"

# Conversion mode: "full-lrf", "on-demand", "background", "manual"
# - full-lrf: Convert all MCA to LRF at startup (strict, no residue)
# - on-demand: Convert chunks lazily as they're accessed
# - background: Convert during idle server time
# - manual: No automatic conversion
conversion-mode = "full-lrf"

# Backup original MCA files after conversion
backup-original-mca = false

# Enable verbose logging
verbose = true

# Auto-migrate worlds on startup
auto-migrate = true
```

### Compression Configuration

```toml
[compression]
# Algorithm: "lz4" (fast), "zlib" (high compression)
algorithm = "lz4"

# Compression level: 1-9 (1=fast, 9=best compression)
level = 3

# Enable fallback compression if primary fails
fallback-enabled = true

# Adaptive compression: auto-select based on data type
adaptive = true
```

### Batch Operations Configuration

```toml
[storage.batch]
# Number of I/O threads for loading
load-threads = 4

# Number of decompression threads
decompress-threads = 2

# Number of compression threads for saving
compress-threads = 2

# Number of write threads for saving
save-threads = 2

# Global executors are now scalable (v2.0): 
# TurboMC uses up to 16 load threads and 32 decompression threads by default on high-core systems.

# Maximum chunks per batch
batch-size = 32

# Maximum concurrent load operations
max-concurrent-loads = 64
```

### Memory-Mapped I/O Configuration

```toml
[storage.mmap]
# Enable memory-mapped I/O for faster access
enabled = true

# Maximum cache size in MB (Increased in v2.1.0)
max-cache-size = 1024

# Prefetch distance in chunks
prefetch-distance = 4

# Prefetch batch size
prefetch-batch-size = 32

# Predictive/Kinematic Prefetching (v2.0)
# Analyzes movement vectors to pre-load chunks in the direction of travel
predictive-enabled = true

# Prediction strength (how many chunks ahead to look)
# Recommended: 12 (Base). At high speeds (flyspeed 10), TurboMC scales this automatically up to 4x (48 chunks).
prediction-scale = 12

# Maximum memory usage in MB
max-memory-usage = 256
```

### OVF (Optimized Voxel Format) Configuration [NEW v2.0]

```toml
[ovf]
# Enable Optimized Voxel Format for high-performance structure loading
enabled = true

# Compression level for RLE (Run-Length Encoding)
# Note: RLE is always active, level 3 is currently standard.
compression-level = 3
```

### Background Conversion Configuration

```toml
[storage.background]
# Check interval for background conversion (minutes)
check-interval-minutes = 5

# Maximum concurrent background conversions
max-concurrent = 2

# CPU usage threshold for idle detection (0.0-1.0)
cpu-threshold = 0.3

# Minimum idle time before starting conversion (ms)
min-idle-time-ms = 30000
```

### Chunk Loading Optimizer Configuration

```toml
[chunk]
# Enable chunk loading optimizer
optimizer.enabled = true

# Default loading strategy: "conservative", "balanced", "aggressive", "extreme", "adaptive"
# Recommended: "aggressive" for v2.0 performance
default-strategy = "aggressive"
```

### Performance Optimization Configuration

```toml
[performance]
# Enable FPS optimizer
fps-optimizer.enabled = true

# Target TPS (ticks per second)
target-tps = 20.0

# TPS tolerance (±)
tps-tolerance = 1.0

# Enable quality manager
quality-manager.enabled = true

# Quality management mode: "auto", "manual"
quality-mode = "auto"

# CPU threshold for quality reduction (0.0-1.0)
cpu-threshold = 0.8

# Memory threshold for quality reduction (0.0-1.0)
memory-threshold = 0.85
```

### Optimization Flags

```toml
[optimizations]
# Redstone optimization
redstone-optimization = true

# Entity optimization
entity-optimization = true

# Hopper optimization
hopper-optimization = true

# Crop growth optimization
crop-growth-optimization = true

# Mob spawning optimization
mob-spawning-optimization = true

# Pathfinding optimization
pathfinding-optimization = true
```

## Recommended Configurations

### High-Performance Server (1000+ players)

```toml
[storage]
format = "lrf"
conversion-mode = "full-lrf"
backup-original-mca = false
verbose = false

[compression]
algorithm = "lz4"
level = 1
adaptive = true

[storage.batch]
load-threads = 8
decompress-threads = 4
compress-threads = 4
save-threads = 2
batch-size = 64
max-concurrent-loads = 128

[storage.mmap]
enabled = true
max-cache-size = 2048
prefetch-distance = 8
max-memory-usage = 512

[chunk]
optimizer.enabled = false

[performance]
fps-optimizer.enabled = true
quality-manager.enabled = true
```

### Medium Server (100-500 players)

```toml
[storage]
format = "lrf"
conversion-mode = "on-demand"
backup-original-mca = false
verbose = true

[compression]
algorithm = "lz4"
level = 3
adaptive = true

[storage.batch]
load-threads = 4
decompress-threads = 2
compress-threads = 2
save-threads = 1
batch-size = 32
max-concurrent-loads = 64

[storage.mmap]
enabled = true
max-cache-size = 512
prefetch-distance = 4
max-memory-usage = 256

[chunk]
optimizer.enabled = false

[performance]
fps-optimizer.enabled = true
quality-manager.enabled = true
```

### Small Server (10-100 players)

```toml
[storage]
format = "lrf"
conversion-mode = "background"
backup-original-mca = true
verbose = true

[compression]
algorithm = "zlib"
level = 6
adaptive = true

[storage.batch]
load-threads = 2
decompress-threads = 1
compress-threads = 1
save-threads = 1
batch-size = 16
max-concurrent-loads = 32

[storage.mmap]
enabled = true
max-cache-size = 256
prefetch-distance = 2
max-memory-usage = 128

[chunk]
optimizer.enabled = false

[performance]
fps-optimizer.enabled = true
quality-manager.enabled = true
```

## Performance Tuning Tips

### For Maximum Speed
1. Set `compression.algorithm = "lz4"` and `level = 1`
2. Enable `storage.mmap.enabled = true`
3. Increase `storage.batch.load-threads` to CPU count
4. Set `storage.background.max-concurrent = CPU count / 2`

### For Maximum Compression
1. Set `compression.algorithm = "zlib"` and `level = 9`
2. Enable `compression.adaptive = true`
3. Increase `storage.batch.compress-threads`
4. Use `conversion-mode = "full-lrf"` for initial conversion

### For Balanced Performance
1. Use default settings (LZ4 level 3, batch size 32)
2. Enable both mmap and batch operations
3. Use `conversion-mode = "on-demand"` for existing worlds
4. Monitor TPS and adjust quality settings

## Monitoring and Debugging

### Enable Detailed Logging
```toml
[storage]
verbose = true
```

### Check Compression Stats
Use `/turbo stats` command to see:
- Compression/decompression counts
- Bytes processed
- Cache hit rates
- Load/save performance

### Monitor Background Conversion
Use `/turbo monitor` command to see:
- Conversion progress
- CPU usage
- Idle detection status
- Estimated time remaining

---

**Last Updated**: 2025-12-24
**Version**: 2.1.0 (The Command & Stress Update)
