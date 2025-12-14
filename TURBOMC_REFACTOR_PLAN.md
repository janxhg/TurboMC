# TurboMC Refactor & Fix Plan

## 1. LRF Storage System

### 1.1. Conversion Modes (ON_DEMAND, BACKGROUND, FULL_LRF, MANUAL)

- **Problemas originales**
  - `FULL_LRF` era permisivo: permitía que quedaran `.mca` en `world/region` tras la conversión masiva.
  - `ON_DEMAND` ejecutaba una conversión masiva de todo el directorio `region/`, contradiciendo la semántica "on-demand" documentada.
  - `BACKGROUND` delega en `BackgroundConversionScheduler`, pero su integración con el ciclo de ticks/idle del servidor no estaba claramente garantizada.
  - Faltaba una implementación real de conversión por-chunk (on-demand) en los hooks de almacenamiento.

- **Estado actual (cambios ya aplicados)**
  - `TurboStorageMigrator.verifyConversion` ahora:
    - Cuenta correctamente `.lrf` y `.mca` usando dos listados de directorio separados.
    - Considera **error** que queden `.mca` tras `FULL_LRF` y lanza `IOException`.
  - `TurboStorageMigrator.migrateOnDemand` ya **no** ejecuta una conversión masiva del directorio `region/`.
    - Solo registra en logs que ON_DEMAND está activo y que la conversión debe hacerse de forma lazy en runtime.
  - `TurboRegionFileStorage.read(ChunkPos)` implementa conversión **on-demand por chunk** cuando:
    - `storage.format = "lrf"` y `conversion-mode = "on-demand"`.
    - Existe `r.X.Z.mca` y aún no existe `r.X.Z.lrf`.
    - El primer acceso lee el chunk desde MCA y lo escribe en el `.lrf` correspondiente; accesos posteriores usan siempre LRF.
  - `TurboRegionFileStorage` ya **no usa reflexión frágil** sobre `RegionFile.regionPath`; calcula las rutas `r.X.Z.mca`/`r.X.Z.lrf` directamente a partir de `ChunkPos` y del `regionFolder`.
  - `BackgroundConversionScheduler.convertRegionAsync(Path mcaFile)` convierte ahora **un archivo `.mca` concreto** a su `.lrf` correspondiente usando `MCAToLRFConverter`, en lugar de reprocesar todo el directorio.

- **Mejoras aplicadas (Fase 2)**
  - `TurboRegionFileStorage.writeToLRF` ahora:
    - Tras escribir un chunk en LRF, si `storage.backup-original-mca = false`:
      - Calcula `r.X.Z.mca` de esa región.
      - Si existe, lo borra con `Files.deleteIfExists(...)` (respeta backups si están habilitados).
      - Loguea la acción si `storage.verbose = true`.
    - Esto hace que el directorio `region/` **converja a solo `.lrf`** a medida que se escriben chunks.
  - `BackgroundConversionScheduler` mejorado:
    - `isServerIdle()` ahora verifica:
      - CPU usage (umbral configurable, por defecto 30%).
      - Tiempo mínimo de inactividad (configurable, por defecto 30 segundos).
    - `getIdleTime()` implementa:
      - Chequeo de jugadores conectados (`Bukkit.getServer().getOnlinePlayers()`).
      - Chequeo de TPS (`Bukkit.getServer().getTicksPerSecond()`).
      - Si hay jugadores o TPS < 18, considera servidor no idle.
    - `convertRegionAsync` ahora borra el `.mca` original tras conversión exitosa (respetando `backup-original-mca`).

- **Limitaciones conocidas**
  - FULL_LRF:
    - Es **estricto** para mundos existentes: si hay `.mca` en `region/` al arrancar, los convierte y falla si queda alguno.
    - En mundos nuevos, al momento de la migración todavía no existen `.mca`, así que la conversión es trivial (0 archivos). Luego Paper crea `.mca` nuevos, pero estos se borran a medida que se escriben en LRF.
  - BACKGROUND:
    - Heurística de idle mejorada pero aún simplificada; puede competir con I/O si el servidor está muy ocupado.
  - Visibilidad en disco:
    - Con `backup-original-mca = false`, el directorio `region/` converge a solo `.lrf` a medida que se usan chunks.
    - Con `backup-original-mca = true`, los `.mca` se preservan como backups.

