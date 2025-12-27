# Plan de Implementación Quirúrgica - TurboMC Parallel Processing

## Resumen Ejecutivo
Este plan detalla la implementación quirúrgica para resolver los conflictos de procesamiento paralelo y prechunking en TurboMC LRF. Se enfoca en consolidar recursos, centralizar coordinación y optimizar cuellos de botella.

## Fases de Implementación

### Fase 1: Consolidación de Thread Pools (Día 1)

#### Objetivo
Eliminar sobresuscripción de CPU y consolidar todos los thread pools en un sistema unificado.

#### Archivos a Modificar:

**1. ParallelChunkGenerator.java**
```java
// ELIMINAR (líneas 62-67):
this.generatorExecutor = Executors.newFixedThreadPool(threads, r -> {
    Thread t = new Thread(r, "TurboMC-ParallelGen-" + System.currentTimeMillis());
    t.setDaemon(true);
    t.setPriority(Thread.NORM_PRIORITY - 1);
    return t;
});

// REEMPLAZAR CON:
private final ExecutorService generatorExecutor;

// EN CONSTRUCTOR:
this.generatorExecutor = TurboStorageManager.getInstance().getGlobalExecutor();
```

**2. TurboStorageManager.java**
```java
// AGREGAR MÉTODO (después de línea 73):
public ExecutorService getGlobalExecutor() {
    return globalLoadExecutor;
}

// MODIFICAR LÍNEA 100:
int loadThreads = Math.max(4, Math.min(
    config.getInt("storage.batch.global-load-threads", processors / 2), 
    MAX_LOAD_THREADS * 2  // Duplicar para soportar generación
));
```

**3. ChunkPrefetcher.java**
```java
// ELIMINAR (línea 38-39):
private Thread workerThread;

// REEMPLAZAR CON:
private final ScheduledExecutorService prefetchExecutor;

// EN CONSTRUCTOR:
this.prefetchExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "TurboMC-HyperView-" + world.dimension().location().getPath());
    t.setDaemon(true);
    t.setPriority(Thread.MIN_PRIORITY);
    return t;
});

// EN start() (línea 56):
this.prefetchExecutor.scheduleAtFixedRate(this::processTick, 2000, 2000, TimeUnit.MILLISECONDS);
```

#### Tests de Validación:
- TestSharedExecutorUsage.java
- TestThreadCount.java
- BenchmarkThreadPoolConsolidation.java

### Fase 2: Memory Management (Día 2)

#### Objetivo
Implementar LRU caches y memory-aware allocation para reducir pressure de memoria.

#### Archivos a Modificar:

**1. ChunkPrefetcher.java**
```java
// REEMPLAZAR LÍNEA 35:
private final Set<Long> visitedChunks = Collections.newSetFromMap(
    new LinkedHashMap<Long, Boolean>(10000, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
            return size() > 50000; // Limitar a 50k chunks
        }
    }
);

// AGREGAR MÉTODO DE CLEANUP:
private void cleanupVisitedChunks() {
    if (visitedChunks.size() > 40000) {
        // Remove oldest 10k entries
        Iterator<Long> it = visitedChunks.iterator();
        for (int i = 0; i < 10000 && it.hasNext(); i++) {
            it.next();
            it.remove();
        }
    }
}
```

**2. TurboChunkLoadingOptimizer.java**
```java
// AGREGAR DESPUÉS DE LÍNEA 84:
private final MemoryMonitor memoryMonitor;

// EN CONSTRUCTOR:
this.memoryMonitor = new MemoryMonitor();

// MODIFICAR getChunkCache() (línea 355):
private ChunkCache getChunkCache(String worldName) {
    return chunkCaches.computeIfAbsent(worldName, k -> {
        int cacheSize = currentStrategy.getCacheSize();
        if (cacheSize <= 0) cacheSize = 1000;
        
        // Memory-aware sizing
        double memoryPressure = memoryMonitor.getMemoryPressure();
        if (memoryPressure > 0.8) {
            cacheSize = (int) (cacheSize * 0.5); // Reduce size under pressure
        }
        
        return new ChunkCache(cacheSize);
    });
}

// AGREGAR CLASE MemoryMonitor:
private static class MemoryMonitor {
    public double getMemoryPressure() {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        return (double) used / rt.maxMemory();
    }
}
```

