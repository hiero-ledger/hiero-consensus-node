# MerkleDb Data File Compaction

---

## Summary

MerkleDb stores data in append-only files. When data items are updated or deleted, old versions remain in their original files and become garbage.
Compaction is the background process that reclaims this wasted space by copying still-alive data items into new files and deleting the old ones.
This document describes how compaction works, the algorithms that drive it, and the classes that implement it.

## What is Compaction and Why It is Needed

MerkleDb is the storage engine behind `VirtualMap`. It persists three categories of data, each in its own file collection (store):

- **IdToHashChunk** — hash chunks containing groups of contiguous node hashes in the virtual Merkle tree. Keys are chunk IDs derived from tree paths.
- **PathToKeyValue** — leaf node data (keys and values). Keys are tree paths.
- **ObjectKeyToPath** — a mapping from bucket IDs to their current tree paths. Keys are user-provided keys (as bytes).

Each store consists of a set of data files on disk and an in-memory (off-heap) index that maps data item keys to data locations in those files.
A data location encodes both the file index and the byte offset within the file where the data item is stored.
This index is the primary structure that determines which data items are alive and which are garbage.

Each store follows an append-only file model. During a flush, all new or updated data items for that store are written
sequentially to a fresh data file. The in-memory index is updated to point to the new locations. The old data items in
previous files are not modified or deleted — they simply become unreachable from the index. These unreachable items are garbage.

Without compaction, the number of data files would grow without bound, and the fraction of live data in each file would
shrink over time. Disk usage would far exceed the actual state size. Compaction solves this by periodically identifying
files with significant garbage, copying their live data into new files, updating the index to point to the new locations, and deleting the old files.

Compaction is a lower-priority background process. It must not interfere with transaction handling or the main MerkleDb
operations (hash/KV reads and flushes). It must also coexist correctly with MerkleDb snapshots, which require
all data files to be in a consistent, read-only state for the duration of the snapshot.

## How Compaction Works

### Data Files and Compaction Levels

Every data file has a compaction level, stored in the file's metadata (`DataFileMetadata.compactionLevel`). Files
produced by flushes are level 0. When files at level N are compacted, the output file is promoted to level `N + 1`,
capped at `maxCompactionLevel` (configurable, default 10).

Levels serve as a stability proxy. Data that has survived multiple compaction rounds and reached a higher level tends
to change less frequently than freshly flushed data at level 0. This property is used to avoid mixing data of different
stability — compaction processes files at a single level only, never mixing levels in the same compaction task.
The output is always promoted to the next level, keeping stable data separated from volatile data.

### Total Item Count Per File

Every data file records the total number of data items it contains. This count is set once when the file is
finalized — in `DataFileCollection.endWriting()` for flush files and in `DataFileCompactor.finishCurrentCompactionFile()`
for compaction output files — and never changes. The count is stored in the file header via `DataFileMetadata.itemsCount`
and is available at runtime through `DataFileReader`.

This immutable count serves as the denominator for garbage ratio calculations: it tells us how many items the file started with,
regardless of how many have since been superseded.

### File System Layout

MerkleDb has a root storage directory. Within it, each store has its own subdirectory:

```
<storageDir>/
├── [...irrelevant files and directories...]
├── idToHashChunk/                 # IdToHashChunk file collection
│   ├── state_idtohashchunk_2025-03-04_14-30-00-123_L0__________0.pbj
│   ├── state_idtohashchunk_2025-03-04_14-30-01-456_L0__________1.pbj
│   ├── state_idtohashchunk_2025-03-04_14-35-00-789_L1_________10.pbj
│   └── state_idtohashchunk_metadata.pbj
├── objectKeyToPath/               # ObjectKeyToPath file collection
│   ├── state_objectkeytopath_..._L0________0.pbj
│   └── state_objectkeytopath_metadata.pbj
└── pathToHashKeyValue/            # PathToKeyValue file collection
    ├── state_pathtohashkeyvalue_..._L0________0.pbj
    └── state_pathtohashkeyvalue_metadata.pbj
```

All three stores share the same file management model via `DataFileCollection`. Each store's data files and metadata file
live in the store's own subdirectory. Compaction output files are written to the **same directory** as the input files —
there is no separate staging area. Old files are deleted in place after compaction completes.

#### File Naming

Data files follow the naming convention:

        {storeName}_{timestamp}_L{level}_{index}.pbj

where `storeName` is the table-qualified store name (e.g. `state_idtohashchunk`),
`timestamp` is the creation time in `yyyy-MM-dd_HH-mm-ss-SSS` format (UTC),
`level` is the compaction level (0 for flush files, incremented on each compaction),
and `index` is a zero-padded integer (10 characters wide, right-aligned with
underscores).

Each store also has a metadata file (`{storeName}_metadata.pbj`) that persists the valid key range across restarts.

#### File Indexes

