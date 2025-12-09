# Flujo de Funcionamiento del Sistema LRF

## 1. Estructura de Archivos

```
D:\ASAS\minecraft_server\papermc_modificado\
├── TurboMC/                          (Proyecto - compilación)
│   ├── turbo.toml                    (Config por defecto del proyecto)
│   ├── paper-server/
│   │   └── src/main/java/com/turbomc/
│   │       ├── config/
│   │       │   └── TurboConfig.java  (Lee turbo.toml)
│   │       └── storage/
│   │           ├── TurboLRFBootstrap.java      (Inicializa LRF)
│   │           ├── TurboStorageMigrator.java   (Migra regiones)
│   │           └── converter/
│   │               └── RegionConverter.java    (Convierte MCA ↔ LRF)
│   │
│   └── build/libs/
│       └── paper.jar                (JAR compilado)
│
└── test_environment/server/          (Servidor en ejecución)
    ├── turbo.toml                    (Config del servidor - AQUÍ SE LEE)
    ├── server.properties
    ├── config/
    │   └── paper-global.yml
    └── world/
        └── region/
            ├── r.0.0.mca            (Antes: Anvil)
            └── r.0.0.lrf            (Después: LRF convertido)
```

---

## 2. Flujo de Inicialización

### Paso 1: Servidor inicia
```
java -Xms1024M -Xmx2048M -jar paper.jar
```

### Paso 2: TurboConfig se inicializa
```java
// En algún punto del bootstrap del servidor (MinecraftServer)
TurboConfig config = TurboConfig.getInstance(new File("."));
```

**¿Qué hace?**
- Busca `turbo.toml` en el directorio actual (`.`)
- Si no existe, crea uno con valores por defecto
- Lee el archivo con `toml4j` (librería TOML)
- Almacena en singleton para acceso global

### Paso 3: TurboLRFBootstrap inicializa
```java
// Llamado desde MinecraftServer bootstrap
TurboLRFBootstrap.initialize(Paths.get("."));
```

**¿Qué hace?**
```
TurboLRFBootstrap.initialize()
    ↓
TurboConfig.getInstance(serverDir)
    ↓
Lee turbo.toml:
    - storage.format = "lrf"
    - storage.auto-convert = true
    - storage.conversion-mode = "on-demand"
    ↓
Imprime en logs:
    [TurboMC][LRF] Storage Format: lrf
    [TurboMC][LRF] Auto-Convert: true
    [TurboMC][LRF] Conversion Mode: on-demand
    ↓
Si auto-convert = true:
    [TurboMC][LRF] Auto-convert enabled. Worlds will be migrated on load.
```

### Paso 4: Cuando se carga un mundo
```java
// Llamado para cada mundo (world, world_nether, world_the_end)
TurboLRFBootstrap.migrateWorldIfNeeded(Paths.get("world"));
```

**¿Qué hace?**
```
TurboLRFBootstrap.migrateWorldIfNeeded(worldPath)
    ↓
TurboConfig.getInstance().migrateWorldRegionsIfNeeded(worldPath)
    ↓
TurboStorageConfig storageCfg = getStorageConfig()
    (Obtiene: format=LRF, autoMigrate=true)
    ↓
TurboStorageMigrator.migrateWorldIfNeeded(worldPath, storageCfg)
    ↓
Verifica:
    - ¿autoMigrate = true? → SÍ
    - ¿targetFormat = LRF? → SÍ
    - ¿Existe world/region/? → SÍ
    ↓
RegionConverter converter = new RegionConverter(true)
    ↓
converter.convertRegionDirectory(
    "world/region",      // origen
    "world/region",      // destino (MISMO = in-place)
    StorageFormat.LRF    // formato objetivo
)
    ↓
Para cada archivo .mca en world/region/:
    1. Lee chunks con MCAToLRFConverter
    2. Comprime con LZ4
    3. Escribe como .lrf
    4. Elimina .mca original
    ↓
[TurboMC][LRF] Region auto-migration complete.
```

---

## 3. Lectura de Configuración

### TurboConfig.java - Métodos principales

