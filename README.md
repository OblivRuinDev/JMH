# JMH — JNI vs FFM (Downcall) benchmark

Benchmarks the per-call overhead of LWJGL/FJGL's two native call paths:

| backend | entry point | target |
|---|---|---|
| `jni` | `org.lwjgl.system.JNI.invoke*` (JNI native method) | `bench_<cType>` in `fjgl` native lib |
| `ffm` | `org.lwjgl.system.Downcall.<cType>` (`ldc` + `invokeExact`) | **the same** `bench_<cType>` |

Every benchmarked signature is covered: `Downcall`'s 1054 cTypes are matched against `JNI`'s
`invoke*` methods (preferring the signed `B`/`S` variants), giving **1015 pairs → 2030 benchmarks**.
The 39 unmatched ones are the synthetic `N → J` resolved variants, which are not real functions.

The benchmark class (`org.lwjgl.system.DowncallJniBench`) and its JMH harness are **already compiled
into `dist/fjgl-0.3.0.jar`**; this repo only runs them and post-processes the results.

## Layout

```
dist/     FJGL 0.3.0 release + per-platform native jars (input)
libs/     JMH runtime: jmh-core, jopt-simple, commons-math3, jspecify (input)
tools/    EnvInfo.java (machine info), Flatten.java (JMH JSON -> CSV)
.github/  CI workflow
out/      results (gitignored, created at run time)
```

## Requirements

* **JDK 25** (the benchmark classes are class file version 69, and the FFM API requires a recent JDK).
* Nothing else: the JMH harness is prebuilt and the JMH runtime jars are committed.

## Run locally (Windows PowerShell)

```powershell
# all 2030 benchmarks, ~3h
./run-local.ps1

# one signature
./run-local.ps1 -Filter '.*DowncallJniBench\.(jni|ffm)_BP$'

# only the FFM side
./run-local.ps1 -Filter '.*ffm_.*'
```

## Run locally (bash)

```bash
java -cp "dist/fjgl-0.3.0.jar:dist/fjgl-0.3.0-natives-linux.jar:libs/*" \
  tools/classes/EnvInfo out/env-linux-x64.json   # after: javac -d tools/classes tools/*.java

java --enable-native-access=ALL-UNNAMED \
  --add-exports=java.base/jdk.internal.access=ALL-UNNAMED \
  --add-exports=java.base/jdk.internal.foreign=ALL-UNNAMED \
  --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-exports=java.base/jdk.internal.util=ALL-UNNAMED \
  -cp "dist/fjgl-0.3.0.jar:dist/fjgl-0.3.0-natives-linux.jar:libs/*" \
  org.openjdk.jmh.Main '.*DowncallJniBench.*' \
  -wi 30 -i 5 -w 100ms -r 500ms -f 1 -t 1 -tu ns \
  -rf json -rff out/jmh-linux-x64.json \
  -jvmArgsAppend "--enable-native-access=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.access=ALL-UNNAMED --add-exports=java.base/jdk.internal.foreign=ALL-UNNAMED --add-exports=java.base/jdk.internal.util=ALL-UNNAMED"

java -cp tools/classes Flatten out/env-linux-x64.json out/jmh-linux-x64.json out/results-linux-x64.csv
```

## Run on GitHub Actions

`Actions → jni-vs-ffm-bench → Run workflow`. It runs the matrix

| runner | platform | arch |
|---|---|---|
| `ubuntu-latest` | linux | x64 |
| `ubuntu-24.04-arm` | linux | arm64 |
| `windows-latest` | windows | x64 |
| `windows-11-arm` | windows | arm64 |
| `macos-13` | macos | x64 |
| `macos-14` | macos | arm64 |

Each job uploads `env-*.json`, `jmh-*.json` and `results-*.csv`; the `merge` job concatenates all
CSVs into `results-all.csv`. Only GitHub-hosted machines are used (no QEMU), so timings are real.

> GitHub-hosted jobs are hard-capped at **6 hours**. Defaults finish in ~3h per platform. Raising
> iterations/forks too far may exceed that; split with a narrower `filter` instead.

## Data format

* `env-<platform>-<arch>.json` — machine/OS/JDK/CPU/RAM.
* `jmh-<platform>-<arch>.json` — native JMH output (score, error, percentiles, **per-iteration
  rawData**, forks, jvmArgs, …).
* `results-<platform>-<arch>.csv` — tidy one-row-per-benchmark CSV derived from both, columns:
  `platform, arch, osName, osVersion, jdk, vmName, cpuModel, ramBytes, backend, signature, ret,
  params, score, error, unit, p0, p25, p50, p75, p90, p95, p99, p99_9, p99_99, p100, forks,
  warmupIterations, measurementIterations, threads, timestamp`.

In a spreadsheet, pivot `signature` with `backend` as columns and `score` as values to get
`ffm / jni` ratios per signature. `signature` is the `cType`: first letter is the return type,
the rest are the parameter types (`V`oid `P`ointer `J`long `N`C long `I`nt `B`yte `S`hort
`Z`boolean `F`loat `D`ouble).

## Notes

* The benchmark's empty targets (`bench_*`, `JNIEXPORT`, return 0) are compiled into the native
  libs by FJGL's `bench.c`; both backends call the very same function, so the measured difference
  is the bridge overhead.
* Only x64/arm64 are exercised. On x86 the C `__cdecl` names would be decorated (`_bench_BP`).
