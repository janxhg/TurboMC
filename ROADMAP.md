# TurboMC Development Roadmap

> **Objetivo:** Transformar TurboMC en un ecosistema completo de alto rendimiento con herramientas propias, optimizaciones avanzadas y compatibilidad total con Paper/Spigot.

---

## 📋 Table of Contents

- [🎯 Priorities Overview](#-priorities-overview)
- [🔧 Format Converters](#-format-converters)
- [🧱 Storage Engine](#-storage-engine)
- [⚡ Performance Optimizations](#-performance-optimizations)
- [🔒 Security & Safety](#-security--safety)
- [🧰 Developer Tools](#-developer-tools)
- [🔍 Debugging & Profiling](#-debugging--profiling)
- [🔌 Plugin Compatibility](#-plugin-compatibility)
- [🤖 Experimental Features](#-experimental-features)

---

## 📅 Version Status
**Current Stable:** v1.8.0 (Zero-Failure Stability)
**Next Milestone:** v1.9.0 (Profiling & Tools)

---

## 🎯 Priorities Overview

### 🔴 **Critical (Fase 1 - Fundación)**
Características esenciales para que TurboMC funcione correctamente.

### 🟠 **High Priority (Fase 2 - Diferenciación)**
Features que hacen a TurboMC único comparado con Paper/Spigot.

### 🟡 **Medium Priority (Fase 3 - Pulido)**
Mejoras de calidad de vida y optimizaciones adicionales.

### 🟢 **Low Priority (Fase 4 - Innovación)**
Características experimentales y avanzadas.

---

# 🔧 Format Converters

## 🔴 Essential Converters

### 1. Zlib → LZ4 Converter
**Propósito:** Migrar compresión de packets y chunks al formato LZ4 más rápido.

**Componentes:**
- [x] `TurboCompressionService` - Wrapper universal de compresión ✅ v1.2.0
- [x] Auto-detección de formato antiguo (zlib) ✅ v1.2.0 (magic bytes)
- [x] Conversión transparente on-the-fly ✅ v1.2.0 (chunk storage)
- [x] Fallback a zlib para compatibilidad con plugins legacy ✅ v1.2.0
- [x] API pública: `compress(byte[])` y `decompress(byte[])` ✅ v1.2.0

**Ejemplo de uso:**
```java
byte[] compressed = TurboCompressionService.compress(data, Algorithm.LZ4);
byte[] decompressed = TurboCompressionService.decompress(compressed);
```

---

### 2. MCA → Linear Region Format (LRF)
**Propósito:** Convertir mundos desde el formato Anvil (.mca) al nuevo formato lineal optimizado.

**Modos de conversión:**
- [x] **Conversión completa:** Todo el mundo de una vez (CLI) ✅ v1.3.0
- [x] **Conversión incremental:** Por regiones con throttling ✅ v1.3.0
- [x] **On-demand:** Convierte chunks cuando son cargados por primera vez ✅ v1.3.0
- [x] **Background:** Conversión automática en bajo uso del servidor ✅ v1.3.0

**Componentes:**
- [x] `LRFRegionWriter` - Escritor del nuevo formato ✅ v1.3.0
- [x] `AnvilRegionReader` - Lector de formato MCA vanilla ✅ v1.3.0
- [x] CLI: `java -jar TurboTools.jar convert world/region --to-lrf`
- [x] Auto-migración opcional al inicio del servidor ✅ v1.3.0 (configuración agregada)
- [x] Progress tracking y logging ✅ v1.3.0

**Estructura LRF:**
```
Header (256 bytes)
├─ Version (4 bytes)
├─ Chunk count (4 bytes)
├─ Compression type (4 bytes)
└─ Offsets table (244 bytes)

Chunks (sequential, no padding)
├─ Chunk 0 (LZ4 compressed)
├─ Chunk 1 (LZ4 compressed)
└─ ...
```

---

### 3. LRF → MCA Converter (Reverse)
**Propósito:** Permitir rollback al formato vanilla si es necesario.

- [x] Implementar `LRFRegionReader` ✅ v1.3.0
- [x] Implementar `AnvilRegionWriter` ✅ v1.3.0
- [x] CLI: `java -jar TurboTools.jar convert world/region --to-mca` v1.4.0
- [x] Validación de integridad durante conversión ✅ v1.3.0


---

## 🟠 Advanced Converters

### 4. NBT → Packed-Binary Format
**Propósito:** Reducir overhead del formato NBT estándar.

**Optimizaciones:**
- [x] Serialización binaria compacta (sin nombres redundantes)
- [x] Tablas de strings deduplicadas globalmente
- [x] Compresión LZ4 opcional
- [x] 30-50% reducción de tamaño vs NBT+GZIP

**API:**
```java
PackedBinary data = NBTConverter.toPackedBinary(nbtTag);
byte[] compressed = data.compress();
```

---

### 5. YAML/JSON → Binary Config Cache
**Propósito:** Acelerar tiempo de inicio del servidor.

**Flujo:**
1. Primera carga: Lee YAML/JSON (humano-legible)
2. Genera `.bin` cache optimizado
3. Próxima carga: Lee directamente del `.bin` (50-70% más rápido)
4. Invalidación automática si YAML cambia

- [x] `ConfigCacheManager` (ConfigCacheBuilder)
- [x] Detección de cambios por hash
- [ ] Migración automática de configs legacy

---

### 6. Schematics (.schem) → OptimizedVoxelFormat (.ovf)
**Propósito:** Mejorar rendimiento de WorldEdit/FAWE.

**Features:**
- [ ] Paletas de bloques optimizadas
- [ ] Compresión RLE (Run-Length Encoding)
- [ ] Carga de estructuras gigantes en <100ms
- [ ] Compatible con WorldEdit API

---

### 7. Zlib ↔ ZSTD Hybrid Compression
**Propósito:** Algoritmo adaptativo según contexto.

- [ ] ZSTD para snapshots (mejor ratio)
- [ ] LZ4 para network packets (más rápido)
- [ ] Auto-detección basada en throughput real
- [ ] Negociación con TurboProxy

---

# 🧱 Storage Engine

## 🔴 Core Storage Components

### Linear Region Format (LRF) Implementation
- [x] `LRFFileParser` - Parser del formato binario ✅ v1.3.0
- [x] `LRFSequentialWriter` - Escritor optimizado ✅ v1.3.0
- [x] `LRFHeader` - Gestión de metadata y offsets (256-byte aligned) ✅ v1.5.0
- [x] `LRFConstants` - Constantes y especificaciones ✅ v1.3.0
- [x] `LRFChunkEntry` - Estructura de entrada de chunk ✅ v1.3.0
- [x] `AnvilRegionReader` - Lector de archivos MCA ✅ v1.3.0
- [x] `AnvilRegionWriter` - Escritor de archivos MCA ✅ v1.3.0
- [x] `MCAToLRFConverter` - Conversor MCA → LRF ✅ v1.3.0
- [x] `LRFToMCAConverter` - Conversor LRF → MCA ✅ v1.3.0
- [x] `RegionConverter` - Auto-detección y conversión unificada ✅ v1.3.0
- [x] `ChunkBatchLoader` - Carga múltiples chunks en paralelo ✅ v1.4.0
- [x] `ChunkBatchSaver` - Escritura por lotes ✅ v1.4.0
- [x] mmap read-ahead engine para SSD/NVMe ✅ v1.4.0
- [x] Validación de integridad (checksums) ✅ v1.4.0
- [x] **Stability Fixes**: Alignment, Cache Thrashing, IO Blocking ✅ v1.5.0


---

## 🟠 Hybrid Chunk Cache System

### Arquitectura de 3 niveles:
```
L1: ChunkHotCache (RAM) 
    └─ 256-512MB, LRU, chunks activos

L2: ChunkWarmCache (mmap)
    └─ 1-2GB, disco mapeado, chunks recientes

L3: ChunkColdStorage (LRF/Disco)
    └─ Almacenamiento permanente
```

**Componentes:**
- [x] `TurboCacheManager` (RAM - Java Heap) ✅ v1.5.0 (Fixed Eviction)
- [ ] `ChunkWarmCache` (mmap - Off-Heap)
- [ ] `ChunkColdStorage` (LRF)
- [x] Política de evicción LRU (Size-based) ✅ v1.5.0
- [ ] Estadísticas: hits/misses por tick
- [ ] Telemetría: `/turbo cache stats`

---

## 🟡 Advanced Storage Features

### BlockState Palette Deduplication
**Propósito:** Reducir uso de RAM hasta 40%.

- [ ] Deduplicación de paletas entre chunks similares
- [ ] Pool global de paletas compartidas
- [ ] Reference counting para garbage collection
- [ ] Estadísticas de ahorro de memoria

### Region Delta Storage (Git-like)
**Propósito:** Backups incrementales ultra eficientes.

- [ ] Guardar solo cambios (deltas) vs versión anterior
- [ ] Backups instantáneos sin copiar todo el mundo
- [ ] Compresión ZSTD para deltas
- [ ] Restauración point-in-time

---

# ⚡ Performance Optimizations

## 🔴 Critical Performance Features

### 1. SIMD Entity Collision Engine
**Ya implementado parcialmente en v1.1.0. Extender a:**
- [x] SIMD bounding box intersection (8 entidades paralelas) ✅ v1.1.0
- [x] Vectorización de distance checks ✅ v1.1.0
- [x] Batch collision detection ✅ v1.1.0
- [x] Soporte AVX-512 en CPUs compatibles v1.7.0

### 2. Network IO Thread Pool
**Propósito:** Descargar compresión/descompresión del main thread.

- [x] Thread pool dedicado para network IO
- [x] Compresión LZ4 en paralelo
- [ ] Descompresión asíncrona de packets
- [ ] Queue non-blocking para main thread

---

## 🟠 High-Impact Optimizations

### 3. SIMD-based Pathfinding (A*)
- [ ] Vectorizar evaluación de nodos
- [ ] `FastTickPathfinderEngine` para mobs
- [ ] Cache de paths recientes
- [ ] Pathfinding incremental

### 4. Better Threading Model (Non-invasive)
**Sin romper compatibilidad con plugins:**

- [ ] Thread pool para chunk I/O
- [ ] Thread pool para entity AI no-crítica
- [ ] Thread pool para redstone pasiva
- [ ] Thread pool para physics pesada (slimes, boats)
- [ ] Work-stealing scheduler

### 5. Stacked Entity Ticking
**Propósito:** Reducir 30-60% CPU en entidades.

**Concepto:**
```
1500 vacas individuales → 1 "CowTickGroup"
  └─ Tick en batch, cache-friendly
```

- [x] Agrupación automática por tipo de entidad
- [x] Tick batch con SIMD donde sea posible
- [x] Grupos dinámicos según carga

### 6. Redstone Graph Engine
**Propósito:** Optimizar circuitos complejos.

- [x] Convertir redstone a DAG (Directed Acyclic Graph)
- [x] Cálculo lazy (solo cuando cambia un nodo)
- [x] Detección de loops infinitos
- [x] 80%+ reducción de CPU en circuitos grandes

### 7. Light Engine 2.0 with SIMD
- [x] Propagación de luz en bloques 8×8×8 vectorizados
- [x] Cache de secciones de luz
- [x] Lazy recalculation
- [x] Prioridad por cercanía a jugadores

---

## 🟡 Medium Priority Optimizations

### 8. Multithreaded Entity Ticking Groups
- [ ] Entidades pasivas → thread separado
- [ ] Colisiones → SIMD batch
- [ ] Redstone → async executor

### 9. Batch Chunk I/O
- [ ] `readChunksBulk(int x, int z, int radius)`
- [ ] `writeChunksBulk(Collection<Chunk>)`
- [ ] Prefetching en background basado en dirección del jugador
- [ ] NVMe-optimized sequential reads

---

# 🔒 Security & Safety

## 🔴 Essential Security

### 1. Rate Limiter Interno
**Propósito:** Prevenir packet spam exploits.

- [x] Rate limiting por jugador
- [x] Rate limiting global
- [x] Diferentes límites por packet type
- [x] Auto-ban temporal en abuse
- [x] Integración con TurboProxy L7

### 2. Chunk Integrity Verification
**Propósito:** Detectar y reparar chunks corruptos.

- [x] Checksum LZ4 por chunk
- [x] Hash incremental de región
- [x] Auto-recuperación desde backup
- [x] Logging de corruption events
- [x] `/turbo verify region <x> <z>`

---

## 🟠 Advanced Security

### 3. Anti-Corruption System
- [x] Watchdog de writes corruptos
- [x] Validación de NBT structure
- [x] Quarantine de chunks sospechosos
- [x] Rollback automático

---

# 🧰 Developer Tools

## 🔴 TurboMC Tools JAR (CLI)

### Conversión de Formatos
```bash
# MCA ↔ LRF
turbotools convert world/region --to-lrf
turbotools convert world/region --to-mca

# Compresión
turbotools compress world.lrf --zlib-to-lz4
turbotools compress world.lrf --algorithm zstd

# Inspección
turbotools inspect region r.0.0.lrf
turbotools inspect chunk r.0.0.lrf 10 15
```

### Benchmarking
```bash
turbotools benchmark --simd
turbotools benchmark --collision 10000
turbotools benchmark --io chunks=1000
turbotools benchmark --compression file.dat
```

**Componentes:**
- [x] CLI framework (picocli o similar)
- [x] Comandos `convert`, `compress`, `inspect`, `benchmark`
- [ ] Progress bars fancy
- [ ] Export a JSON para CI/CD

---

## 🟠 TurboMC Region Inspector

### Visual Inspector (GUI o TUI)
- [x] Viewer hexadecimal de regiones LRF
- [x] Tree view de chunk structure
- [x] Block palette visualizer
- [x] Compression ratio stats
- [x] Export a PNG (top-down view)

---

## 🟡 Config Tuning Automático

**Inspirado en Pufferfish:**
- [ ] Auto-ajuste de thread pools según CPU
- [ ] Dynamic view distance según TPS
- [ ] Entity batching adaptativo
- [ ] `/turbo autotune enable`

---

# 🔍 Debugging & Profiling

## 🟠 TurboProfiler (Built-in)

**Features:**
- [ ] Profiler nativo (mejor que Spark)
- [ ] `/tps ui` con gráficos en tiempo real
- [ ] Estadísticas por plugin
- [ ] Breakdown por categoría:
  - Entities
  - Chunks I/O
  - AI/Pathfinding
  - Collisions
  - Redstone
  - Network
- [ ] Export a flame graphs
- [ ] Web UI opcional (localhost:25566)

---

## 🟠 PacketInspector

**Monitoring de red:**
- [ ] Bytes por tick enviados
- [ ] Ratio de compresión por algoritmo
- [ ] Costo por packet type
- [ ] Top 10 packets más costosos
- [ ] `/turbo packets analyze`

---

## 🟡 WorldStressTest Command

**Testing de carga:**
```
/stress entities 20000 --type zombie
/stress pistons 500 --tick-rate 20
/stress explosions 100 --spread 50
/stress players 100 --ai
```

- [ ] Simulación de mobs
- [ ] Simulación de redstone
- [ ] Simulación de explosiones
- [ ] Bot players con AI básica

---

# 🔌 Plugin Compatibility

## 🔴 TurboAPI (Public API)

**Nuevas APIs expuestas para plugins:**

```java
// Compression API
TurboCompression.compress(data, Algorithm.LZ4);

// Region Format API
TurboRegion region = TurboRegion.load(x, z);
region.getChunk(cx, cz).setBlock(...);

// SIMD Utilities
TurboSIMD.dotProduct(vectorA, vectorB);

// Profiling Hooks
TurboProfiling.startSection("my-plugin-task");
// ... code ...
TurboProfiling.endSection();
```

**Componentes:**
- [ ] `turbo-api` módulo separado
- [ ] Javadocs completos
- [ ] Ejemplos en GitHub
- [ ] Maven/Gradle artifacts

---

## 🟠 Compatibility Modes

### Vanilla Behavior Mode vs Turbo Mode

**Switch dinámico:**
```yaml
# turbo.yml
mode: turbo  # or 'vanilla'
```

- [ ] Modo Vanilla: Comportamiento 100% Paper-compatible
- [ ] Modo Turbo: Todas las optimizaciones activas
- [ ] Hybrid mode: Turbo pero compatible con plugins legacy

---

## 🟡 Crash Recovery Mode

**Si el servidor crashea:**
1. Reinicia en safe mode (sin plugins)
2. Repara regiones corruptas
3. Reconstruye índices
4. Log detallado del crash
5. Rollback opcional

- [ ] `--safe-mode` flag
- [ ] Auto-repair de chunks
- [ ] Index rebuilder
- [ ] Crash analyzer

---

# 🤖 Experimental Features

## 🟢 GPU Particle Engine

**Server-side physics con GPU:**
- [ ] OpenCL/CUDA via JNI
- [ ] TNT masiva en GPU
- [ ] Water physics
- [ ] Falling blocks simulation
- [ ] **Muy experimental, solo si es viable**

---

## 🟢 GPU-based Light Engine

**Light recalculation en milisegundos:**
- [ ] Light propagation en GPU
- [ ] Return a CPU solo resultados finales
- [ ] Fallback a CPU si no hay GPU

---

## 🟢 Parallel Region Generation

**Generación del mundo multi-threaded:**
- [ ] 8+ threads generando regiones simultáneamente
- [ ] Mapas gigantes (10k×10k) en minutos
- [ ] Seed-based coordination
- [ ] Plugin hooks para custom generators

---

# 📊 Implementation Checklist

## Fase 1: Fundación (Q1 2025)
- [x] ViaVersion Integration ✅ v1.3.0
- [x] LZ4 Compression base ✅ v1.2.0
- [x] Zlib/LZ4 Dual-algorithm system ✅ v1.2.0
- [x] TOML Configuration ✅ v1.2.0
- [x] Chunk storage compression ✅ v1.2.0
- [x] LRF Format v1.0 (deferred to Fase 2) ✅ v1.5.0
- [x] MCA→LRF Converter (deferred to Fase 2) ✅ v1.3.0
- [ ] Basic TurboAPI (deferred to Fase 2)

## Fase 2: Diferenciación (Q2 2025)
- [ ] SIMD Collision Engine
- [ ] Network Thread Pool
- [ ] TurboTools CLI
- [ ] Redstone Graph Engine
- [ ] TurboProfiler

## Fase 3: Pulido (Q3 2025)
- [ ] Hybrid Cache System
- [ ] All converters completos
- [ ] Region Inspector
- [ ] PacketInspector
- [ ] Crash Recovery

## Fase 4: Innovación (Q4 2025)
- [ ] GPU Particle Engine (experimental)
- [ ] Parallel Generation
- [ ] Full TurboPlugin SDK
- [ ] Advanced ML features

---

# 🎓 Resources & References

- **ViaVersion Docs:** https://docs.viaversion.com
- **LZ4 Specs:** https://github.com/lz4/lz4
- **Paper API:** https://paper.readthedocs.io
- **SIMD in Java:** Project Panama (JEP 426)
- **Anvil Format:** https://minecraft.wiki/w/Anvil_file_format

---

**Última actualización:** 2025-12-14
**Versión del documento:** 1.5.0
