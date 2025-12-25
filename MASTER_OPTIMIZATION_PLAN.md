# TurboMC Master Optimization Plan
## Complete System Analysis & Action Items

---

## 🔍 Current System Analysis

### ✅ What's Working
- **LRF Format**: Functional, 30-40% compression vs MCA
- **Zstd Compression**: Level 3 achieves good balance
- **Batch I/O**: Reduces syscall overhead
- **LOD 4 Extraction**: Successfully collecting heightmap data
- **Multi-threading**: Parallel chunk generation works

### 🔴 Critical Issues

#### Issue #1: MMap Race Condition (CRITICAL)
**Problem**: `MMapReadAheadEngine` reads stale data during concurrent writes
```
[TurboMC][Integrity] CORRUPTION confirmed: Primary and backup checksums mismatch
```

**Root Cause**:
```java
// MMapReadAheadEngine.java
ByteBuffer mapped = channel.map(FileChannel.MapMode.READ_ONLY, offset, length);
// ⚠️ Mapping doesn't invalidate when file changes on disk
// ⚠️ No synchronization with BatchSaver flush
```

**Fix Strategy**:
1. Add `MappedByteBuffer.force()` after writes
2. Implement `FlushBarrier` between write and read
3. Or: Switch to direct FileChannel reads (safer but slower)

---

#### Issue #2: LOD Visual Rendering (Not Implemented)
**Problem**: GlobalIndex collects data but nothing renders it

**Why Ghost Chunks Failed**:
- Empty chunks overwrite real chunks
- Paper API restrictions
- Massive bandwidth cost

**Correct Solution**: Client-side shader rendering (v2.5.0)

---

#### Issue #3: Chunk Generation Slowness
**Problem**: "noto un poco mas lento el chunking"

**Causes**:
1. LOD extraction adds CPU overhead
2. Integrity validation on every save
3. Compression CPU time
4. No chunk data optimization

---

## 🎯 Master Optimization Plan

### Phase 1: Fix Critical Bugs (v2.3.3 - URGENT)

#### 1.1 MMap Synchronization Fix
```java
// TurboStorageManager.java
public class FlushBarrier {
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    
    public void beforeWrite() { lock.writeLock().lock(); }
    public void afterWrite() { 
        mappedBuffer.force(); // Flush to disk
        lock.writeLock().unlock(); 
    }
    
    public void beforeRead() { lock.readLock().lock(); }
    public void afterRead() { lock.readLock().unlock(); }
}
```

**Implementation**:
- [ ] Add FlushBarrier to TurboStorageManager
- [ ] Wrap all MMap operations with barriers
- [ ] Add force() after batch saves
- [ ] Test with 1000 chunk stress test

---

#### 1.2 Integrity Validation Optimization
**Problem**: Running CRC32C + SHA256 on EVERY save is expensive

**Solution**: Smart validation
```java
// Only validate:
// - First save of a chunk (corruption detection)
// - After system crash recovery
// - Random 1% sampling during normal operation

if (isFirstSave || afterCrash || random.nextFloat() < 0.01) {
    validator.validate(data);
}
```

---

### Phase 2: Chunk Memory Optimization (v2.4.0 - NEW!)

#### 2.1 Palette Compacting
**Problem**: Minecraft stores full 16-bit palette even for mostly-air chunks

**Current**:
```
Chunk with 10 unique blocks → 16-bit palette (65,536 entries)
Memory: 16 × 16 × 16 × 2 bytes = 8KB per section
```

**Optimized**:
```java
// CompactPalette.java
public class CompactPalette {
    
    public static PalettedContainer<BlockState> compact(PalettedContainer<BlockState> original) {
        int uniqueBlocks = countUniqueBlocks(original);
        
        if (uniqueBlocks <= 16) {
            return new SingleValuePalette<>();  // 0 bits per block!
        } else if (uniqueBlocks <= 256) {
            return new BytePalette<>();         // 8 bits per block
        }
        
        return original; // Keep original if complex
    }
}
```

**Expected Savings**: 50-70% memory for typical chunks

---

#### 2.2 Empty Section Pruning
**Problem**: Storing all 24 vertical sections even if empty

**Solution**:
```java
// ChunkOptimizer.java
public static LevelChunk pruneEmptySections(LevelChunk chunk) {
    LevelChunkSection[] sections = chunk.getSections();
    
    for (int i = 0; i < sections.length; i++) {
        if (sections[i].hasOnlyAir()) {
            sections[i] = null; // Free memory
        }
    }
    
    return chunk;
}
```

**Expected Savings**: 30-40% for chunks with large empty volumes

---

#### 2.3 NBT Compression Optimization
**Problem**: Each chunk stores redundant NBT data

**Current NBT Structure**:
```json
{
  "Level": {
    "xPos": -50,
    "zPos": -28,
    "sections": [...],
    "Heightmaps": {
      "WORLD_SURFACE": [long array],
      "MOTION_BLOCKING": [long array],  // Redundant!
      "OCEAN_FLOOR": [long array]       // Rarely used!
    },
    "TileEntities": [...],
    "Entities": [...]
  }
}
```

