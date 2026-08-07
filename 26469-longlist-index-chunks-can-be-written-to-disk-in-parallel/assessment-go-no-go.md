# Assessment: is the parallel LongList write the right optimization, and is it a go?

This document answers two questions about the branch
`26469-longlist-index-chunks-can-be-written-to-disk-in-parallel`:

1. Was the optimization done in the most appropriate way, given that not all
   (especially production) LongList implementations benefit?
2. Is it a go or a no-go?

It is based on a multi-agent review of the branch diff, `proposal.md`,
`benchmark-results.md`, the raw JMH JSON still present in the working tree, and
the surrounding production code paths. Load-bearing claims were independently
re-derived from code; where a claim rests on reasoning rather than measurement,
that is stated.

---

## Verdict

**Conditional go — but reframe what is being shipped, and evaluate one cheaper
rival before committing to the parallel machinery as the Disk solution.**

- The implementation itself is sound: no blocker or major defects were found,
  the partition math was independently re-verified as covering every body byte
  exactly once, `P=1` is byte-identical to `main` wherever `main` does not
  throw, there is no executor deadlock, and the test suite genuinely proves the
  proposal's own acceptance criteria. At the merged default (`P=1`) the parallel
  path is provably inert (no range task submitted, no pool thread created).
- The user's central concern is **confirmed, and it is not fixable within this
  design space**: the production-default `LongListSegment` cannot benefit from
  any per-list parallel buffered-write scheme to a single file (root cause
  below). "Not all implementations benefit" is a property of the problem, not a
  flaw of this particular implementation.
- The measured beneficiary, `LongListDisk`, *does* have a production audience —
  a more important one than the proposal states — but the mechanism of its win
  (hiding Disk's own copy overhead) means a **sequential kernel-space copy
  (`transferFrom`/`copy_file_range`) could plausibly deliver the same or a
  larger win at `P=1` with no thread pool, no new public API, and no config
  knob**. The proposal's alternatives tables never consider this option. It
  should be benchmarked head-to-head on the representative Linux host before
  the parallel design is accepted as the Disk solution.
- Independent of the performance question, the branch contains a genuine
  correctness fix (snapshot failure/interruption propagation in
  `MerkleDbDataSource.snapshot()`) that is valuable on its own — but it is
  currently neutralized on the highest-stakes path by an upstream
  `catch (Throwable)` (see "Companion fix" below).

---

## 1. Why Segment cannot benefit — the concern is inherent, not an implementation flaw

Mechanistic root cause, consistent with every measured number:

- **`LongListDisk` is the only implementation with a separable non-write stage
  per chunk.** Its writer does, per 8 MiB chunk: positional read from its
  backing temp file (a *different* inode) into a heap `ByteBuffer`
  (`TRANSFER_BUFFER_THREAD_LOCAL`), then a positional write to the target
  ([LongListDisk.java:414-465](../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/collections/LongListDisk.java)).
  Because the buffer is heap-allocated, the JDK additionally stages both
  syscalls through a temporary direct buffer — two hidden memcpys per chunk. At
  `P=1` one thread strictly alternates read/copy/copy/write; at `P=2` one
  worker's source read overlaps another worker's target write, taking the read
  stage off the serial critical path. That is the entire win. Evidence: Disk
  `P≥2` times *converge onto Segment's `P=1` baseline* (real snapshot: Disk
  405/432/377 ms → 267/290/282 ms vs Segment ~250-285 ms) and the benefit is
  flat from `P=2` through `P=16` — once the read stage is hidden, nothing is
  left to overlap. An adversarial verifier confirmed the direction of this
  mechanism while noting the exact overlap mode (pipelining vs concurrent
  page-cache reads) cannot be distinguished from the data, and that in the
  isolated benchmark the convergence is only partial (Disk `P=2` stays 15-29%
  above Segment `P=1`).
