# TurboMC 🚀

**Versión 2.0.0 (The Speed Update)**  
Fork avanzado de **PaperMC 1.21.10** optimizado para **velocidad extrema**, **almacenamiento moderno** y **servidores de última generación**.

[Changelog](./versions.md) · [Features](./TURBOMC_FEATURES_COMPLETE.md) · [Benchmarks](./CACHE_BENCHMARKS.md)

---

## 🔥 ¿Qué es TurboMC?

TurboMC redefine el rendimiento en Minecraft. Reemplaza el almacenamiento MCA por **LRF (Linear Region Format)**, incorpora **OVF (Optimized Voxel Format)**, **SIMD**, **I/O Asíncrono de alto nivel**, y una IA de prefeching predictivo que elimina el stuttering incluso a velocidades de vuelo extremas.

---

## 🏗️ Optimized Voxel Format (OVF) [NUEVO v2.0]
- **Velocidad Absurda**: Carga estructuras de 16 millones de bloques en **<20ms**.
- **Compresión Inteligente**: RLE (Run-Length Encoding) nativo que reduce tamaños de schematics masivos a bytes.
- **Zero Lag**: Diseñado para integrarse con WorldEdit/FAWE sin congelar el servidor.

---

## 🧱 Almacenamiento LRF v2
- **Motor de I/O Predictivo**: Analiza vectores de movimiento para pre-cargar chunks.
- **Integridad Total**: Fixes de descompresión Zstd que garantizan cero corrupción.
- **Optimizado para NVMe**: Bypass de cache L1 para maximizar el throughput de hardware moderno.
- **Ahorro de Espacio**: Compresión Zstd/LZ4 adaptativa.

---

## ⚡ Rendimiento de Próxima Generación
- **SIMD Collision Engine**: Vector API para físicas sin lag.
- **MMap Read-Ahead Engine**: Lectura directa a memoria del SO.
- **Prediction Scale 12x**: Prefetching agresivo para flyspeed 10+.
- **Batch I/O v2**: Procesamiento en ráfagas de 32 chunks.

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
--add-modules=jdk.incubator.vector
```