Each `DataFileCollection` maintains a monotonically increasing `nextFileIndex` counter (an `AtomicInteger`).
Every new file — whether created by a flush or by compaction — gets the next index from this counter. Indexes are never reused. This means:

- Flush files and compaction output files share a single index sequence per store.
- A compaction output file always has a higher index than any of its input files.
- File indexes are not contiguous — gaps appear when old files are deleted.

The index is used for two purposes: it is part of the file name on disk, and it is packed into data locations in the in-memory index.
A **data location** is a 64-bit long that encodes both the file index and the byte offset of a data item within that file:

```
[ 24 bits: file index + 1 ][ 40 bits: byte offset ]
```

The file index is stored with a +1 offset so that data location `0` can serve as `NON_EXISTENT_DATA_LOCATION` (a sentinel).
This encoding allows up to 16 million files and 1 TB per file. The in-memory index (`LongList` or `LongListOffHeap`)
maps data item keys to data locations. When compaction copies a data item to a new file, it atomically updates the
index entry via `putIfEqual()` to point to the new data location (new file index + new byte offset).

#### What Happens During Compaction

From the file system perspective, a compaction of level N files in a store proceeds as follows:

1. **New file created.** `DataFileCollection.newDataFile()` allocates a new file index from `nextFileIndex`, creates a `.pbj`
   file in the store directory, and returns a `DataFileWriter`. The file is immediately registered as a reader (via `addNewDataFileReader()`).
   However, it's not yet visible in `getAllCompletedFiles()`, as it is not yet marked as "completed" — it will not be
   eligible for compaction itself until step 3.

2. **Data items copied.** The compactor iterates the index. For each entry pointing to a file in the compaction set,
   it reads the data item from the old file and writes it to the new file. The index is atomically updated to the new data location.

3. **File finalized.** After all items are copied, the output file is completed: metadata (item count, compaction level)
   is written, the reader is marked as completed, and the file becomes visible in `getAllCompletedFiles()`.

4. **Old files deleted.** The compactor calls `DataFileCollection.deleteFiles()` to remove the old files.
   The `DataFileCollection` atomically removes the old readers from its file list (via CAS loop) and deletes the files from disk.
   Any active readers — including snapshot hard links — maintain their file handles, so in-flight reads are not affected.
   The data is only physically freed when all hard links (working directory, snapshot, and saved state) are removed.
   On restore, the reverse happens: `hardLinkTree()` links from the `saved-state` directory into a new working directory under `swirlds-tmp`,
   and `MerkleDbDataSource` opens it as a normal database. Compaction then operates on these linked files as usual.

#### Snapshots

Snapshots create hard links from the store directory to a target snapshot directory.
Because compaction output files live in the same directory as input files, the snapshot simply hard-links all `.pbj` files.
If compaction is in progress, `pauseCompaction()` ensures the current output file is flushed and closed before hard links are created.

### Garbage Estimation via Index Scanning

The system needs to know how much garbage each file contains in order to make compaction decisions.
Rather than maintaining real-time counters on the hot path, garbage is estimated by a background scanner task.

The scanner (`GarbageScanner`) traverses the in-memory (off-heap) index for a given file collection. For each index entry, the scanner
checks which file the entry points to and increments an alive counter for that file. Index entries pointing to files not present
in the scanner's collection snapshot (e.g. new files created by a concurrent flush or compaction after the snapshot was taken)
are silently skipped. This is safe because the skipped entries represent items that have already moved to newer
files - the old files' alive counts are unaffected.
After the full traversal, the scanner knows how many items in each file are still referenced by the index. Two ratios
are computed per file:

```
garbageRatio(file) = 1 - (aliveItems / totalItems)
deadToAliveRatio(file) = (totalItems - aliveItems) / aliveItems
```

The `garbageRatio` is used to estimate projected output file size: `fileSize × (1 - garbageRatio)`. The `deadToAliveRatio`
drives the compaction decision — it describes the "garbage collection speed" for a file. A higher ratio means faster GC:
more dead items reclaimed per alive item copied. The ratio has an important additive property: for a set of files, the
aggregate ratio is `Σdead / Σalive`, which describes the combined GC speed of the entire batch.

Files with `totalItems == 0` are assigned `garbageRatio = 1.0` and `deadToAliveRatio = MAX_VALUE` (fastest possible GC —
nothing to copy, or file needs full rewrite). This covers two cases: the file is truly empty (compaction will be a no-op,
which is harmless), or the file was written by an older version that did not record the item count.
Files with zero alive items also get `deadToAliveRatio = MAX_VALUE` (nothing to copy — perfect).
Files with zero dead items get `deadToAliveRatio = 0.0` (no garbage — never individually selected for compaction).

In rare cases involving successive `HalfDiskHashMap` bucket doublings, the scanner's deduplication heuristic may
not fully prevent double-counting, causing `aliveItems` to exceed `totalItems`. The `aliveItems()` accessor caps
the returned value at `totalItems` via `Math.min(totalItems(), aliveItems)`, ensuring `garbageRatio` stays in
[0.0, 1.0] and `deadToAliveRatio` does not become negative. This is a conservative correction — the file appears
to have zero garbage, which means it won't be selected in phase 1 but may still be absorbed in phase 2 if a dirty
group has budget.

