# TurboMC v2.0.0 — The Speed Update

## 🚀 Overview
TurboMC es un fork avanzado de PaperMC enfocado en **velocidad extrema**, **almacenamiento moderno** e **integridad de datos**, diseñado para servidores que no pueden permitirse ni un milisegundo de retraso.

## 🏗️ Optimized Voxel Format (OVF) [NUEVO v2.0]
**Estado:** Implementado & Validado
**Paquete:** `com.turbomc.voxel.ovf`

Reemplazo de alto rendimiento para el formato `.schem` de WorldEdit y FAWE.

- **RLE Compression:** Algoritmo Run-Length Encoding nativo que optimiza el almacenamiento de bloques repetidos.
- **Ultra-Fast Decompression:** Capaz de reconstruir grids de 16 millones de bloques en menos de 20ms.
- **Asynchronous Conversion:** Conversor asíncrono integrado para migrar archivos `.schem` masivos sin congelar el hilo principal.
- **Minimal Metadata:** Reducción de overhead de NBT legacy en favor de un formato binario puro.

## 💽 LRF v2: Predictive Streaming Engine [DETALLES v2.3]
**Estado:** Optimizado & Estabilizado
**Clases:** `MMapReadAheadEngine`, `IntentPredictor`

El motor de almacenamiento LRF ha evolucionado en la v2.3 a un sistema de **Streaming Predictivo Real**.

- **Intent Prediction Engine (v2.3):** A diferencia de modelos simples de velocidad, TurboMC ahora analiza el historial de movimiento (últimos 3 segundos) para deducir la "intención" del jugador.
- **Probability Tunnels:** Genera un túnel de carga probabilístico en lugar de un vector lineal, cubriendo cambios de dirección detectados en tiempo real.
- **Elytra & Trident Multipliers:** Multiplica dinámicamente el lookahead al detectar vuelos de alta velocidad, permitiendo una carga fluida de hasta **64 chunks** de distancia.
- **Windows File Lock Fix:** Implementación de desmapeo explícito de memoria (Unsafe Buffer Cleanup) que permite operar en sistemas Windows sin bloqueos de archivos `.lrf`.
- **Parallel Chunk Pipeline:** Optimización de hilos que permite la carga paralela masiva distribuida entre múltiples regiones de forma asíncrona.

## 💾 Advanced Converters & Integration...

### PaperMC Architecture Alignment
**Estado:** Implementado & Optimizado
**Paquete:** `com.turbomc.storage.optimization`

Eliminación de conflictos arquitectónicos con PaperMC mediante la consolidación de recursos.

- **Global Thread Pooling:** Se eliminó la "explosión de hilos" (thread explosion) por región. Ahora todo el servidor usa 4 pools globales (Load, Save, Comp, Decomp) escalables según los núcleos de la CPU.
- **Context Switching Reduction:** Reducción drástica del overhead de CPU al evitar la creación de cientos de hilos competidores.
- **Resource Protection:** Sistema inteligente de cierre de regiones que protege los recursos compartidos.

### Hopper "Smart Sleep"
**Estado:** Implementado & Validado
**Clase:** `HopperBlockEntity`

Optimización agresiva de Tolvas (Hoppers) para servidores con granjas masivas.

- **Adaptive Cooldown:** Las tolvas inactivas entran en un modo de "sueño" aumentando su cooldown exponencialmente (de 8 a 200 ticks) si no hay ítems que mover.
- **Instant Wake-up:** Despertar instantáneo al detectar cambios en el inventario propio o mediante eventos externos.
- **Paper Compatible:** Diseñado para trabajar sobre las optimizaciones nativas de Paper sin reemplazarlas.

---

### NBT Packed-Binary Format (Internal)
**Estado:** Implementado & Validado
**Paquete:** `com.turbomc.nbt`

Formato binario optimizado para almacenamiento interno de NBT.

> [!IMPORTANT]
> **Compatibilidad Garantizada:** Aislamiento total. 
> `NBT (Paper/Plugins)` ↔ `NBTConverter` ↔ `PackedBinaryNBT (Turbo Interno)`

- **Plugins:** Interactúan solo con NBT estándar (`CompoundTag`).
- **Turbo:** Usa `PackedBinary` solo internamente para I/O.
- **TNBT Transcoding (v2.0):** Capa de traducción automática en tiempo real que permite a los sistemas de Minecraft leer datos optimizados sin errores de compatibilidad (soluciona "Invalid tag id: 84").
- **Features:** Deduplicación de strings, compresión LZ4, Header Magic `TNBT`.

### Binary Config Cache
**Estado:** Activo (Auto-habilitado)
**Paquete:** `com.turbomc.config.cache`

Sistema de caché binaria para `paper-global.yml` y futuras configs.

- **Automático:** Se activa al iniciar el servidor.
- **Rendimiento:** Carga 50% más rápida (lectura binaria vs parseo YAML).
- **Auto-Update:** Hash SHA-256 detecta cambios en el YAML y regenera la caché.

---

### (1.8.0)
## 🧱 Linear Region Format (LRF)

### Core
- Formato nativo **LRF (Linear Region Format)** optimizado para SSD/NVMe
- Acceso secuencial sin padding
- Headers compactos con checksums y metadatos
- Escritura directa de chunks (sin MCA intermedio)

### Features
- Compresión LZ4 / ZSTD / Zlib
- Integridad: CRC32 / CRC32C / SHA-256
- Reparación automática de corrupción
- Conversión MCA ↔ LRF
- Conversión:
  - FULL_LRF (nativo)
  - ON_DEMAND
  - BACKGROUND
  - MANUAL

---

## 💽 Motor de I/O
- Memory-mapped I/O (mmap)
- Prefetching predictivo
- Batch loading & saving
- Eliminación de IO blocking (`channel.force`)
- Cache multinivel RAM + disco

---

## ⚡ Rendimiento
- SIMD Collision Engine (Vector API Java 21+)
- Carga paralela de chunks
- Priorización basada en jugadores
- Optimización dinámica de calidad
- Pools de threads dedicados para I/O

---


## 🧠 Gestión de Calidad
- Presets: LOW / MEDIUM / HIGH / ULTRA / DYNAMIC
- Ajuste automático según TPS y carga
- Entity culling
- Particle optimization

---

## 🔒 Seguridad & Red
- Handshake seguro
- Anti-flood
- Validación de hostname
- Soporte BungeeCord / Proxy
- Thread-safe networking

---

## 🛠 Comandos
- `/turbo storage stats`
- `/turbo storage convert`
- `/turbo storage validate`
- `/turbo storage flush`
- `/lrfrepair scan`
- `/lrfrepair repair`

---

## 📊 Monitoreo
- Métricas de almacenamiento
- Estadísticas de caché
- Métricas de integridad
- Logging avanzado

---

## 📦 Requisitos
- Java 21+
- PaperMC 1.21.10
- Compatible Bukkit / Spigot
