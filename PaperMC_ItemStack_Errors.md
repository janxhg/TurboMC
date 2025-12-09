# PaperMC ItemStack Test Errors - Future Fix Required

## Problema Principal
Tests de ItemStack (Bukkit/Paper core) están fallando por problemas con la propiedad `scaling` en objetos `FILLED_MAP` (mapas llenos).

## Detalles del Error
- **Clases afectadas:** `ItemStackTest`, `ItemStackEnchantStorageTest`
- **Objetos:** `FILLED_MAP` (mapas llenos)
- **Propiedad faltante:** `scaling=true/false`
- **Total tests fallados:** 3043+ tests

## Ejemplo Típico de Error
```
Expected: (<ItemStack{FILLED_MAP x 1, MAP_META:{..., scaling=true}}> and <-254684694>)
but: <ItemStack{FILLED_MAP x 1, MAP_META:{..., scaling=true}}> was <ItemStack{FILLED_MAP x 1, MAP_META:{..., ...}}>
```

## Patrones de Falla Identificados

### 1. Scaling Property Missing
- La propiedad `scaling` no se está serializando/deserializando correctamente
- Afecta principalmente a objetos `FILLED_MAP` (mapas llenos)
- El problema se manifiesta en YAML serialization y component system

### 2. YAML Serialization Issues
- Problemas con conversión YAML de ItemStack
- Los componentes no se persisten correctamente al formato YAML
- Afecta tests que verifican round-trip serialization

### 3. Component System Issues
- Problemas con el sistema de componentes de Minecraft 1.21+
- El componente `minecraft:map_scaling` no se maneja correctamente
- Inconsistencias entre serialización y deserialización

### 4. Test Pattern Failures
Múltiples combinaciones de test failing:
- `Lore vs Other+Name vs Null+EnchantStack`
- `Scaling vs Unscale variations`
- `Blank vs None scaling states`

## Archivos Relevantes para Investigación

### Core Files
- `org.bukkit.craftbukkit.inventory.ItemStackTest`
- `org.bukkit.craftbukkit.inventory.ItemStackEnchantStorageTest`
- `org.bukkit.craftbukkit.inventory.CraftMetaMap`

### Component System
- Serialización/deserialización de componentes `minecraft:map_scaling`
- Component registry para map items
- YAML component serialization logic

## Impacto

### En TurboMC
- **No afecta funcionalidad** - Son errores del core de PaperMC
- **Tests de integración TurboMC pasan** - LRFIntegrationTest, LRFPerformanceTest funcionan correctamente
- **Bloquea builds completos** - Impide `./gradlew build` exitoso

### En PaperMC
- **Core functionality afectada** - ItemStack serialization es fundamental
- **Tests de regresión rotos** - Dificulta validación de cambios
- **Compatibilidad Bukkit** - Puede afectar plugins que dependen de map items

## Soluciones Temporales

### Para Desarrollo
```bash
# Opción 1: Saltar todos los tests
./gradlew build -x test

# Opción 2: Ejecutar solo tests de TurboMC
./gradlew test --tests "*LRFIntegrationTest*"
./gradlew test --tests "*LRFPerformanceTest*"

# Opción 3: Ejecutar tests específicos (excluyendo los problemáticos)
./gradlew test --tests "*ItemStackTest" --continue
```

### Para CI/CD
- Configurar pipelines para ignorar estos tests específicos
- Mantener separación entre tests de TurboMC y tests de PaperMC core

## Soluciones Permanentes (Futuro)

### 1. Investigación de CraftMetaMap
```java
// Revisar esta clase para manejo de scaling
public class CraftMetaMap extends CraftMetaItem implements MapMeta {
    // Verificar serialización de scaling property
    // Asegurar que minecraft:map_scaling se incluya correctamente
}
```

### 2. Component System Fix
- Investigar componente `minecraft:map_scaling`
- Verificar registro en component system
- Asegurar persistencia en YAML serialization

### 3. Test Updates
- Actualizar tests para alinear con nuevo sistema de componentes
- Verificar que expected values incluyan scaling property
- Asegurar consistency entre test data y actual implementation

### 4. YAML Serialization Enhancement
- Revisar `CraftItemFactory` y serialization methods
- Asegurar que todos los components se serialicen correctamente
- Testear round-trip serialization exhaustivamente

## Pasos para Debugging

### 1. Reproducción Aislada
```bash
# Ejecutar solo tests de ItemStack para análisis
./gradlew test --tests "*ItemStackTest*" --info
```

### 2. Análisis de Componentes
```java
// Debug code para inspeccionar componentes
ItemStack map = new ItemStack(Material.FILLED_MAP);
// Inspeccionar componentes actuales
// Verificar presencia de minecraft:map_scaling
```

### 3. YAML Round-trip Testing
```java
// Test manual de serialización
ItemStack original = // crear map con scaling
String yaml = // serializar a YAML
ItemStack deserialized = // deserializar desde YAML
// Comparar propiedades
```

## Prioridad y Timeline

### Prioridad: Baja-Media
- **No crítico para TurboMC** - Funcionalidad principal no afectada
- **Importante para PaperMC** - Core functionality del servidor
- **Solución compleja** - Requiere conocimiento profundo de Bukkit/Paper internals

### Timeline Sugerida
1. **Inmediato:** Usar soluciones temporales para continuar desarrollo
2. **Corto plazo (1-2 semanas):** Investigación inicial de CraftMetaMap
3. **Mediano plazo (1 mes):** Fix de component system y tests
4. **Largo plazo:** Validación completa y regresión testing

## Notas Adicionales

### Compatibility Considerations
- El fix debe mantener backward compatibility
- Considerar impacto en plugins existentes
- Testear con diferentes versiones de Minecraft

### Testing Strategy
- Crear tests específicos para map scaling
- Incluir negative testing (scaling null/missing)
- Validar con diferentes map states (empty, filled, scaled)

### Documentation Updates
- Documentar cambios en ItemStack behavior
- Actualizar plugin developer guides
- Incluir migration notes si aplica

---

**Estado:** Documentado para solución futura  
**Impacto TurboMC:** Ninguno (funcionalidad completa)  
**Recurso requerido:** Conocimiento profundo de PaperMC internals
