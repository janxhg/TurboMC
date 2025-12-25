# TurboMC 🚀

**Versión 2.2.0 (The Command & Stress Update)**  
Fork avanzado de **PaperMC 1.21.10** optimizado para **velocidad extrema**, **almacenamiento moderno** y **servidores de última generación**.

[Changelog](./versions.md) · [Features](./TURBOMC_FEATURES_COMPLETE.md) · [Benchmarks](./BENCHMARKS.md)

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
- **SIMD Collision Engine**: Vector API para físicas paralelas.
- **Extreme Pre-fetching Engine**: Lookahead de hasta 800 bloques en la dirección de viaje.
- **Scalable I/O Pipeline**: Arquitectura de hilos global con hasta 32 workers de descompresión.
- **Batch I/O v2**: Procesamiento asíncrono optimizado para Folia/Moonrise.

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

