# Faster reconnect setup for large states

## 1. Motivation

Reconnect of a 1B-account state (about 3.26B leaves) works under 10K TPS load, but only with
`consensus.roundsExpired = 4000` on all nodes (section 7). Each reconnect cycle takes 4–5 minutes, and a node that
reconnects falls behind by that much. It converges only if its lag after the reconnect stays within the peers'
event retention window.

About half of each cycle is setup: copying the MerkleDB data source on the teacher and on the learner, before any
data is transferred. One measured cycle:

| Phase | Duration |
|---|---|
| Teacher copies its data source (pipeline paused) | 80 s: snapshot 29 s + rebuild 51 s |
| Teacher sends signatures | about 4 s |
| Learner copies its data source (pipeline paused) | 67 s: snapshot 32 s + rebuild 35 s |
| Tree transfer | 90–160 s |

The two copies run one after the other: the learner starts its copy only after it receives the teacher's signatures.
Across seven cycles they took 140–180 s together.

This proposal shortens the setup in three independent steps:
1. Copy a data source without writing its indexes to disk and reading them back.
2. Keep the virtual pipeline paused only for the snapshot, not for the rebuild.
3. Start the learner's copy while it waits for the teacher.

A shorter cycle means less lag after a reconnect. That lowers the event retention a reconnect needs, and shortens
the catch-up afterwards.

## 2. The cost of a data source copy today

