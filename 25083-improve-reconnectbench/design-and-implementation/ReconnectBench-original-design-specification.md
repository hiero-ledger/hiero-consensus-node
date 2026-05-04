# ReconnectBench Redesign — Design Document

## Purpose of this document

This document describes a redesign of `ReconnectBench`, the JMH benchmark for measuring virtual-map reconnect performance. The goal is to turn `ReconnectBench` into a tool the team can trust for iterating on reconnect changes — one that reliably reports whether a change helps, hurts, or is neutral, with enough fidelity that the answer carries over to a real cluster.

The existing `ReconnectBench` class will be substantially rewritten; this document is the design for that rewrite, not for a new parallel benchmark.

The document is structured in two parts. The **narrative portion** covers *why* the benchmark needs to look the way it does — the problem we're solving, the key insight that shapes most decisions, and the scope choices we've deliberately made. The **specification portion** covers *what* and *how* — architecture, parameters, outputs, limitations.

## What reconnect is, briefly

A consensus node that has fallen behind its peers needs to catch up. Reconnect is the process by which two nodes — a **teacher** (healthy, up-to-date) and a **learner** (behind) — synchronize their virtual-map state. The learner sends requests asking about specific positions in the teacher's tree; the teacher responds with either "your copy is already correct" (clean) or "here's the correct data" (dirty). After many such round-trips, the learner ends up with a tree that matches the teacher's snapshot taken at reconnect start.

Reconnect is latency-sensitive, message-heavy, and I/O-intensive. A real reconnect for a 100-million-leaf state can take 20–30 minutes in production. Multi-billion-leaf reconnects take hours to days.

## Why the current benchmark isn't used

The team effectively does not use `ReconnectBench` to evaluate reconnect changes. Every meaningful change is validated on a real cluster instead, which takes 4–6 hours per iteration (longer at multi-billion scale) and requires DevOps coordination. The local benchmark exists but has not earned the team's trust.

The reasons are layered. The most important one shapes the entire redesign.

## The central insight: speculation amplification

**Most reconnect traversal algorithms speculate.** The learner doesn't wait for the teacher to respond to every request before sending the next one. It keeps a pipeline of requests in flight. This is necessary for throughput — if you waited for each response, a round-trip network latency of 1 ms multiplied by 20 million requests would mean reconnect takes over 5 hours just in network travel time.

But speculation comes at a cost. Requests get sent based on whatever the learner knows at the moment of sending. If a response that would have made a request unnecessary is still in flight, the learner doesn't know that yet — so it sends the request anyway. When the delayed response finally arrives ("that whole subtree is clean"), the requests already dispatched for nodes in that subtree are wasted. The teacher still receives them, processes them, and sends back "clean" responses. That's wasted work on both sides.

**How much waste happens depends on how fast responses arrive relative to how fast requests are sent.**

Consider an illustrative case: a data-center network where one-way latency is around 500 microseconds. Responses come back slowly enough that waiting for them would idle the pipeline — speculation is a necessary cost to maintain throughput. Traversal order algorithms are tuned with this in mind.

On a laptop loopback, the round-trip time is at most a few microseconds — effectively zero from the algorithm's point of view. The algorithm's send decisions are made based on whatever state is current at the moment of the decision; when responses would have influenced a decision but haven't arrived yet, the algorithm proceeds without that information. On a fast network, many new requests can be enqueued and dispatched before any of the in-flight responses arrives. Many of those requests turn out to be unnecessary — a response that would have pruned them arrives almost immediately after they were sent. The proportion of speculative-but-redundant requests is much higher than on a realistic network.

The practical consequence is counterintuitive and critically important:

> **A change that looks faster on a zero-latency local benchmark can be slower in a real cluster, and vice versa.**

Concretely: an algorithm change that "fills the pipeline faster" on a laptop might achieve its speedup by sending more speculative requests. On a cluster where speculation is already more expensive, that same change may produce net slowdown. Without modeling realistic latency, the benchmark can tell you the opposite of the truth.

This is the single most important reason the current benchmark is untrustworthy. Everything else in this design is either in service of making this model correct, or is an honest limitation we've chosen to accept because fixing it isn't worth the cost.

## Why the current delay mechanism doesn't help

The current benchmark has `delayNetworkMicroseconds` and `delayStorageMicroseconds` parameters, both implemented as `Thread.sleep` calls at specific points in the message pipeline. The intent was to let engineers measure relative performance under artificial "slow network" conditions. The implementation has two properties that make it unsuitable for that purpose.

**First, the delays are applied per message on a serial critical path** — the single writer thread of the async output stream for the network delay, and the caller thread inside `sendAsync` for the storage delay. Real networks and real disks don't work this way. A real network pipelines bytes in flight, not one message at a time; a real disk serves many concurrent requests from its queue. Applying a delay per message on a serialized path imposes a pipeline structure that has no analogue in the systems being modeled.

**Second, any `Thread.sleep` or equivalent park on a hot pipeline path turns an asynchronous stage into one that is gated by the OS scheduler.** Java 25 on POSIX platforms does support sub-millisecond sleep precision, so `Thread.sleep(Duration.ofNanos(1000))` can in principle sleep for approximately 1 µs. But every sleep yields the thread, and re-acquiring the CPU costs at least tens of microseconds depending on scheduler load. When this happens per message on the single writer thread, upstream producers queue up while the writer is parked, then the writer wakes, processes one message, sleeps, and repeats. An asynchronous pipeline becomes a scheduler-gated stop-and-go. On Windows, the default system timer resolution is about 15.6 ms, so sub-millisecond sleep requests round up to tens of milliseconds — the same mechanism is substantially worse there, which matters for developers on Windows laptops.

Concrete measurements illustrate how badly these properties interact. At a state size of 500K leaves:

- Zero delay: reconnect completes in about 3 seconds.
- `delayNetworkMicroseconds=1` alone: about 314 seconds.
- `delayStorageMicroseconds=1` alone: about 298 seconds.
- Both set to 1 µs: about 258 seconds (single measurement, may include benchmark noise).

A 1 µs per-message delay cannot physically add 300 seconds to a 3-second reconnect. The serial depth of the reconnect pipeline is a few hundred to a few thousand dependent messages; real 1 µs delay on each of those would add milliseconds, not minutes. The observed blowup comes from scheduler wakeup costs on a de-asynchronized pipeline, not from delay magnitude.

The non-monotonic pattern — combined delay not being strictly slower than either alone — reinforces the point. Physical network and disk latencies are additive; adding a second constraint cannot make a system faster. Behavior that looks non-monotonic under this mechanism is a sign that the knobs aren't modeling physical constraints at all, but perturbing pipeline scheduling in ways dependent on which threads happen to sleep and when.