- **Pendiente / Próximos pasos**
  1. Evaluar un modo explícito de "pure LRF" (p.ej. `conversion-mode = "pure-lrf"`):
     - Contrato fuerte: ningún `.mca` operativo en `region/`; solo `.lrf` y, opcionalmente, backups.
     - Requiere integración más profunda con el ciclo de vida de `RegionFileStorage` para evitar la creación de `.mca` físicos.
  2. Optimizar BACKGROUND:
     - Considerar métricas adicionales (colas de I/O, memoria disponible) para idle detection.
     - Implementar parada limpia del scheduler en shutdown del servidor.
  3. Documentar política para mundos nuevos:
     - FULL_LRF en mundos nuevos: crear directamente `.lrf` sin pasar por `.mca`.
     - Compatibilidad y rollback con `LRFToMCAConverter`.

---

## 2. Runtime Storage & Caching

### 2.1. TurboStorageManager & HybridChunkCache

- **Problemas actuales**
  - Múltiples capas de almacenamiento/cache:
    - `TurboStorageManager` (batch loader/saver, mmap, integridad).
    - `HybridChunkCache` (L1 RAM, L2 mmap, L3 disco) con su propio uso de `MMapReadAheadEngine`.
  - Riesgo de solapamiento entre cachés y rutas de I/O.
  - Falta de una historia única y clara de "todos los accesos de chunk pasan por X".

- **Estado actual (Fase 2)**
  - `HybridChunkCache` marcado como **EXPERIMENTAL**:
    - No está integrado en la ruta principal de I/O.
    - Documentación y logs indican claramente que es una base para futuras mejoras.
    - No se activa automáticamente; requeriría integración explícita si se desea usar.
  - `TurboStorageManager` sigue siendo la fachada principal de I/O.
  - `ChunkBatchLoader` / `ChunkBatchSaver` siguen siendo la ruta de batch operations.
  - Camino de I/O real: `TurboIOWorker` → `TurboRegionFileStorage` → `TurboStorageManager` (para LRF).

- **Decisión arquitectónica**
  - Se ha decidido **no reescribir el stack de storage/caching** en esta fase.
  - El camino de I/O actual es coherente y funcional.
  - `HybridChunkCache` queda como base para futuras optimizaciones avanzadas (L1/L2/L3 caching).
  - Prioridad: estabilidad y corrección sobre features de marketing.
     - Unificar la política de caching (evitar múltiples caches superpuestas).
  3. Añadir tests de integración de carga/guardado de chunks LRF vs MCA.

---

## 3. Chunk Loading Optimizer

### 3.1. TurboChunkLoadingOptimizer (Completamente Opcional)

- **Problemas originales**
  - Tenía `Thread.sleep` y latencias falsas que degradaban rendimiento.
  - No estaba realmente integrado en el pipeline de carga de chunks de Paper.
  - Muchos métodos eran "cosmética" más que optimizaciones reales.

- **Estado actual (Fase 2)**
  - Removidos todos los `Thread.sleep` de métodos de carga.
  - Métodos ahora devuelven resultados triviales rápidamente si el optimizer está deshabilitado.
  - Nuevo flag de config: `chunk.optimizer.enabled` (por defecto **false**).
  - Si no está habilitado:
    - No ejecuta ninguna lógica extra.
    - No crea hilos de monitorización.
    - No interfiere con el pipeline normal de chunks.
  - Si está habilitado:
    - Activa preloading, parallel generation, caching, priority loading según config.
    - Mantiene estadísticas de rendimiento.

- **Limitaciones conocidas**
  - Incluso habilitado, la integración con Paper's `ChunkHolderManager` es superficial.
  - Las estrategias de carga (CONSERVATIVE, BALANCED, AGGRESSIVE, EXTREME) son más marcos que implementaciones reales.
  - Recomendación: dejar deshabilitado en producción a menos que se verifique impacto positivo.

- **Pendiente / Próximos pasos**
  1. Si se desea activar en el futuro:
     - Integración profunda con `ChunkHolderManager` y prioridades reales.
     - Pruebas exhaustivas de impacto en TPS y memoria.
  2. Alternativa: mantenerlo como feature experimental/opcional indefinidamente.

### 3.1. TurboChunkLoadingOptimizer

- **Problemas actuales (antes de cambios)**
  - El optimizador simulaba tiempos de carga con `Thread.sleep(…)` en:
    - `loadChunkVanilla`
    - `loadChunkHighPriority`
    - `loadChunkParallel`
  - Esto introducía **latencia artificial** sin beneficios reales y podía degradar el rendimiento.
  - El sistema actuaba más como un stub/demo que como un optimizador real de Paper.

