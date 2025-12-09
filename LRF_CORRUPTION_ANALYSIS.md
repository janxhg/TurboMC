# Análisis del Problema: Detección de Chunks Corruptos en Conversión LRF

## Problema Identificado

Cuando el sistema convierte archivos `.mca` a `.lrf`, **no elimina los archivos `.mca` originales**. Esto causa que:

1. **Ambos formatos coexisten** en el mismo directorio
2. **El sistema detecta chunks como corruptos** porque intenta leer `.lrf` como si fuera `.mca`
3. **Doble lectura** del mismo chunk en diferentes formatos

## Código Problemático

### `TurboRegionFileStorage.java` - Línea 201
```java
String regionFileName = String.format("r.%d.%d.mca", regionX, regionZ);
Path lrfPath = regionFolder.resolve(regionFileName.replace(".mca", ".lrf"));

if (java.nio.file.Files.exists(lrfPath)) {
    return lrfPath;  // Retorna .lrf si existe
}
return regionFolder.resolve(regionFileName);  // Si no, retorna .mca
```

### `MCAToLRFConverter.java` - Línea 152
```java
String lrfFileName = fileName.replace(LRFConstants.MCA_EXTENSION, LRFConstants.LRF_EXTENSION);
```

**El problema:** El converter crea `.lrf` pero **no elimina el `.mca` original**.

## Flujo del Problema

1. **Conversión:** `r.0.0.mca` → `r.0.0.lrf` (pero `r.0.0.mca` permanece)
2. **Lectura:** Sistema busca `r.0.0.mca`, encuentra que existe `r.0.0.lrf`, usa `.lrf`
3. **Detección:** Sistema de integridad ve ambos archivos, intenta validar `.lrf` como `.mca`
4. **Resultado:** "Chunk corrupto" porque el formato no coincide

## Soluciones Propuestas

### Opción 1: Eliminar .mca después de conversión (Recomendada)
```java
// En MCAToLRFConverter.java - después de línea 90
if (lrfSize > 0 && chunks.size() > 0) {
    // Eliminar el archivo .mca original después de conversión exitosa
    try {
        Files.delete(mcaPath);
        if (verbose) {
            System.out.println("[TurboMC] Removed original: " + mcaPath.getFileName());
        }
    } catch (IOException e) {
        System.err.println("[TurboMC] Warning: Failed to delete original .mca file: " + e.getMessage());
    }
}
```

### Opción 2: Mover .mca a directorio de backup
```java
// Crear directorio backup
Path backupDir = mcaPath.getParent().resolve("backup_mca");
Files.createDirectories(backupDir);

Path backupPath = backupDir.resolve(mcaPath.getFileName());
Files.move(mcaPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
```

### Opción 3: Cambiar lógica de detección
```java
// En TurboRegionFileStorage.java - modificar getRegionPath()
private Path getRegionPath(ChunkPos pos) {
    int regionX = pos.getRegionX();
    int regionZ = pos.getRegionZ();
    
    String regionFileName = String.format("r.%d.%d.mca", regionX, regionZ);
    Path lrfPath = regionFolder.resolve(regionFileName.replace(".mca", ".lrf"));
    Path mcaPath = regionFolder.resolve(regionFileName);
    
    // Prioridad: LRF > MCA, pero si ambos existen, usar solo LRF
    if (Files.exists(lrfPath)) {
        return lrfPath;
    }
    
    return mcaPath;
}
```

### Opción 4: Validación mejorada
```java
// En el sistema de integridad, verificar formato antes de validar
private boolean isValidFormatForValidation(Path filePath) {
    String fileName = filePath.getFileName().toString().toLowerCase();
    return fileName.endsWith(".lrf") || fileName.endsWith(".mca");
}
```

## Impacto del Problema

### Actual
- **Falsos positivos** de corrupción
- **Doble almacenamiento** (mismo chunk en dos formatos)
- **Confusión** en el sistema de detección
- **Waste de espacio** en disco

### Si no se soluciona
- **Degradación del rendimiento** (lectura duplicada)
- **Corrupción real** si ambos archivos se modifican
- **Inconsistencia** en el almacenamiento

## Recomendación

**Implementar Opción 1 (Eliminar .mca)** con:
1. **Verificación de éxito** antes de eliminar
2. **Backup opcional** configurable
3. **Logging claro** de operaciones
4. **Rollback automático** si la conversión falla

## Archivos a Modificar

1. `MCAToLRFConverter.java` - Agregar eliminación de .mca
2. `TurboRegionFileStorage.java` - Mejorar detección de formato
3. `TurboStorageMigrator.java` - Agregar cleanup post-conversión
4. `TurboConfig.java` - Agregar opción `backup-original-mca`

## Testing Requerido

1. **Conversión con eliminación** - Verificar que .lrf funciona
2. **Rollback** - Probar recuperación si conversión falla
3. **Integridad** - Confirmar que no hay falsos positivos
4. **Performance** - Medir impacto de la eliminación
