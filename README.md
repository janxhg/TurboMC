# TurboMC 🚀

**Versión 2.3.2 (The Stability Update)**  
Fork avanzado de **PaperMC 1.21.10** optimizado para **velocidad extrema**, **almacenamiento moderno** y **servidores de última generación**.

[Changelog](./Changelogs.md) · [Features](./TURBOMC_FEATURES_COMPLETE.md) · [Benchmarks](./BENCHMARKS.md)

---

## 🔥 ¿Qué es TurboMC?

TurboMC redefine el rendimiento en Minecraft. Reemplaza el almacenamiento MCA por **LRF (Linear Region Format)**, incorpora **OVF (Optimized Voxel Format)**, **SIMD**, **I/O Asíncrono de alto nivel**, y una IA de prefeching predictivo que elimina el stuttering incluso a velocidades de vuelo extremas.

---

## 🏗️ Optimized Voxel Format (OVF) [NUEVO v2.0]
- **Velocidad Absurda**: Carga estructuras de 16 millones de bloques en **<20ms**.
- **Compresión Inteligente**: RLE (Run-Length Encoding) nativo que reduce tamaños de schematics masivos a bytes.
- **Zero Lag**: Diseñado para integrarse con WorldEdit/FAWE sin congelar el servidor.

---

## 🧱 Almacenamiento LRF v2 (Ultra-Estable)
- **Motor de I/O Predictivo Proactivo**: Analiza vectores de movimiento en cada acceso para pre-cargar chunks mucho antes de que el jugador los necesite.
- **Lookahead Dinámico de 48 Chunks**: Escalado automático según la velocidad del jugador.
- **TNBT Transcoding**: Integración perfecta con entidades y POI de Minecraft vainilla.
- **Optimizado para NVMe**: Acceso directo mediante MMap sin cuellos de botella de caché de software.
- **Ahorro de Espacio**: Compresión LZ4 ultra-rápida por defecto.

---
 
 ## 🧪 Suite de Stress Testing (v2.2.0)
 - **Mobs Stress**: Generación masiva de entidades con safety-cap (2000) para validar tick engine.
 - **Redstone Grid**: Generador de patrones complejos (48x48 chunks) para estrés de redstone.
 - **Physics Sim**: Simulación de gravedad (arena/grava) con hard-cap (5000) para validar SIMD physics.
 - **Flight Benchmark**: Tests automatizados de carga de chunks a alta velocidad.
 
 ---

## ⚡ Rendimiento de Próxima Generación
- **FlushBarrier Synchronization (v2.3.2)**: Eliminación total del race condition de MMap para cero corrupción.
- **Smart Validation (v2.3.2)**: Sampling inteligente del 1% reduce overhead de CPU en 99%.
- **4-Tier Parallel LOD System (v2.3.1)**: Jerarquía dinámica que permite pre-cargar hasta **64 chunks** con costo CPU/IO cercano a cero para el 75% del radio.
- **SIMD Collision Engine**: Vector API para físicas paralelas.
- **Deep Prefetching Engine**: Lookahead coordinado de hasta 512 bloques.
- **Parallel-Safe Fast Path**: Carga de chunks asíncrona optimizada para Moonrise.

---

## 🧠 Gestión de Calidad Dinámica
- Ajuste en tiempo real basado en MSPT.
- Entity culling inteligente.
- Optimización de partículas y Redstone.
- Presets dinámicos (Extreme, Performance, Balanced).

---

## 🛠 Requisitos
- Java 21+
- Flag obligatorio para SIMD:
```bash
 --enable-preview  and  --add-modules=jdk.incubator.vector
```