```java
// Constructor - se llama UNA SOLA VEZ
private TurboConfig(File serverDirectory) {
    this.configFile = new File(serverDirectory, "turbo.toml");
    // Busca: ./turbo.toml (directorio actual)
    
    if (!configFile.exists()) {
        createDefaultConfig(); // Crea si no existe
    }
    
    this.toml = new Toml().read(configFile);
    // Parsea el TOML con toml4j
}

// Métodos de lectura
public String getStorageFormat() {
    return toml.getString("storage.format", "auto");
    // Lee: [storage] format = "lrf"
}

public boolean isAutoConvertEnabled() {
    return toml.getBoolean("storage.auto-convert", false);
    // Lee: [storage] auto-convert = true
}

public TurboStorageConfig getStorageConfig() {
    String formatStr = getStorageFormat();      // "lrf"
    boolean autoMigrate = isAutoConvertEnabled(); // true
    
    StorageFormat format = StorageFormat.fromString(formatStr);
    // Convierte "lrf" → StorageFormat.LRF
    
    return new TurboStorageConfig(format, autoMigrate);
    // Retorna objeto con config lista
}
```

---

## 4. Conversión de Regiones

### RegionConverter.java - Conversión in-place

```java
public Object convertRegionDirectory(
    Path sourceDir,           // world/region
    Path targetDir,           // world/region (MISMO)
    StorageFormat targetFormat // StorageFormat.LRF
) throws IOException {
    
    // 1. Detecta archivos .mca
    // 2. Para cada .mca:
    //    - Lee con MCAToLRFConverter
    //    - Comprime datos con LZ4
    //    - Escribe como .lrf
    //    - Elimina .mca
    
    // Resultado:
    // r.0.0.mca  →  r.0.0.lrf
    // r.1.0.mca  →  r.1.0.lrf
    // etc.
}
```

---

## 5. Archivos TOML Involucrados

### turbo.toml (Proyecto TurboMC)
```
D:\ASAS\minecraft_server\papermc_modificado\TurboMC\turbo.toml
```
- **Propósito**: Defaults del sistema
- **Usado**: Durante compilación/desarrollo
- **Valores por defecto**: format="auto", auto-convert=false

### turbo.toml (Servidor)
```
D:\ASAS\minecraft_server\papermc_modificado\test_environment\server\turbo.toml
```
- **Propósito**: Configuración del servidor en ejecución
- **Usado**: Cuando inicia `java -jar paper.jar`
- **Valores actuales**: format="lrf", auto-convert=true
- **ESTE ES EL QUE SE LEE REALMENTE**

---

## 6. Flujo Completo en Logs

```
[08:21:21 INFO]: [bootstrap] Loading TurboMC 1.21.10-DEV...
    ↓
[TurboMC] Created default configuration file: turbo.toml
    (Si no existe, crea uno)
    ↓
[TurboMC][LRF] Initializing LRF storage system...
    ↓
[TurboMC][LRF] Storage Format: lrf
[TurboMC][LRF] Auto-Convert: true
[TurboMC][LRF] Conversion Mode: on-demand
    ↓
[TurboMC][LRF] Auto-convert enabled. Worlds will be migrated on load.
    ↓
[TurboMC][LRF] Auto-migration enabled: converting regions in 'world/region' to LRF...
    ↓
[TurboMC] Creating LRF region: r.0.0.lrf (compression: LZ4)
[TurboMC] Wrote 1024 chunks to r.0.0.lrf (2847392 bytes)
    ↓
[TurboMC][LRF] Region auto-migration complete.
```

---

## 7. Resumen

| Componente | Archivo | Función |
|-----------|---------|---------|
| **TurboConfig** | `TurboConfig.java` | Lee `turbo.toml` y expone métodos getter |
| **TurboLRFBootstrap** | `TurboLRFBootstrap.java` | Inicializa el sistema y llama a migrador |
| **TurboStorageMigrator** | `TurboStorageMigrator.java` | Ejecuta la migración de regiones |
| **RegionConverter** | `RegionConverter.java` | Convierte archivos .mca ↔ .lrf |
| **turbo.toml** | `test_environment/server/` | Configuración del servidor (LA QUE SE LEE) |

---

## 8. Cómo Cambiar Configuración

**Para cambiar el comportamiento:**

1. Edita `D:\ASAS\minecraft_server\papermc_modificado\test_environment\server\turbo.toml`
2. Cambia valores:
   ```toml
   [storage]
   format = "mca"           # Vuelve a vanilla
   auto-convert = false     # No auto-migra
   ```
3. Reinicia el servidor
4. Los cambios se aplican automáticamente

**NO necesitas recompilar**, solo reiniciar el servidor.

