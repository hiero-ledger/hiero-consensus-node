# consensus-benchmark-tools

Classes and scripts shared by the consensus-layer performance tests. Not part of the runtime
module graph.

## Architecture

Tooling module — support code for the performance tests in
[`consensus-otter-tests`](../consensus-otter-tests), [`consensus-sloth`](../consensus-sloth),
[`consensus-network-simulation`](../consensus-network-simulation), and
the JMH benchmarks (`src/jmh`) of other modules. For the modules these tests exercise, see the
[architecture overview](../docs/consensus-layer/architecture/overview.md).

## Benchmarks

The classes in this module are the measurement tooling of the
[benchmark conventions](../docs/consensus-layer/benchmarks/README.md), which every JMH benchmark
follows, `LatencyHistogramBenchmark` in `src/jmh` included.

## Scripts

Developer tools in [`scripts/`](scripts), run locally on a benchmark's profiles and logs. Each
script's header gives its usage. The scripts that CI runs live in
[`.github/workflows/support/scripts/benchmarks/`](../../.github/workflows/support/scripts/benchmarks).

- `profile-regions.sh` — checks that a benchmark measures the code it is meant to measure: how
  much of a CPU profile falls in the measured code, in the benchmark's own setup, and in other
  threads such as the garbage collector.
- `fork-modes.sh` — runs a benchmark in single forks and records an allocation profile and the JIT
  compiler's log in each, grouped by the memory the forks allocate per call: the input that
  `alloc-sites.py` and `inline-tree.py` need when forks of a benchmark run differently.
- `alloc-sites.py` — shows which code allocates how much memory per benchmark call, side by side
  for several runs. The JIT compiler sometimes optimizes the same code differently from one run
  to the next, so that some runs allocate more and run slower; the script points at the code
  whose allocation differs.
- `inline-tree.py` — shows how the JIT compiler compiled a method: which calls it inlined
  (copied into the method, so that they are optimized together), which it did not, and why. It
  explains why two runs compiled the same code differently.
- `jfr-components.sh` — shows how the CPU time of running nodes splits across the consensus-layer
  components, such as gossip, event intake, and consensus, from Java Flight Recorder recordings.
- `sloth-stages.sh` — summarizes a [Sloth](../consensus-sloth) run: how long after its arrival an
  event passes each processing stage of a node, and how long the network takes to reach consensus.

## Dependency Rules

May depend on:
- Any consensus-layer module except structural-transitional ones, including impl modules

Must not depend on:
- Any structural-transitional module — those are on their way out of the consensus layer, and
benchmark tooling must not pin them in place
- `swirlds-common`, `swirlds-platform-core` — legacy, being eliminated

May be depended on only by:
- `consensus-otter-tests`
- `consensus-sloth`
- `consensus-network-simulation`, in its tests only
- The JMH source sets (`src/jmh`) of any module
