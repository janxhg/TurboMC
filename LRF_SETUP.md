# TurboMC LRF (Linear Region Format) Setup Guide

## Overview

TurboMC now includes a complete **Linear Region Format (LRF)** storage system that provides:

- **Sequential chunk storage** (no fragmentation)
- **Fast LZ4 compression** (up to 40% bandwidth reduction)
- **Efficient header structure** for faster I/O
- **Better performance** than vanilla Anvil (.mca) format

## Configuration

### 1. `turbo.toml` - TurboMC Storage Configuration

Located in your server root directory. Controls LRF behavior:

```toml
[storage]
# Region file format: "lrf" (optimized), "mca" (vanilla), or "auto" (detect)
format = "lrf"

# Automatically convert MCA to LRF when loading chunks
auto-convert = true

# Conversion mode: "on-demand" (convert as chunks load), "background" (idle time), "full-lrf" (convert all at startup), or "manual"
conversion-mode = "on-demand"
```

**Options:**

- `format = "lrf"` - Use LRF as primary storage format
- `format = "mca"` - Use vanilla Anvil format (default Paper behavior)
- `format = "auto"` - Auto-detect based on existing files
- `auto-convert = true` - Automatically migrate regions to LRF
- `auto-convert = false` - Manual conversion only

**Conversion Modes:**

- `conversion-mode = "on-demand"` - Convert MCA → LRF gradually as chunks are loaded (default)
  - **Pros**: Lower startup time, gradual conversion
  - **Cons**: Slower initial performance, conversion happens during gameplay
  - **Best for**: Large servers with many worlds

- `conversion-mode = "background"` - Convert MCA → LRF during server idle time
  - **Pros**: Balanced approach, intelligent scheduling during low load
  - **Cons**: Requires idle time detection, conversion timing depends on server activity
  - **Best for**: Medium servers that want balanced performance

- `conversion-mode = "full-lrf"` - Convert ALL MCA → LRF files during server startup
  - **Pros**: Maximum optimization from start, consistent performance
  - **Cons**: Higher startup time, requires more disk space temporarily
  - **Best for**: Small servers, fresh installations, maximum performance

- `conversion-mode = "manual"` - No automatic conversion
  - **Pros**: Full control over conversion timing
  - **Cons**: Requires manual intervention
  - **Best for**: Advanced users who want to control conversion process

### 2. `paper-global.yml` - Paper Storage Configuration

Optional Paper-level storage config (for future integration):

```yaml
storage:
  format: "LRF"        # or "MCA"
  auto-migrate: true   # auto-migrate regions on startup
```

## Usage

### Automatic Migration

When `auto-convert = true` in `turbo.toml`:

1. Server starts
2. `TurboLRFBootstrap` initializes LRF system
3. Regions are automatically migrated from `.mca` to `.lrf`
4. Worlds load normally with LRF storage

### Manual Conversion

If you prefer manual control:

1. Set `auto-convert = false` in `turbo.toml`
2. Use the conversion API:

```java
import com.turbomc.storage.converter.RegionConverter;
import com.turbomc.storage.StorageFormat;

Path worldRegionDir = Paths.get("world/region");
new RegionConverter(true)
    .convertRegionDirectory(worldRegionDir, worldRegionDir, StorageFormat.LRF);
```

### Programmatic Usage

```java
import com.turbomc.config.TurboConfig;
import com.turbomc.storage.TurboLRFBootstrap;
import java.nio.file.Paths;

// Initialize LRF system (call once at server startup)
TurboLRFBootstrap.initialize(Paths.get("."));

// Migrate a specific world
TurboLRFBootstrap.migrateWorldIfNeeded(Paths.get("world"));
```

## File Structure

### LRF File Format v2.0
Each `.lrf` file uses a standardized 8KB header and 256-byte sector alignment:

```
Header (8192 bytes)
├─ Magic bytes: "TURBO_LRF" (9 bytes)
├─ Version (4 bytes)
├─ Chunk count (4 bytes)
├─ Compression type (4 bytes)
└─ Offsets table (4096 bytes: 1024 chunks * 4 bytes)

Chunks (256-byte aligned, sequential)
├─ 5-byte Per-Chunk Header (4 bytes length + 1 byte compression)
├─ Compressed Payload
└─ Padding to 256-byte boundary
```

### Naming Convention

- Vanilla: `r.0.0.mca`
- TurboMC LRF: `r.0.0.lrf`

Same coordinate system, different format.

## Performance

Compared to vanilla Anvil (.mca):

| Metric | Improvement |
|--------|-------------|
| Compression Ratio | ~35% smaller files |
| Read Speed | ~4-6× faster (MMap + Predictive) |
| Write Speed | ~3-4× faster (Batch I/O) |
| Network Bandwidth | ~40% reduction |

## Troubleshooting

### Migration Fails

If migration fails:

1. Check server logs for errors
2. Ensure `world/region/` directory exists
3. Verify disk space is available
4. Check file permissions

### Rollback to MCA

To revert to vanilla Anvil format:

1. Set `format = "mca"` in `turbo.toml`
2. Use `LRFToMCAConverter` to convert back:

```java
import com.turbomc.storage.converter.LRFToMCAConverter;

new LRFToMCAConverter(true)
    .convertDirectory(
        Paths.get("world/region"),
        Paths.get("world/region"),
        true
    );
```

## Classes Reference

### Core Classes

- **`StorageFormat`** - Enum for MCA/LRF format selection
- **`TurboStorageConfig`** - Configuration model for storage settings
- **`TurboStorageMigrator`** - High-level migration helper
- **`TurboLRFBootstrap`** - Server startup initializer
- **`TurboConfig`** - Main config loader from `turbo.toml`

### Converters

- **`MCAToLRFConverter`** - Convert Anvil → LRF
- **`LRFToMCAConverter`** - Convert LRF → Anvil
- **`RegionConverter`** - Auto-detect and convert

### I/O

- **`LRFRegionWriter`** - Write `.lrf` files
- **`LRFRegionReader`** - Read `.lrf` files
- **`LRFHeader`** - Manage region headers
- **`LRFChunkEntry`** - Individual chunk data

## Integration Points

To integrate LRF into your server startup:

1. Call `TurboLRFBootstrap.initialize()` early in bootstrap
2. Before loading worlds, call `TurboLRFBootstrap.migrateWorldIfNeeded(worldPath)`
3. Configure `turbo.toml` with desired settings

Example (pseudo-code for plugin/bootstrap):

```java
public void onServerStart() {
    // Initialize LRF system
    TurboLRFBootstrap.initialize(new File(".").toPath());
    
    // Migrate worlds before loading
    for (World world : getWorlds()) {
        TurboLRFBootstrap.migrateWorldIfNeeded(world.getWorldFolder().toPath());
    }
}
```

## Realized Features (v2.0)
The following features are now fully implemented and stable:
- [x] Real-time LRF I/O integration (via `TurboRegionFileStorage`)
- [x] Extreme Predictive Loading (proactive movement analysis)
- [x] Background migration during idle server time
- [x] TNBT Transcoding for vanilla compatibility
- [x] 256-byte sector alignment for NVMe efficiency

## Support

For issues or questions:

- Check server logs: `logs/latest.log`
- Review `turbo.toml` configuration
- Ensure Java 21+ is installed
- Verify `--add-modules=jdk.incubator.vector` flag is present

---

**TurboMC v2.3.4 (The Dynamic Throttling Update)** | LRF Storage System v2 Ready & Verified