**3. TurboStorageManager.java**
```java
// MODIFICAR LÍNEA 568:
int maxCacheSize = config.getInt("storage.mmap.max-cache-size", 512);
double memoryPressure = new MemoryMonitor().getMemoryPressure();
if (memoryPressure > 0.7) {
    maxCacheSize = (int) (maxCacheSize * (1.0 - memoryPressure));
}
```

#### Tests de Validación:
- TestLRUCache.java
- TestMemoryPressure.java
- MemoryLeakTest.java

### Fase 3: Coordinación Centralizada (Día 3)

#### Objetivo
Implementar UnifiedChunkQueue para evitar redundancia y priority inversion.

#### Archivos a Modificar:

**1. TurboStorageManager.java**
```java
// AGREGAR DESPUÉS DE LÍNEA 61:
private final PriorityBlockingQueue<UnifiedChunkTask> unifiedChunkQueue;
private final AtomicInteger unifiedTaskId = new AtomicInteger(0);

// EN CONSTRUCTOR (línea 75):
this.unifiedChunkQueue = new PriorityBlockingQueue<>(1000);

// AGREGAR MÉTODO:
public CompletableFuture<ChunkAccess> submitUnifiedTask(UnifiedChunkTask task) {
    task.setId(unifiedTaskId.incrementAndGet());
    unifiedChunkQueue.offer(task);
    return task.getFuture();
}

// AGREGAR CLASE:
public static class UnifiedChunkTask implements Comparable<UnifiedChunkTask> {
    private final int chunkX, chunkZ;
    private final Priority priority;
    private final CompletableFuture<ChunkAccess> future;
    private final String source; // "prefetch", "pregeneration", "loading"
    private int id;
    
    public enum Priority {
        CRITICAL(1), HIGH(2), NORMAL(3), LOW(4), BACKGROUND(5);
        private final int value;
        Priority(int value) { this.value = value; }
        public int getValue() { return value; }
    }
    
    @Override
    public int compareTo(UnifiedChunkTask other) {
        int prioCmp = Integer.compare(this.priority.getValue(), other.priority.getValue());
        if (prioCmp != 0) return prioCmp;
        return Integer.compare(this.id, other.id);
    }
}
```

**2. ParallelChunkGenerator.java**
```java
// ELIMINAR LÍNEA 29:
private final PriorityBlockingQueue<GenerationTask> taskQueue;

// REEMPLAZAR CON:
private final TurboStorageManager storageManager;

// MODIFICAR queueGeneration() (línea 81):
public CompletableFuture<ChunkAccess> queueGeneration(int chunkX, int chunkZ, int priority) {
    if (!enabled) {
        return CompletableFuture.completedFuture(null);
    }
    
    UnifiedChunkTask.Priority taskPriority = priority <= 3 ? 
        UnifiedChunkTask.Priority.HIGH : 
        UnifiedChunkTask.Priority.BACKGROUND;
    
    UnifiedChunkTask task = new UnifiedChunkTask(
        chunkX, chunkZ, taskPriority, 
        CompletableFuture.supplyAsync(() -> null),
        "pregeneration"
    );
    
    return storageManager.submitUnifiedTask(task);
}
```

**3. ChunkPrefetcher.java**
```java
// MODIFICAR processTick() (línea 122):
// REEMPLAZAR:
generator.queueGeneration(cx, cz, 10);

// CON:
UnifiedChunkTask task = new UnifiedChunkTask(
    cx, cz, UnifiedChunkTask.Priority.BACKGROUND,
    CompletableFuture.supplyAsync(() -> null),
    "prefetch"
);
TurboStorageManager.getInstance().submitUnifiedTask(task);
```