One argument for accepting a distorted mechanism is that if both baseline and test variants suffer the same distortion, relative comparisons still reveal which is faster. The argument holds only when the distortion is uniform across variants. It isn't. Different traversal orders produce different message counts, different pipeline depths, and different concurrency patterns, and the per-message-sleep mechanism interacts differently with each. Relative comparisons under this mechanism can reverse direction depending on how the delay interacts with each algorithm's specific pipeline.

The lesson is that the problem isn't delay precision or the specific sleep implementation. It's the architectural choice to inject delay as a per-message serial sleep. No amount of precision-tuning fixes that. The fix is to model the network as a byte-level wire with bandwidth and latency, applied to the stream rather than per message.

### What this means for the design

The benchmark must simulate realistic network conditions at the right level of abstraction. Not faithfully enough to predict absolute cluster times (that's too hard on a laptop), but faithfully enough that the *trend* of a change — "this makes reconnect faster" — transfers from benchmark to cluster.

Our working definition of success:

> If a change makes the benchmark 20% faster, the same change should make a real cluster reconnect roughly 20% faster.

Not exactly 20%. But not 20% in the opposite direction. Not 20% faster in the benchmark and 3% slower in production because speculation dynamics differ. "Roughly directionally consistent" is the bar.

## Trend transfer: what transfers from laptop to cluster

The benchmark is specifically designed to make changes in *reconnect code* transfer predictably from laptop to cluster. This includes:

- Traversal order algorithms.
- Synchronizer logic (teaching and learning sides).
- Thread pool sizing for teacher receive, learner send, and learner receive tasks.
- Request-response handling and pipeline coordination.
- Flush batching within the reconnect flusher.
- Flow control and feedback mechanisms.

For these changes, the laptop tells you reliably whether a change is a **win**, a **loss**, or **neutral**. Magnitude transfers reasonably but not exactly: a laptop improvement usually produces a meaningful improvement at cluster scale, though the exact percentage may differ.

**The benchmark is not designed for MerkleDb configuration changes, index layout changes, or storage-layer experiments.** These interact with cache sizes, disk access patterns, and index structures in ways that vary substantially with state size. A MerkleDb layout change that helps at 100M may behave very differently at 10B where cache hit rates collapse. Such changes need direct cluster measurement; the laptop cannot predict them.

Two residual caveats within the supported scope, worth knowing but not workflow-disrupting:

- **Thread pool sizing** where the optimal count depends on how much disk latency there is to hide. The optimal may differ between laptop (caches warm) and cluster (caches partially cold). The laptop tells you a change *works*; the cluster tells you the best specific values.
- **Flush batch sizes** where the optimum shifts with absolute data volume. Same pattern: laptop picks direction, cluster tunes magnitude.

## The workflow

The benchmark is a filter, not a predictor. It lets you eliminate bad ideas cheaply so that expensive cluster time is reserved for ideas that survived cheap validation.

**Use the local benchmark for iteration.** Private to the engineer's workstation, no DevOps coordination required. Try a change, see whether it helps, drop a bad direction, refine a good one. Cycle time: tens of minutes to an hour.

**Use a real two-node cluster reconnect for final validation.** Confirm that laptop results hold at production scale before merging. Cycle time: hours to days, plus coordination cost.

The laptop is an order of magnitude faster than cluster validation and requires no other people or systems. A successful laptop → cluster loop replaces what today is "always go straight to cluster, because the laptop benchmark isn't trusted."

Running the benchmark itself on cluster-class hardware (as opposed to a real two-node cluster reconnect) is possible but specialized — typically only useful when laptop RAM is the constraint, which for state sizes the laptop benchmark targets shouldn't happen.

## What we chose to simulate

The benchmark runs on an engineer's laptop — realistically an M-series MacBook Pro or similar — and produces results that transfer to cluster production. There's a fundamental gap between these two environments: the laptop is one machine, one JVM, one disk; the cluster is two separate hosts with their own CPU caches, garbage collectors, operating systems, and a real network between them.

We can't close this gap entirely. But we can close the parts that matter most for *direction of change* transferring correctly. Here's what we chose to simulate and why.

### Network: simulate, because it matters most

As established above, network latency drives the speculation dynamic. We simulate:

- **One-way latency** — how long a byte takes to travel from one side's socket to the other's readable buffer. Typical data-center values range from 50 to 500 microseconds; cross-region can be tens of milliseconds.
- **Bandwidth** — how many bytes per second can flow. A single TCP flow in a data center typically sustains between 1 and 10 gigabits per second depending on hardware and network configuration. The specific value to model is a configuration parameter, not a fixed assumption.
- **Backpressure** — the fact that a sender cannot outrun a receiver indefinitely. TCP limits the amount of sent-but-not-yet-read bytes via its sliding window; the benchmark reproduces that pressure so senders naturally slow down when receivers are slow.

Without these, the "network" on a laptop is effectively instantaneous and infinitely fast, which is why speculation behavior goes wrong. Applying bandwidth and latency at the byte level (not per message) also avoids turning the pipeline into a scheduler-gated path, the way the current per-message sleep mechanism does.

We do *not* simulate:

- **Jitter and tail latency** — real networks have heavy-tailed delay variance: occasional big delays from packet reordering, buffer bloat, or retransmits. Modeling these faithfully would introduce unpredictable variance into benchmark runs, making it harder to tell whether a measured difference is due to the change under test or due to a random tail event. For A/B comparisons, stable constant latency is more valuable than realistic variable latency. Future work if reconnect tail behavior becomes worth studying.
- **Packet loss and retransmission** — not relevant inside a data center under normal conditions.
- **TCP congestion control** — we're modeling observed behavior, not the mechanisms that produce it.

### Teacher workload: simulate, because it affects response timing

In production, the teacher is a live consensus node. While serving reconnect, it's also processing transactions: applying them to its virtual map, periodically making a copy of the map (which triggers flush and hash cycles through the virtual pipeline), and so on. This load contends with reconnect work for CPU, memory, and allocation rate.

If we don't model this, the benchmark has a teacher that's 100% dedicated to answering reconnect requests — faster than any real teacher would be. Its responses arrive sooner than reality, which *feeds directly back into the speculation issue*. Fast teacher responses on top of a fast network means the learner's in-flight speculation accumulates further before any response arrives to prune it.

We simulate the teacher workload as real `put`, `remove`, and `get` operations on the teacher's mutable map copy, in a configurable mix that reflects typical consensus workload, at a configurable transactions-per-second rate, with periodic `copy()` calls to mimic consensus rounds advancing. Reconnect itself reads from a detached snapshot taken at reconnect start, so the workload doesn't change what reconnect sees — it only adds realistic resource contention.

The specification portion describes the workload's specific parameters.

We do **not** simulate a learner workload. In production, a learner that's reconnecting is dedicated to reconnect — it isn't processing new transactions during that time. The learner-side should do only reconnect work, which is what the benchmark currently does.

### Divergence patterns: let the engineer pick

Reconnect happens because the teacher's tree has diverged from the learner's. The shape of that divergence — which keys were added, which were modified, which were removed, and where those changes land in the key space — influences which parts of the tree traversal algorithms have to walk. Different patterns stress different code paths, and a change that helps one pattern can be neutral or harmful on another.

Production reconnects mostly look like "teacher advanced by many consensus rounds while the learner was offline." Within that frame, several realistic shapes exist depending on the application's key-assignment scheme and workload:

- **Uniform** modifications and additions spread across the whole key space. Matches workloads with broadly-distributed transaction traffic.
- **Additions appended at the end** of the key range. Matches systems with monotonically-assigned keys (e.g., auto-incrementing IDs) where new entities land at one end of the range.
- **Additions at the start** of the key range. Less common but real — for instance, systems that recycle small IDs or where "start" corresponds to recently-created entities.
- **Teacher has fewer keys than the learner** (removals dominate). Unusual in production but physically possible; it also exercises a distinct code path on the learner, which cleans up old leaves that fall outside the teacher's leaf-path range.

Rather than hard-coding one pattern, the benchmark lets the engineer configure divergence per operation type and per region. Patterns compose: an engineer can set additions to be appended at the end and modifications to be uniformly distributed, reflecting a realistic mix. The default is uniform-everywhere (matching current behavior), but engineers evaluating a change should run it against multiple patterns — at least uniform plus additions-at-end — to check that wins transfer across scenarios.

The specification portion describes the pattern parameters.

### Disk I/O: do not simulate, because it's already real

Both laptop and cluster nodes have real disks doing real reads and writes during reconnect. An M-series laptop's internal SSD is actually faster than some cluster-node disks in raw numbers, but they're within the same order of magnitude.

MerkleDb absorbs most reconnect reads through its in-process caches (default 1M-entry leaf record cache, 262K-entry hash chunk cache). Learner writes go through a batched flusher on a background thread. With a realistic network model in place, per-operation work is dominated by network delivery time, not disk time. Simulating disk delay on top of a real disk doesn't make the laptop more cluster-like — it just makes the laptop slower than the cluster in some unprincipled way.

The one place this might matter is changes specifically targeting disk I/O patterns (read batching, prefetching, cache use). For such changes, the laptop may underestimate the benefit because laptop disk is fast. But storage-layer changes are outside this benchmark's purpose. Reconnect-code changes don't alter disk access patterns enough to matter.

### Reporting: add richness, because message-count patterns matter

Current benchmark output is just a wall-time number. That's not enough given the speculation issue. A change that makes wall time 20% better but sends 50% more clean responses is a red flag — it may be succeeding locally by over-speculating.

We add first-class reporting of:

- Messages sent teacher → learner and learner → teacher.
- Clean vs. dirty responses (the clean/dirty ratio is the speculation-waste signal).
- Bytes transferred in each direction.

Anyone evaluating a reconnect change should watch these alongside wall time.

Stage-level timing (init, tree traversal, flush, finalize) and finer-grained profiling (hot methods, allocation hotspots) are deliberately out of scope for the benchmark's own output. Stage timings are already visible in reconnect logs via `ReconnectMapStats` and log markers; CPU and allocation profiles are best gathered with dedicated tools (JFR, `async-profiler`) when a specific wall-time result warrants deeper investigation. The benchmark's job is end-to-end wall time plus message-pattern metrics; deeper analysis has better tools.

## What we chose NOT to address, and why

Being explicit about what's out of scope is as important as what's in.

### Same-JVM teacher and learner

In a real cluster reconnect, teacher and learner are on different physical hosts. Each has its own operating system, its own Java process, its own garbage collector, its own CPU cache hierarchy. A GC pause on the teacher doesn't pause the learner. A memory allocation on the teacher doesn't compete with the learner's allocations.

In the benchmark, they're in the same JVM. This causes:

- **Shared garbage collector** — a stop-the-world pause triggered by teacher workload freezes the learner too. In production, that wouldn't happen. The benchmark slightly over-reports learner-side cost because of this.
- **Shared CPU cache hierarchy and memory bandwidth** — teacher and learner threads run on the same cores in the same process, so they compete for L1/L2/L3 cache space and memory channels. On a multi-socket server (let alone two separate hosts), they wouldn't.
- **Shared OS scheduler** — threads from both sides compete for cores.

Notably, what is *not* shared between teacher and learner in this setup is MerkleDb's in-process caches. Each `VirtualMap` owns its own `MerkleDbDataSource` instance with its own `leafRecordCache`, its own `hashChunkCache`, and its own off-heap indices. The teacher and learner read from different files on disk; there's no channel by which the learner's reads warm the teacher's caches or vice versa. OS-level cache warmth mostly comes from each side's own repeated reads across iterations (discussed next), not from cross-contamination between teacher and learner.

Fixing the shared-JVM limitations requires running teacher and learner in separate processes — a substantial infrastructure investment. Given that the speculation dynamics we care most about are captured by the network model, and same-JVM overhead tends to produce *uniform* degradation across algorithm variants rather than *variable* degradation, we've chosen to accept this limitation. Documented, not fixed.

### Page cache eviction between runs

After the first reconnect iteration, the teacher's and learner's data files are warm in the OS page cache. A second iteration gets cache hits. In production, reconnect happens infrequently and typically after some state divergence, so caches are partially warm but not perfectly so.

On Linux we could drop page caches between iterations; on macOS the equivalent is harder. Rather than add OS-specific workarounds, we accept that later iterations may be slightly faster than the first. For A/B comparisons this washes out, which is what we care about.

### Portable CPU affinity

Java doesn't expose thread-to-core affinity portably. We could use native calls on Linux, but not on macOS. Rather than add OS-specific code to partially mitigate a problem we've already accepted (shared-CPU), we skip affinity entirely.

### Jitter

Already discussed under network simulation. The short version: realistic jitter would undermine A/B comparison stability, and the benchmark's value comes from comparing variants. Future work if reconnect tail-latency analysis becomes needed.

### Absolute cluster time prediction

The benchmark does not predict how long reconnect will take in the cluster. That's a harder problem, requires cluster-specific calibration, and isn't the team's need. Trend transfer is the need.

## Narrative summary

The benchmark's purpose is: **tell an engineer, in tens of minutes, whether their reconnect change is likely a win, a loss, or neutral — such that the answer transfers to a real two-node cluster reconnect.**

The main thing that makes this hard is the speculation dynamic, which requires modeling realistic network conditions — at the byte level, not as per-message sleeps. The second thing is teacher workload, which affects teacher response timing and therefore also affects speculation. Divergence patterns are a third axis: the shape of teacher-learner divergence affects which code paths reconnect exercises, and an engineer should validate their change against multiple patterns. Everything else either follows from these decisions, or is an honest limitation we've chosen to accept.

The scope is deliberately narrow: reconnect-code changes. Storage-layer changes are out of scope and need cluster measurement.

---

# Specification

This portion covers the implementation design. Section 1 sketches the architecture. Sections 2–4 detail the three tunable components (network model, workload model, divergence patterns). Section 5 is a parameter reference. Sections 6–7 cover lifecycle and reporting. Sections 8–9 summarize limitations and deferred work.

## 1. Architecture

### 1.1 The layer stack

A reconnect in the benchmark has four distinct layers, stacked from "closest to application logic" to "closest to the wire":

```

┌──────────────────────────────────────────────────────────────┐
│ Reconnect application logic                                  │
│   TeacherPullVirtualTreeView, LearnerPullVirtualTreeView,    │  REAL
│   traversal orders, sync tasks, flushers                     │
├──────────────────────────────────────────────────────────────┤
│ Reconnect framework                                          │
│   TeachingSynchronizer, LearningSynchronizer,                │  REAL
│   AsyncInputStream, AsyncOutputStream                        │
├──────────────────────────────────────────────────────────────┤
│ Network simulation                                           │
│   Bandwidth throttle, one-way latency, in-flight bytes       │  SIMULATED
│   limit. Wraps the underlying socket streams.                │
├──────────────────────────────────────────────────────────────┤
│ Transport                                                    │
│   Real loopback TCP socket (preserves serialization costs)   │  REAL
└──────────────────────────────────────────────────────────────┘
```

Everything above the network simulation layer is unchanged real code — the same `LearningSynchronizer`, `TeachingSynchronizer`, traversal orders, and async streams that run in production. Everything below is a real TCP loopback socket, preserving real serialization and deserialization costs.

The network simulation layer sits between these two real layers. It is the only thing the benchmark does differently from production, plus the teacher workload (which runs alongside, not in the stack).

The teacher workload is orthogonal to this stack. It runs on the teacher's mutable map copy, which reconnect does not read from (reconnect reads from a detached snapshot taken at reconnect start). The workload affects reconnect only through shared JVM resources — CPU, heap, GC.

### 1.2 What runs where

The benchmark harness creates one JVM. Inside that JVM:

- **Teacher side**: a `VirtualMap` mutable head that the workload drives; a detached snapshot that the teacher synchronizer reads from; teacher synchronizer threads (receive tasks, async output, async input).
- **Learner side**: a `VirtualMap` containing the learner's starting (behind) state; learner synchronizer threads (send tasks, receive tasks, async streams, the reconnect hasher, the reconnect flusher).
- **Network simulation**: two throttled stream wrappers — one for teacher→learner direction, one for learner→teacher.
- **Workload**: a small pool of worker threads executing put/remove/get operations against the teacher's mutable head, plus one coordinator thread responsible for `copy()` cadence.
- **JMH framework threads**: one thread per `@Benchmark` invocation, plus fork/warmup machinery.

All of these share one heap, one GC, one set of CPU cores, one disk. That's the fundamental same-JVM limitation, already discussed in the narrative.

### 1.3 How the existing code is reused

Whenever possible, the benchmark reuses production classes unchanged:

- `LearningSynchronizer`, `TeachingSynchronizer`: used as-is. These are the code under test.
- `TeacherPullVirtualTreeView`, `LearnerPullVirtualTreeView`: used as-is.
- `AsyncInputStream`, `AsyncOutputStream`: used as-is. The benchmark no longer subclasses these with delay-injecting variants.
- `NodeTraversalOrder` implementations: used as-is. The benchmark picks one via `VirtualMapConfig.reconnectMode()`, same mechanism as production.
- `VirtualMap`, `VirtualMapLearner`, `ReconnectHashLeafFlusher`, `MerkleDbDataSource`: all used as-is.

The current `BenchmarkSlowLearningSynchronizer`, `BenchmarkSlowTeachingSynchronizer`, and `BenchmarkSlowAsyncOutputStream` classes are deleted, as is `LongFuzzer`. They implement the per-message sleep mechanism described in the narrative as unsuitable; nothing in them is salvageable.

`PairedStreams` is replaced with a new paired-streams implementation that wraps the socket streams with the network simulation layer described in §2.

`StateBuilder` is expanded to support divergence patterns (§4). `MerkleBenchmarkUtils.hashAndTestSynchronization` is simplified to remove its delay parameters and its selection between "slow" and "fast" synchronizer variants — there's only one path now.

## 2. Network model

This is the novel part of the design. It replaces the `Thread.sleep`-based per-message delay in the current benchmark with a proper wire-level simulation.

### 2.1 Goals and non-goals

The network model must produce three observable effects:

1. **A byte written at time T becomes readable on the other side at time T + latency.** Latency is applied on the *reader* side, not the writer; delaying writes doesn't change when bytes actually travel.
2. **Throughput in each direction is capped at the configured bandwidth.** Once the sender has sent bandwidth × elapsed-time bytes, subsequent sends block until the time budget replenishes.
3. **A sender cannot accumulate unboundedly-many bytes ahead of a slow reader.** When the in-flight byte count exceeds a configured cap, the sender blocks — this is TCP backpressure.

The model is deliberately simple. It does *not* model TCP slow-start, congestion windows, retransmits, or jitter. It's a best-behaved data-center network with stable latency and a fixed bandwidth ceiling.

### 2.2 Implementation outline

The network simulation wraps the socket's `InputStream` and `OutputStream` on each side. There are four wrapped streams in total: teacher output, teacher input, learner output, learner input. Each direction (teacher→learner, learner→teacher) has a matched pair coordinated by a shared `Channel` object representing that direction.

**A `Channel` holds:**

- The configured one-way latency for this direction.
- The configured bandwidth (bytes per second) for this direction.
- An in-flight byte counter with a configured cap.
- A queue of `(bytes, readableAt)` tuples, where `readableAt` is the wall-clock time at which the receiver is allowed to see those bytes.

**The wrapped `OutputStream` on the sending side:**

- Passes bytes through to the real socket (preserving serialization cost and preventing the in-JVM buffer from growing without bound).
- Records each write with the `Channel`: appends `(bytes, now + latency)` to the queue, increments the in-flight counter.
- Before any write, waits if the in-flight counter is at the cap, OR if the bandwidth budget is exhausted (token bucket).

**The wrapped `InputStream` on the receiving side** follows a specific ordering to keep the simulation authoritative over pacing:

1. Read bytes from the underlying socket into an internal buffer as they physically arrive. This keeps the OS socket buffer drained so the kernel doesn't apply its own backpressure.
2. Park the reader thread until the head queue entry's `readableAt` time has passed.
3. Hand the bytes to the caller and decrement the `Channel`'s in-flight counter, unblocking any sender waiting on the cap.

The ordering matters: reading the socket first ensures bytes are physically on the local side before the simulated latency clock starts running for their delivery; parking before hand-off ensures the application observes the simulated latency; decrementing in-flight only on hand-off (not on socket read) ensures backpressure behaves like TCP's window.

**Socket buffer sizing.** The simulation assumes the OS socket buffer is larger than `networkInflightBytesLimit` so that OS-level backpressure never engages before the model's backpressure does. Default loopback socket buffers on Linux and macOS are in the 64–256 KB range — the same order of magnitude as the model's 128 KB in-flight default — which is too close for comfort. The benchmark explicitly requests a large buffer (4 MB on both sides, both directions) via `Socket.setSendBufferSize` and `Socket.setReceiveBufferSize` at connect time. These are hints; the OS may ignore or cap them. On macOS the kernel-level cap `kern.ipc.maxsockbuf` may need to be raised for large requests to be honored. The benchmark reads back the actual buffer sizes via `getSendBufferSize`/`getReceiveBufferSize` and logs them; if the actual buffer is smaller than `networkInflightBytesLimit`, the benchmark logs a warning because the simulation may no longer be authoritative over pacing.

### 2.3 Rate limiting implementation

The bandwidth throttle is a token-bucket algorithm: tokens accumulate at the configured rate (bytes per second), and each write consumes tokens equal to its byte count. A write that would drop the bucket below zero blocks until enough tokens accumulate.

An existing rate-limiter in the codebase (`org.hiero.consensus.concurrent.utility.throttle.RateLimiter`, used by `TeacherPullVirtualTreeReceiveTask`) may be suitable. If its semantics don't fit (it appears to be request-count oriented rather than byte oriented), a simple byte-oriented token bucket will be implemented inline — the logic is small, on the order of 50–100 lines including tests. This is an implementation decision to be made during coding, not design.

No external dependencies will be added.

### 2.4 Latency implementation

Latency is applied via thread parking on the reader side. `Thread.sleep` is avoided on the critical path; the reader thread parks until the head queue entry's `readableAt` time passes, using nanosecond-precision scheduling.

The important property for the benchmark's correctness is that latency is applied as a *delivery-time shift* on the reader, not a serial sleep on a writer. Even if the park call has tens of microseconds of scheduling overhead, that overhead is absorbed by the reader thread that would otherwise be idle waiting for bytes — it is not on a pipeline's critical path, so it doesn't cascade into wall time the way the current mechanism's writer-side sleep does.

Realistic cluster latencies are well above any scheduler-resolution floor, so precision is not a concern in practice.

### 2.5 Symmetry and independence

Teacher→learner and learner→teacher are independent channels with independent configuration. In principle they can have different bandwidth, different latency, different in-flight caps. In practice the defaults use the same values for both directions, modeling a symmetric network link. Asymmetric configuration is supported for testing scenarios where, for example, the teacher's upload bandwidth is the constraint.

### 2.6 Zero-delay mode

Setting network latency to 0 and bandwidth to effectively unlimited produces behavior equivalent to unmodeled loopback — useful for isolating non-network effects of a change. The wrapped streams still function correctly in this degenerate case; they just don't introduce any delay. This mode is explicitly for diagnostic use ("is this change's impact network-sensitive or not?"), not for normal benchmark runs. Normal runs set realistic network parameters.

## 3. Workload model

The workload drives transaction load on the teacher during reconnect to produce realistic teacher-side resource contention.

### 3.1 Operations

The workload performs a mix of three operations on the teacher's mutable head `VirtualMap`:

- **`put`**: either updates an existing key (80% of puts by default) or adds a new key (20%). Existing-key updates are chosen from the current key range; new keys use indices beyond the current range to avoid accidental updates.
- **`remove`**: removes an existing key. `VirtualMap.remove` triggers tree restructuring (moving the last leaf into the removed position), which is a qualitatively different stress from put.
- **`get`**: reads the value for a randomly chosen key. Exercises the read path without mutating the tree.

The three are mixed according to configurable ratios. Default mix is a placeholder pending calibration against production telemetry.

### 3.2 Copy cadence

Every N operations (configurable), the workload calls `copy()` on the teacher's mutable map, acquiring the new mutable head and letting the previous head go through the virtual pipeline for hashing and flushing. This mimics consensus rounds advancing.

N is counted across all operation types (put, remove, get), not just mutations. This matches production where the round boundary is based on transaction count regardless of whether individual transactions are reads or writes.

### 3.3 Threading, rate limiting, and coordination

The workload uses a small pool of threads (configurable, typically 4–8) executing operations concurrently. A token-bucket rate limiter caps total operations per second at the configured TPS. Each worker thread picks an operation type according to the configured mix, then executes it.

`VirtualMap` enforces a single-writer invariant on its mutable head, and `copy()` flips the map from mutable to immutable. Calling `copy()` while a worker is inside `put`/`remove`/`get` is not safe — it produces assertion failures on assertions-enabled builds and risks inconsistent state otherwise. Concurrent mutations and a `copy()` call must be serialized.

The benchmark uses a **pause-and-swap** protocol to serialize these safely:

1. A single coordinator thread tracks operation count. When the count reaches N, the coordinator signals workers to pause and waits on a phaser (or equivalent rendezvous primitive) for all workers to reach a safe point between operations.
2. The coordinator calls `copy()` on the current mutable head and publishes the new mutable head via an `AtomicReference<VirtualMap>` that workers will read.
3. The coordinator releases the phaser. Workers resume, each acquiring the new mutable head reference before their next operation.

Workers acquire the mutable head reference from the `AtomicReference` once per operation, call the operation, and then check the phaser state before starting the next one. At 2000 TPS and 1000 operations per round, `copy()` happens roughly twice per second; the pause-and-swap overhead is small relative to each round's work.

Assertions-enabled builds should additionally verify that no worker is mid-operation when `copy()` is invoked, catching any regression in the coordination protocol.

### 3.4 Warmup

The workload starts in `@Setup(Level.Invocation)`, before JMH begins timing the `@Benchmark` method. It runs for a configurable warmup duration before reconnect begins, so that the teacher has reached a realistic steady state: some copies completed, some flushes in progress, heap in a post-young-GC state, JIT warm on workload hot paths.

The warmup duration should be long enough for the virtual pipeline to complete several copy→flush→hash cycles and for MerkleDb's background compaction to have begun (compaction typically kicks in after several accumulated flushes). With the default TPS and operations-per-round, a 10–15 second warmup produces roughly 20–30 rounds and at least one compaction cycle. Engineers running with different TPS or operations-per-round values should adjust the warmup accordingly. The default should be treated as a starting point, not a target.

When the `@Benchmark` method returns (reconnect completes), the workload is signaled to stop, and the JMH invocation-level teardown cleans up workload threads.

### 3.5 Interaction with reconnect reads

Reconnect reads from a detached snapshot (`VirtualMap.detach()`), not from the mutable head. The workload writes to the mutable head. These are fully independent data sources at the MerkleDb level — `detach()` creates a separate `MerkleDbDataSource` instance from a snapshot.

Contamination is therefore limited to shared JVM resources:

- CPU contention between workload threads and reconnect threads.
- Heap allocation pressure from workload, affecting GC for all threads.
- OS page cache and disk I/O contention on the same file system.

This matches production behavior, where the two copies are logically isolated but share the process. No explicit synchronization between workload and reconnect is needed.

### 3.6 Reproducibility

The workload uses a `Random` seeded from a configurable parameter. All randomness — operation selection, key selection, value generation — flows from this single seed, so runs with the same parameters produce the same workload sequence. This is important for A/B comparisons where workload-induced variance would otherwise muddy results.

### 3.7 Exception propagation

Exceptions in workload threads cause the benchmark invocation to fail loudly. The workload has no silent-failure path: if an operation throws, the exception is captured by the coordinator thread, reconnect is aborted, and the JMH invocation terminates with the captured exception.

This is important because silent workload failure would cause the teacher to appear less loaded than configured, making reconnect look faster than it should — a hidden correctness bug.

## 4. Divergence patterns

Divergence is the mismatch between the learner's starting tree and the teacher's tree at reconnect start. `StateBuilder` constructs this divergence during setup. In the current implementation, all three operations (add, modify, remove) are applied uniformly across the full key range. In the redesigned benchmark, each operation type can be distributed according to a configurable pattern, and the patterns compose.

### 4.1 Per-operation spatial distribution

Three independent distribution parameters, one per operation type:

- `additionPattern` — where teacher-only keys are placed in the tree.
- `modificationPattern` — where modified keys are located.
- `removalPattern` — where removed keys are located (learner has them, teacher doesn't).

Each pattern selects from:

- **UNIFORM** — choose keys uniformly from the current range. Matches a workload where transactions touch keys broadly across the key space.
- **APPEND_END** — additions go beyond the current range (so they increase the teacher's last-leaf-path). Modifications and removals pick from the end-most region of the current range. Matches monotonic-key workloads where new entities land at one end.
- **APPEND_START** — symmetric with APPEND_END but at the low-key end. Matches less common applications where "start of the key range" corresponds to recently-assigned or frequently-touched keys.

These patterns compose. For example: `additionPattern = APPEND_END, modificationPattern = UNIFORM, removalPattern = UNIFORM` models "teacher advanced by appending new keys while ongoing transactions modified arbitrary existing keys."

### 4.2 The teacher-smaller scenario

A separate pattern, not an orthogonal operation distribution: the teacher has fewer keys than the learner, exercising the learner's cleanup-of-old-leaves code path. This triggers `VirtualMapLearner.deleteOldLeavesBeforeNewFirstLeafPath` and `deleteOldLeavesAfterNewLastLeafPath`.

Controlled by a `teacherSmallerShrinkFraction` parameter: 0.0 means "teacher has the same or more keys" (normal scenarios above), positive values mean "teacher has that fraction fewer keys at the top of the range."

**Construction order and composition semantics** (fixed, not left to implementer discretion):

1. Both teacher and learner are built identically with `maxKey` keys in `[1, maxKey)`.
2. Divergence operations are applied according to `additionPattern`, `modificationPattern`, `removalPattern` and their associated probabilities (`teacherAddProbability`, `teacherModifyProbability`, `teacherRemoveProbability`). After this step, the teacher's key set may be larger or smaller than the learner's depending on the mix.
3. `teacherSmallerShrinkFraction` is applied as a final step that trims the top end of the teacher's key range (highest keys) by `shrinkFraction × currentTeacherKeyCount` keys.

`teacherSmallerShrinkFraction` and `teacherRemoveProbability` are distinct mechanisms and do not double-count:

- `teacherRemoveProbability` targets individual keys scattered through the range (teacher has gaps where the learner has keys).
- `teacherSmallerShrinkFraction` trims a contiguous top-end range from the teacher (teacher's `lastLeafPath` is below the learner's `lastLeafPath`).

Both can be enabled together without conflict — they target different keys.

**Worked example.** With `maxKey = 1000000`, `teacherAddProbability = 0.05`, `additionPattern = APPEND_END`, `teacherModifyProbability = 0.05`, `teacherRemoveProbability = 0.00`, `teacherSmallerShrinkFraction = 0.10`:

1. Teacher and learner start at 999,999 keys each.
2. Additions append ~50,000 keys at the end of the teacher, making the teacher's key count ~1,049,999 with keys in [1, 1,050,000). Modifications change values of ~50,000 keys scattered across the teacher's range.
3. Shrink trims ~105,000 keys from the top of the teacher. The teacher ends with ~945,000 keys in roughly [1, 945,000). The learner's highest keys (945,000–999,999) exist on the learner but not the teacher, triggering the learner's cleanup path for those.

### 4.3 Default pattern

The default, in the absence of overrides, is UNIFORM for all three operation types and `teacherSmallerShrinkFraction = 0`. This matches the current benchmark's behavior, so existing runs produce comparable results.

### 4.4 Recommended workflow for evaluating changes

An engineer evaluating a reconnect change should run it against at least two patterns: uniform (the baseline) and additions-at-end (the most common production shape). A change that helps both is a strong signal. A change that helps one but hurts the other is a signal to investigate further before trusting the result. Engineers working on specific code paths (e.g., the teacher-smaller cleanup path) should add the relevant pattern.

Running multiple patterns is manual — engineers invoke the benchmark once per pattern, then compare outputs. JMH's `@Param` mechanism supports this cleanly via `-p` overrides.

## 5. Parameters

Each parameter below lists its type, default, and meaning. Defaults marked `PLACEHOLDER` are provisional values that should be calibrated once the team has production telemetry. Non-placeholder defaults are reasonable for general use.

### 5.1 Inherited from `BaseBench`

Unchanged. The benchmark inherits `numRecords`, `numFiles`, `maxKey`, `keySize`, `recordSize`, `numThreads` as currently specified.

### 5.2 Divergence parameters

Existing parameters (unchanged semantics):

|         Parameter          |   Type   |     Default      |                                Meaning                                 |
|----------------------------|----------|------------------|------------------------------------------------------------------------|
| `randomSeed`               | `long`   | as currently set | Seed for divergence construction in `StateBuilder`.                    |
| `teacherAddProbability`    | `double` | `0.05`           | Probability that each key has a teacher-only addition.                 |
| `teacherModifyProbability` | `double` | `0.05`           | Probability that each key's value differs between teacher and learner. |
| `teacherRemoveProbability` | `double` | `0.05`           | Probability that each key is learner-only (removed from teacher).      |

New pattern parameters:

|           Parameter            |   Type   |  Default  |                                                                                                                                   Meaning                                                                                                                                   |
|--------------------------------|----------|-----------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `additionPattern`              | enum     | `UNIFORM` | Spatial distribution of teacher-only additions. One of `UNIFORM`, `APPEND_END`, `APPEND_START`.                                                                                                                                                                             |
| `modificationPattern`          | enum     | `UNIFORM` | Spatial distribution of modifications. Same values.                                                                                                                                                                                                                         |
| `removalPattern`               | enum     | `UNIFORM` | Spatial distribution of removals. Same values.                                                                                                                                                                                                                              |
| `teacherSmallerShrinkFraction` | `double` | `0.0`     | Fraction by which the teacher's key range is shrunk from the top relative to the learner's post-divergence key count. 0.0 means teacher has at least as many keys as the learner (normal scenarios). Positive values trigger the cleanup-of-old-leaves path on the learner. |

### 5.3 Network model parameters

|              Parameter              |  Type  |      Default      |                                                                                       Meaning                                                                                       |
|-------------------------------------|--------|-------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `networkLatencyMicroseconds`        | `long` | `500`             | One-way latency applied to each byte. 500 µs is a plausible data-center value. Set to 0 for zero-delay diagnostic mode.                                                             |
| `networkBandwidthMegabitsPerSecond` | `long` | `1000`            | Per-direction throughput cap. 1 Gbps is a plausible data-center floor; 10 Gbps is an upper end.                                                                                     |
| `networkInflightBytesLimit`         | `int`  | `131072` (128 KB) | Bytes-in-flight cap per direction. Models TCP send buffer / window. Smaller values produce more aggressive backpressure. Must be smaller than the OS socket buffer size (see §2.2). |
| `networkSocketBufferBytes`          | `int`  | `4194304` (4 MB)  | Requested OS socket send/receive buffer size. The OS may cap or ignore this; the benchmark logs the actual size and warns if it is smaller than `networkInflightBytesLimit`.        |

All are configurable per run via JMH `@Param` or command-line `-p` overrides. Engineers running a latency sweep do so by running multiple invocations with different values.

### 5.4 Teacher workload parameters

|            Parameter            |   Type    |       Default       |                                                                          Meaning                                                                           |
|---------------------------------|-----------|---------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `teacherWorkloadEnabled`        | `boolean` | `true`              | Master switch. When false, the teacher is idle during reconnect.                                                                                           |
| `teacherWorkloadTps`            | `int`     | `PLACEHOLDER 2000`  | Target transactions per second. Includes puts, removes, and gets.                                                                                          |
| `teacherWorkloadOpsPerRound`    | `int`     | `PLACEHOLDER 1000`  | Operations per `copy()`. Drives how often virtual pipeline flush cycles trigger.                                                                           |
| `teacherWorkloadPutRatio`       | `double`  | `PLACEHOLDER 0.30`  | Fraction of operations that are puts.                                                                                                                      |
| `teacherWorkloadRemoveRatio`    | `double`  | `PLACEHOLDER 0.10`  | Fraction of operations that are removes.                                                                                                                   |
| `teacherWorkloadGetRatio`       | `double`  | `PLACEHOLDER 0.60`  | Fraction of operations that are gets. Must sum with put/remove ratios to 1.0.                                                                              |
| `teacherWorkloadPutUpdateRatio` | `double`  | `0.80`              | Within put operations, fraction that update existing keys (rest add new keys).                                                                             |
| `teacherWorkloadThreads`        | `int`     | `4`                 | Worker thread pool size.                                                                                                                                   |
| `teacherWorkloadWarmupMillis`   | `long`    | `PLACEHOLDER 10000` | Time to run the workload before reconnect starts. Should cover enough copy rounds to reach pipeline steady state and begin MerkleDb compaction (see §3.4). |
| `teacherWorkloadSeed`           | `long`    | `0xC0FFEE`          | Seed for the workload's `Random`. Change to produce different workload sequences with the same parameters.                                                 |

The ratios, TPS, operations-per-round, and warmup duration are placeholders. Once the team has production telemetry (operation mix, round transaction volume, effective TPS), these should be calibrated to match. The warmup duration specifically should be tuned so the measured phase begins after the pipeline has reached steady state under the chosen TPS and operations-per-round combination.

### 5.5 JMH configuration

The class-level JMH annotations are:

```java
@BenchmarkMode(Mode.SingleShotTime)
@Fork(value = 1)
@Warmup(iterations = 1)
@Measurement(iterations = 3)
```

**Why `SingleShotTime`** rather than `AverageTime`. `SingleShotTime` is JMH's mode for benchmarks where one invocation is the unit being measured, with no implicit batching or amortization. Reconnect takes minutes per invocation; running it "many times per iteration and averaging" doesn't fit. `SingleShotTime` names what's actually being done: one reconnect per measurement, with the invocation's wall time reported as the result. In `SingleShotTime` mode, JMH runs `@Setup(Level.Invocation)` outside the timed window, which is what we need for workload warmup.

**Why 3 measurement iterations** rather than the previous 7. Three iterations give a usable mean and rough variance at one-third the runtime. Engineers who want tighter confidence intervals for a final pre-merge validation can override via `-i N` on the command line, up to 7 or more.

GC configuration is not pinned via JVM arguments in the benchmark source. Engineers select the GC appropriate to their production configuration via `jvmArgs` on the runner. The existing `-Xmx` argument is a placeholder; real runs should use values appropriate to the state size being tested.

## 6. JMH lifecycle

### 6.1 Trial level (`@Setup(Level.Trial)` / `@TearDown(Level.Trial)`)

Setup:
- Load configuration.
- Build or restore teacher and learner state via `StateBuilder`, using the configured divergence patterns.
- Flush and save both maps.
- Create the teacher's mutable-head copy (for workload) from the immutable teacher map.
- Pre-hash the immutable teacher map (so reconnect doesn't pay hashing cost that production would have paid earlier).
- Register metrics.
- Build the verification array.

Teardown:
- Release maps, close data sources, clean up.

### 6.2 Invocation level (`@Setup(Level.Invocation)` / `@TearDown(Level.Invocation)`)

Setup:
- Reset counters on the benchmark-local `ReconnectMapStats` instance.
- Start the teacher workload (against the teacher's mutable head).
- Sleep for the workload warmup duration to let the teacher reach steady state.
- Return control to JMH, which starts timing and calls the `@Benchmark` method.

The `@Benchmark` method performs the reconnect. When it returns:

Teardown:
- Signal the workload to stop.
- Wait for workload threads to terminate.
- Release the reconnected learner map.
- Report metrics.

The warmup delay happens before JMH starts timing, so it is outside the measured interval. The reconnect itself is the only thing JMH measures.

### 6.3 Notes on lifecycle choices

With `SingleShotTime`, each JMH iteration contains exactly one invocation. Using `@Setup(Level.Invocation)` and `@Setup(Level.Iteration)` would therefore be equivalent in practice, but `Level.Invocation` is more precise about intent: the setup belongs to the specific reconnect call, not the iteration's measurement window.

## 7. Reporting

### 7.1 Primary metric

JMH's standard output remains the primary metric. With `SingleShotTime`, each iteration's result is the wall time of the single reconnect in that iteration. Example:

```
ReconnectBench.reconnect  ss  3  623.400 ± 18.200  s/op
```

(`ss` is the mode column for SingleShotTime.)

### 7.2 Auxiliary counters

Additional metrics are exposed via a JMH `@AuxCounters` state object. Each counter is updated during the reconnect invocation and reported alongside the primary metric.

|         Counter         |                      Meaning                       |
|-------------------------|----------------------------------------------------|
| `msgsTeacherToLearner`  | Total messages from teacher to learner.            |
| `msgsLearnerToTeacher`  | Total messages from learner to teacher.            |
| `responsesClean`        | Count of teacher responses marked clean.           |
| `responsesDirty`        | Count of teacher responses with dirty flag.        |
| `bytesTeacherToLearner` | Total bytes serialized and sent teacher → learner. |
| `bytesLearnerToTeacher` | Total bytes serialized and sent learner → teacher. |

The clean/dirty speculation-waste ratio is computed externally from `responsesClean` and `responsesDirty` when comparing runs. It is not an aux counter itself because `@AuxCounters` is cleanest with integer counters; derived ratios are better computed at analysis time.

**Plumbing.** The production `ReconnectMapMetrics` wires counters through the platform `Metrics` registry, which is not a natural fit for JMH. Instead, the benchmark defines a plain `ReconnectMapStats` implementation that holds atomic longs, passes it into `VirtualMapLearner` and related constructors at invocation-setup time, and the `@AuxCounters` `@State` object reads those atomic longs at the iteration's end. Counters are reset in `@Setup(Level.Invocation)` before each reconnect so no values leak between iterations.

### 7.3 Output format

These counters appear as additional rows in the standard JMH result table:

```
ReconnectBench.reconnect                       ss  3   623.400 ±  18.200  s/op
ReconnectBench.reconnect:msgsTeacherToLearner  ss  3  24350000             #  ops
ReconnectBench.reconnect:msgsLearnerToTeacher  ss  3  24350000             #  ops
ReconnectBench.reconnect:responsesClean        ss  3  19480000             #  ops
ReconnectBench.reconnect:responsesDirty        ss  3   4870000             #  ops
```

This is raw JMH output, not pretty, but self-contained and diff-able between runs. A future improvement could emit a summary table in a more digestible format.

### 7.4 Log artifacts

Detailed per-iteration logs continue to be written to the benchmark's log directory, including `ReconnectMapStats` output and MerkleDb statistics. These are available for deep dives when summary numbers raise questions, and they include the stage-level timing information that the benchmark's own metrics deliberately don't duplicate.

## 8. Known limitations

Consolidated here for easy reference. Each is discussed in more detail in the relevant section or in the narrative portion.

**Same-JVM contamination.** Teacher and learner share GC, CPU caches, memory bandwidth, OS scheduler. Produces uniform pessimism on the learner side; mostly washes out in A/B comparisons. Not fixable without two-process harness. MerkleDb in-process caches are *not* shared — each side has its own.

**Warm-cache later iterations.** After the first iteration, OS page cache and MerkleDb internal caches are warm. Later iterations are slightly faster. Same effect in both variants under test, so A/B comparisons are unaffected.

**No CPU affinity.** Java doesn't support portable affinity. Teacher and learner threads compete on the OS scheduler. Accepted limitation.

**No jitter.** Constant latency only. Real networks have tail events; the benchmark doesn't. Acceptable for A/B comparisons; inadequate for tail-latency analysis.

**Workload defaults are placeholders.** TPS, operation mix, operations per round, and warmup duration are initial guesses. Should be calibrated against production telemetry.

**Thread pool sizing transfer.** Optimal thread counts may differ between laptop (warm caches, less latency to hide) and cluster (more disk latency, cache pressure). Laptop picks direction; cluster tunes magnitude.

**Flush batch size transfer.** Same pattern as thread pool sizing — laptop direction-accurate, cluster magnitude-accurate.

**Magnitude transfer is ballpark.** A laptop improvement of N% typically gives a meaningful cluster improvement but not exactly N%. Direction reliability is the benchmark's value proposition.

**Scale extrapolation is not modeled.** 100M laptop does not predict 10B cluster for changes that affect cache-miss behavior. For reconnect-code changes this is mostly fine; for storage-layer changes it's not, and those are out of scope anyway.

**Parsed-but-not-reported metrics.** Some potentially useful MerkleDb internal metrics (compaction timing, cache hit rates) exist but are not pulled into benchmark output. Future work.

## 9. Future work

Deferred items, in rough priority order:

1. **Workload calibration** — once production telemetry is available, replace placeholder defaults with measured values.
2. **Network jitter** — optional jitter distribution for tail-latency studies, off by default.
3. **Learner flush throttling** — if measurement shows flush is a laptop-vs-cluster divergence point that matters.
4. **Richer reporting** — pretty summary tables, diff tool against baseline runs.
