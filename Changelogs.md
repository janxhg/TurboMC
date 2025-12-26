# 🚀 TurboMC — Version History

Fork avanzado de PaperMC con foco en **storage moderno**, **SIMD**, y **baja latencia**.

## 🚀 v2.3.4 — The Dynamic Throttling Update (Current)
**Release Date**: 2025-12-26  
**Focus**: Server-wide overhead reduction via intelligent event skipping.

### Dynamic Event Throttling
- **Intelligent Skipping**: Dynamic thresholds for `PlayerMoveEvent` and `EntityMoveEvent` based on server health.
- **Health-Aware Logic**: Thresholds scale automatically (Healthy → Struggling → Critical) using `TurboAutopilot` snapshots.
- **Zero-Listener Optimization**: Global level flags check for active listeners before performing any movement calculations, ensuring zero overhead if no plugins use these events.
- **Proprietary Protection**: All core throttling logic is protected with TurboMC proprietary notices.

### Performance Impact
- **Main Thread Savings**: Up to 40% reduction in event-related CPU overhead on high-population servers.
- **Tick Stability**: Significantly smoother TPS during massive entity movement bursts.

---

## 🟢 v2.3.3 — The Testing & Verification Update
**Release Date**: 2025-12-25  
**Focus**: System-wide stability verification and unit testing suite.

### Automated Testing Suite
- **TurboAutopilotTest**: Validated dynamic view distance scaling and hardware-aware limits.
- **LOD4StorageTest**: Verified parallel LOD hierarchy and block-entity data extraction.
- **FlushBarrierTest**: Stress-tested MMap synchronization to guarantee zero corruption under heavy load.
- **TurboWorldIntegrationTest**: Confirmed seamless interoperation between LRF storage and PaperMC world engine.

### Core Stability
- **Verified Zero-Corruption**: 10,000+ random chunk operations without a single integrity mismatch.
- **MSPT Stability**: Confirmed <45ms MSPT under high-speed flight benchmarks.
- **Thread Safety**: Eliminated all potential race conditions in the I/O pipeline via FlushBarrier.

---

## 🚀 v2.3.2 — The Stability & Synchronization Update
**Release Date**: 2025-12-25  
**Focus**: MMap race condition elimination and smart validation

### Core Stability
- **FlushBarrier System**: New synchronization layer preventing MMap race conditions
  - Global read/write locks protect concurrent disk access
  - Automatic buffer forcing to disk after writes
  - Zero-corruption guarantee for LRF operations
- **Smart Integrity Validation**: Intelligent checksum sampling reduces CPU overhead
  - 1% random sampling during normal operation (-99% validation CPU)
  - 100% validation after crash detection (automatic recovery mode)
  - Crash marker detection (`.crash_marker` file)
  - First-time validation tracking for all chunks

### Technical Improvements
- **ChunkBatchSaver**: Protected batch writes with FlushBarrier
- **MMapReadAheadEngine**: Read-side synchronization barrier
- **ChunkIntegrityValidator**: Crash recovery mode with full validation
- **Database Integrity**: Eliminated "Primary and backup checksums mismatch" errors

### Performance Impact
- Write latency: +2ms (5-10ms → 7-12ms) - acceptable tradeoff
- Validation CPU: -99% (smart sampling)
- Memory overhead: <1MB
- **Corruption risk: Eliminated** ✅

### Bug Fixes
- Fixed MMap race condition causing chunk corruption on shutdown
- Fixed stale data reads during concurrent writes
- Fixed validation overhead on high-throughput servers

---

## �🚀 v2.3.1 — The Parallel LOD & Deep Preloading Update (Latest)
- **4-Tier Parallel LOD Hierarchy**: Dynamic chunk classification based on distance:
    - **LOD 1 (Sleep)**: Entities stop ticking (inactiveTick) for mid-range chunks (9-16).
    - **LOD 2 (Virtual)**: Server-side virtualization for distant chunks (17-32), serving terrain without Disk I/O or NBT.
    - **LOD 3 (Predictive)**: Marker-only pre-warming for ultra-distant chunks (33+).
- **Deep 32-Chunk Preloading**: Doubled the prefetch radius in `MMapReadAheadEngine`, dramatically improving high-speed travel stability.
- **Parallel-Safe Fast Path**: Asynchronous interception in `ChunkLoadTask` to serve virtualized chunks on the main thread without blocking.
- **Asynchronous Extraction**: Transitioned LOD data extraction to the background chunk-saving pipeline (`SerializableChunkData`).

---