#### Tests de Validación:
- TestUnifiedChunkQueue.java
- TestPriorityScheduling.java
- TestTaskDeduplication.java

### Fase 4: Optimización de I/O (Día 4)

#### Objetivo
Reducir lock contention en LRFRegionWriter y SharedRegionResource.

#### Archivos a Modificar:

**1. LRFRegionWriter.java**
```java
// AGREGAR DESPUÉS DE LÍNEA 40:
private final StampedLock headerLock = new StampedLock();

// MODIFICAR writeChunkStreaming() (línea 221):
// REEMPLAZAR synchronized blocks con StampedLock:
long stamp = headerLock.tryOptimisticRead();
if (!headerLock.validate(stamp)) {
    stamp = headerLock.readLock();
    try {
        currentPos = channel.position();
        // ... existing read operations
    } finally {
        headerLock.unlockRead(stamp);
    }
}

// PARA ESCRITURA:
stamp = headerLock.writeLock();
try {
    // ... existing write operations
} finally {
    headerLock.unlockWrite(stamp);
}
```

**2. SharedRegionResource.java**
```java
// AGREGAR READ-WRITE LOCKS:
private final ReadWriteLock headerLock = new ReentrantReadWriteLock();
private volatile long lastHeaderUpdate;

// MODIFICAR invalidateHeader():
public void invalidateHeader() {
    headerLock.writeLock().lock();
    try {
        cachedHeader = null;
        lastHeaderUpdate = System.currentTimeMillis();
    } finally {
        headerLock.writeLock().unlock();
    }
}

// MODIFICAR getHeader():
public LRFHeader getHeader() throws IOException {
    headerLock.readLock().lock();
    try {
        if (cachedHeader != null && !isHeaderStale()) {
            return cachedHeader;
        }
    } finally {
        headerLock.readLock().unlock();
    }
    
    // Upgrade to write lock
    headerLock.writeLock().lock();
    try {
        // Double-check pattern
        if (cachedHeader == null || isHeaderStale()) {
            cachedHeader = loadHeader();
        }
        return cachedHeader;
    } finally {
        headerLock.writeLock().unlock();
    }
}
```

#### Tests de Validación:
- TestIOContention.java
- TestStampedLock.java
- BenchmarkIOReduction.java

### Fase 5: Configuración Dinámica (Día 5)

#### Objetivo
Implementar configuración unificada y auto-ajuste basado en hardware.

#### Archivos a Modificar:

**1. turbo.toml**
```toml
# AGREGAR SECCIÓN:
[performance.thread-pools]
# Configuración unificada para todos los componentes
total-threads = "auto"  # auto = CPU cores * 2, o número específico
load-ratio = 0.4        # 40% para carga y generación
write-ratio = 0.2       # 20% para escritura  
compression-ratio = 0.3 # 30% para compresión
prefetch-ratio = 0.1    # 10% para prefetch/background

# Configuración de memoria dinámica
[performance.memory]
total-memory-budget-mb = "auto"  # auto = 30% de RAM disponible
cache-ratio = 0.6               # 60% del budget para caches
mmap-ratio = 0.3               # 30% para memory-mapped I/O
compression-ratio = 0.1         # 10% para buffers de compresión

# Umbral de presión de memoria
memory-pressure-threshold = 0.8  # 80% para empezar a reducir caches
```