- **Estado actual (cambios ya aplicados)**
  - Eliminados todos los `Thread.sleep(…)` de los métodos de carga.
  - La API y comentarios se mantienen, pero ahora los métodos son stubs ligeros que:
    - Devuelven `ChunkLoadResult` exitoso sin introducir delays artificiales.
    - Dejan abierta la puerta a integrar la carga real de Paper en el futuro.

- **Pendiente / Próximos pasos**
  1. Decidir política de uso:
     - O bien integrarlo de verdad con el sistema de tickets/carga de chunks de Paper.
     - O bien mantenerlo como un wrapper de métricas opcional/desactivado por defecto.
  2. Si se opta por integración real:
     - Usar APIs de Paper/Spigot para cargar/generar chunks en lugar de futures con stubs.
     - Sincronizar con el `ChunkMap`/`ServerLevel` de forma segura.
  3. Exponer una config en `turbo.toml` para habilitar/deshabilitar este optimizador.

---

## 4. Hooks de Almacenamiento (TurboStorageHooks)

- **Situación actual**
  - `TurboStorageHooks` proporciona:
    - `getTurboStorage(RegionFileStorage)` y `getTurboWorker(IOWorker)` para envolver vanilla.
    - Métodos `readChunkEnhanced` / `writeChunkEnhanced` que llaman a `TurboRegionFileStorage` si está disponible.
    - Detección de si TurboMC está activado a través de `TurboConfig` y `ConversionMode`.
  - `hookRegionFileStorage` y `hookIOWorker` actualmente solo loggean; la integración real está delegada a modificaciones directas en el código de Paper/IOWorker.

- **Pendiente / Próximos pasos**
  1. Añadir lógica de conversión on-demand dentro de estos hooks:
     - En lectura: si el chunk proviene de `.mca` y el formato objetivo es LRF en modo ON_DEMAND:
       - Convertir ese chunk a LRF y escribirlo en la región LRF.
     - En escritura: asegurarse de que los nuevos chunks se escriben en el formato elegido (MCA o LRF) según `TurboConfig`.
  2. Reforzar `isTurboEnabled` para modos específicos:
     - En `FULL_LRF`, considerar bloquear rutas que intenten operar sobre `.mca` salvo en rollback explícito.
  3. Añadir métricas en los hooks para contar conversiones on-demand por mundo.

---

## 5. Comportamiento por Mundo / Ciclo de Vida

- **Objetivos de diseño**
  - Distinguir claramente entre:
    - Mundos nuevos en formato LRF (FULL_LRF)
    - Mundos migrados desde MCA
  - Evitar estados mixtos sin control.

- **Pendiente / Próximos pasos**
  1. Definir y documentar:
     - Cómo se crea un mundo nuevo cuando `storage.format = "lrf"`.
     - Qué ocurre si se cambia de `mca` a `lrf` con mundos ya existentes.
  2. Conectar `TurboLRFBootstrap.performFullLRFMigration` con la política de aceptación de conexiones:
     - Asegurar que el servidor no acepta jugadores hasta que la migración FULL_LRF haya terminado sin errores.
  3. Añadir logs claros por mundo:
     - Estado final de formato (`MCA`, `LRF` o mixto con error).

---

## 6. Plan de Aplicación de Cambios

### 6.1. Cambios ya aplicados

1. **TurboStorageMigrator.verifyConversion**
   - Arreglo del conteo de `.lrf` / `.mca` usando dos streams separados.
   - FULL_LRF ahora lanza excepción si quedan `.mca` en `region/` tras la conversión.

2. **TurboStorageMigrator.migrateOnDemand**
   - Eliminada la conversión masiva del directorio `region/`.
   - Ahora solo informa por logs que ON_DEMAND estará a cargo de los hooks de runtime.

3. **TurboChunkLoadingOptimizer**
   - Eliminados todos los `Thread.sleep` de los métodos de carga de chunks.
   - Se mantiene la API, pero como stub sin latencia artificial.

### 6.2. Próximos cambios a aplicar (orden sugerido)

1. Revisar y, si es necesario, simplificar el stack de almacenamiento/caching para que haya un único punto de entrada real (`TurboStorageManager` como fachada principal).
2. Revisar `BackgroundConversionScheduler` y asegurar una integración correcta con el ciclo de vida del servidor.
3. Opcional: convertir `TurboChunkLoadingOptimizer` en un optimizador real o hacerlo claramente opcional/desactivado por defecto.

Este archivo debe mantenerse actualizado a medida que se apliquen nuevos cambios en el código de TurboMC.
