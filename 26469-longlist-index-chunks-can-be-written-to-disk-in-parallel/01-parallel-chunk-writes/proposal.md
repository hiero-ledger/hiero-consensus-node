# Parallel LongList writes and final-force removal

> **Status:** Design record for the parallel chunk writer implemented on this
> branch and the final-force removal selected for this PR. The production-path
> force removal and its Javadocs are still pending. Experiment results and
> current decisions are maintained separately.
> Use
> [`snapshot-optimization-report.md`](../snapshot-optimization-report.md) for
> the current investigation state.

---

## Summary

This branch adds configurable parallel writing for MerkleDB snapshot index
files using absolute file offsets. Each worker writes a disjoint range of
`LongList` chunks, so output retains the version-3 layout while the target
storage stack gets an opportunity to service multiple writes concurrently.
Parallel output is byte-identical to the one-argument writer for the normal
stable snapshot shapes used by MerkleDB and exercised by the regression test.

The implementation keeps the three existing top-level index tasks and gives
each `LongList` an explicit configured thread count. At one thread per list, the
existing caller writes the complete list sequentially. Above one, the caller
submits up to that many fixed contiguous range tasks to one snapshot-scoped bounded
pool and waits for them; it does not also write a range. All five built-in
`LongList` implementations retain their current source-copy loops and buffer
strategies. The implementation therefore holds those designs constant while
changing the amount of concurrent source and target work. Later diagnostics
confirmed that source representation and cache residency still affect the
measured result.

The completed Linux campaign confirms that parallel writing can improve all
five implementations, although the size of the benefit and the best writer
count vary by implementation and workload. The default remains one writer per
LongList, while higher values are available as an explicit configuration
choice. See
[`linux-benchmark-results.md`](linux-benchmark-results.md).

The no-force campaigns also confirm that the unforced mean was lower in all
280 matched Linux configurations. The current force
does not make the complete signed-state snapshot durable, so its removal is
included in this PR. The remaining storage work is deferred rather than
eliminated. See
[`remove-final-force.md`](../03-remove-final-force/remove-final-force.md).