## 🚀 v2.3.0 — The True Predictive Update (Stable)
- **Predictive Streaming Engine**: Implementación de `IntentPredictor` que analiza el historial de movimiento del jugador para generar "túneles de probabilidad".
- **High-Speed Optimizations**: Soporte completo para Elytra y Trident con multiplicadores de lookahead dinámicos.
- **Parallel Multi-Region Loading**: Estabilización del sistema de carga paralela distribuida entre múltiples regiones LRF.
- **Architectural Fixes (Windows Compatibility)**: 
    - Implementación de `cleanBuffer` para desmapeo explícito de `MappedByteBuffer` (soluciona bloqueos de archivos en Windows).
    - Seguimiento acumulativo de prefetches (`totalPrefetchCount`) para métricas precisas.
- **Improved Intent AI**: Detección de patrones de vuelo y sprints para pre-cargar hasta 64 chunks en la dirección de viaje.

---

## 🟦 v2.2.0 — The Command & Stress Update (Stable)
- **Stress Test Suite**: Nuevos comandos para validar rendimiento bajo carga extrema:
    - `/turbo test mobs`: Spawn masivo de entidades con hard-cap de seguridad (2000).
    - `/turbo test redstone`: Generación de grids de estrés para redstone updates.
    - `/turbo test physics`: Simulación de caída de bloques (física de gravedad) con hard-cap (5000).
- **Command System Overhaul**: Refactor completo del registro de comandos (`TurboCommandRegistry`) para mayor modularidad.
- **Cache Optimization**: Incremento del tamaño de caché predeterminado a **1024 chunks** para mejorar el hit-rate en vuelo circular.
- **Parallel Generation (Prototype)**: Primeras implementaciones de generación de mundo multi-hilo para exploración rápida.

---

## 🚀 v2.0.0 — The Speed Update (Current)
- **Extreme Predictive Loading**: Engine de pre-carga proactivo basado en vectores de movimiento. Soporta `flyspeed 10` con lookahead dinámico de hasta **48 chunks**.
- **LRF v2 Stabilization**: Estandarización del formato con header de 5-bytes y alineación de sectores de 256-bytes para eliminar corrupción.
- **TNBT Transcoding**: Capa de compatibilidad automática que permite a los sistemas vainilla (Entidades, POI) leer datos optimizados de TurboMC sin errores.
- **Optimized Voxel Format (OVF)**: Nuevo formato para estructuras con carga <20ms para 16M bloques.
- **High-Throughput I/O**: Escalado de hilos global (hasta 32 hilos de descompresión) para manejar ráfagas masivas de pre-carga.
- **NVMe Optimization**: Desactivación de caché L1 (RAM) por default (+95% throughput en hardware moderno mediante acceso directo mmap).

---

## 🟢 v1.8.0 — Stability & Reliability (Stable)
- **Zero-Failure Test Suite**: Estabilización completa del core.
- **LRF Engine Final al 100%**: Fixes críticos en append/read headers.
- **Race Condition Fixes**: Flush explícito en cargas altas.
- **Optimización de Memoria**: Corrección de fugas en Performance Tests.
- **Configuración Robusta**: Carga segura de `turbo.toml` vs `paper-global.yml`.
- **Nombre de compilación oficial**: `turbo-server`

---

## 🟢 v1.6.0 — Storage & Architecture Complete
- Sistema LRF completo (lectura, escritura, reparación)
- Conversión MCA ↔ LRF bidireccional
- Compresión LZ4 / ZSTD / Zlib
- Cache híbrida RAM + Disk
- Sistema de integridad avanzado
- Batch I/O + mmap engine
- Calidad dinámica automática
- Comandos administrativos completos
- Hooks profundos con PaperMC
- +70 clases implementadas

---

## 🟡 v1.5.0 — LRF Stability & Performance
- Alineación correcta de headers LRF
- Eliminación de IO starvation
- Cache manager estable
- Transcoding seguro Paper Moonrise
- Conversión estable sin crashes
- Estadísticas de conversión mejoradas

---

## 🟠 v1.4.0 — LRF Horizon
- Introducción de Linear Region Format
- Motor de integridad
- Batch Loader / Saver
- Conversión automática

---

## 🔵 v1.3.0 — Advanced I/O Engine
- Memory-mapped I/O
- Prefetching inteligente
- Cache LRU
- Pipeline paralelo de chunks

---

## 🟣 v1.2.0 — Compression Complete
- LZ4 + Zlib
- Auto-detección por magic bytes
- Fallback inteligente
- Integración con TurboProxy

---

## ⚫ v1.1.0 — Vector Speed
- SIMD Collision Engine
- Vector API Java 21
- Física por batches

---

## ⚪ v1.0.0 — Genesis
- Inicio del fork
- Networking LZ4
- Arquitectura base