- **`LongListSegment` has nothing to overlap.** Its per-chunk work is a single
  positional write from a `MemorySegment`-backed direct-buffer view — no source
  I/O, no staging copy
  ([LongListSegment.java:298-334](../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/collections/LongListSegment.java)).
  The write side of one regular file is a serial resource: buffered writes to a
  single inode serialize on the per-file exclusive write lock (`i_rwsem` on
  ext4/XFS; equivalent vnode serialization on APFS) — the JDK's lock-free
  positioned dispatch moves that serialization from the JVM into the kernel, it
  does not remove it. And the serial path is already saturated: isolated
  Segment `P=1` moves 7.451 GiB in ~1.42 s ≈ 5.3 GiB/s *including*
  `force(true)`, roughly the dev machine's SSD sequential-write bandwidth; the
  real snapshot already runs three concurrent list writers at baseline.
  Extra threads add only overhead — hence the isolated benchmark's flat 7.6-9.1%
  regression at *every* `P>1` (0/3 blocks faster in all fifteen parallel cells).
- **`LongListDiskSegment` is the controlled experiment proving the model.** Its
  source is also a disk file, but mmap-based — the "read" happens inside the
  write syscall's copy-in, with no separable stage — and it behaves exactly like
  the memory-backed lists (neutral-to-slower at every `P>1`). `LongListHeap` is
  the converse control: it alone has a large CPU stage (per-element
  `AtomicLongArray` gather, ~2.1 GiB/s) and it alone among memory-backed lists
  gains from `P≥3`. Parallelism helps precisely when a non-write stage exists.
- **Linux/NVMe outlook (reasoned, not measured):** a Segment win on the
  production stack would require concurrent buffered writes to one file to
  actually run in parallel (they serialize on `i_rwsem` on ext4/XFS) *and*
  writeback device queue depth to scale with user writer count (it doesn't;
  kernel flusher threads issue it). Expect the pending representative
  confirmation to come back **neutral for Segment, with a small regression
  risk** — and treat any apparent Segment win as an anomaly to root-cause, not
  a confirmation target. EOF-extension serialization was checked as an
  alternative explanation and rejected: Disk grows the identical target file
  through the identical write path and still wins 33-40%.

### The stage-overlap model in one picture

Buffered file writing is a two-hop journey: user memory → page cache (a CPU
copy inside the syscall, serialized per file by the kernel's exclusive
per-inode write lock), then page cache → device (kernel writeback plus the
final `force(true)`, at device speed, independent of writer-thread count).
Threads can never make the writing itself faster; they can only overlap
*other* work with it. An implementation's parallel speedup is therefore
proportional to how much separable non-write work its loop does:

```text
Disk P=1:   [read 1][write 1][read 2][write 2]      write path idle ~50%
Disk P=2:   A: [read 1][write 1][read 3][write 3]
            B: [read 2][ wait  ][write 2][read 4]   write path ~100% busy
Segment:    [write 1][write 2][write 3]             already 100% — nothing to hide
```

Disk gains ~33% because a second worker's source read (a different file;
reads take only a shared lock) fills the write path's idle half; the win
plateaus at `P=2` because the write path is then saturated. Segment/OffHeap
have no second stage. DiskSegment's mmap fuses its read *inside* the write
syscall — disk-backed, yet it behaves exactly like Segment, the controlled
experiment proving the win requires a *separable* stage, not disk residence.
Heap's separable stage is a slow CPU gather (no lock on reading a Java
array), which is why it alone among memory-backed lists gains at `P≥3`.
Corollary: parallelism did not make Disk's write faster — it hid Disk's extra
copies behind the write, so its best case is convergence onto Segment's time,
which is exactly what the data shows (Disk `P≥2` ≈ Segment `P=1`).

### Generality: two walls, only one of them filesystem-specific

- **The lock wall is filesystem-specific.** Exclusive per-inode locking of
  buffered writes holds for ext4, XFS, and (per measured behavior) APFS — but
  ZFS uses range locks (disjoint-range writes to one file genuinely
  parallelize) and XFS takes a *shared* lock for `O_DIRECT` writes. On such
  stacks the branch's threads would truly run concurrently. The production
  stack is pinned (buffered `FileChannel` on ext4/XFS), where the wall stands.