**Optimized**:
```java
// NBTOptimizer.java
public static CompoundTag optimizeChunkNBT(CompoundTag nbt) {
    CompoundTag level = nbt.getCompound("Level");
    
    // 1. Remove redundant heightmaps
    CompoundTag heightmaps = level.getCompound("Heightmaps");
    heightmaps.remove("MOTION_BLOCKING");      // Can be derived
    heightmaps.remove("MOTION_BLOCKING_NO_LEAVES");
    heightmaps.remove("OCEAN_FLOOR");
    
    // 2. Deduplicate tile entities
    ListTag tiles = level.getList("TileEntities", 10);
    tiles = deduplicateTileEntities(tiles);
    
    // 3. Remove empty arrays
    if (level.getList("Entities", 10).isEmpty()) {
        level.remove("Entities");
    }
    
    return nbt;
}
```

**Expected Savings**: 15-25% NBT size reduction

---

#### 2.4 Block State Deduplication
**Problem**: Many chunks share identical block states

**Solution**: Global block state pool
```java
// BlockStatePool.java
public class BlockStatePool {
    private static final Map<BlockState, Integer> stateToId = new HashMap<>();
    private static final List<BlockState> idToState = new ArrayList<>();
    
    public static int intern(BlockState state) {
        return stateToId.computeIfAbsent(state, s -> {
            int id = idToState.size();
            idToState.add(s);
            return id;
        });
    }
    
    public static BlockState get(int id) {
        return idToState.get(id);
    }
}

// In chunk serialization:
// Instead of: "minecraft:stone[variant=granite]"
// Store: ID 42 (4 bytes instead of 30)
```

**Expected Savings**: 40-60% for block state data

---

### Phase 3: Advanced Generation Optimization (v2.4.5)

#### 3.1 Predictive Chunk Pre-Generation
**Concept**: Generate chunks player will visit BEFORE they arrive

**Implementation**:
```java
// PredictiveGenerator.java
public class PredictiveGenerator {
    
    public void analyzePath(ServerPlayer player) {
        Vec3 velocity = player.getDeltaMovement();
        Vec3 position = player.position();
        
        // Project future position
        Vec3 futurePos = position.add(velocity.scale(20)); // 1 second ahead
        ChunkPos futureChunk = new ChunkPos(futurePos);
        
        // Pre-generate in cone ahead of player
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                ChunkPos target = new ChunkPos(
                    futureChunk.x + dx,
                    futureChunk.z + dz
                );
                
                if (!isGenerated(target)) {
                    queueGeneration(target, Priority.HIGH);
                }
            }
        }
    }
}
```

---

#### 3.2 Lazy Chunk Population
**Problem**: Full population (trees, ores) happens immediately

**Solution**: Defer decoration until player is close
```java
// LazyPopulator.java
public class LazyPopulator {
    
    public LevelChunk generateSparse(ChunkPos pos) {
        LevelChunk chunk = new LevelChunk();
        
        // Only generate:
        // 1. Terrain shape (stone, dirt, grass)
        // 2. Basic heightmap
        // 3. NO trees, ores, structures
        
        chunk.setStatus(ChunkStatus.TERRAIN);
        return chunk;
    }
    
    public void populateWhenNear(LevelChunk chunk) {
        // When player within 8 chunks:
        // - Add trees
        // - Add ores
        // - Add structures
        
        chunk.setStatus(ChunkStatus.FULL);
    }
}
```

**Expected Speedup**: 3-5x faster initial generation

---

### Phase 4: Client-Side LOD (v2.5.0)

#### 4.1 HTTP API for GlobalIndex
```java
// TurboAPIServer.java
@RestController
public class TurboAPIServer {
    
    @GetMapping("/api/chunks/index/{world}")
    public byte[] getWorldIndex(@PathVariable String world) {
        Path twiPath = dataFolder.resolve(world + "/turbo_index.twi");
        byte[] data = Files.readAllBytes(twiPath);
        
        // Compress with Zstd level 6
        return Zstd.compress(data, 6);
    }
    
    @GetMapping("/api/chunks/region/{world}/{rx}/{rz}")
    public ChunkIndexRegion getRegion(
        @PathVariable String world,
        @PathVariable int rx,
        @PathVariable int rz) {
        
        // Return 32x32 chunk index for specific region
        return indexManager.getRegion(world, rx, rz);
    }
}
```

---

#### 4.2 TurboLOD Fabric Mod
**Structure**:
```
TurboLOD-fabric/
├── IndexDownloader.java      # Fetch .twi from server
├── ShaderRenderer.java        # GPU-based impostor rendering
├── ChunkLODCache.java         # Client-side cache
└── resources/
    └── shaders/
        ├── lod_vertex.vsh     # Heightmap → geometry
        └── lod_fragment.fsh   # Distance-based fade
```

**Rendering Pipeline**:
1. Download `.twi` file on join (10-20MB one-time)
2. Parse heightmap + biome data
3. Generate impostor quads on GPU
4. Render with distance fade shader
5. Update incrementally as player moves