**2. TurboConfig.java**
```java
// AGREGAR MÉTODOS:
public int getUnifiedThreadCount() {
    String total = getString("performance.thread-pools.total-threads", "auto");
    if ("auto".equals(total)) {
        return Runtime.getRuntime().availableProcessors() * 2;
    }
    return getInt("performance.thread-pools.total-threads", 16);
}

public int getLoadThreadCount() {
    int total = getUnifiedThreadCount();
    double ratio = getDouble("performance.thread-pools.load-ratio", 0.4);
    return Math.max(2, (int) (total * ratio));
}

public long getMemoryBudgetMB() {
    String budget = getString("performance.memory.total-memory-budget-mb", "auto");
    if ("auto".equals(budget)) {
        Runtime rt = Runtime.getRuntime();
        long available = (rt.maxMemory() - rt.totalMemory() + rt.freeMemory()) / 1024 / 1024;
        return (long) (available * 0.3); // 30% de memoria disponible
    }
    return getLong("performance.memory.total-memory-budget-mb", 1024);
}
```

**3. TurboStorageManager.java**
```java
// MODIFICAR CONSTRUCTOR PARA USAR CONFIGURACIÓN UNIFICADA:
int totalThreads = config.getUnifiedThreadCount();
int loadThreads = config.getLoadThreadCount();
int writeThreads = (int) (totalThreads * config.getDouble("performance.thread-pools.write-ratio", 0.2));
int compressionThreads = (int) (totalThreads * config.getDouble("performance.thread-pools.compression-ratio", 0.3));
int prefetchThreads = (int) (totalThreads * config.getDouble("performance.thread-pools.prefetch-ratio", 0.1));
```

#### Tests de Validación:
- TestDynamicConfiguration.java
- TestHardwareDetection.java
- TestAutoAdjustment.java

## Scripts de Testing

### TestRunner.java
```java
public class TestRunner {
    public static void main(String[] args) {
        System.out.println("=== TurboMC Parallel Processing Tests ===");
        
        // Fase 1 Tests
        runTest("Thread Pool Consolidation", TestSharedExecutorUsage::run);
        runTest("Thread Count Validation", TestThreadCount::run);
        runTest("Performance Benchmark", BenchmarkThreadPoolConsolidation::run);
        
        // Fase 2 Tests  
        runTest("LRU Cache Functionality", TestLRUCache::run);
        runTest("Memory Pressure Handling", TestMemoryPressure::run);
        runTest("Memory Leak Detection", MemoryLeakTest::run);
        
        // Fase 3 Tests
        runTest("Unified Queue Operations", TestUnifiedChunkQueue::run);
        runTest("Priority Scheduling", TestPriorityScheduling::run);
        runTest("Task Deduplication", TestTaskDeduplication::run);
        
        // Fase 4 Tests
        runTest("I/O Contention Reduction", TestIOContention::run);
        runTest("StampedLock Performance", TestStampedLock::run);
        runTest("I/O Benchmark", BenchmarkIOReduction::run);
        
        // Fase 5 Tests
        runTest("Dynamic Configuration", TestDynamicConfiguration::run);
        runTest("Hardware Detection", TestHardwareDetection::run);
        runTest("Auto Adjustment", TestAutoAdjustment::run);
        
        System.out.println("=== All Tests Completed ===");
    }
}
```

## Métricas y Monitoring

### MetricsCollector.java
```java
public class MetricsCollector {
    private final AtomicLong totalTasksSubmitted = new AtomicLong();
    private final AtomicLong totalTasksCompleted = new AtomicLong();
    private final AtomicLong totalExecutionTime = new AtomicLong();
    private final AtomicInteger activeThreads = new AtomicInteger();
    private final AtomicLong memoryUsed = new AtomicLong();
    
    public void recordTaskSubmitted() {
        totalTasksSubmitted.incrementAndGet();
    }
    
    public void recordTaskCompleted(long executionTimeMs) {
        totalTasksCompleted.incrementAndGet();
        totalExecutionTime.addAndGet(executionTimeMs);
    }
    
    public PerformanceReport generateReport() {
        long submitted = totalTasksSubmitted.get();
        long completed = totalTasksCompleted.get();
        long totalTime = totalExecutionTime.get();
        
        double avgTime = completed > 0 ? (double) totalTime / completed : 0;
        double throughput = completed > 0 ? (double) completed / (System.currentTimeMillis() / 1000.0) : 0;
        
        return new PerformanceReport(submitted, completed, avgTime, throughput, 
                                   activeThreads.get(), memoryUsed.get());
    }
}
```

