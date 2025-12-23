# 🚀 TurboMC — Version History

Fork avanzado de PaperMC con foco en **storage moderno**, **SIMD**, y **baja latencia**.

---

## 🚀 v2.0.0 — The Speed Update (Current)
- **Optimized Voxel Format (OVF)**: Nuevo formato para estructuras con carga <20ms para 16M bloques.
- **High-Speed Flight Ready**: Soporte oficial para `flyspeed 10` sin stuttering.
- **NVMe Optimization**: Desactivación de caché L1 (RAM) por default (+95% throughput en hardware moderno).
- **Zstd Padding Fix**: Corrección crítica en lectura de chunks alineados que eliminan errores de descompresión.
- **Aggressive Prefetching**: Predicción de movimiento extendida a 12 chunks (Prediction Scale 12x).
- **Configuración Automática**: Defaults ajustados para servers de alto rendimiento.

---

## 🟢 v1.8.0 — Stability & Reliability (Stable)
- **Zero-Failure Test Suite**: Estabilización completa del core.
- **LRF Engine Final al 100%**: Fixes críticos en append/read headers.
- **Race Condition Fixes**: Flush explícito en cargas altas.
- **Optimización de Memoria**: Corrección de fugas en Performance Tests.
- **Configuración Robusta**: Carga segura de `turbo.toml` vs `paper-global.yml`.
- Nombre de compilación oficial: `turbo-server`

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
