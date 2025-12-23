# TurboMC v1.8.0 — Advanced Storage & Performance Engine

## 🚀 Overview
TurboMC es un fork avanzado de PaperMC enfocado en **alto rendimiento**, **almacenamiento moderno** y **estabilidad extrema**, diseñado para servidores con alta carga de chunks, entidades y tráfico de red.

---

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