## Rollback Plan

### RollbackManager.java
```java
public class RollbackManager {
    private static final String BACKUP_DIR = "./rollback_backup/";
    
    public static void createBackup() {
        // Backup critical files before changes
        backupFile("ParallelChunkGenerator.java");
        backupFile("TurboStorageManager.java");
        backupFile("ChunkPrefetcher.java");
        backupFile("turbo.toml");
    }
    
    public static void rollback() {
        // Restore from backup if needed
        restoreFile("ParallelChunkGenerator.java");
        restoreFile("TurboStorageManager.java");
        restoreFile("ChunkPrefetcher.java");
        restoreFile("turbo.toml");
    }
}
```

## Checklist de Validación Final

### Performance Validation
- [ ] CPU Usage < 95% bajo carga máxima
- [ ] Memory Usage < 512MB para caches
- [ ] I/O Wait < 10% del tiempo total
- [ ] Thread Count < 30 hilos activos
- [ ] Latencia de chunk loading < 50ms (P95)

### Functional Validation  
- [ ] Todos los tests unitarios pasan
- [ ] Tests de integración pasan
- [ ] Benchmarks muestran mejora > 20%
- [ ] No memory leaks detectados
- [ ] No deadlocks bajo alta concurrencia

### Configuration Validation
- [ ] Configuración dinámica funciona
- [ ] Auto-ajuste responde a cambios
- [ ] Hardware detection correcto
- [ ] Fallback a valores por defecto

## Timeline Detallado

### Día 1 - Thread Pool Consolidation
- **Mañana (4h)**: Modificar ParallelChunkGenerator y TurboStorageManager
- **Tarde (4h)**: Modificar ChunkPrefetcher y tests básicos
- **Noche (2h)**: Validación y debugging

### Día 2 - Memory Management  
- **Mañana (4h)**: Implementar LRU cache en ChunkPrefetcher
- **Tarde (4h)**: Memory-aware caching en TurboChunkLoadingOptimizer
- **Noche (2h)**: Memory budget en TurboStorageManager

### Día 3 - Coordinación Centralizada
- **Mañana (4h)**: UnifiedChunkQueue en TurboStorageManager
- **Tarde (4h)**: Integración con generators y prefetchers
- **Noche (2h)**: Tests de coordinación y deduplicación

### Día 4 - I/O Optimization
- **Mañana (4h)**: Optimizar LRFRegionWriter con StampedLock
- **Tarde (4h)**: Mejorar SharedRegionResource con RW locks
- **Noche (2h)**: Benchmarks de I/O y validación

### Día 5 - Configuración y Testing Final
- **Mañana (4h)**: Configuración dinámica y auto-ajuste
- **Tarde (4h)**: Tests completos de integración
- **Noche (2h)**: Validación final y documentación

## Comandos de Ejecución

### Build y Test
```bash
# Build con cambios
./gradlew build -x test

# Ejecutar tests específicos
./gradlew test --tests "*ParallelProcessing*"

# Ejecutar benchmarks
./gradlew test --tests "*Benchmark*"

# Validación completa
./gradlew test --tests "*ParallelProcessing*" --tests "*Benchmark*" --tests "*Integration*"
```

### Monitoring durante Testing
```bash
# Memory usage
jstat -gc -t <pid> 5s

# Thread dump  
jstack <pid> > thread_dump.txt

# CPU profiling
jvisualvm --openpid <pid>
```

Este plan proporciona una implementación quirúrgica sistemática con validación en cada fase, minimizando riesgos y garantizando mejoras medibles en el rendimiento del sistema de procesamiento paralelo de TurboMC.