The scanner does not filter or group files by compaction eligibility — it returns the full `IndexedGarbageFileStats`
for all completed files. Filtering by `gcRateThreshold`, grouping by projected output size, and phase 2 absorption
are performed by the `MerkleDbCompactionCoordinator` when it processes scan results in `submitCompactionTasks()`.

The coordinator applies a two-phase selection algorithm per level:

1. **Phase 1 — Select eligible files:** files whose `deadToAliveRatio` exceeds `gcRateThreshold` are collected as
   compaction candidates for their level. These are files with enough garbage to justify compaction on their own.

2. **Split into groups:** the eligible files at each level are partitioned into non-overlapping groups bounded by
   projected output size (`maxCompactedFileSizeInMB`).

3. **Phase 2 — Absorb remaining files (per group):** for each group, the coordinator checks if there is headroom
   in both the aggregate `Σdead / Σalive` ratio (must be above `gcRateThreshold`) and the projected output size
   (must be below `maxCompactedFileSizeInMB`). If so, remaining files at the level (those NOT selected in **phase 1**)
   are considered for absorption. The remaining files are pre-sorted by dead/alive ratio descending (once per level),
   and each group draws from a shared pool. Files whose addition would push the aggregate ratio below the threshold
   or the projected output size past the cap are skipped — the coordinator continues to consider subsequent files
   rather than stopping at the first rejection. Absorbed files are removed from the shared pool via
   `Iterator.remove()`, so no file can be claimed by more than one group. Sorting by ratio descending ensures files
   that least worsen the aggregate are absorbed first, building maximum headroom for subsequent candidates.

   This per-group absorption ensures that every compaction task has an opportunity to consolidate small clean files,
   not just the first group. If **phase 1** produces many eligible files that span multiple groups, each group independently
   absorbs whatever extra files fit within its own budget.

The output of `scan()` is an `IndexedGarbageFileStats` — a flat array of per-file statistics indexed by file index.
This is purely informational: it contains alive/dead counts and derived ratios for every completed file, with no
filtering or grouping. The coordinator reads these stats in `submitCompactionTasks()` to apply **phase 1** filtering,
group splitting, and per-group **phase 2** absorption.

A critical property makes periodic scanning viable rather than continuous tracking: **garbage only grows between compactions**.
A file's alive count can only decrease (as flushes write newer versions of its items) or stay the same. It never increases,
because compaction always writes to new files, not existing ones. A scan result that says "file X has 30% garbage" is therefore
a conservative underestimate by the time it is consumed. Stale results lead to compacting slightly more than strictly necessary,
never less — a safe direction to err in.

The scanner is read-only with respect to data files. It only reads the index, which resides in off-heap memory.
There is no disk I/O involved, making the scanner a lightweight background task that does not compete with flushes for disk bandwidth.

Scanner tasks are triggered after flushes. At most one scanner task per store runs at any given time. If a scan is
already in progress when a new flush completes, no additional scan is scheduled. Scanner instances are created once per
data source (during `MerkleDbDataSource` construction) and reused across flushes — only the scan execution is per-flush.
The scanner reads fresh state from the index and file collection each time `scan()` is called, so reuse is safe.

#### HalfDiskHashMap Deduplication

The ObjectKeyToPath store uses a `HalfDiskHashMap` (HDHM), which can double its bucket count when the map grows beyond
a load threshold. During doubling, the bucket index (`LongList`) is extended from size `M` to `2M`. For each original
bucket at index `x` (where `x < M`), the entry at `x + M` initially points to the same data location as `x`. Buckets
are only sanitized (entries filtered by hash mask) lazily when they are next read or written during a flush.

Until sanitized, both `index[x]` and `index[x + M]` hold identical data locations. If the scanner naively iterates the
full index, it double-counts alive items for every unsanitized pair. This inflates alive counts above total item counts,
producing negative garbage ratios that prevent compaction from ever triggering for this store.

The scanner handles this by enabling `deduplicateMirroredEntries` mode for the ObjectKeyToPath store. In this mode,
instead of iterating the full index, the scanner iterates only the lower half (`0` to `N/2 - 1`). For each index `x`,
it reads both `index[x]` and `index[x + N/2]`. If both data locations are identical, the entry at `x + N/2` is a
stale duplicate and only the lower-half entry is counted. If they differ, both are counted — the bucket at `x + N/2`
has been sanitized and contains independent data.

This approach relies on the fact that HDHM doubling produces exact mirror pairs at offset `N/2`. With the large default
initial capacity (1 billion buckets), doubling is a rare event, and essentially all buckets are sanitized by flushes
before a second doubling could occur. In the unlikely event of two doublings in quick succession, the single-level
dedup provides a 50% correction — imperfect but sufficient to prevent compaction freezing.