- **The sink wall is universal and binding.** Even where the lock wall falls,
  the stage that parallelizes is the user→page-cache copy, which the
  measurements show is already hidden (see epistemics below); the bytes are
  fixed and the device's write bandwidth plus the `force` barrier are
  thread-count-invariant. Realistic best case on a friendlier filesystem:
  parallel Segment stops being ~7-9% *slower*, without getting meaningfully
  faster.
- **Approach-independence.** Every alternative parallelization scheme either
  hits the lock wall or, having evaded it, parallelizes a stage that was never
  the bottleneck: multiple `FileChannel`s on one file (same inode, same lock);
  `mmap` the target (evades the lock, parallelizes the already-free copy
  stage, still pays full device time at `msync`/force, plus SIGBUS/unmapping
  hazards — rejected in the proposal for those costs); `O_DIRECT`/io_uring
  (evade the lock, save one already-hidden memcpy, at large complexity and
  changed durability/caching semantics); sharding one index into N files
  (separate locks — but that is the parallelism the snapshot already harvests
  across its three lists, the device is still shared, and it is a
  reader-visible format change). Only changes to *what is written or
  promised* move Segment — fewer bytes (sparse-skip, compression) or weaker
  durability — and those are not parallelization. "Not all implementations
  benefit" is therefore a property of the problem, not of this branch's
  particular scheme.

### Epistemics of "Segment is at hardware limits"

The ~5.3 GiB/s figure is arithmetic on verified measurements: 8e9 bytes ÷
1.352-1.498 s block means (reconciled against the raw JMH JSON), timed window
including create, writes, `force(true)` (F_FULLFSYNC on macOS), and close —
i.e. 4.97-5.51 GiB/s = 5.4-5.9 GB/s. The "≈ device write bandwidth" reading
is an *inference* with one weak and three strong legs:

- *Weak leg:* published M3 Max SSD sequential-write figures (~5-7 GB/s,
  capacity-dependent) bracket the computed rate — a hardware-class ballpark
  only; no `fio`/raw-device baseline was run on the host and the SSD capacity
  is unrecorded.
- *Strong legs:* (1) the CPU side cannot account for the wall time — copying
  7.45 GiB into the page cache at tens of GB/s should take ~0.1-0.2 s, not
  ~1.4 s, so the clock is set downstream (writeback + flush); (2) three
  implementations with entirely different source mechanics (Segment, OffHeap,
  DiskSegment) converge on ~1.35-1.76 s for the same output, so the limiter
  is their only shared component, the target-file write path; (3) added
  writers never help any of them at any `P` — the signature of a saturated
  sink.

The conclusion needs only "the limiter is downstream of anything Java threads
can influence," which the strong legs establish without knowing the device's
exact ceiling. Caveat: the sparse fixture writes mostly zero bytes; if the
SSD controller compresses internally (unverified either way), the isolated
absolute number flatters the true ceiling — one more reason isolated
absolutes are quarantined (§4). The Linux campaign should convert this
inference into a measurement via the storage-ceiling control in §7 step 4.

**Conclusion for question 1:** within the chosen design space — parallel
buffered positional writes to a single target `FileChannel`, preserving each
implementation's buffer strategy — the implementation is about as good as it
can be. The reason production-default Segment gets nothing is physics, not
code: it is a saturated single-inode buffered write with no separable stage.
No scheduler, striping, thread-count, or pre-extension variant will change
that; the proposal's own decision not to pursue them is correct. The honest
answer for Segment is "already at hardware limits; leave it alone." What *was*
missed is that the same analysis undermines the necessity of parallelism for
Disk — see §3.

## 2. Who actually benefits in production — better and worse than the proposal says

Verified selection logic: production constructs only `LongListSegment` (default)
or `LongListDisk` (`preferDiskBasedIndices = diskBasedIndices ||
merkleDb.useDiskIndices`,
[MerkleDbDataSource.java:279](../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/MerkleDbDataSource.java)).
Heap, OffHeap, and DiskSegment are never constructed outside tests/jmh. No
config in the repo sets `useDiskIndices=true`.

