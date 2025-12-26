# LRF Conversion Modes - TurboMC

## Overview

TurboMC LRF supports four conversion modes that determine when and how MCA files are converted to LRF format.

## Conversion Modes

### 1. ON_DEMAND (Default)

**Configuration:**
```toml
[storage]
conversion-mode = "on-demand"
auto-convert = true
```

**Behavior:**
- Converts MCA → LRF gradually as chunks are loaded during gameplay
- Each chunk is converted the first time it's accessed
- Lower initial server startup time
- Conversion happens while players are online

**Pros:**
- Faster server startup
- No upfront conversion time
- Works well with large worlds

**Cons:**
- Performance impact during gameplay
- Slower initial chunk loading
- Conversion happens during peak usage

**Best for:**
- Large servers with many worlds
- Production servers that can't afford long startup times
- Servers with existing large worlds



### 2. BACKGROUND

**Configuration:**
```toml
[storage]
conversion-mode = "background"
auto-convert = true
```

**Behavior:**
- Converts MCA → LRF during server idle time
- Intelligent scheduling during low server load periods
- Balanced approach between startup time and performance
- Conversion happens when server is not busy

**Pros:**
- Balanced performance approach
- Intelligent scheduling during idle moments
- No impact on peak server performance
- Good compromise between startup time and optimization

**Cons:**
- Requires idle time detection
- Conversion timing depends on server activity
- May take longer to complete full conversion

**Best for:**
- Medium servers
- Servers with variable player loads
- Environments that want balanced performance


### 3. FULL_LRF

**Configuration:**
```toml
[storage]
conversion-mode = "full-lrf"
auto-convert = true
```

**Behavior:**
- Converts ALL MCA → LRF files during server startup
- Complete conversion before any player can join
- Maximum optimization from the start
- Higher initial startup time

**Pros:**
- Maximum performance from the start
- Consistent performance during gameplay
- No conversion overhead during gameplay
- All chunks optimized immediately

**Cons:**
- Longer server startup time
- Requires temporary disk space
- Not suitable for very large worlds

**Best for:**
- Small to medium servers
- Fresh server installations
- Maximum performance requirements
- Servers with downtime for maintenance



### 4. MANUAL

**Configuration:**
```toml
[storage]
conversion-mode = "manual"
auto-convert = false
```

**Behavior:**
- No automatic conversion
- Requires manual intervention
- Full control over conversion timing

**Pros:**
- Complete control over conversion process
- Can schedule conversions during low-usage periods
- No automatic changes to world files

**Cons:**
- Requires manual action
- More complex setup
- Risk of forgetting to convert

**Best for:**
- Advanced users
- Servers with specific maintenance windows
- Testing and development environments



## Usage Examples

### Switching to Background Mode

1. Edit `turbo.toml`:
```toml
[storage]
format = "lrf"
auto-convert = true
conversion-mode = "background"
```

2. Restart server
3. Conversion will happen during idle server time

### Switching to Full LRF Mode

1. Edit `turbo.toml`:
```toml
[storage]
format = "lrf"
auto-convert = true
conversion-mode = "full-lrf"
```

2. Restart server
3. All MCA files will be converted during startup

### Switching to On-Demand Mode

1. Edit `turbo.toml`:
```toml
[storage]
format = "lrf"
auto-convert = true
conversion-mode = "on-demand"
```

2. Restart server
3. Files will convert gradually as chunks are loaded

### Manual Conversion

1. Set manual mode in `turbo.toml`:
```toml
[storage]
format = "lrf"
auto-convert = false
conversion-mode = "manual"
```

2. Use conversion API:
```java
import com.turbomc.storage.converter.RegionConverter;
import com.turbomc.storage.StorageFormat;

Path worldRegionDir = Paths.get("world/region");
new RegionConverter(true)
    .convertRegionDirectory(worldRegionDir, worldRegionDir, StorageFormat.LRF);
```



## Performance Comparison

| Mode | Startup Time | Gameplay Performance | Disk Usage | Complexity |
|------|-------------|---------------------|------------|------------|
| ON_DEMAND | Fast | Variable (improves over time) | Gradual increase | Low |
| BACKGROUND | Medium | Consistent (improves gradually) | Gradual increase | Medium |
| FULL_LRF | Slow | Consistent (optimal) | Immediate increase | Low |
| MANUAL | Fastest | Depends on conversion timing | Controlled | High |




## Migration Between Modes

### From ON_DEMAND to BACKGROUND
1. Change `conversion-mode` to `"background"`
2. Restart server
3. Background conversion will run during idle time

### From BACKGROUND to FULL_LRF
1. Change `conversion-mode` to `"full-lrf"`
2. Restart server
3. Full conversion will run on startup

### From Any Mode to MANUAL
1. Set `auto-convert = false`
2. Set `conversion-mode = "manual"`
3. Restart server
4. Use manual conversion as needed



## Troubleshooting

### Background Mode Not Converting
- Ensure server has idle periods
- Check server load and player activity
- Monitor conversion progress in server logs

### Full LRF Takes Too Long
- Consider switching to background or on-demand mode
- Ensure sufficient disk space
- Monitor conversion progress in server logs

### On-Demand Performance Issues
- Consider switching to background or full-lrf mode
- Pre-convert frequently used areas
- Schedule conversions during low-usage periods

### Manual Conversion Not Working
- Verify `auto-convert = false`
- Check file permissions
- Ensure world directory is accessible



## Technical Details

### Conversion Process

**ON_DEMAND:**
```
Chunk Request → Check if .lrf exists → Convert .mca → .lrf → Load chunk
```

**BACKGROUND:**
```
Idle Detection → Schedule Conversion → Convert .mca → .lrf → Repeat during idle periods
```

**FULL_LRF:**
```
Server Start → Scan all .mca files → Convert all to .lrf → Accept connections
```

**MANUAL:**
```
Manual API Call → Convert specified .mca files → .lrf → Ready for use
```

### File Management

- **ON_DEMAND**: Original .mca files are deleted after conversion
- **BACKGROUND**: Original .mca files are deleted after conversion during idle time
- **FULL_LRF**: All .mca files are converted, then deleted
- **MANUAL**: Depends on conversion method used

### Rollback Support

All modes support rollback to MCA format:
```java
import com.turbomc.storage.converter.LRFToMCAConverter;

new LRFToMCAConverter(true)
    .convertDirectory(Paths.get("world/region"), Paths.get("world/region"), true);
```



**TurboMC v2.3.4 (The Dynamic Throttling Update)** | LRF Conversion Modes Implemented & Verified