The same mirroring problem affects compaction itself, not just scanning. Without deduplication in the compactor,
after a bucket doubling the standard compaction path (`compactWithNoDedup`) iterates the full index, encounters
both `index[x]` and `index[x + N/2]` pointing to the same data location, reads the same bucket twice, and writes
two independent copies to the output file. Both index entries are CAS-updated to different new locations. Data
consistency is preserved, but the output file is inflated — roughly 25% larger than the input when approximately
half the entries are still unsanitized after a fresh doubling. This inflation cascades through levels: the inflated
output at level 1 is compacted to level 2 (still inflated), then to level 3, and so on, until the affected buckets
are eventually sanitized by flushes.

The compactor handles this with `deduplicateMirroredEntries` mode (enabled only for ObjectKeyToPath via the
`DataFileCompactor` constructor). When enabled, `compactWithDedup` iterates only the lower half of the index.
For each pair `(index[x], index[x + N/2])`:

- If both point to the same location (mirrored/unsanitized): the data is read and written once. Both index entries
  are updated to point to the same new location — the lower half via CAS inside `compactSingleItem`, the upper half
  via a standalone `putIfEqual` (no data write, no snapshot lock needed since no file I/O occurs).
- If they differ (already sanitized): each is processed independently, same as the non-dedup path.

The `indexSize` used for dedup is captured from `HalfDiskHashMap.getNumOfBuckets()` at compactor construction time
(via the compactor factory, which runs at task execution time). HDHM resizing is blocked while compaction is running
(`isCompactionRunning` guard in the flush path), so `indexSize` remains valid for the duration of the compaction.

### Compaction Triggering

Compaction decisions are driven by the dead-to-alive ratio threshold (`gcRateThreshold`, default 0.5). A file whose
`deadToAliveRatio` (dead items / alive items) exceeds this threshold is eligible for compaction. A threshold of 0.5
means: for every 2 alive items copied, at least 1 dead item must be reclaimed.

The coordinator extends the candidate set via per-group **phase 2** absorption. For each group created by partitioning
eligible files, remaining non-eligible files are considered for absorption from a shared pool (pre-sorted by dead/alive
ratio descending). Files that would push the group's aggregate ratio below `gcRateThreshold` or the projected output
past `maxCompactedFileSizeInMB` are skipped. Absorbed files are removed from the pool so no other group at the same
level can claim them. This maximizes file consolidation across all groups at a level.

### Consolidation of Small Files

When workloads are update-heavy (e.g. `ObjectKeyToPath` receiving entity updates rather than inserts),
flushes produce many small files with little garbage. These files accumulate garbage very slowly -
each one stays on disk for a long time before its dead/alive ratio reaches `gcRateThreshold`.
As a result, many small files coexist on disk at any given time.

Consolidation addresses this with a size-based check that runs as part of the per-level processing in
`submitCompactionTasks()`, after garbage-based tasks for the level have been assigned. The algorithm per level:

1. Skip level 0 — level 0 files are fresh flush output, expected to be small and consumed by garbage-based
   compaction. Without this exclusion, consolidation would trigger too aggressively at level 0.
2. Collect all remaining files at this level (those not assigned to garbage-based tasks) whose size is below
   `consolidationMaxInputFileSizeMB` (default 50 MB).
3. If the count is below `consolidationMinFileCount` (default 10), skip — not enough small files to justify the work.
4. Submit all candidates as a single consolidation task (reuses `CompactionTask`).

This is self-limiting by design. The output file from consolidation exceeds `consolidationMaxInputFileSizeMB`, so it
will never be re-selected as a consolidation candidate. Small files accumulate again from subsequent flushes, and the
cycle repeats. Each file participates in at most one consolidation, preventing endless re-copying of live data.

Consolidation tasks use the `"_consolidate_N"` key pattern (where N is the level). Garbage and consolidation
tasks at the same level share a single counter entry via the unified `"_level_N"` key. They share the compaction
thread pool and support pause/resume for snapshots identically to garbage-based tasks.

### Compaction Task Submission

After each flush, the flush handler submits two kinds of tasks to the compaction thread pool:

1. **A scanner task** (if one is not already running for this store). The scanner traverses the in-memory index
   and caches per-file garbage statistics in `scanStatsByStore`.