---

## 📊 Expected Performance Gains

| Optimization | Memory Saved | Speed Gain | Priority |
|--------------|--------------|------------|----------|
| **MMap Fix** | 0% | 0% (stability) | 🔴 CRITICAL |
| **Palette Compact** | 50-70% | N/A | 🟡 HIGH |
| **Section Pruning** | 30-40% | N/A | 🟡 HIGH |
| **NBT Optimization** | 15-25% | 10-15% | 🟢 MEDIUM |
| **Block State Pool** | 40-60% | 5-10% | 🟢 MEDIUM |
| **Predictive Gen** | N/A | 2-3x | 🟡 HIGH |
| **Lazy Population** | N/A | 3-5x | 🟡 HIGH |
| **Client LOD** | 0% (server) | Infinite render | 🔵 FUTURE |

**Combined Expected Result**:
- **Memory**: 60-80% reduction per chunk
- **Generation**: 5-10x faster for player movement
- **Disk I/O**: 40-50% less data written
- **Render Distance**: Client-side unlimited

---

## 🗺️ Implementation Roadmap

### v2.3.3 - Stability (1-2 weeks)
- [x] Disable ghost chunks
- [ ] Fix MMap race condition
- [ ] Optimize integrity validation
- [ ] Stress test with 100+ chunks/sec

### v2.4.0 - Chunk Memory Optimization (2-3 weeks)
- [ ] Implement CompactPalette
- [ ] Implement Section Pruning
- [ ] Implement NBT Optimizer
- [ ] Benchmark memory usage
- [ ] A/B test with vanilla

### v2.4.5 - Generation Optimization (2-3 weeks)
- [ ] Implement PredictiveGenerator
- [ ] Implement LazyPopulator
- [ ] Tune thread pool sizes
- [ ] Profile CPU usage

### v2.5.0 - Client-Side LOD (4-6 weeks)
- [ ] Create HTTP API
- [ ] Develop TurboLOD Fabric mod
- [ ] Implement shader renderer
- [ ] Test with Distant Horizons integration
- [ ] Public beta release

---

## 🔧 Configuration Recommendations

### For Current Stability
```toml
[storage.mmap]
enabled = false  # Until race condition fixed

[storage.integrity]
enabled = true
validation-probability = 0.01  # 1% sampling

[storage.batch]
save-threads = 6
load-threads = 12
```

### For Maximum Performance (After Fixes)
```toml
[storage.mmap]
enabled = true
prefetch-distance = 12
max-memory-usage = 1024

[chunk.optimization]
palette-compacting = true
section-pruning = true
nbt-optimization = true
block-state-pool = true

[generation]
predictive-enabled = true
lazy-population = true
prediction-scale = 20
```

---

## 📝 Testing Strategy

### Phase 1: Stability Tests
```bash
# 1. MMap stress test
./test-scripts/mmap-stress.sh 1000  # 1000 concurrent writes

# 2. Corruption detection
./test-scripts/corruption-test.sh   # Inject faults, verify recovery

# 3. Long-term stability
./test-scripts/24h-stress.sh        # Run server for 24h
```

### Phase 2: Performance Tests
```bash
# 1. Memory profiling
./test-scripts/memory-profile.sh    # Measure per-chunk RAM

# 2. Generation speed
./test-scripts/gen-benchmark.sh     # Chunks/second

# 3. I/O throughput
./test-scripts/io-benchmark.sh      # MB/s read/write
```

---

## 🎯 Success Metrics

### v2.3.3 (Stability)
- ✅ Zero chunk corruption in 24h stress test
- ✅ Server runs at 20 TPS with 10 players
- ✅ No integrity validation errors

### v2.4.0 (Memory)
- ✅ 70%+ memory reduction per chunk
- ✅ 1000 chunks loaded uses <2GB RAM
- ✅ Startup time <30 seconds

### v2.4.5 (Generation)
- ✅ 500+ chunks/second generation speed
- ✅ Zero lag spikes during Elytra flight
- ✅ CPU usage <50% during generation

### v2.5.0 (LOD)
- ✅ Client renders 2000+ chunks at 60 FPS
- ✅ <200MB additional client RAM
- ✅ Seamless transition between LOD levels

---

## 🤝 Community Involvement

### Open Source Contributions
- Publish CompactPalette as standalone library
- Contribute to Paper's chunk system
- Share NBT optimization techniques

### Mod Ecosystem
- TurboLOD API for third-party mods
- Distant Horizons integration
- Shader pack compatibility

---

**Last Updated**: 2025-12-25  
**Current Version**: v2.3.2  
**Next Milestone**: v2.3.3 Stability Fix  
**Long-term Goal**: v2.5.0 Client LOD

---

## 📚 References

- [Minecraft Chunk Format](https://minecraft.wiki/w/Chunk_format)
- [PalettedContainer Source](https://github.com/PaperMC/Paper)
- [Distant Horizons Architecture](https://gitlab.com/jeseibel/distant-horizons)
- [LRF Format Spec](./LRF_FORMAT.md)
