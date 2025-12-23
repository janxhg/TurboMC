# TurboMC Cache Benchmark Suite

## Quick Start

```bash
# Run all benchmarks
./gradlew :turbo-server:test --tests "CacheBenchmark"

# Run specific test
./gradlew :turbo-server:test --tests "CacheBenchmark.testSequentialAccess"

# Run correctness tests
./gradlew :turbo-server:test --tests "CacheCorrectnessTest"
```

## Benchmarks Included

### 1. Sequential Access Test
Tests reading chunks in order (0,0), (1,0), (2,0)...
- **Best case for**: No cache (no reuse)
- **Expected**: No cache should be faster

### 2. Random Access Test  
Tests random chunk access pattern
- **Realistic**: Player exploration
- **Expected**: Depends on hit rate

### 3. Hot Chunks Test
80% of access to 20% of chunks
- **Best case for**: Cache
- **Expected**: Cache should be faster

### 4. Cache vs No Cache
Detailed metrics comparison:
- Throughput (chunks/sec)
- Average latency (ms)
- Percentile differences

### 5. Compression Level Test
Tests levels 1, 3, 10, 20
- **Shows**: When cache helps vs hurts
- **Expected**: High levels = cache useless

### 6. Degradation Test
Measures performance as cache fills
- **Shows**: LinkedHashMap O(n) problem
- **Expected**: Progressive slowdown

## Example Output

```
================================================================================
TurboMC Cache Performance Benchmark
================================================================================

[1/6] Testing Sequential Access...
  Testing with cache ENABLED...
  Testing with cache DISABLED...
  Sequential Access   : Cache= 5234ms  NoCache= 4821ms  Diff= +413ms (+8.6%)  ❌ CACHE SLOWER

[2/6] Testing Random Access...
  Testing with cache ENABLED...
  Testing with cache DISABLED...
  Random Access       : Cache= 6012ms  NoCache= 4995ms  Diff=+1017ms (+20.4%) ❌ CACHE SLOWER

[3/6] Testing Hot Chunks (80/20)...
  Testing with cache ENABLED...
  Testing with cache DISABLED...
  Hot Chunks (80/20)  : Cache= 3421ms  NoCache= 4821ms  Diff= -1400ms (-29.0%) ✅ CACHE FASTER

[4/6] Testing Cache vs No Cache...
  Detailed Metrics:
  ----------------------------------------------------------------------
  Metric                          With Cache    Without Cache       Diff
  ----------------------------------------------------------------------
  Throughput (chunks/sec)            166.39           200.20     -16.9%
  Avg Latency (ms)                     6.01             5.00      20.3%
  ----------------------------------------------------------------------

[5/6] Testing Compression Levels...
  Testing compression levels:
  ----------------------------------------------------------------------
  Level         With Cache    Without Cache       Difference
  ----------------------------------------------------------------------
  1                  487ms            412ms              75ms (SLOWER)
  3                 1250ms           1100ms             150ms (SLOWER)
  10                4500ms           4450ms              50ms (SLOWER)
  20               25000ms          24950ms              50ms (SLOWER)
  ----------------------------------------------------------------------

[6/6] Testing Performance Degradation...
  Measuring degradation (cache filling up):
  --------------------------------------------------
  Iteration              Time (ms)      Degradation
  --------------------------------------------------
  0                          450ms         baseline
  100                        480ms            6.7%
  200                        520ms           15.6%
  300                        580ms           28.9%
  400                        650ms           44.4%
  500                        720ms           60.0%
  600                        800ms           77.8%
  700                        890ms           97.8%
  800                        980ms          117.8%
  900                       1100ms          144.4%
  --------------------------------------------------

================================================================================
SUMMARY
================================================================================
Recommendations:
  • If cache is consistently SLOWER: Disable cache in config
  • If degradation > 20%: Implement better eviction strategy
  • If compression level 20: Reduce to 3 or use LZ4
  • If NVMe storage: Cache overhead likely exceeds benefit
================================================================================
```

## Interpreting Results

**Cache helps if:**
- ✅ Hot chunks: Cache > 20% faster
- ✅ Degradation < 20%
- ✅ Compression level ≤ 3

**Cache hurts if:**
- ❌ Sequential/Random: Cache slower
- ❌ Degradation > 50%
- ❌ Compression level > 10

## Running Custom Tests

Edit `CacheBenchmark.java` to:
- Change BENCHMARK_ITERATIONS
- Test different chunk sizes
- Modify access patterns  
- Add your own tests

## CI Integration

Add to `.github/workflows/benchmark.yml`:
```yaml
- name: Run Cache Benchmarks
  run: ./gradlew :turbo-server:test --tests "CacheBenchmark"
  
- name: Upload Results
  uses: actions/upload-artifact@v2
  with:
    name: benchmark-results
    path: build/reports/tests/
```