2. **Compaction tasks** via `submitCompactionTasks()`. The coordinator:
   a. **Filters** scan results against the live file collection (`DataFileCollection.getAllCompletedFiles()`).
   Files present in scan results but no longer in the collection (deleted by a previous compaction cycle since
   the scan ran) are excluded. This planning-time filtering replaces the previous execution-time stale-file
   intersection and `compactionInProgress` flag mechanism.
   b. For each level, applies **phase 1** filtering: selects files whose `deadToAliveRatio > gcRateThreshold`.
   c. **Splits** each level's eligible files into non-overlapping groups bounded by projected output size.
   d. Applies **phase 2** absorption per group: absorbs additional non-eligible files from a shared remaining pool
   for the level, removing absorbed files from the pool so no group can claim the same file.
   e. **Submits** each group as an independent compaction task.
   f. **Consolidation**: for the same level, collects remaining small files (not assigned to garbage tasks,
   below `consolidationMaxInputFileSizeMB`, level > 0) and submits a consolidation task if the count meets
   `consolidationMinFileCount`.

   Multiple tasks for the same level run concurrently. This is safe because each task operates on a disjoint set of
   input files and writes to its own output file. Levels are discovered from `scanStatsByStore`, so compaction tasks
   are only submitted once the first scan for a store has completed. New tasks for a level are only submitted when
   ALL tasks from the previous batch for that level have completed (tracked by a per-level counter under the
   unified `"_level_N"` key, which covers both garbage and consolidation tasks).

**Projected output size cap.** The `maxCompactedFileSizeInMB` parameter (default 10000 = 10 GB) limits the estimated
size of each compaction task's output. For each candidate file, the projected alive size is `fileSize × (1 - garbageRatio)`.
Files are taken in iteration order (file index order from the scanner) and accumulated into a group until the next file would
push the cumulative projected size over the cap; then a new group is started. At least one file per group is always included,
so a single large-but-mostly-garbage file is never skipped.

This output-oriented estimation matches the copying collector's cost model: a file's processing cost is proportional
to its alive data (which must be read and copied), not its total size. A 500 GB file with 99.9% garbage is fast
to process — only ~500 MB of data is copied — while a 10 GB file with 10% garbage requires copying 900 MB.

**The flow for each compaction task when it executes:**

1. Check if compaction is still enabled (exit if disabled during shutdown).
2. Create a `DataFileCompactor` via the factory and register it for pause/resume/interrupt.
3. Compact the assigned files into new output file(s) at `level + 1` (capped at `maxCompactionLevel`).
4. In the `finally` block, clean up coordinator state: remove the compactor from `compactorsByName`,
   remove the task key from `taskKeys`, decrement the per-level counter, and notify waiters.

Because stale files are filtered at planning time (step 2a above), the task receives only files that were
present in the live file collection at submission time. There is no execution-time stale-file filter or
`compactionInProgress` flag.

### Compaction Execution

When a compaction task runs, it creates a `DataFileCompactor` and processes its assigned group of files. It creates a
new output file at `level + 1`, traverses the index to identify live items in the compaction set, copies them to the
output file, updates the index, and deletes the old files.

The compactor supports two iteration modes:

**Standard mode (`compactWithNoDedup`):** Used for IdToHashChunk and PathToKeyValue stores where each index entry is
unique. The compactor iterates all valid index entries via `index.forEach()`. For each entry pointing to a file in the
compaction set, `compactSingleItem` reads the data item from the old file, writes it to the new file via
`DataFileWriter.storeDataItemWithTag()`, and atomically updates the index via `CASableLongIndex.putIfEqual()`. The
`putIfEqual` call ensures correctness under concurrency — if a concurrent flush has already updated the index entry
to point to an even newer file, the CAS fails and the compactor's copy is correctly skipped.

**Dedup mode (`compactWithDedup`):** Used for the ObjectKeyToPath store (HalfDiskHashMap) to avoid writing mirrored
bucket entries twice after a bucket index doubling. Instead of iterating the full index, this mode iterates only the
lower half (`0` to `indexSize/2 - 1`). For each pair `(key, key + halfSize)`, it reads both index entries and
checks whether they point to the same data location. Mirrored entries (same location) are written once and both
index entries updated to the same new location. Sanitized entries (different locations) are processed independently.
See the "HalfDiskHashMap Deduplication" section for details on the mirroring problem and the pairing logic.

Each item copy is performed under the `snapshotCompactionLock` to coordinate with snapshots. If a snapshot pauses
compaction mid-iteration, the current output file is finalized and a new one is opened when compaction resumes.

The compactor tracks output size via `totalCompactedBytes` — a field accumulated in `finishCurrentCompactionFile()`
under `snapshotCompactionLock` each time an output file is finalized. This avoids reading output file sizes from
disk after compaction completes, which would be unsafe: a snapshot-interrupted output file can be picked up and
deleted by a concurrent compaction task before the original run finishes.

### Snapshot Interaction

Snapshots require all data files to be in a consistent, read-only state so they can be hard-linked to a target directory.
If compaction is in progress and a new file is being written to, that file must be flushed and finalized before the snapshot can proceed.

This is handled through `MerkleDbCompactionCoordinator.pauseCompactionAndRun(IORunnable)`, which coordinates with
each `DataFileCompactor`'s `snapshotCompactionLock`.

**When a snapshot is requested:**

`pauseCompactionAndRun()` is called with the snapshot action. This method:

1. Pauses all active compactors. Each `DataFileCompactor.pauseCompaction()` acquires its `snapshotCompactionLock`.
   If compaction is writing to a file, the file is flushed and closed. No further writes occur until the action completes.
2. Executes the snapshot action (hard links are created).
3. In a `finally` block, resumes all compactors. Each `DataFileCompactor.resumeCompaction()` opens a new output file
   and releases the lock. Compaction resumes where it left off.

If no compaction is in progress for a given compactor, `pauseCompaction()` simply acquires the lock (preventing a new compaction from starting),
and `resumeCompaction()` releases it.

When multiple compaction tasks run concurrently for the same store (whether at different levels or within the same level
as parallel groups), `pauseCompactionAndRun()` iterates over all active compactors in `compactorsByName` and pauses each one.
Each `DataFileCompactor` instance has its own `snapshotCompactionLock`, so pausing is independent per compactor.

## Implementation

### Thread Pool and Concurrency Model

All compaction-related tasks (both scanning and compaction) run on a shared fixed-size `ThreadPoolExecutor`,
managed by `MerkleDbCompactionCoordinator`. The pool size is configurable via `MerkleDbConfig.compactionThreads()` (default 4).

**The concurrency constraints are:**

- At most one scanner task per store at any time.
- Multiple compaction tasks for the same store at the same level may run concurrently, each processing a non-overlapping group of files.
- Multiple compaction tasks for the same store at different levels may run concurrently.
- Scanner and compaction tasks for the same store may run concurrently.
- Different stores are fully independent.
- New tasks for a level are only submitted when all tasks from the previous batch for that level have completed (counter-based deduplication).

The `MerkleDbCompactionCoordinator` tracks tasks using two structures:

- **Submitted tasks** (`taskKeys`): a single `Set<String>` that tracks all queued and running tasks — both scanners and compaction tasks.
  Task keys use distinct patterns (`"_scan"` for scanners, `"_compact_N_C"` for garbage compaction where N is the level and C is the group index,
  `"_consolidate_N"` for consolidation where N is the level).
  A task is in this set from submission until its `finally` block.
- **Active compactors** (`compactorsByName`): tracks tasks that have created a `DataFileCompactor` and are actively compacting.
  This is a subset of submitted tasks. Used for `pauseCompactionAndRun()` during snapshots and `interruptCompaction()` during shutdown.
- **Per-level task counts** (`compactionTaskCounts`): tracks the number of outstanding tasks per level via a unified
  `"_level_N"` key (e.g. `"IdToHashChunk_level_2" → 3`). Both garbage and consolidation tasks at a level share
  this counter. When the count reaches zero, new tasks for that level can be submitted from the next scan cycle.

The coordinator's `submitCompactionTasks()` method performs:
1. Filter scan results against the live file collection (intersect `IndexedGarbageFileStats` with `DataFileCollection.getAllCompletedFiles()`).
2. For each level: Phase 1 filter by `deadToAliveRatio > gcRateThreshold`, split eligible files into groups via `splitIntoGroups()`.
3. Per-group phase 2: `absorbIntoGroup()` draws from a shared remaining pool (pre-sorted by dead/alive ratio descending),
absorbs files that fit both ratio and size limits, and removes them from the pool via `Iterator.remove()`.
4. Submit each group as a `CompactionTask`, tracking assigned files in a per-level `levelAssigned` set.
5. For the same level (if level > 0 and consolidation is enabled): collect remaining small files not in `levelAssigned`,
submit as a consolidation task if the count meets `consolidationMinFileCount`.
6. Record the total number of submitted tasks (garbage + consolidation) in the per-level counter.

**`DataFileCompactor`** performs the actual compaction for a given file collection. Each instance is used for a single
compaction run — the coordinator creates a fresh instance per task because each instance carries its own `snapshotCompactionLock`,
writer, and reader state. Its key responsibilities are:

- `compactSingleLevel()`: the entry point. It receives a pre-computed list of files and the target output level,
  creates a new output file, traverses the index to identify live items, copies them, updates the index, and deletes the old files.
  It also handles logging and metrics reporting (duration, saved space, file size by level).

- `compactFiles()`: dispatches to either `compactWithNoDedup` (standard iteration over all index entries) or
  `compactWithDedup` (lower-half iteration with mirrored entry detection) depending on the `deduplicateMirroredEntries` flag.
  Both paths call `compactSingleItem` for each live entry, which reads the old data, writes it under the `snapshotCompactionLock`,
  and atomically updates the index via `putIfEqual()`.

- `pauseCompaction()` / `resumeCompaction()`: coordinate with snapshots. `pauseCompaction()` acquires the `snapshotCompactionLock`, flushes, and closes the current output
  file if compaction is in progress. `resumeCompaction()` opens a new output file and releases the lock.

- `interruptCompaction()`: sets a volatile flag that the main compaction loop checks periodically, providing a non-invasive way to stop
  a running compaction without `Thread.interrupt()` side effects.