|      Metadata      |                                                               Entities                                                                |
|--------------------|---------------------------------------------------------------------------------------------------------------------------------------|
| Status             | Parallel writer implemented and measured; final-force removal selected for this PR and awaiting production-path implementation          |
| Designer           | [@thenswan](https://github.com/thenswan)                                                                                              |
| Functional impacts | MerkleDB and VirtualMap snapshot writing                                                                                              |
| Related issue      | [#26469: LongList index chunks can be written to disk in parallel](https://github.com/hiero-ledger/hiero-consensus-node/issues/26469) |
| Related work       | [#25820: Zero-downtime upgrade](https://github.com/hiero-ledger/hiero-consensus-node/issues/25820)                                    |
| Last updated       | 2026-08-28                                                                                                                            |

---

## Purpose and context

### Current snapshot path

The production snapshot call chain is documented in
[`state-snapshot-spec.md`](../../platform-sdk/swirlds-state-api/docs/state-snapshot-spec.md):

```text
StateLifecycleManager
  -> VirtualMap
    -> MerkleDbDataSourceBuilder
      -> MerkleDbDataSource.snapshot()
```

[`MerkleDbDataSource.snapshot()`](../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/MerkleDbDataSource.java)
submits six top-level tasks to its cached snapshot executor. Three tasks write a
`LongList`:

1. `idToDiskLocationHashChunks`;
2. `pathToDiskLocationLeafNodes`; and
3. the `HalfDiskHashMap` bucket index, through
   [`HalfDiskHashMap.snapshot()`](../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/files/hashmap/HalfDiskHashMap.java).

The three lists are therefore already written concurrently, but every list body
is written sequentially. This matters when defining both the parallel-writer
rollback and the new thread limit: the present baseline is as many as three
concurrent index writes, not one.

The other three top-level snapshot tasks write metadata and snapshot data-file
collections. Data-file collection snapshots primarily flush metadata and create
hard links; they do not rewrite all stored data. See
[`DataFileCollection.snapshot()`](../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/files/DataFileCollection.java).

The implementation retains the existing top-level snapshot task structure.
Each LongList caller synchronously joins its own range tasks before its
top-level task completes, so a target is never closed while one of its range
writers is still active.

The implementation also closes a correctness hole at the existing top-level
task boundary. `MerkleDbDataSource.snapshot()` records the first failure from
its six submitted tasks, waits for every task even when the caller is
interrupted, and then propagates the failure or interruption. This small change
is required before an end-to-end benchmark can treat a returned snapshot as a
successful result, and it guarantees that accepted snapshot work does not
continue after `snapshot()` returns. As in the existing lifecycle, callers must
not race `snapshot()` with data-source shutdown; rejected task submission during
concurrent `close()` is outside this proposal.

### Current LongList file and implementation behavior

[`AbstractLongList.writeToFile()`](../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/collections/AbstractLongList.java)
creates a new file, writes the header, delegates body writing to the concrete
implementation, calls `FileChannel.force(true)`, and closes the target. The
selected change in this PR will keep the worker-completion and close boundaries
but remove that isolated final force.

The version-3 file consists of:

```text
+-------------------------+-------------------------------------------+
| 12-byte header          | body                                      |
| version + minValidIndex | raw 8-byte entries for [min, size)        |
+-------------------------+-------------------------------------------+
```

`maxValidIndex` is not stored. A reader reconstructs the list's upper bound from
`minValidIndex` and the file size. This proposal does not change the header, the
body encoding, the compact removal of the prefix before `minValidIndex`, or the
reader. For normal stable snapshot shapes, parallel and sequential output must
be byte-for-byte identical; no file-format version change is required.

The five implementations have different sources:

|                                                          Implementation                                                           |                       Source representation                       |                                    Current write behavior                                     |                               Production use                                |
|-----------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------|-----------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------|
| [`LongListHeap`](../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/collections/LongListHeap.java)               | `AtomicLongArray` chunks                                          | Iterates individual indices into a 1 MiB direct buffer                                        | Tests/legacy                                                                |
| [`LongListOffHeap`](../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/collections/LongListOffHeap.java)         | Direct `ByteBuffer` chunks                                        | Writes chunk views sequentially                                                               | Tests/legacy                                                                |
| [`LongListSegment`](../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/collections/LongListSegment.java)         | Shared-arena `MemorySegment` chunks                               | Writes segment views sequentially                                                             | Default production index                                                    |
| [`LongListDisk`](../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/collections/LongListDisk.java)               | Logical chunks mapped to non-contiguous offsets in a backing file | Uses a chunk-sized thread-local buffer to read a full chunk or boundary slice, then writes it | Production when `useDiskIndices=true`                                       |
| [`LongListDiskSegment`](../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/collections/LongListDiskSegment.java) | File-mapped shared-arena segments at fixed offsets                | Writes mapped segment views sequentially                                                      | ZDT-oriented implementation, not currently selected by `MerkleDbDataSource` |

The default `longListChunkSize` is 1,048,576 longs, or 8 MiB per
chunk. The allowed maximum is almost 2 GiB per chunk, and a list can contain up
to 2,097,152 chunks. These limits rule out one submitted task per chunk. The
implementation deliberately retains every implementation's existing buffer
strategy, including the chunk-sized buffers already used by several writers;
their per-worker memory cost is part of the benchmark result.

### Snapshot source stability

The production builder path invokes
[`MerkleDbDataSource.pauseCompactionAndRun()`](../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/MerkleDbDataSource.java);
[`MerkleDbCompactionCoordinator`](../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/MerkleDbCompactionCoordinator.java)
owns the pause/resume and resumes from its `finally` block only after the
synchronous `MerkleDbDataSource.snapshot()` call returns or throws. A direct
call to the public snapshot method does not itself pause compaction. The
synchronous
[`VirtualMap.createSnapshot()`](../../platform-sdk/swirlds-virtualmap/src/main/java/com/swirlds/virtualmap/VirtualMap.java)
path snapshots a detached data-source copy, and the asynchronous path writes the
snapshot after its cache flush has completed. These production paths provide the
source stability on which the existing production `LongListSegment` and
`LongListDisk` writers rely.

The public `LongList` contract remains weaker: a concurrent `put()` may produce
a mixed-version file. This proposal does not promise an atomic snapshot to
arbitrary concurrent callers.

### Goals

- Reduce end-to-end MerkleDB snapshot wall time on storage that benefits from
  queued writes.
- Preserve the version-3 format and all existing readers, with byte-identical
  output between the sequential control and parallel path for normal stable
  snapshot shapes.
- Bound threads, tasks, and channels independently of the number of
  chunks. The implementation deliberately retains current staging-buffer
  types, sizes, and allocation patterns; broader allocation or transfer
  changes remain separate measured decisions.
- Preserve today's three-list concurrency at one configured thread per list.
- Ensure no range worker accesses a source or target after `writeToFile()`
  returns by joining every range task before close.
- Preserve the existing exception behavior where possible, while propagating a
  range task's write failure through the new `writeToFile()` call.
- Let `writeToFile()` return without the isolated final LongList force, while
  preserving worker completion and target close before return.
- Provide a parallel-writer rollback and enough measurement to choose a
  default.

### Non-goals

- Changing the `LongList` file format or rebuilding indexes differently.
- Making arbitrary concurrent mutations produce an atomic `LongList` snapshot.
- Parallelizing snapshot metadata or hard-link creation.
- Replacing `FileChannel` with a native or asynchronous I/O stack.
- Making the complete snapshot directory transactionally atomic. Failed
  snapshot-directory cleanup is broader than this optimization.
- Providing a new whole-snapshot durability guarantee. That would require all
  snapshot files and the published directory to participate in one end-to-end
  synchronization protocol.
- Claiming that removing the LongList force eliminates storage work or produces
  the same percentage reduction in complete-snapshot time.
- Adding duplicate ZDT timing instrumentation; the existing snapshot timing
  work is the measurement context for this change.

## Requirements and acceptance criteria

### Correctness

1. Sequential and parallel writers produce byte-identical files for every
   built-in implementation and normal stable snapshot shape.
2. A file produced in parallel can be loaded by every compatible implementation.
3. Worker ranges cover every body byte exactly once at its intended offset,
   with no gaps or overlaps.
4. On success, every worker finishes before the target channel closes and
   `writeToFile()` returns.
5. A worker failure is not reported as success, and all submitted tasks reach
   quiescence before `writeToFile()` returns.
6. A top-level snapshot task failure or caller interruption is reported only
   after all six accepted snapshot tasks, including their LongList workers,
   quiesce. Concurrent data-source shutdown is outside the supported snapshot
   lifecycle.
7. Both public `writeToFile()` paths close the target without the final force
   after worker completion, and their Javadocs describe the changed return and
   error-reporting boundary.

### Resource bounds

Let `P` be the configured thread count per LongList and `L=3` the current
number of LongList writers in one data-source snapshot:

1. The snapshot-scoped range executor has at most `L * P = 3P` platform
   threads.
2. For `P>1`, submissions and retained range futures are at most
   `L * P = 3P`, plus at most three transient aggregate `allOf()` futures. The
   executor queue is therefore bounded by construction even if its underlying
   queue type is not capacity-bounded.
3. Simultaneously executing range bodies are at most `L * P = 3P`.
4. The experiment does not claim a new staging-memory bound. Each range writer
   mirrors its implementation's current allocation and buffer behavior. Live
   staging can therefore grow with participating threads and configured chunk
   size; Disk thread locals can retain their buffers for the thread lifetime.
   Record this cost with the timing results.
5. Ranges and tasks depend on `P`, not on the number or configured size of
   chunks, except that a list cannot create more non-empty ranges than it has
   active chunks.
6. The optimization uses one target `FileChannel` per index file—at most three
   LongList target channels here—not one per worker. Disk workers share the
   list's already-open positional source channel; no source descriptor is added
   per range.
7. No common pool, unbounded worker count, or one-thread-per-chunk mechanism is
   used.

These are per-`MerkleDbDataSource.snapshot()` bounds. Concurrent snapshots of
different data sources multiply them; that behavior must be represented in the
end-to-end performance campaign.

The `P=1` control submits no range task and invokes each implementation's
canonical body writer once for the complete list.

### Performance

1. A selected default above one must show a reproducible end-to-end snapshot
   wall-time improvement on representative Linux storage and must not cause a
   material regression in either production index mode.
2. The corrected Linux campaign shows that higher writer counts can improve
   every implementation, but the best count and the size of the gain vary by
   implementation and workload.
3. The default therefore remains `1`. Higher counts are explicit operational
   choices for storage where measurements justify the additional resources.
4. Removing the final LongList force must be described as an earlier
   `writeToFile()` return, not as eliminated storage work or an already measured
   complete-snapshot speedup.

Issue #26469 does not specify a numeric performance target. The branch decision
therefore uses the completed measurements together with the safe one-writer
default rather than a threshold invented in this proposal.

## Design decisions

### 1. Use explicit-position writes to one target FileChannel

All worker writes use
`MerkleDbFileUtils.completelyWrite(fc, buffer, fileOffset)`. No
worker reads or changes the channel's shared position.

The Java 25 [`FileChannel` contract](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/FileChannel.html)
permits operations with explicit positions to proceed concurrently, while
leaving actual concurrency implementation-specific. This repository pins
[JDK 25.0.2](../../gradle/toolchain-versions.properties), and its production Linux
image pins
[Adoptium Temurin HotSpot 25.0.2+10](../../hedera-node/infrastructure/docker/containers/production-next/consensus-node/Dockerfile).
In that exact Unix implementation, `FileChannelImpl.write(ByteBuffer, long)`
does not take the shared channel-position lock because
`needsPositionLock()` is false, and `IOUtil.write()` dispatches positioned
writes to native `pwrite(2)`. Several worker calls can therefore enter native
positional I/O concurrently on the provider used by the project.

That does not guarantee concurrent completion or a speedup. The production
container declares its saved-state path as a volume, but the backing filesystem,
mount options, and storage device are deployment-controlled. Filesystem locking,
page-cache behavior, writeback, block scheduling, or the device may still
serialize work. Representative end-to-end measurements remain the authority
for performance.

One channel is preferred because it preserves the current ownership model,
uses one descriptor, and gives the coordinator one close boundary after all
writes.

#### Alternatives

|              Alternative              |                                          Advantages                                          |                                                        Disadvantages                                                         |                           Decision                            |
|---------------------------------------|----------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------|
| One target channel, positional writes | Small change; one descriptor; one close boundary; no shared-position lock on the pinned Unix provider | Filesystem/device completion may still serialize or regress                                                             | **Selected**                                                  |
| One target channel per worker         | May alter behavior on a provider that serializes per channel                                 | More descriptors and cleanup; no expected benefit on the pinned Unix provider                                                 | Not justified by the measurements                            |
| `AsynchronousFileChannel`             | Explicit asynchronous API                                                                    | Unix provider commonly delegates blocking writes to an executor; harder partial-write, buffer-lifetime, and failure handling | Rejected                                                      |
| Memory-map the target                 | Parallel memory copies and one mapped layout                                                 | Very large mappings, explicit unmapping/force concerns, larger behavior change                                               | Rejected                                                      |

### 2. Reuse the existing live bounds

For `P>1`, the implementation preserves the current ordering: it opens the
target and writes the existing live header first. It derives the active chunk interval directly from
`minValidIndex.get()` and `size()`, as the sequential writer does. Each assigned
range uses those same accessors to clip its boundary indices and calculate its
absolute target offset. Production snapshot paths stabilize the source, so the
values remain unchanged in the intended use. This does not make concurrent
mutation atomic.

For a normal non-empty list:

```text
bodyLongCount = size() - minValidIndex.get()
expectedSize  = FILE_HEADER_SIZE_V3 + bodyLongCount * Long.BYTES
```

After writing the header, retain the existing `size() > 0` body gate used by
the one-argument writer. If the permitted valid-range state places
`minValidIndex` at or beyond `size`, the calculated active chunk count is
non-positive and the parallel body returns without submitting a task. This
matches the sequential writer's existing header-only output and prevents a
zero-task partition.

For each assigned range:

```text
startIndex = max(
        minValidIndex.get(),
        (long) firstChunkInclusive * longsPerChunk)
endIndex = min(
        size(),
        (long) lastChunkExclusive * longsPerChunk)
fileOffset = FILE_HEADER_SIZE_V3
        + (startIndex - minValidIndex.get()) * Long.BYTES
```

The active interval is calculated once from `firstChunkWithDataIndex` through
`totalNumOfChunks`. Within each assigned interval, chunk-based implementations
reuse the existing sequential calculations and names:
`firstValidIndexInChunk`, `bytesWrittenSoFar`, and `remainingBytes`. The only
parallel-specific addition is the positional `fileOffset`, which advances as
each chunk is written. Heap retains its existing buffered value loop. All file
offsets use `long` arithmetic.

The all-five byte-identity test keeps both partial boundary chunks populated and
leaves one complete interior chunk absent. This exercises boundary clipping and
zero filling while comparing one full-range invocation with partitioned
invocations of the same implementation-specific copy loop.

The existing `LongListDisk` source loop does not clip an absent partial boundary
chunk. That public edge state is outside the production snapshot shape and is
not corrected as part of this focused optimization; byte identity for that
state is therefore not an acceptance criterion for this change.

### 3. Let positional workers grow the target

The implementation writes each disjoint body range directly at its
absolute target offset. It does not pre-extend the file. Successful-output
correctness is independent of worker order: once every range succeeds and is
joined, the highest written range establishes the final length and the complete
range partition establishes every body byte.

The `FileChannel` API does not promise overlapping size-changing operations.
Although the pinned provider lets multiple calls enter `pwrite(2)`, filesystem
EOF-extension or block-allocation work may still serialize internally. Initial
writes beyond EOF may therefore expose less overlap than writes within an
existing file. This is a possible performance limitation, not a correctness
requirement.

A one-byte positional write at `expectedSize - 1` could establish the logical
length before workers start; the final-range worker would overwrite that byte.
It would not reserve physical storage or guarantee a speedup. The completed
control and write-path diagnostic use the same growing-file path and did not
identify file growth as a material limiter, so this historical option was
closed without a prototype.

#### Alternatives

|             Alternative              |                         Advantages                          |                                                  Disadvantages                                                   |       Decision       |
|--------------------------------------|-------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------|----------------------|
| Let positional workers grow the file | Smallest focused change; successful output is deterministic | Early size-changing writes may serialize                                                                         | **Selected**         |
| One-byte logical extension           | Removes size changes from worker writes after setup         | Extra write; no physical reservation; failed output can still have the expected logical length; unproven benefit | Closed by measurement |
| Native `fallocate`                   | Can reserve physical space and fail early                   | Non-portable native dependency                                                                                   | Rejected             |
| Write a temporary file and rename    | Stronger publication semantics                              | Broader naming, move, and durability behavior                                                                    | Outside this change  |

### 4. Configure total writer threads per LongList

The configuration is:

```text
merkleDb.longListSnapshotThreadsPerList
```

with minimum and default `1`. It describes total writer threads for each
LongList, not threads added to the existing callers:

- `P=1` takes the existing sequential path. Each of the three top-level callers
  writes its complete list directly, so the current ceiling remains three
  concurrent index writers.
- `P>1` partitions each list into at most `P` ranges and submits every range to
  a snapshot-scoped worker pool. The top-level caller only coordinates and joins
  those tasks; it does not own another range.
- The three lists are independent, so the snapshot can execute up to `3P`
  LongList range writers.

Immediately before the existing snapshot tasks, `MerkleDbDataSource`
constructs one snapshot-scoped fixed platform-thread executor with capacity
`3P`. Each `writeToFile()` joins
all of its range futures before returning, so the existing snapshot completion
barrier also implies that no LongList worker is still using its source or
target. The executor is closed after the snapshot tasks finish. At `P=1`, each
LongList delegates directly to its one-argument writer and submits no task;
fixed-pool workers are created on demand, so the current three-list execution
topology is preserved exactly.

The range executor must be distinct from the executor running the LongList
callers when that caller executor is bounded. Otherwise all caller threads can
block in `allOf().join()` while their range work is queued behind them on the
same saturated executor. MerkleDB therefore keeps the snapshot-scoped range
executor separate from its existing `snapshotExecutor`. The pool has no static
first-configuration-wins state and retains no idle threads between snapshots.

At the default 8 MiB Disk chunk size, the current thread-local transfer strategy
gives 48 participating workers a rough maximum of `48 * 8 MiB = 384 MiB` of
LongList-owned transfer buffers at `P=16`, before JDK/kernel memory. This is not
a total-process retention bound because other long-lived threads may already
retain buffers in the static thread local. At `P=2`, the corresponding rough
per-snapshot maximum is `6 * 8 MiB = 48 MiB`.

| Threads per LongList | Index-writer ceiling | Resource meaning |
|---------------------:|---------------------:|---|
|                  `1` |                    3 | Sequential path and production default |
|                  `2` |                    6 | Small parallel setting |
|                  `8` |                   24 | Commonly near the measured 1B plateau, with more buffers and stacks |
|                 `16` |                   48 | Higher resource and contention ceiling |

A fixed value is preferred over `availableProcessors()` or a CPU percentage.
Storage queue depth, filesystem behavior, and device parallelism do not scale
reliably with reported CPU count, especially in containers.

The bound is per `MerkleDbDataSource.snapshot()` invocation, not node-wide.
Concurrent snapshots of several data sources can therefore create up to `3P`
workers per snapshot. This is deliberately not a node-wide bound.

Each list submits at most `P` fixed ranges. Therefore one longer list cannot
consume writer slots intended by this configuration for the other lists after
its own tasks are running. The measurements did not establish a range tail that
would justify finer work units or a different scheduler.

#### Alternatives

|                Executor ownership                 |                                                                      Advantages                                                                       |                                                                       Disadvantages                                                                        |   Decision   |
|---------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------|
| Snapshot-scoped range pool sized for `3P` workers | Preserves the `P=1` concurrency topology; capacity for `P` workers per list; no threads retained between snapshots; no first-configuration-wins state | Requires passing an executor through the two snapshot call paths                                                                                           | **Selected** |
| Pool per `LongList.writeToFile()`                 | Encapsulated in the list                                                                                                                              | Creates three pool lifecycles and obscures the total snapshot thread count                                                                                 | Rejected     |
| Pool retained by each data source                 | Avoids repeated thread creation                                                                                                                       | Retains idle threads, including on temporary snapshot copies; close-order coupling                                                                         | Rejected     |
| Static node-wide pool                             | Bounds multiple simultaneous data sources                                                                                                             | First-configuration-wins problem, cross-data-source head-of-line blocking, awkward lifecycle                                                               | Rejected     |
| Existing cached snapshot executor                 | No new pool                                                                                                                                           | Couples blocking parents and children; making it fixed can starve parents waiting for children; the LongList worker lifecycle is no longer snapshot-scoped | Rejected     |
| Compaction executor                               | Existing configured bounded pool                                                                                                                      | Compaction tasks can occupy it while blocked by snapshot coordination, creating starvation or deadlock                                                     | Rejected     |
| Common `ForkJoinPool` / parallel streams          | Minimal plumbing                                                                                                                                      | Process-wide CPU-oriented resource; poor ownership for blocking storage I/O                                                                                | Rejected     |
| A bounded number of virtual-thread range tasks    | Same logical concurrency limit; cheap thread creation                                                                                                 | Changes the execution model without removing the need to bound tasks and storage concurrency                                                               | Rejected     |
| Virtual thread per chunk                          | Cheap thread objects                                                                                                                                  | Does not bound device queue depth, task count, or buffers                                                                                                  | Rejected     |

The proposed `IntStream.range(...).parallel()` pattern is a concise way to
stripe loop indices, but it uses the process-wide common `ForkJoinPool`.
Its loop bound does not establish a snapshot-owned I/O-thread budget, and the
three lists would contend with unrelated common-pool work. It also gives weaker
ownership and quiescence control for blocking channel I/O. Running that pattern
inside a custom pool per list returns to the rejected three-pool design.
The measurements did not justify striped ownership, so the implementation
retains contiguous ranges.

The pool creates platform threads with the repository's `ThreadConfiguration`
and a distinct LongList-snapshot name, matching nearby MerkleDB executor setup.

### 5. Partition into bounded contiguous ranges and join all range futures

For a non-empty list with `P>1`, MerkleDB passes its configured per-list thread
count:

```text
rangeCount = min(P, activeChunkCount)
```

The implementation uses quotient-and-remainder partitioning to create
`rangeCount` contiguous, non-overlapping ranges whose chunk counts differ by
at most one. It submits every range with
`CompletableFuture.runAsync(...)`; the caller coordinates and joins but does
not write an additional range.

In concrete terms, divide `activeChunkCount` by `rangeCount`. Every range gets
the quotient, and the first `remainder` ranges get one additional chunk. For 10
chunks and 3 ranges, `10 / 3` gives quotient 3 and remainder 1, producing
`[0,4)`, `[4,7)`, and `[7,10)`. Every chunk belongs to exactly one range, the
ranges stay adjacent in the target file, and their sizes differ by at most one
chunk.

The caller joins a single `CompletableFuture.allOf(...)` before the target
channel closes. The coordinator also joins futures
already submitted if a later submission fails. A checked worker `IOException`
is wrapped at the `CompletableFuture` boundary and unwrapped for the caller.
This is the only new exception translation needed; the first implementation
does not add cancellation, failure ranking, suppression, or a custom task state
machine.

This creates at most `P` ranges and `P` futures per list. Contiguous ranges
preserve target locality and are appropriate because chunks normally contain
equal byte counts except at the two boundaries. The implementation retains that
rationale as a short inline comment near the partitioning code.

#### Alternatives

|                       Scheduling model                       |                                                           Advantages                                                            |                                     Disadvantages                                      |                          Decision                           |
|--------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------|-------------------------------------------------------------|
| Fixed contiguous range tasks and `CompletableFuture.allOf()` | Small, bounded, deterministic, and local; configured threads have one meaning; `allOf().join()` gives a simple quiescence point | A coarse range cannot be split                                                         | **Selected**                                                |
| One task per chunk                                           | Natural load balancing                                                                                                          | Up to 2,097,152 tasks/futures; unacceptable scheduler and memory overhead              | Rejected                                                    |
| Striped/non-contiguous lanes                                 | Spreads each worker across the whole list                                                                                       | Weaker target locality; changes ordering without increasing the configured concurrency | Deferred; current measurements do not justify it            |
| More bounded ranges or dynamic chunk/batch claiming          | Better tail balancing                                                                                                           | More atomic coordination and less locality/determinism                                 | Not justified by the measurements                            |

### 6. Preserve current buffer and source-copy behavior

The implementation changes scheduling and target positioning, not the buffer
strategy. This is deliberate: replacing buffers at the same time would make it
impossible to tell whether a result came from additional writes in flight or
from different allocation, copying, and channel-call behavior.

- **Heap:** retains the current 1 MiB direct staging buffer and index-by-index
  copy loop within each assigned range.
- **OffHeap:** retains direct chunk views, the current zero-chunk buffer, and the
  existing slice/limit pattern within each assigned range.
- **Segment:** retains independent views over the current `MemorySegment`
  source, the same eagerly allocated chunk-sized heap zero buffer, and the
  existing per-chunk write shape within each assigned range.
- **Disk:** keeps the current chunk-sized heap
  `TRANSFER_BUFFER_THREAD_LOCAL`, positional source reads, and current
  per-chunk transfer shape; each participating thread naturally receives its
  own thread-local buffer.
- **DiskSegment:** retains mapped segment views, the same eagerly allocated
  chunk-sized heap zero buffer, and the existing per-chunk write shape within
  each assigned range.

These choices intentionally mean that adding workers can create one existing
staging or zero buffer per participating range writer. With the default 8 MiB
chunk, `P=16` can therefore make this cost visible. This resource growth is one
reason the production default remains one writer.

A bounded direct staging buffer (the earlier 1 MiB proposal) would change
heap-versus-native allocation, buffer lifetime, copying through the JDK, and
the number of source/target channel calls. Current measurements do not justify
that separate change or a configurable staging-size setting.

### 7. Join range tasks before close

Worker lifecycle handling remains local to the write. The implementation
retains the range `CompletableFuture` instances and joins
`CompletableFuture.allOf(...)` before the target channel closes. The join is
also reached when a later task submission fails, so
already-submitted workers cannot outlive the target channel.

The implementation does not cancel or interrupt worker I/O. A worker
`IOException` is represented as
an unchecked completion failure inside the lambda and converted back to
`IOException` at the `writeToFile()` boundary. If any range fails, the normal
try-with-resources path closes the target after the accepted workers finish. As
today, a failed write may leave an incomplete newly-created file;
transactional publication and cleanup are separate concerns.

The LongList method intentionally does not introduce a multi-error ordering
policy, manual suppression, or an explicit caller-interruption state machine.
Those are broader robustness topics, not prerequisites for testing positional
LongList writes.

### 8. Close without the final LongList force

After the pending production change, the successful path will close the target
after all body writers finish and without calling `FileChannel.force(true)`.
Closing completes the Java write calls, but Linux may continue writing cached
file pages to storage after `writeToFile()` returns.

The force selected for removal covers only the LongList index files. The other
snapshot files and the published directory are not synchronized as one
durability operation, so that wait does not make the complete snapshot durable.
Removing it changes the local return and error boundary: a writeback error
reported only by `force(true)` can no longer reach this snapshot call.

This decision is supported by the forced/unforced Linux campaigns. The
unforced mean was lower in all 280 matched configurations. When the
focused benchmark forced the target immediately after return, the total time
was within 1.0% of the forced path. The change therefore defers the remaining
storage work; it does not eliminate it. The effect on complete snapshot time
remains part of the final production-path comparison.

## Changes

### Architecture and components

The flow is:

```text
MerkleDbDataSource.snapshot
  |
  |-- construct snapshot-scoped range executor (3P workers, workers lazy)
  |-- submit six existing top-level tasks
  |     |
  |     |-- LongList A: caller submits/joins up to P ranges --+
  |     |-- LongList B: caller submits/joins up to P ranges --+-- shared 3P-thread pool
  |     `-- HDHM LongList: caller submits/joins up to P ranges --+
  |
  |-- wait for the existing snapshot completion barrier
  `-- close range executor
```

At `P=1`, each LongList takes its sequential path and submits nothing, so the
lazy executor creates no range-worker thread.

No worker pool is stored in a `LongList`, no static executor is introduced, and
the caller-provided executor is never shut down by `LongList`.

### LongList API

The existing method signature remains:

```java
void writeToFile(Path file) throws IOException;
```

The branch adds an overload that accepts a caller-owned executor and the
configured total writer-thread count for this LongList:

```java
void writeToFile(
        Path file,
        Executor executor,
        int threadCount) throws IOException;
```

This is the smallest plumbing for the selected shared-pool design. The API is
retained because the per-list task bound cannot be inferred from the aggregate
executor.

`LongList` declares the overload without a default implementation.
`AbstractLongList`, the only in-repository implementation of `LongList`,
provides the common parallel path. External direct implementations must add the
overload when recompiled; this source-compatibility cost is accepted for the
retained API. For `threadCount == 1`, it uses the sequential body path and
submits no task, preserving the previous file bytes and per-list scheduling but
not the force wait selected for removal. The generalized body now uses
positional writes, so `P=1` is not an exact performance comparison with the
implementation on `main`.

The `threadCount` argument must reach `writeToFile()` because a generic
`Executor` exposes neither its intended parallelism nor this list's task count.
Even `ThreadPoolExecutor.getMaximumPoolSize()` would couple the API to one
implementation and expose the aggregate `3P` pool size rather than the desired
per-list count. The explicit value lets the list bound and partition its work
before submission.

The implementation does not need an `ExecutorService`: for `P>1`, it submits
at most one `CompletableFuture.runAsync(...)` per range to the supplied
`Executor` and joins all of them with `CompletableFuture.allOf(...)`. The caller
does not write a range.

The caller owns the executor lifecycle. MerkleDB keeps it private to the
snapshot coordinator and terminates it only after the top-level snapshot work
has completed.

The corresponding `HalfDiskHashMap.snapshot(...)` overload lets
`MerkleDbDataSource` pass the same range executor to its bucket index. The
existing overload remains compatible and sequential per list.

The overload's contract states that it never shuts the executor down, joins its
range tasks before return, and treats the count as total writer threads for
this list. A bounded caller executor and the supplied range executor must be
distinct so parent tasks cannot saturate the pool while waiting for their own
queued children.

An internal snapshot-write context object could avoid two parameters and expose
less executor policy, but it would add a new public type to an already exported
package. The direct overload is the smaller retained change.

### AbstractLongList structure

`AbstractLongList` contains the common range calculation, bounded partitioning,
task submission, and joining. Sequential and parallel paths call the
same implementation-specific body hook, conceptually equivalent to:

```java
protected void writeLongsData(
        FileChannel fc,
        long startIndex,
        long endIndex,
        long fileOffset) throws IOException;
```

`AbstractLongList` converts chunk ownership into these logical bounds and the
absolute target offset directly in the range-submission loop, then passes
`startIndex`, `endIndex`, and `fileOffset` to the canonical body hook. A
separate `writeLongsDataRange()` adapter is unnecessary. Each built-in
implementation contains one generalized copy of its original source loop; the
one-argument path calls it once for the full body, while the parallel path calls
it once per assigned range. The common coordinator has no per-implementation
capability check or orchestration branch.

### Configuration

The branch adds this property to
[`MerkleDbConfig`](../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/config/MerkleDbConfig.java):

```java
@Min(1)
@ConfigProperty(defaultValue = "1")
int longListSnapshotThreadsPerList
```

The value is not serialized in a snapshot and may be changed between runs. It
controls total writer threads for each LongList snapshot output; it must not
reuse `compactionThreads`, because the workloads have different tuning and
lifecycle semantics.

The implementation retains `1` as the default, preserving sequential behavior
per list. Higher values raise the aggregate ceiling from three writers to
`3P` and are explicit storage-specific choices. The corrected Linux campaign
does not establish one higher value as the universal default.

The implementation adds no arbitrary upper-bound validation. The benchmark
campaign used explicit bounded values; it does not justify a production
annotation that claims a maximum.

Adding a component to the public `MerkleDbConfig` record changes its canonical
constructor descriptor. Configuration-framework consumers remain property
compatible, and the two direct constructor calls in
`MerkleDbCompactionCoordinatorTest` were updated. This proposal does
not add a duplicate legacy constructor with the record's full parameter list:
the current
[`ConfigDataFactory`](../../platform-sdk/swirlds-config-impl/src/main/java/com/swirlds/config/impl/internal/ConfigDataFactory.java)
requires exactly one public record constructor, so such a shim would also
require a configuration-framework change. A separate configuration record
avoids the constructor ABI change but would broaden constructor and
configuration plumbing throughout MerkleDB. The implementation's focused
plumbing therefore explicitly accepts recompilation of direct record callers.
Also note that `@Min` is enforced by the configuration framework, not by direct
`new MerkleDbConfig(...)` calls.

### Public disk format

There is no disk-format change. Existing version-2 and version-3 readers remain
unchanged, and new snapshots continue to use version 3. Byte-for-byte comparison
against the one-thread writer for the tested stable snapshot shape is an
acceptance test.

### Javadocs and implementation comments

The parallel-writer API and scheduler documentation covers the items below.
The pending force-removal edit must also update the `writeToFile()` return and
error boundary:

- `LongList.writeToFile()` and its overload: executor ownership,
  separation from a bounded caller executor, per-list thread/task bounds,
  coordinator-only caller behavior for `P>1`, synchronous quiescence, failure,
  and weak concurrent-mutation semantics;
- `AbstractLongList` and the retained concrete implementations where
  method-level documentation describes implementation-specific source copying;
- the corresponding `HalfDiskHashMap.snapshot()` overload;
- the new `MerkleDbConfig` option, its threads-per-list meaning, and
  the writer-scheduling and staging-buffer rollback at `1`;
- the corrected `snapshotExecutor` comment, which now describes the six
  top-level tasks, noting that the bounded LongList workers use a separate
  snapshot-scoped pool; and
- one focused inline comment explaining that contiguous ranges preserve target
  locality.

### Metrics and logging

No new permanent metric is required. Existing
per-task trace logs and overall snapshot timing provide the high-level result,
and the ZDT measurement work supplies broader startup/shutdown context.

Measurements remain in benchmark output rather than production logging. The
branch adds no per-chunk trace output.

## Test coverage

The branch adds focused coverage for the new successful and failure paths
while reusing the existing snapshot/restore suites:

- one parameterized case for all five built-in implementations writes
  the same stable source through the one-argument sequential writer and
  `P=16`, requires `Files.mismatch()` to return `-1`, asserts the exact
  size, and reopens through value assertions;
- both boundaries are partial and populated, with one complete interior chunk
  deliberately absent. This exercises boundary clipping and zero filling in
  the shared implementation-specific copy loop;
- a `P=1` test verifies that no executor task is submitted and output remains
  byte-identical to the one-argument writer;
- the permitted `minValidIndex >= size` state remains header-only and
  byte-identical instead of entering parallel partitioning;
- a controlled range writer and latches verify that a worker `IOException`
  reaches the caller only after the other submitted range tasks quiesce;
- the existing MerkleDB snapshot/reopen path runs once in each production
  index mode (`LongListSegment` and `LongListDisk`) with `P>1`;
- a pre-created snapshot target verifies that the corresponding top-level
  task's `IOException` reaches the snapshot caller; and
- a pre-interrupted snapshot caller verifies that the method preserves the
  interrupt, reports `IOException`, and leaves all six expected outputs present
  after return.

These checks cover the regressions that would invalidate timings: wrong bytes,
wrong length, unreadable output, broken integration, hidden unfinished work,
or ordinary worker failure. They do not attempt to prove kernel/device overlap
or cover unrelated invalid-state and concurrent-mutation behavior.

The focused `IOException` test covers the parallel worker-failure boundary and
the essential quiescence guarantee together. An overlap-only test and a
separate `RuntimeException`/`Error` matrix are outside this focused change.

### Performance evidence

The focused
[`LongListSnapshotBenchmark`](../../platform-sdk/swirlds-merkledb/src/jmh/java/com/swirlds/benchmark/LongListSnapshotBenchmark.java)
isolates all five implementations without multiplying the unrelated get/put
matrix in the older `LongListBenchmark`.

The corrected Linux campaign used four leaf counts, three chunk sizes, and
multiple writer counts. At one billion leaves and the default chunk size, the
best measured reductions ranged from 3.8% for Segment to 13.1% for Heap; Disk
improved by 8.1%. At five billion leaves, several gains were smaller. No one
higher writer count was best across every implementation and workload.

The benchmark protocol, complete tables, caveats, environment, and raw
evidence are recorded in
[`linux-benchmark-results.md`](linux-benchmark-results.md).

The resulting parallel-writer setting is deliberately simple: one writer
remains the default and preserves the previous writer-thread and buffer use.
Operators can select a higher count when measurements on their storage justify
it.

## Risks and mitigations

| Risk | How it is handled |
|---|---|
| Without the final `force(true)`, a LongList file can be published before Linux writes every cached page to storage. A writeback error reported only by that force will no longer reach this snapshot call. | This is an accepted behavior change in this PR. All range writes still finish and the channel closes before snapshot publication. The current force covers only LongList files; a whole-snapshot durability guarantee would require a separate end-to-end protocol. Update the `writeToFile()` contract to state the new return and error boundary. |
| Removing the force makes `writeToFile()` return earlier but does not remove the remaining storage work. Deferred writeback can overlap or contend with later snapshot work. | The unforced mean was lower in all 280 matched Linux configurations. In the focused run, adding an immediate post-return force brought total time within 1.0% of the forced path. Describe this as earlier return, not higher durable throughput or a measured complete-snapshot speedup. |
| A higher writer count can be slower or use more resources, depending on the LongList implementation, list size, storage, and `LongListDisk` source-cache state. | Keep one writer per LongList as the default and parallel-writer rollback. The Linux matrix found gains but no universal higher count, and the Disk diagnostic confirmed that source-cache state changes the size of the gain. Higher counts remain an explicit environment-specific setting. |
| Parallel writers add threads and per-worker buffers. Concurrent snapshots of different data sources multiply this cost. | The snapshot-scoped range pool has at most `3P` threads and at most `3P` submitted tasks; `P=1` submits no range work. Each list still uses one target channel. If all three lists have at least `P` active ranges and each range allocates an 8 MiB full-chunk buffer, the rough per-snapshot buffer total is 48 MiB at `P=2` and 384 MiB at `P=16`, before JDK and kernel memory. Heap uses a 1 MiB buffer instead. |
| A bad range boundary, worker failure, or interruption could produce an invalid file or let work continue after return. | Workers use disjoint absolute offsets and all accepted work is joined before channel close. The outer snapshot waits for all six accepted top-level tasks. Tests cover byte-identical output and reopening for all five implementations, both production index modes, worker I/O failure and quiescence, top-level failure, and caller interruption. |

## Implementation status

There are no prerequisite PRs. The parallel writer is implemented; the
production-path final-force removal remains to be applied in this PR.

1. **Parallel-writer implementation complete.** The branch adds the
   snapshot-scoped shared pool, positional writes, and fixed contiguous ranges
   while retaining each implementation's source loop and buffers.
2. **Parallel-writer correctness coverage complete.** Tests cover byte
   identity, exact length, restoration for all five implementations, both
   production modes, worker failure and quiescence, and top-level snapshot
   failure and interruption.
3. **Representative LongList measurement complete.** The corrected Linux
   campaign covers all five implementations through five billion leaves with
   equal comparative sampling.
4. **Production setting selected.** The feature is configurable and the
   default remains one writer per LongList. Higher counts are an explicit
   storage-specific choice.
5. **Final-force decision complete; implementation pending.** Focused and
   broad Linux campaigns support removing the isolated force. The production
   path and its Javadocs still need the selected return and error boundary.

## References

- [Issue #26469](https://github.com/hiero-ledger/hiero-consensus-node/issues/26469)
- [ZDT epic #25820](https://github.com/hiero-ledger/hiero-consensus-node/issues/25820)
- [Java 25 `FileChannel`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/nio/channels/FileChannel.html)
- [Pinned project JDK](../../gradle/toolchain-versions.properties)
- [Production Temurin image](../../hedera-node/infrastructure/docker/containers/production-next/consensus-node/Dockerfile)
- [Temurin `FileChannelImpl.write(ByteBuffer,long)`](https://github.com/adoptium/jdk25u/blob/9e3c947043a44ccd3f515db8d4f1c7caf1194796/src/java.base/share/classes/sun/nio/ch/FileChannelImpl.java#L1212-L1271)
- [Temurin Unix position-lock decision](https://github.com/adoptium/jdk25u/blob/9e3c947043a44ccd3f515db8d4f1c7caf1194796/src/java.base/share/classes/sun/nio/ch/NativeDispatcher.java#L44-L50)
- [Temurin `IOUtil` positioned-write dispatch](https://github.com/adoptium/jdk25u/blob/9e3c947043a44ccd3f515db8d4f1c7caf1194796/src/java.base/share/classes/sun/nio/ch/IOUtil.java#L113-L139)
- [Temurin Unix `pwrite` bridge](https://github.com/adoptium/jdk25u/blob/9e3c947043a44ccd3f515db8d4f1c7caf1194796/src/java.base/unix/classes/sun/nio/ch/UnixFileDispatcherImpl.java#L64-L72)
- [Temurin native `pwrite` dispatch](https://github.com/adoptium/jdk25u/blob/9e3c947043a44ccd3f515db8d4f1c7caf1194796/src/java.base/unix/native/libnio/ch/UnixFileDispatcherImpl.c#L94-L102)
- [`LongList`](../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/collections/LongList.java)
- [`AbstractLongList`](../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/collections/AbstractLongList.java)
- [`MerkleDbFileUtils`](../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/utilities/MerkleDbFileUtils.java)
- [`MerkleDbDataSource`](../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/MerkleDbDataSource.java)
- [`HalfDiskHashMap`](../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/files/hashmap/HalfDiskHashMap.java)
- [State snapshot specification](../../platform-sdk/swirlds-state-api/docs/state-snapshot-spec.md)
- [Platform design proposal process](../../platform-sdk/docs/proposals/README.md)
