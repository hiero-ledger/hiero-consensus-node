# Benchmarks

Conventions for the JMH benchmarks of the consensus layer's modules: how to write one, calibrate it on the benchmark
machine, and track it every night. They apply to every benchmark in a module's `src/jmh` source set.

## Index

|               File               |    Topic    |                                          Summary                                          |
|----------------------------------|-------------|-------------------------------------------------------------------------------------------|
| [writing.md](writing.md)         | Writing     | Module setup, inputs, latency and allocation measurement, profilers, profiling.           |
| [calibration.md](calibration.md) | Calibration | The calibration run and its analysis, forks and iterations, fork modes, heap, the record. |
| [operations.md](operations.md)   | Operations  | Tracking, the smoke run, the nightly run, the benchmark machine, publishing, alerts.      |

The shared Java tooling is described in the [`consensus-benchmark-tools` README](../../../consensus-benchmark-tools/README.md),
which also lists the developer scripts.

## The pipeline

```
writing.md       benchmark class in src/jmh
                 LatencyRecorder times every call, LatencyProfiler reports the percentiles and alloc.norm
                 profile-regions.sh proves that the measured time is spent in the code under test
                     │
calibration.md   007 calibration workflow on the benchmark machine
                 run_benchmarks.py --jar  →  analyze_calibration.py
                 →  @Fork, @Warmup, @Measurement and the Javadoc calibration block
                     │
operations.md    the module applies org.hiero.gradle.feature.benchmark-tracking and names the benchmark
                 pull requests:       812 smoke run  →  jmhSmoke
                 every night:         905 nightly run  →  jmhTracked  →  run_benchmarks.py --manifest
                                      →  publish_results.py  →  Mimir and Loki  →  grafana-dashboard.json
                                      →  alert (not set up yet)
```

## Definition of done

A benchmark is done when:

1. **Its inputs are the same in every run:** seeded, and generated outside the measured calls
   ([writing.md](writing.md#inputs-and-the-measured-region)).
2. **It checks its output after every invocation,** so that it cannot measure code that does nothing without anyone
   noticing.
3. **A profiled run shows that the measured time is spent in the code under test,** and that the timing itself takes
   well under a percent ([writing.md](writing.md#profiling)).
4. **It reports its latency through `LatencyProfiler`:** `latency.mean`, p50 to p99.99, max, and `alloc.norm`. It
   reports p99.99 only if one iteration records enough calls ([writing.md](writing.md#reporting)).
5. **Its `@Fork`, `@Warmup`, and `@Measurement` are calibrated on the benchmark machine** and recorded in the
   calibration block of its Javadoc, which links the raw results ([calibration.md](calibration.md#the-calibration-record)).
6. **Its module tracks it:** it runs briefly in the CI of every pull request and in full every night on the benchmark
   machine, and its results appear in Grafana, labeled with the commit, the JDK, the runner, and the machine
   ([operations.md](operations.md)).
7. **It has an alert whose threshold comes from the noise of its nightly history**
   ([operations.md](operations.md#alerts)).
