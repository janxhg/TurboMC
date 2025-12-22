# TurboMC 🚀

**Versión 1.6.0**  
Fork avanzado de **PaperMC 1.21.10** optimizado para **alto rendimiento**, **almacenamiento moderno** y **servidores exigentes**.

[Changelog](./versions.md) · [Features](./TURBO_FEATURES.md)

---

## 🔥 ¿Qué es TurboMC?

TurboMC reemplaza el almacenamiento tradicional MCA por **LRF (Linear Region Format)**, incorpora **SIMD**, **I/O moderno**, **cache inteligente** y **gestión dinámica de calidad**, manteniendo compatibilidad total con plugins.

---

## 🧱 Almacenamiento LRF
- Generación nativa de chunks (FULL_LRF)
- Conversión MCA ↔ LRF
- Compresión LZ4 / ZSTD
- Reparación automática de corrupción
- Ahorro de hasta ~50% de espacio
- Ideal para SSD / NVMe

---

## ⚡ Rendimiento Extremo
- SIMD Collision Engine (Vector API)
- Carga paralela de chunks
- Batch I/O + mmap
- Zero IO lag
- Cache híbrida RAM + disco

---

## 🧠 Calidad Dinámica
- Ajuste automático según carga
- Entity culling
- Optimización de partículas
- Presets configurables

---

## 🔒 Seguridad & Red
- Anti-flood
- Handshake seguro
- Soporte Proxy / BungeeCord
- Networking thread-safe

---

## 🛠 Requisitos
- Java 21+
- Flag obligatorio:
```bash
--add-modules=jdk.incubator.vector