**`DataFileCollection`** manages the set of data files for a single store. It provides methods to create new files
(`startWriting()` / `endWriting()`), add readers for compaction output files (`addNewDataFileReader()`),
delete compacted files (`deleteFiles()`), and retrieve the list of all completed files (`getAllCompletedFiles()`).
The file list is stored as an `AtomicReference<ImmutableIndexedObjectList<DataFileReader>>` and is updated via `getAndUpdate()`,
which uses a CAS loop. This makes concurrent modifications from multiple compaction tasks (and the flush thread) safe.

**`DataFileReader`** represents a single data file and provides read access to its data items.
It holds the file's `DataFileMetadata` (including compaction level and item count) and tracks whether the file
has been fully written (`setFileCompleted()`). Only completed files are eligible for compaction.
The file's `metadataRef` is an `AtomicReference<DataFileMetadata>` because metadata is updated after construction:
`DataFileCollection.endWriting()` propagates the final `itemsCount` from the writer, and `setCompactionLevel()`
swaps the metadata with a new compaction level. These updates happen on the flush or compaction thread while scanner
and compaction threads may concurrently read the metadata. The atomic reference ensures cross-thread visibility
without explicit synchronization.

**`DataFileMetadata`** stores per-file metadata in the file header: file index, creation date, compaction level, and
total item count. The compaction level is a byte `(max 127)`, and the item count is set once at file creation.

**`MerkleDbDataSource`** is the top-level data source that ties everything together. It owns the three stores
(`hashChunkStore`, `keyValueStore`, `keyToPath`), the in-memory indices (`idToDiskLocationHashChunks`, `pathToDiskLocationLeafNodes`),
and the `MerkleDbCompactionCoordinator`. It also holds three `GarbageScanner` instances (`chunkStoreScanner`, `pathToKeyValueStoreScanner`, `objectkeyToPathScanner`),
created once during construction and reused across flushes. After each flush, it triggers scanner tasks and submits compaction tasks for eligible levels.

### Edge Cases

**No scan results available yet.** After the first few flushes, scanning tasks may not have completed.
`submitCompactionTasks` reads from `scanStatsByStore` and returns immediately if no stats are available (null check).
No compaction tasks are submitted until the first scan completes and populates results. This is correct — compaction
simply doesn't start until the first scan finishes. There is no harm in delaying compaction for a few seconds at startup.
If a scan fails (e.g. due to a concurrent snapshot modifying the file collection), `scanStatsByStore` is not populated for
that store, and the next flush will trigger a new scan attempt.

**Compaction interrupted by snapshot.** If a snapshot is requested while compaction is writing to an output file,
the file is flushed and closed via `pauseCompaction()`. After the snapshot, `resumeCompaction()` opens a new output file
and compaction continues. This means a single compaction run may produce multiple output files (all at the same target level).
This is handled transparently — the `newCompactedFiles` list tracks all files produced during one compaction run.
When multiple compactors are active for the same store (at different levels or as parallel groups within a level), each is paused independently.

**Compaction interrupted by shutdown.** The `interruptCompaction()` method sets a volatile flag that the main loop
checks between data items. If the flag is set, compaction stops, and any files that were not fully processed are
left in place for the next compaction run. The partially written output file is finalized and included in
future compactions. The unprocessed input files are not deleted.

**Scanner runs concurrently with compaction for the same store.** This is allowed. The scanner reads the index,
which is being atomically updated by the compactor. The scanner might see some entries pointing to old files and others
pointing to new files. This is harmless: the worst outcome is a slightly imprecise alive count,
which is acceptable since garbage only grows between compactions.

**Scanner runs concurrently with flush for the same store.** Also allowed and harmless for the same reason.
The flush writes new data items and updates the index. The scanner might miss some updates, leading to a slight
overcount of alive items in old files. The next scan will correct this.

