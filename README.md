# TurboMC 🚀

**Versión 1.5.0** | [Changelog](./versions.md) | [Documentación](TURBO_FEATURES.md) | [Roadmap](./ROADMAP.md)

TurboMC es un fork de alto rendimiento de PaperMC optimizado para servidores con alta densidad de entidades, jugadores y configuraciones proxy.

## 🚀 Características Principales

### 🌟 Linear Region Format (LRF) v1.5
- **Almacenamiento optimizado** para SSD/NVMe
- **Conversión MCA a LRF** con compresión LZ4
- **Modo Full LRF** para generación directa de chunks
- **Sistema de comandos** con `/turbo storage convert`
- **Estadísticas detalladas** de conversión

### ⚡ Rendimiento Mejorado
- **Conversión rápida**: 1675 chunks en 4.61 segundos
- **Compresión eficiente**: Hasta 47.8% de ahorro de espacio
- **Gestión de memoria** optimizada
- **Manejo mejorado** de errores y logging

### 🔄 Modos de Operación
- **Modo Manual**: Conversión de archivos MCA existentes
- **Full LRF**: Generación nativa de chunks (recomendado para nuevos mundos)
  - Sin conversión MCA/LRF
  - Carga más rápida de chunks
  - Sin sobrecarga de conversión

## 📋 Requisitos
- **Java 21+** (requerido)
- **Flag de inicio obligatorio**:
  ```bash
  --add-modules=jdk.incubator.vector