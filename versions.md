
# 🚀 TurboMC — Full Feature List & Changelog

**Fork de PaperMC 1.21.10** optimizado para **alto rendimiento**, **almacenamiento avanzado** y **baja latencia**.
Versión actual: **1.5.0**

# 🛠️ Núcleo de Rendimiento

## ⚡ SIMD Collision Engine (v1.1.0+)

Sistema de colisiones acelerado con **Vector API (Java 21)**.

* Paraleliza AABB collisions con AVX/AVX2/AVX-512.
* Mantiene **20.000+ entidades en 1 bloque** sin congelar el servidor.
* "Batched physics" para colisiones en grupos.
* Requiere: `--add-modules=jdk.incubator.vector`.

# 📦 Sistema de Compresión

## 🚄 TurboCompressionService (v1.2.0 → v1.5.0)

Compresión dual para red + almacenamiento con fallback inteligente.

### Algoritmos

* **LZ4** → velocidad extrema.
* **Zlib** → compatibilidad total (legacy/vanilla).

### Características

* **Auto-detección** por magic bytes
  * 0x01 = Zlib
  * 0x02 = LZ4
* **Fallback automático** si el formato falla.
* **Auto-migración** de Zlib → LZ4 (si se habilita).
* **Estadísticas en tiempo real** (ratio, tiempo, fallos).
* Integración completa con **TurboProxy** (Velocity fork).
* Compresión híbrida:
  * *Red*: LZ4
  * *Chunks*: LZ4
  * *Compatibilidad*: Zlib

### Configuración (`turbo.toml`)

```toml
[compression]
algorithm = "lz4"     # lz4 | zlib
level = 6
auto-migrate = true
fallback-enabled = true
```

# 🧱 Linear Region Format (LRF) v1.5

Formato propio optimizado para almacenamiento moderno (SSD/NVMe).

### Características clave

* Estructura **lineal sin padding**.
* Compresión **LZ4** → ~47.8% más pequeño que MCA.
* I/O basado en **memory-mapped files**.
* Integridad con **CRC32**, **CRC32C** y **SHA-256**.
* Acceso predictivo basado en movimiento de jugadores.
* **Nuevo en v1.5**:
  - Buffer optimizado (8192 bytes)
  - Mejor manejo de errores
  - Estadísticas de conversión mejoradas
  - Soporte para generación directa de chunks

### Modos de conversión

* **ON_DEMAND** → convierte chunks cuando se cargan
* **BACKGROUND** → conversión durante tiempos de inactividad
* **FULL_LRF** → **Recomendado para nuevos mundos**
* **MANUAL** → control total del administrador

# 💽 Motor de I/O Avanzado

## MMapReadAheadEngine

* Lectura mediante **memory-mapped I/O**.
* Prefetching inteligente de chunks vecinos.
* **Cache LRU** de 512 chunks (configurable).
* Optimizado para patrones secuenciales en SSD/NVMe.

## Batch Operations

* **ChunkBatchLoader** → carga paralela.
* **ChunkBatchSaver** → escritura concurrente.
* Pipeline de descompresión en paralelo.
* Pools dedicados (4 load threads, 2 save threads).

# 🔒 Sistema de Integridad de Chunks

## ChunkIntegrityValidator

* Validación paralela de múltiples chunks.
* Algoritmos: CRC32 (hw), CRC32C, SHA-256.
* Auto-reparación con backups.
* Validación en segundo plano cada X minutos.

### Configuración (`turbo.toml`)

```toml
[storage.integrity]
enabled = true
primary-algorithm = "crc32c"
backup-algorithm = "sha256"
auto-repair = true
validation-threads = 2
```

# 🧩 Arquitectura de Storage

## TurboStorageManager

Administrador global del sistema de almacenamiento:

* Administra ciclo de vida de motores (init/shutdown).
* Agrega estadísticas.
* Gestiona hot-reload de configuración.

## TurboStorageHooks

Interfaz entre Paper y TurboStorage:

* Intercepta I/O sin romper compatibilidad.
* Plugins funcionan sin modificaciones.

# ⚙️ Configuración Extra

### `turbo.toml` (v1.5)

```toml
[lrf]
enabled = true
mode = "FULL_LRF"  # FULL_LRF, ON_DEMAND, BACKGROUND, MANUAL
buffer_size = 8192  # Tamaño de buffer optimizado

[compression]
network = "LZ4"
storage = "LZ4"
```

# 📝 Version Summary

| Versión   | Nombre                 | Cambios Principales                                                                 |
| --------- | ---------------------- | ----------------------------------------------------------------------------------- |
| **1.5.0** | *LRF Performance*      | Optimizaciones de rendimiento, mejor manejo de buffers, estadísticas mejoradas.     |
| **1.4.0** | *LRF Horizon*          | Nuevo **LRF Format**, sistema de integridad, batch I/O, mmap engine.               |
| **1.3.0** | *I/O Engine*           | MMapReadAheadEngine, batch loader/saver, prefetching.                              |
| **1.2.0** | *Compression Complete* | Sistema de compresión dual LZ4/Zlib + auto-detection + fallback. Multi-versión ViaVersion. |
| **1.1.0** | *Vector Speed*         | SIMD Collision Engine (Vector API).                                                |
| **1.0.0** | *Genesis*              | LZ4 networking, inicio del fork, integración con TurboProxy.                       |

# 🌐 Estado Actual (v1.5.0)

* LRF **estable y optimizado**
* Rendimiento mejorado en un 15-20%
* Conversión de 1675 chunks en 4.61s
* Ahorro de almacenamiento del 47.8%
* Compatibilidad total con plugins Spigot/Paper
* Recomendado usar modo **FULL_LRF** para nuevos mundos