**Multiple compaction tasks at the same level (parallel groups).** Each task creates its own `DataFileCompactor`
with independent writer state. They operate on disjoint sets of files (non-overlapping groups assigned at submission time
by the coordinator's `splitIntoGroups` + per-group `absorbIntoGroup`), so they do not interfere with each other's data.
They do share the `DataFileCollection`, but `addNewDataFileReader()` and `deleteFiles()` are both atomic CAS-loop operations
and are safe under concurrency. Each group produces its own output file at the target level.

**Stale scan results referencing deleted files.** Between the time a scan completes and `submitCompactionTasks` runs,
concurrent compaction tasks may have already compacted and deleted some of the candidate files. The coordinator handles
this by intersecting scan results against the current live file collection (`DataFileCollection.getAllCompletedFiles()`)
at the start of `submitCompactionTasks`. Files present in scan results but no longer in the collection are silently
excluded before any phase 1/phase 2 processing begins. This planning-time filtering ensures that only files currently
on disk are assigned to compaction or consolidation tasks.

**New files created during compaction.** Flushes continue while compaction runs, producing new level 0 files.
These files are not included in the current compaction run (the compaction set is fixed at scan time).
They will be evaluated in the next scan cycle.

**CAS failure during index update.** When the compactor calls `putIfEqual(path, oldLocation, newLocation)`, the CAS
may fail if a concurrent flush has already updated the index entry for that path to point to an even newer file.
This is correct: the flush's data is more recent, so the compactor's copy should be discarded.
The old file still gets deleted at the end of compaction, which is safe because the index no longer points to it
(it points to the flush's file instead).

**File with zero alive items.** A file where all items have been superseded by newer flushes has 100% garbage.
Files with `totalItems == 0` (either truly empty or written by older metadata versions) are also assigned a garbage
ratio of 1.0, ensuring they are always eligible for compaction.

**All files at a level below the GC rate threshold.** If no file at a level has a `deadToAliveRatio` above `gcRateThreshold`,
that level produces no phase 1 candidates. Since **phase 2** only runs for groups derived from **phase 1** candidates, no files are
selected and compaction is not scheduled for such levels.

**Many small files but all below GC threshold.** If no file at a level exceeds `gcRateThreshold`, garbage-based
compaction produces no tasks. However, if the number of small files (below `consolidationMaxInputFileSizeMB`) meets
or exceeds `consolidationMinFileCount`, consolidation will merge them regardless of garbage ratio. This is the primary
scenario consolidation was designed for: update-heavy workloads where files are mostly alive but individually too small.

**Consolidation output re-consolidation.** Consider a hypothetical example: if consolidation merges
100 × 1 MB files, the output is roughly 70–100 MB (depending on how much garbage is reclaimed).
Assuming a `consolidationMaxInputFileSizeMB` of 50 MB, this output exceeds the threshold and will
never be re-selected as a consolidation candidate. If the output happens to be below the threshold
(e.g. only a few files were merged), it may be re-consolidated in a future cycle — but only once
enough additional small files accumulate to meet `consolidationMinFileCount`. This makes
re-consolidation rare and bounded.

**Compaction output file at maxCompactionLevel.** When files at `maxCompactionLevel` are compacted, the output file
stays at the same level (the cap prevents further promotion). This ensures a bounded number of levels and predictable metric cardinality.

**HalfDiskHashMap doubling during compaction.** If the ObjectKeyToPath map doubles while a scan is in progress or between
a scan and compaction, the scanner's deduplication heuristic may be slightly imprecise (comparing against the pre-doubling
half size). This is harmless — at worst, some alive items are double-counted in the old scan result, leading to a slightly
underestimated garbage ratio. The next scan will use the post-doubling index size and produce accurate results.
The compactor's dedup mode (`compactWithDedup`) captures `indexSize` at construction time from the live bucket count.
HDHM resizing is blocked while compaction is running, so the captured value remains valid for the duration of the run.
If no doubling has occurred, the dedup iteration still covers all entries correctly — the lower and upper halves simply
contain independent data, and no mirroring is detected.

### Configuration

The following configuration parameters in `MerkleDbConfig` control compaction behavior:

|             Parameter             | Default |                                                                                                                                                                            Description                                                                                                                                                                            |
|-----------------------------------|---------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `compactionThreads`               | 4       | Size of the shared thread pool for scanner and compaction tasks.                                                                                                                                                                                                                                                                                                  |
| `maxCompactionLevel`              | 10      | Maximum compaction level. Output files at this level stay at this level on subsequent compactions.                                                                                                                                                                                                                                                                |
| `gcRateThreshold`                 | 0.5     | Minimum dead-to-alive ratio for compaction decisions. In **phase 1**, files whose individual ratio exceeds this value are selected. In **phase 2**, remaining files are considered for absorption as long as adding each one keeps the aggregate ratio above this value. A value of 0.5 means: for every 2 alive items copied, at least 1 dead item is reclaimed. |
| `maxCompactedFileSizeInMB`        | 10000   | Maximum projected output size (MB) per compaction task. Also the size cap for per-group **phase 2** absorption. Candidates are partitioned into groups bounded by this size. 10 GB by default.                                                                                                                                                                    |
| `consolidationMaxInputFileSizeMB` | 10      | Maximum file size (MB) for consolidation candidates. Files larger than this are never consolidated — they are the output of previous consolidation runs. Set to 0 to disable consolidation entirely.                                                                                                                                                              |
| `consolidationMinFileCount`       | 10      | Minimum number of small files at a level before consolidation triggers. Prevents pointless runs when only a few small files exist.                                                                                                                                                                                                                                |

### Observability

The following metrics are reported:

- **Compaction duration per level** (`compactionTimeMs`): how long each compaction task took, broken down by store and target compaction level.
- **Space saved per level** (`compactionSavedSpaceMb`): bytes reclaimed per compaction task, reported as the difference between input and output file sizes.
- **File size by level** (`fileSizeByLevelMb`): total disk space used by each compaction level, per store. This shows how data is distributed across levels and whether higher levels are growing.