Three operations copy a MerkleDB data source:
- the teacher, in `VirtualMap.detach()`
  ([`VirtualMap.java:1207`](../../../../platform-sdk/swirlds-virtualmap/src/main/java/com/swirlds/virtualmap/VirtualMap.java#L1207));
- the learner, in `VirtualMap.detachAsDataSourceCopy()`
  ([`VirtualMap.java:1182`](../../../../platform-sdk/swirlds-virtualmap/src/main/java/com/swirlds/virtualmap/VirtualMap.java#L1182));
- writing the reconnected state to disk, in `VirtualMap.createSnapshot()`
  ([`VirtualMap.java:1376`](../../../../platform-sdk/swirlds-virtualmap/src/main/java/com/swirlds/virtualmap/VirtualMap.java#L1376)).

Each copy is a snapshot to disk followed by a rebuild from that snapshot:
- **Snapshot:** writes the in-memory indexes to files and hard-links all data files. 28–32 s.
- **Rebuild:** hard-links the snapshot again, loads the indexes back, and opens a reader for every data file, reading
  its header. 35–51 s.

What a copy moves at this scale, measured from the index files of a saved state:

| Part | Size |
|---|---|
| `pathToDiskLocationLeafNodes` (3.26B entries) | 26.1 GB |
| `idToDiskLocationHashChunks` (1.09B entries) | 8.7 GB |
| HalfDiskHashMap bucket index (2^28 buckets) | 2.1 GB |
| **Indexes per data source** | **37 GB (34.4 GiB off-heap)** |
| Data files | 43K, about 900 GiB, hard-linked, not copied |

The stage of the rebuild that loads the 26 GB leaf index and opens 35.8K readers takes two thirds of it (23–27 s).

`createSnapshot()` is slower, at about 140 s, because its copy uses disk-based indexes (`offlineUse = true`). Its
rebuild copies the index files into `LongListDisk` temp files, and its final snapshot copies them again. It's off the
critical path, but its first snapshot holds the learner's pipeline pause for about 28 s, and pauses on one pipeline
run one after the other.

## 3. Step 1: copy the data source without the disk round trip

### Idea

Add `VirtualDataSourceBuilder.copy(VirtualDataSource source, String label, boolean compactionEnabled, boolean
offlineUse)`. Its default implementation is today's `snapshot()` + `build()`, so other data source implementations
keep working. `MerkleDbDataSourceBuilder` overrides it. With the pipeline and compaction paused, as for a snapshot
today:

1. Flush the hash chunk cache, as `snapshot()` does.
2. Hard-link the completed data files of the three stores into the copy's directory, once.
3. Copy the three `LongListSegment` indexes in memory: allocate a new chunk for each non-null source chunk, and
   `MemorySegment.copy()` into it. Chunks are independent, so the copy runs in parallel. The parallel long list
   writes in [#26557](https://github.com/hiero-ledger/hiero-consensus-node/pull/26557) already split a list into
   balanced, contiguous chunk ranges, one per writer task. The copy can use the same split, executor and thread
   count setting.
4. Build the new `MerkleDbDataSource` from these pieces, instead of loading files. Create its readers with the
   source readers' `DataFileMetadata`, through the existing `DataFileReader(config, path, metadata)` constructor, so
   no file headers are read.

If the source uses disk-based indexes (`merkleDb.useDiskIndices = true`), the override falls back to the default.

### What the copy must carry over

- **Data source:** `validLeafPathRange`, `initialCapacity`, `hashChunkHeight` and the loaded hash digest type.
  The leaf record cache, the compaction coordinator and the garbage scanners start fresh, as after a rebuild today.
- **Each `DataFileCollection`:** `validKeyRange`, the completed files, and `nextFileIndex` set to the highest file
  index + 1, as the rebuild sets it today. Data locations encode file indexes, not paths, so the copied indexes stay
  valid for the linked files.
- **Readers:** `DataFileMetadata` is immutable, so the copy can reuse it. The readers themselves can't be shared,
  because the source closes them when it compacts or closes.
- **HalfDiskHashMap:** the bucket index and `numOfBuckets`. That's what `HalfDiskHashMap.snapshot()` writes. It
  resizes only during a flush, which the pause excludes.

### Why the copy is as consistent as `snapshot()`

The copy reads the same in-memory state that `snapshot()` writes to disk, under the same conditions:
- The pipeline pause excludes flushes.
- Compactors update indexes and delete files only while holding `snapshotCompactionLock`
  ([`DataFileCompactor.java:518`](../../../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/files/DataFileCompactor.java#L518)).
  `pauseCompaction()` takes that lock and finishes the output file in progress. New compaction tasks can't register
  while `pauseCompactionAndRun()` holds the coordinator's monitor.
- `getAllCompletedFiles()` excludes files that are still being written.

Like a snapshot, `copy()` must run inside the pipeline pause and set `snapshotInProgress`, so it can't overlap a
snapshot of the same data source.

### `createSnapshot()`

`createSnapshot()` uses disk-based indexes on purpose: `offlineUse` was added in
[#16090](https://github.com/hiero-ledger/hiero-consensus-node/pull/16090) so that such copies "should use as little
resources as possible". An in-memory copy there would add 34.4 GiB of off-heap memory on the learner, when it already
holds the reconnect data source and, from the next cycle, its copy. Three options:
- **Option A, memory-neutral:** inside the pause, write the source's index chunks straight into the copy's
  `LongListDisk` temp files. That's the parallel `writeToFile()` from #26557, aimed at a different file. It's one
  37 GB write instead of three passes, but the pause stays about as long as a snapshot.
- **Option B, short pause:** inside the pause, make the in-memory copy (a few seconds). Outside it, write the copy to
  `LongListDisk` and free it. This holds 34.4 GiB of extra off-heap memory for about 30 s, against the `offlineUse`
  intent. On the learner, that raises non-reclaimable memory from about 186 GiB to about 220 GiB, out of a 256 GiB
  container limit.
- **Option C, mapped:** inside the pause, take the snapshot as today. Outside it, map the snapshot's index files
  instead of copying them into `LongListDisk` temp files (see "Alternative: map the indexes" below). Like option A, it
  pauses for a snapshot, needs no extra off-heap memory and makes two passes over the indexes instead of three. Unlike
  `LongListDisk`, the copy reads its indexes from memory instead of with a file read per entry.

### Expected effect (estimate)

| Copy | Today | With step 1 |
|---|---|---|
| Teacher (`detach`) | 80–84 s | roughly 10–20 s |
| Learner (`detachAsDataSourceCopy`) | 52–93 s | roughly 10–20 s |
| State write (`createSnapshot`) | about 140 s, 28 s of it paused | about 80 s: about 30 s paused (A, C) or a few seconds (B) |

#26557 on its own speeds up snapshots about 3 times, which shrinks the snapshot half of each copy. The rebuild half
remains, so step 1 still removes most of the copy time.

Peak off-heap memory doesn't change for `detach()` and `detachAsDataSourceCopy()`: today's rebuild already allocates
a second set of in-memory indexes.

### Alternative: map the indexes

Instead of copying the indexes in memory, a copy can keep today's snapshot and map the snapshot's index files with
`FileChannel.map(MapMode.PRIVATE, offset, length, arena)` instead of loading them. Mapping only sets up page tables. An
entry is read from the file when it's first accessed, and a write goes to a private copy of its page, never to the
file. [`LongListDiskSegment`](../../../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/collections/LongListDiskSegment.java),
added in [#25903](https://github.com/hiero-ledger/hiero-consensus-node/pull/25903) for fast upgrades, already maps
index chunks this way, with `MapMode.READ_WRITE`. A `PRIVATE` variant of it can back the copy's three indexes.

Pros:
- Loading the indexes, most of the rebuild, takes milliseconds: about 4,400 mapped 8 MiB chunks per data source.
- Pages the copy doesn't write to are page cache, which the kernel can reclaim. Today a copy takes 34.4 GiB of
  off-heap memory that it can't. A copy that is only read needs no anonymous memory for its indexes.
- Only the pages the copy accesses are read. The teacher reads the entries of the nodes it sends, not all 3.26B.

Cons:
- The snapshot half stays: the indexes are still written to disk, in 28–32 s today and about 10 s with #26557. Step
  1 removes both halves.
- The first write to a page costs a page fault and a 4 KB copy. A copy that is written everywhere ends up with as much
  anonymous memory as today, after 6.4M such faults for the leaf index alone.
- A page that has been evicted from the page cache costs a disk read on its next access, about 100 µs on NVMe.
- The mapping keeps the snapshot's files in use: their 37 GB of disk is freed only when the copy is closed, even if
  the snapshot directory is deleted earlier.

Fit per copy:

| Copy | Fit |
|---|---|
| Teacher (`detach`) | Good. The copy is only read, and lives only for the transfer. Its indexes need no anonymous memory, and only the part the transfer touches is read. |
| State write (`createSnapshot`) | Good, as option C. Flushing the cache snapshot writes to few pages. |
| Learner (`detachAsDataSourceCopy`) | Poor. The copy becomes the node's live data source. Under load nearly every page gets written, during the reconnect's flushes and after it, and the mapping keeps the snapshot's files for as long as the node runs. Step 1's in-memory copy fits better. |

On the teacher, mapping is slower than step 1 by the snapshot write, about 10 s with #26557, unless skipping its
`fsync` makes that write much cheaper (item 3 below). In return, it saves 34.4 GiB of non-reclaimable memory on a
node that keeps handling transactions during the transfer.

Things to get right:
1. **File layout.** Today's index files can't be mapped as they are. The 12-byte header
   ([`AbstractLongList.java:515`](../../../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/collections/AbstractLongList.java#L515))
   puts every value at an offset of 4 mod 8. `LongListSegment` reads and updates values with volatile access and
   `compareAndSet`
   ([`LongListSegment.java:222`](../../../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/collections/LongListSegment.java#L222)),
   which need 8-byte alignment. The copy's index files need `LongListDiskSegment`'s layout, value `i` at a fixed
   offset, with the header padded to a page, so every chunk starts on a page boundary. These files are temporary and
   read only by the process that wrote them, so their format can change freely. Saved states keep today's format.
2. **The mapped files must never change.** `PRIVATE` keeps the copy's writes out of the files. But on Linux, pages
   the copy hasn't written yet show later changes made to the file by anyone else, and truncating the file makes the
   next access fail with `SIGBUS`. Index files are created with `CREATE_NEW` and never rewritten today. The rebuild
   hard-links them, so no link to a mapped file may be opened for writing while it's mapped.
3. **`fsync`.** `writeToFile()` ends with `force(true)`
   ([`AbstractLongList.java:505`](../../../../platform-sdk/swirlds-merkledb/src/main/java/com/swirlds/merkledb/collections/AbstractLongList.java#L505)).
   A snapshot that is only mapped by the same process doesn't need it. Without it, the write goes to the page cache
   at memory speed, until the kernel starts throttling dirty pages. How fast 37 GB can be written this way in the
   node's cgroup has to be measured.
4. **The right `map()` overload.** `FileChannel.map(mode, position, size)` returns a `MappedByteBuffer`, limited to
   2 GiB. The overload with an `Arena` returns a `MemorySegment`. Map each chunk in its own shared arena, as
   `LongListDiskSegment` does, so chunks below the valid range can be unmapped one by one.
5. **Memory accounting.** Mapped pages are charged to the container's cgroup as page cache. The kernel can reclaim
   them, so they don't lead to OOM kills, but they count towards the container's memory usage. They don't appear in
   NMT or the MerkleDB off-heap metrics, so memory monitoring has to include file-backed memory.
6. **Garbage scans.** A scan reads the whole index and pulls every page in. The teacher's and the state write's copies
   are built with compaction disabled, so this applies only if a copy that compacts is mapped.

## 4. Step 2: keep the pipeline paused only for the snapshot

`detach()` and `detachAsDataSourceCopy()` run the snapshot, the rebuild and the cache snapshot (or its flush) inside
`pipeline.pausePipelineAndExecute(...)`. The lifecycle thread can't merge or flush newer copies for 50–85 s.
`createSnapshot()` has kept only the snapshot inside the pause since
[#21123](https://github.com/hiero-ledger/hiero-consensus-node/pull/21123), which fixed the same problem for large
states: a long pause makes unhandled copies pile up and triggers backpressure.

Restructure both methods the same way:

```java
final ValueReference<VirtualNodeCache> cacheSnapshot = new ValueReference<>();
final Path snapshotPath = pipeline.pausePipelineAndExecute("detach", () -> {
    cacheSnapshot.setValue(cache.snapshot());
    return dataSourceBuilder.snapshot(null, dataSource);
});
try {
    final VirtualDataSource copy = dataSourceBuilder.build(getLabel(), snapshotPath, /* as today */ ...);
    // detach(): return new RecordAccessor(metadata.copy(), hashChunkHeight, cacheSnapshot.getValue(), copy);
    // detachAsDataSourceCopy(): flush(cacheSnapshot.getValue(), copy); return copy;
} finally {
    FileUtils.deleteDirectory(snapshotPath);
}
```

This is safe for the same reasons it's safe in `createSnapshot()`: the snapshot directory is self-contained, and the
cache snapshot is an independent copy. `RecordAccessor` holds no reference to the source data source.

The teacher's pause shrinks from about 80 s to the snapshot (about 29 s), and with step 1 to a few seconds. This
doesn't shorten the cycle, but it protects the teacher's transaction handling under load. It's small and independent
of step 1.

## 5. Step 3: start the learner's copy while waiting for the teacher

`ReconnectStateLearner.execute()`
([`ReconnectStateLearner.java:161`](../../../../platform-sdk/consensus-reconnect-impl/src/main/java/org/hiero/consensus/reconnect/impl/ReconnectStateLearner.java#L161))
first waits for the teacher's signatures (41–94 s), and only then creates the `VirtualMapLearner`, which copies the
learner's data source (52–93 s). The copy depends only on the learner's own state, which doesn't change during the
reconnect: gossip is stopped and the queues are cleared before `execute()` starts.

Start the copy asynchronously at the beginning of `execute()`, and hand it to `LearningSynchronizer.synchronize()`
when the transfer starts. Points to get right:
- **No leaks:** if receiving the signatures fails, the transfer fails or the reconnect is interrupted, the prepared
  copy must be closed (`abortOnException()`). Otherwise 34.4 GiB of off-heap memory leaks.
- **No wasted copies:** the teacher rejects requests in `ReconnectStatePeerProtocol.shouldAccept()`, before
  `execute()` runs, so rejected requests don't start a copy.
- **The previous state write:** the copy needs the learner's pipeline pause, which the previous cycle's
  `createSnapshot()` may still hold for up to about 28 s.

On its own, step 3 would have saved 40–60 s in the measured cycle. With steps 1 and 2, the learner's copy is hidden
behind the teacher's, with option B for `createSnapshot()`. With option A or C, it finishes about 40–50 s into the cycle.

## 6. Combined estimate

| | Today | Steps 1 + 2 + 3 (estimate) |
|---|---|---|
| Waiting for the teacher | 41–94 s | about 15–25 s |
| Learner copy (afterwards) | 52–93 s | hidden (option B), about 20–30 s (option A or C) |
| Transfer | 90–160 s | unchanged |
| **Cycle** | **4–5 min** | **about 1.8–3.1 min (B) or 2.2–3.5 min (A or C)** |

At about 10.4 rounds/s, that's a lag of about 1,100–2,200 rounds after a reconnect, instead of about 3,200. A
retention window of 2,500–3,000 rounds would then be enough, and catch-up would take about 5–10 minutes instead of
about 15.

The transfer is the next target. In short cycles it's limited by round trips while walking the internal nodes, not
by bandwidth, hashing or disk.

## 7. Current baseline: convergence with `roundsExpired = 4000`

With `consensus.roundsExpired = 4000` on all nodes, under 10K TPS load, the learner converged on its second
reconnect:

| | Reconnect 1 | Reconnect 2 |
|---|---|---|
| Lag when the reconnect started | 21,399 rounds | 4,743 rounds |
| Waiting for the teacher | 62 s | 89 s |
| Learner copy | 62 s | 73 s |
| Transfer | 375 s, 5.1 GB | 148 s, 1.4 GB |
| **Cycle** | **8.3 min** | **5.2 min** |
| Lag after the reconnect | 4,743 rounds: fell behind again | about 3,240 rounds: converged |

- **Margin:** the node falls behind again when its lag exceeds about 3,973 rounds (the 4,000-round window minus its
  own 27-round ancient window). Reconnect 2 ended about 730 rounds, or about 70 s, under that. With a 3,000-round
  window, the same cycle would have failed.
- **Catch-up:** the node became `ACTIVE` 14.3 min after reconnect 2. It handled about 14 rounds/s, against
  10.4 rounds/s produced by the network. It then stayed `ACTIVE` for 21 hours, with no errors and no ISS.
- **Required configuration:**
    - `MALLOC_MMAP_THRESHOLD_=4194304` in the node's environment. Without it, glibc kept the index memory freed by
      each copy in its malloc arenas, and the node was OOM-killed after 4–17 reconnect cycles.
    - [#27736](https://github.com/hiero-ledger/hiero-consensus-node/pull/27736) removes a separate 20–25 s wait at the
      start of the learner's copy, for garbage scans to finish.

This is a stopgap: every node keeps 4,000 rounds of events, and a cycle longer than about 6.4 min still doesn't
converge.