The nuance the proposal misses — **the synchronous save path uses
`LongListDisk` on every node, including default-configured ones**:
`VirtualMap.createSnapshot()` builds its offline data-source copy with
`offlineUse=true` ([VirtualMap.java:1416](../platform-sdk/swirlds-virtualmap/src/main/java/com/swirlds/virtualmap/VirtualMap.java)),
which flows into the constructor as `diskBasedIndices=true`. So a sync save
writes every index **twice**: once from the live Segment-backed source (neutral
for this branch) and once from the Disk-backed offline copy — the slower of the
two writes and exactly the one this branch speeds up ~25-35%.

- **Async periodic saves** (default: `saveStatePeriod=900 s`,
  `saveStateAsync=true` → ~96/day): live Segment source, measured neutral.
  This is effectively all routine snapshot work — **zero measured benefit in
  default config**.
- **Sync saves** (freeze/upgrade, reconnect, ISS, fatal, genesis first round,
  PCES recovery, or `saveStateAsync=false`): rare, but they sit on the
  ZDT-critical (#25820) shutdown wall clock and the reconnect-teacher pause
  window. At the 50M-leaf benchmark scale the Disk win is ~120-150 ms per
  snapshot; index size scales linearly with state, so at plausible
  mainnet-scale states this extrapolates to low single-digit seconds per freeze
  save (reasoned extrapolation, not a measurement — no in-repo ZDT measurement
  quantifies snapshot share of shutdown yet).
- **Opt-in `useDiskIndices=true` deployments** (low-memory nodes): full
  benefit, but whether any real deployment runs this cannot be answered from
  this repo — it must be confirmed with node operations before being counted
  as value.
- **Caveat on durability of the beneficiary:** `LongListDiskSegment` is the
  ZDT-oriented implementation designed to *eliminate* the freeze-time index
  rewrite via file handoff. It is selected nowhere today, but if it ships for
  upgrades, the main default-config beneficiary of this branch largely
  disappears. Any Disk-only investment should be sized with that in mind.

## 3. The unexamined rival: kernel-space copy for Disk at P=1

The Disk win is **overhead hiding, not new storage parallelism**: Disk `P=1`
is slower than Segment `P=1` on the same fixture by roughly the cost of its own
read-into-heap-and-copy cycle, and `P=2` merely converges it back. That
overhead can be attacked directly, sequentially:

- **Per-chunk `FileChannel.transferFrom(src, position, count)`** — on the
  pinned Linux Temurin 25 this becomes `copy_file_range`/`sendfile` (zero
  user-space copies). Feasibility was adversarially verified against the code:
  every chunk is one contiguous `memoryChunkSize` region in the backing temp
  file (chunks are reordered *across* the file by `freeChunks` recycling, never
  fragmented internally), and the branch's own refactor already parameterized
  the exact per-chunk `(position, count)` clipping needed. Only null chunk
  slots need the zero-fill path. One verified prerequisite: the target must be
  pre-sized (one positional write at `expectedSize-1`) because `transferFrom`
  does not extend the destination file. Notably, the existing comment at
  [LongListDisk.java:422-424](../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/collections/LongListDisk.java)
  dismisses `completelyTransferFrom` for a reason (data rearrangement) that
  only applies to whole-file transfers — and the repo already uses
  `completelyTransferFrom` for the inverse copy on the Disk load path.
  The proposal's alternatives tables never mention this option.
- **Minimal fallback:** switching `TRANSFER_BUFFER_THREAD_LOCAL` to a direct
  `ByteBuffer` removes the two hidden JDK staging memcpys per chunk with zero
  concurrency risk — a one-line experiment worth a `P=1` arm in any campaign.
- These compose with, and are not blocked by, the merged range design: the new
  `writeLongsData(fc, startIndex, endIndex, fileOffset)` hook is exactly the
  contract a `transferFrom`-based override needs. A `transferFrom` win at `P=1`
  would also be immune to the Linux single-inode write-serialization risk that
  hangs over the parallel-pwrite result, and it would benefit from cold source
  files rather than being threatened by them.

If sequential kernel-space transfer matches the parallel win on representative
hardware, the simpler change should ship and the parallel machinery (executor,
interface overload, config knob) becomes unnecessary complexity for this
problem. If it falls short (e.g. cold-cache sources where true read/write
overlap matters), the parallel design earns its surface. That head-to-head is
cheap and belongs in the already-planned Linux campaign.

## 4. Evidence quality — what is solid and what is overclaimed

The raw JMH JSON in the working tree was independently reconciled against every
table in `benchmark-results.md` — all 96 entries match; counterbalancing was
real. Solid: the Disk-mode improvement (restate as **~25-35%**, not "33.0%" —
same-block changes were 25.2/33.9/33.0%), the retraction of the Segment 12.7%
matrix result, keeping default `P=1`, and narrowing to a `P=1` vs `P=2` gate.

Overclaimed or unproven:

- **Segment "no repeatable change in either direction" overreads an
  underpowered test.** With n=15/arm and sd 36.5-67.3 ms on a ~250 ms baseline,
  the confirmation can only exclude effects larger than roughly ±16% — i.e. it
  cannot exclude the 7-9% regression the isolated benchmark shows *consistently*
  (the tightest result in the whole campaign). Pooled means were nominally 6.8%
  worse at `P=2` with a fatter tail (max 457.6 vs 349.6 ms); only the medians
  look equal. Honest phrasing: "no effect detectable above ~10-16%; a small
  `P=2` regression remains unexcluded for the production default."
- **The campaign is not reproducible from the branch as checked in.** All
  real-snapshot decision rows came from a benchmark variant with an
  `indexImplementation` parameter that was deleted; the checked-in
  `MerkleDbSnapshotBenchmark` (`useDiskIndices` param, warmup=1/measurement=2)
  and `LongListSnapshotBenchmark` (P defaults {1,3,16} — missing `P=2`, the
  sole candidate; listSize 100M vs the campaign's 1B) cannot regenerate the
  recorded numbers, and no counterbalanced runner script is checked in.
- **Branch `P=1` vs `main` performance parity is asserted, never measured**
  (positional writes replaced relative writes). Risk is low but the Linux
  campaign should carry a `main`-vs-branch `P=1` arm to close it.
- **The Disk win is established only for page-cache-hot sources on APFS.** The
  Linux gate must include a dropped-cache (cold source) condition and record
  filesystem/mount, since cold sources are more production-representative (and
  should favor the overlap mechanism, so this is a validation, not a threat).
- **The isolated benchmark's fixture is pathologically sparse** — it stores
  one value per 1,048,576-long chunk (~954 real values out of 1e9), so every
  chunk materializes and the full 7.45 GiB file is still written, but the
  source is 99.9999% untouched zeros: memory reads are served from shared
  zero pages, and `LongListDisk`'s backing temp file is physically
  hole-ridden (reads zero-fill without device I/O). Consequences: absolute
  times and *cross-implementation rankings* from the isolated benchmark are
  not production-representative (and SSD-internal compression of zero-heavy
  data, unverified, could flatter them further). What remains valid — and is
  in fact the statistically strongest data in the campaign — is
  *within-implementation scaling across P* with the fixture held constant:
  the Segment 7-9% regression (complete sample separation in every block),
  the Disk plateau shape, the DiskSegment control, and Heap's CPU-stage
  scaling. The docs correctly quarantine it to that diagnostic role, and the
  dense, production-shaped 50M-leaf benchmark carried the decision, so no
  conclusion was contaminated — but the fixture must be reworked before the
  Linux campaign (§7 steps 3-4).

## 5. Code findings (none blocking; fix before or at merge)

1. **`threadCount <= 0` silently writes a truncated header-only file** — only
   `==1` is special-cased; add an `IllegalArgumentException` guard in
   `AbstractLongList.writeToFile(Path, Executor, int)` (mirror in the
   `HalfDiskHashMap` overload). Unreachable from config (`@Min(1)`), reachable
   for direct API callers.
2. **Torn-read window in the parallel coordinator** — `minValidIndex.get()` is
   read three times and `size()` per range
   ([AbstractLongList.java:533,561,563,565](../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/collections/AbstractLongList.java)),
   where `main`'s sequential writer read once. Under the documented weak
   concurrent-mutation contract, a concurrent `updateValidRange` between reads
   can produce overlapping/gapped positional writes (structural corruption)
   instead of `main`'s stale-but-consistent bytes; there is also a reachable
   state (`updateValidRange(-1, x)`) where sequential throws but parallel
   silently writes a shifted body. Fix: hoist both into locals once.
3. **Exception masking in the `finally`-join** — a submission-time failure can
   be replaced by a worker `IOException` thrown from the join; attach the
   in-flight exception as suppressed instead.
4. **Interface addition without a `default`** — `com.swirlds.merkledb.collections`
   is an exported package; a one-line default delegating to `writeToFile(file)`
   removes the external source/binary-compat cost entirely.
5. **No `@Max`/clamp on `longListSnapshotThreadsPerList`** — a config typo
   requests millions of pool threads (`3P` can even overflow int).
6. **Pre-existing `LongListDisk` bug widened at `P>1`** — `fillBufferWithZeroes`
   ignores boundary clipping for absent chunks, so the excluded
   absent-partial-boundary-chunk state (already corrupt sequentially on `main`,
   unreachable via normal production shapes; Segment/OffHeap/DiskSegment clip
   correctly) becomes nondeterministic cross-range overlap under parallel
   writes. File a follow-up bug; fixing it would let the proposal drop its
   byte-identity exclusion.
7. **Wedge topology** (deliberate tradeoff, document it): `awaitSnapshotTasks`
   is uninterruptible with no timeout. The async path is bounded upstream by
   `asyncSnapshotTimeout` (750 s) but the cancel cannot stop in-progress
   writes; the sync path has no timeout at all. Also,
   `MerkleDbDataSource.close()` during an in-flight snapshot can, on its
   5-minute timeout path, close lists while orphaned range workers still hold
   chunk views — a tail-risk use-after-close window `main`'s sequential write
   could not produce.

Test coverage: every bullet of the proposal's minimal gate has a test that
proves what it claims (independently verified, including the latch-controlled
failure/quiescence test and both production index modes at `P=16`). One gap
worth closing **before `P=2` is enabled anywhere**: no test writes a
*restored-from-file* list through the parallel path — the production steady
state after restart, and precisely the `LongListDisk` shape (non-contiguous
recycled chunk offsets) that has the only measured benefit.

## 6. Companion fix required for the branch's clearest default-config value

The failure-propagation hardening in `MerkleDbDataSource.snapshot()` is a real
pre-existing-bug fix (on `main`, a failed snapshot task was logged and the
snapshot reported success). But it currently **does not reach the synchronous
path**: `VirtualMapStateLifecycleManager.createSnapshot()` catches `Throwable`,
logs, and returns normally
([VirtualMapStateLifecycleManager.java:192-211](../platform-sdk/swirlds-state-impl/src/main/java/com/swirlds/state/merkle/VirtualMapStateLifecycleManager.java)),
after which the save is recorded as successful — so a failed *freeze-state*
snapshot is still reported as a success one level up. The async periodic path
does propagate correctly. A companion fix (stop swallowing `Throwable` there,
or propagate a failure signal) is needed for the correctness value to hold on
the path where it matters most, and is arguably higher-value than the parallel
writes themselves.

## 7. Recommended plan

Ordered; steps 1-2 are independent of the performance outcome.

1. **Extract the correctness fixes into their own PR**: the
   `runWithSnapshotExecutor`/`awaitSnapshotTasks` failure-and-interruption
   propagation change, plus the upstream companion fix in
   `VirtualMapStateLifecycleManager.createSnapshot()`. Mergeable value
   regardless of the go/no-go on parallel writes.
2. **Apply the small code fixes** from §5 (guard, hoisted reads, suppressed
   exception, interface `default`, config clamp) and file the `LongListDisk`
   boundary-clipping follow-up bug. Add the restored-list parallel-write test.
3. **Restore reproducibility and fix the isolated harness before the external
   campaign**: check in the counterbalanced runner protocol; align
   `LongListSnapshotBenchmark` params with the documented candidate set (it
   currently lacks `P=2`, the sole candidate, and defaults to 100M longs vs
   the campaign's 1B); rework its fixture to dense population (every index,
   realistic values — a smaller dense list beats a huge sparse one) so the
   backing file is physically real and source memory genuinely resident;
   either re-add the all-five loader behind a benchmark-only flag or mark the
   all-five rows non-reproducible. Do not re-run the sparse form anywhere —
   it answers no remaining question.
4. **Run the representative Linux/NVMe campaign with three arms, not two**:
   `P=1` (branch), `P=2` (branch), and **sequential per-chunk
   `transferFrom` for `LongListDisk`** (plus, cheaply, a direct-transfer-buffer
   `P=1` variant and a `main` baseline arm). Both production modes; warm *and*
   dropped-cache Disk sources; power the Segment arm to bound a change within
   ~±3-5% (the current ±16% cannot exclude the isolated 7-9% regression);
   record filesystem/mount/iostat; add a **storage-ceiling control** — an
   `fio` sequential-write baseline plus a trivial single-threaded writer
   streaming the same byte count to the same filesystem with a final `fsync`
   — so "Segment is at the storage ceiling" becomes a measured fact instead
   of an inference (if the dumb control beats Segment `P=1`, the no-headroom
   analysis in §1 is falsified where it can be seen); include at least one
   concurrent-load scenario. Expected outcomes to pre-register: Segment
   neutral (any Segment win is an anomaly to root-cause); Disk `P=2` win
   transfers; the open question is whether `transferFrom` at `P=1` matches
   it.
5. **Decide on the winner's shape**:
   - If `transferFrom ≈ P=2`: ship the sequential transfer rewrite of
     `LongListDisk.writeLongsData` and **drop the parallel machinery**
     (executor plumbing, interface overload, config knob) — the branch's range
     hook and tests still made this cheap to build and validate.
   - If `P=2` materially beats `transferFrom` (e.g. cold sources): merge the
     parallel design with default `P=1`, document
     `longListSnapshotThreadsPerList=2` as a tuning knob whose benefit is
     confined to Disk-backed writes (sync saves + opt-in disk indices), and
     only then consider flipping the default.
   - Confirm with node operations whether any real deployment runs
     `useDiskIndices=true`; if not, the sync-save path is the entire
     beneficiary and the ZDT epic's direction (DiskSegment handoff, which
     eliminates that rewrite) caps the payoff — coordinate before investing
     further.
6. **Do not pursue** Segment-side parallel variants, gathering writes,
   batching, or striping: three concurrent memory-source writers already
   saturate a single-inode buffered write sink, and the Amdahl ceiling for
   intra-list parallelism (~33% end-to-end, set by the largest list) is only
   reachable when per-writer throughput is overhead-limited, which Segment is
   not. If default-mode snapshot latency ever becomes a target, the levers are
   elsewhere: the sync path writes every index byte twice (temp Segment write +
   final Disk write — hard-linking or reusing the temp files could save more
   than parallelizing the second write), and the reconnect-teacher detach holds
   the pipeline pause across a full index write.

### Documentation corrections to make on this branch

- `benchmark-results.md`: restate 33.0% as ~25-35%; reword the Segment
  conclusion to "no effect detectable above ~10-16%, isolated 7-9% regression
  unexcluded"; note the campaign's non-reproducibility from checked-in code.
- `proposal.md`: add the per-chunk `transferFrom` alternative (with the
  pre-sizing prerequisite) to Design decision 1's alternatives table — it is
  the strongest unconsidered option; correct the resource-bounds note that "no
  source descriptor is added per range" if that route is taken; note that the
  sync save path gives `LongListDisk` a default-config production audience.
- Add `longListSnapshotThreadsPerList` to the `merkledb-compaction.md` config
  table (the branch currently breaks that documented convention), and move the
  proposal artifacts from the repository root into
  `platform-sdk/docs/proposals/<name>/` per the documented proposal flow.
