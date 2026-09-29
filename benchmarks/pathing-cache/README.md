# pathing cache benchmark

compares the production A* implementation on this branch against upstream `1.21.4` at `622977066159bd47ac9708fb4842dc3a87f2b63c`. the fork's `1.21.4` was fast-forwarded to that commit before branching.

requires Python 3 and JDK 21. create a separate unchanged baseline worktree, then run from the candidate checkout:

```powershell
git worktree add --detach ../barigo-cache-baseline 622977066159bd47ac9708fb4842dc3a87f2b63c
python benchmarks/pathing-cache/run.py --baseline ../barigo-cache-baseline --java-home "C:/Program Files/Eclipse Adoptium/jdk-21.0.7.6-hotspot"
```

on other machines, replace the JDK path or set `JAVA_HOME`. the runner checks the baseline commit and tracked source, builds both checkouts, compiles the benchmark, and runs the cache integration checks before timing. generated classes, build logs, raw samples and `summary.json` go under `build/pathing-cache/`.

each comparison uses isolated `baritone.*` class loaders in one JVM. Minecraft and dependency classes are shared. the settings shim bypasses client startup and personal settings files in both loaders. production A*, movement costs, block states, node storage, goal heuristics and path reconstruction run unchanged.

the default run uses four fresh JVMs per cache size, 20 warmup batches and 30 timed pairs per scenario per JVM. baseline/candidate order alternates every pair, and cache-size order alternates between forks. the short scenario batches 100 fresh searches; all others use one. both loaders must return identical expanded-node counts, movement counts, node-map sizes, path lengths, end costs and hashes of every path position. results summarize the median of the four per-fork medians. allocation counts come from `ThreadMXBean`, on the searching thread.

the timed region includes context/world construction, claiming and clearing caches, search, path reconstruction, releasing caches and result verification. it excludes movement assembly, path execution and client ticks. it measures steady-state reuse after warmup, not the first allocation on a new worker. the harness uses reflection equally for both implementations.

the synthetic worlds use floor y=64 and a loaded region of +/-4096 blocks. `flat` targets 300,64,100. `short` targets 12,64,8. `obstacles` targets 240,64,160 with four-block bedrock walls every 24 x blocks, leaving gaps along z. `terraces` uses triangular one-block elevation steps every 12 x blocks. `mining` targets 96,64,32 through three-block stone walls every 16 x blocks, with breaking enabled and fixed tool strength 0.2. `composite` uses 128 block goals starting at 300,64,100.

## measured results

measured on Windows 11, Ryzen 7 3700X, 8 cores / 16 logical processors, Temurin 21.0.7+6, G1, `-Xms512m -Xmx1g`. 120 timed pairs per scenario per cache size, 1440 pairs total. every comparison returned the same path signature.

default 64K buffers:

| scenario | baseline ms | reused ms | time saved | baseline bytes | reused bytes | time saved across forks |
|---|---:|---:|---:|---:|---:|---:|
| short | 0.503 | 0.112 | 77.8% | 2,968,004 | 84,328 | 77.5% to 78.0% |
| flat | 39.842 | 40.645 | -2.0% | 5,269,048 | 2,385,384 | -2.2% to -1.5% |
| obstacles | 72.920 | 67.685 | 7.2% | 6,405,192 | 3,521,528 | -2.0% to 15.3% |
| terraces | 71.730 | 73.342 | -2.2% | 6,328,152 | 3,444,488 | -2.8% to 2.5% |
| mining | 46.615 | 46.808 | -0.4% | 5,015,400 | 2,131,832 | -3.7% to 3.8% |
| composite | 55.392 | 56.475 | -2.0% | 5,272,664 | 2,388,988 | -5.5% to -0.0% |

positive time saved means faster, negative means slower. the short case was faster in 120/120 pairs, with 77.5% to 78.0% reductions across forks and 97.2% less allocation. long searches allocated 45.0% to 57.5% fewer bytes. there is no consistent long-search speedup at the default size. flat was 1.5% to 2.2% slower in every fork; composite was also slightly slower. the obstacle aggregate depends heavily on one fork, so do not treat its 7.2% as a reliable gain.

optional 16K buffers, compared against the unchanged 64K baseline in separate paired JVMs:

| scenario | baseline ms | reused 16K ms | time saved | time saved across forks | baseline uncached reads | 16K uncached reads |
|---|---:|---:|---:|---:|---:|---:|
| short | 0.519 | 0.092 | 82.3% | 80.7% to 83.0% | 358 | 358 |
| flat | 44.579 | 34.229 | 23.2% | 20.2% to 25.8% | 103,918 | 103,918 |
| obstacles | 73.016 | 56.875 | 22.1% | 15.1% to 24.5% | 177,454 | 228,690 |
| terraces | 83.380 | 62.464 | 25.1% | 18.9% to 27.4% | 178,870 | 217,494 |
| mining | 56.239 | 41.917 | 25.5% | 16.6% to 26.6% | 52,828 | 115,545 |
| composite | 59.419 | 46.863 | 21.1% | 18.7% to 22.1% | 103,918 | 103,918 |

16K reduced short-search time by 82.3% and long-search time by 21.1% to 25.5% in the aggregate. every fork improved, but the smaller cache needed 2.19x as many uncached reads for mining. the allocation benefit comes from reuse at either capacity. absolute times varied between forks, so compare paired before/after results rather than treating the baseline times in the two tables as interchangeable.

these are headless synthetic searches. uncached block lookups here are cheaper than loaded Minecraft chunks, and mixins are absent. `PrecomputedData` uses its registry fallback. this does not measure FPS, ticks, multi-worker throughput or live-world hit rates.

the default remains 65536 entries. worker threads retain one mining buffer set and one block buffer set, about 2.75 MiB per participating worker with compressed references. nested searches allocate separate temporary arrays, and a size change replaces idle retained buffers. this trades retained worker memory for less per-search allocation. values stay in the arrays, but all three key arrays reset to the empty sentinel on every claim.

`#pathingCacheSize 16384` selects the smaller cache for new contexts and block interfaces. sizes round up to a power of two and clamp to 1024..65536. the 16K option retains about 0.69 MiB per worker, but can cause more uncached reads. keep the default for deployments until representative live-world measurements justify changing it.

## validation

`SearchCacheTest` covers capacity normalization, mining/block reuse, nested ownership, resizing and foreign-thread release. the standalone `CacheChecks` uses real `CalculationContext`, `BlockStateInterface` and `MovementHelper` against a mutable world. it checks setting snapshots, owner-only cache access, foreign claim/release, nested searches and 100 alternating stone/air searches at each of four requested capacities.

the root test suite passed with 94 tests. `compileJava compileLaunchJava compileTestJava` passed with all configured loaders. `gradlew.bat build -x javadoc` passed and produced all 12 distribution jars. API javadoc fails on both upstream and this branch because of existing missing descriptions and obsolete Minecraft method references in `TickEvent` and `IGameEventListener`.
