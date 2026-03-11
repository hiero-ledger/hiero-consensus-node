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
- **ObjectKeyToPath** — a mapping from application keys to their current tree paths. Keys are user-provided keys (as bytes).

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
all data files to be in a consistent, read-only state for the duration of the snapshot. Achieving a high degree of
compaction parallelism is explicitly a non-goal, as it would contend with main operations for CPU and disk resources.

## How Compaction Works

### Data Files and Compaction Levels

Every data file has a compaction level, stored in the file's metadata (`DataFileMetadata.compactionLevel`). Files
produced by flushes are level 0. When files at level N are compacted, the output file is promoted to level `N + 1`,
capped at `maxCompactionLevel` (configurable, default 5).

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

MerkleDb has a root storage directory (`<round_dir>/data/state/`). Within it, each store has its own subdirectory:

```
<storageDir>/
├── [...irrelevant files and directories...]

├── idToHashChunk/                 # IdToHashChunk file collection
│   ├── state_idtohashchunk_2025-03-04_14-30-00-123__________0.pbj
│   ├── state_idtohashchunk_2025-03-04_14-30-01-456__________1.pbj
│   ├── state_idtohashchunk_2025-03-04_14-35-00-789_________10.pbj
│   └── state_internalhashes_metadata.pbj
├── objectKeyToPath/                       # ObjectKeyToPath file collection
│   ├── state_objectkeytopath_...________0.pbj
│   └── state_objectkeytopath_metadata.pbj
└── pathToHashKeyValue/                    # PathToKeyValue file collection
    ├── state_pathtohashkeyvalue_...________0.pbj
    └── state_pathtohashkeyvalue_metadata.pbj
```

All three stores share the same file management model via `DataFileCollection`. Each store's data files and metadata file
live in the store's own subdirectory. Compaction output files are written to the **same directory** as the input files —
there is no separate staging area. Old files are deleted in place after compaction completes.

#### File Naming

Data files follow the naming convention:

```
{storeName}_{timestamp}_{index}.pbj
```

where `storeName` is the table-qualified store name (e.g. `myTable_internalhashes`), `timestamp` is the creation time
in `yyyy-MM-dd_HH-mm-ss-SSS` format (UTC), and `index` is a zero-padded integer (10 characters wide, right-aligned with underscores).
For example: `myTable_internalhashes_2025-03-04_14-30-00-123__________0.pbj`.

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

   If a snapshot interrupts compaction, the current output file is finalized, and a second output file is created after
   the snapshot completes — so a single compaction run may produce multiple output files in the directory.

3. **Output file finalized.** The writer is closed (`DataFileWriter.close()` rewrites the header with the final `itemsCount`,
   then truncates the file to its exact size). The reader is marked as completed (`setFileCompleted()`), making it eligible for future compactions.

4. **Input files deleted.** If all data items were successfully processed, the old files are removed from the `DataFileCollection`'s
   in-memory file list (via atomic `getAndUpdate()`) and deleted from disk. If compaction was interrupted (e.g. by shutdown),
   the old files are **not** deleted — they remain on disk and will be compacted in a future run.

At no point are files moved between directories. All I/O happens within the store's subdirectory.

#### Relationship to State Saving

MerkleDb's working directory resides under the platform's temporary directory (as of now it's `swirlds-tmp`).
When the platform saves a signed state for a round, data files flow through two stages —
both using hard links, never byte-level copies:

- **Snapshot**: `DataFileCollection.snapshot()` creates hard links from the store's working directory
  into a snapshot directory. Index files (off-heap `LongList` structures) are serialized to the snapshot directory since they are in-memory, not file-backed.
  Compaction is paused for the duration of the snapshot creation.
- **State persistence**: The platform links the snapshot directory into the final `saved-state` location (`<round_dir>/data/state/...`) via `hardLinkTree()`.

Because hard links share the same underlying `inode`, compaction can safely delete old files from the working directory after copying their live
data — any previously taken snapshot still holds a valid hard link to the original file blocks on disk.
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
checks which file the entry points to and increments an alive counter for that file. After the full traversal,
the scanner knows how many items in each file are still referenced by the index. The garbage ratio for a file is then:

```
garbageRatio(file) = 1 - (aliveItems / totalItems)
```

After computing garbage ratios for all files, the scanner filters candidates in the same pass: any file whose garbage ratio
exceeds `garbageThreshold` is eligible for compaction. An optional per-level size cap (`maxCompactionDataPerLevelInKB`)
limits the total size of files selected for one compaction run at each level.
The output of `scan()` is a `Map<Integer, List<DataFileReader>>` — compaction candidates grouped by level, ready to be consumed by compaction tasks.

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

### Compaction Triggering

Compaction decisions are driven by a garbage threshold and an optional per-level size cap. The `garbageThreshold` configuration parameter
`(default 0.3)` controls which files are compacted: any file whose garbage ratio exceeds this threshold is eligible for the compaction set for
its level. The `maxCompactionDataPerLevelInKB` parameter `(default 0, disabled)` caps the total size of files selected for one compaction run at
that level. Both parameters are applied by the scanner during `scan()`, so the scan output is a ready-to-use candidate list.

### Compaction Task Submission

After each flush, the flush handler submits two kinds of tasks to the compaction thread pool:

1. **A scanner task** (if one is not already running for this store). The scanner traverses the in-memory index,
   computes per-file garbage stats, applies threshold and size-cap filtering, and caches the resulting candidate lists in `scanResultsByStore`.

2. **A compaction task for each level** present in the latest scan results (if a task for that store and level is not already queued or running).
   Levels are discovered from `scanResultsByStore`, so compaction tasks are only submitted once the first scan for a store has completed.

Crucially, compaction tasks do **not** evaluate scan results at submission time. Evaluation is deferred to execution time inside the task itself.
This design addresses a staleness problem: if all compaction threads are busy with long-running higher-level compactions, a task submitted during flush `N`
may not execute until flush `N+100` or later. By deferring evaluation, the task always uses the most recent scan results,
ensuring it can include files that were written after the task was submitted.

**The flow for each compaction task when it executes:**

1. Check if compaction is still enabled (exit if disabled during shutdown).
2. Read cached scan results from `scanResultsByStore`. If no results are available (scanner hasn't completed yet), exit — the next flush will submit a new task.
3. Read the scanner output for this task's level from `scanResultsByStore`. The scanner has already filtered by garbage threshold and size cap,
   producing a ready-to-use list of `DataFileReader` instances.
4. If no files are listed for this level (or the level is absent from results), exit (no-op).
5. Create a `DataFileCompactor` via the factory, register it for pause/resume, and compact.

This deferred evaluation model means that scanner-produced candidate lists are shared across all compaction
tasks for the same store. The scanner runs once (per flush, at most), and all level tasks read from the same cached result.
Since scanning is one to two orders of magnitude cheaper than compaction, the cost of a single scan amortized across multiple level tasks is negligible.

Multiple compaction tasks may run concurrently for the same store, each compacting a different level.
For example, level 0 and level 3 of IdToHashChunk may be compacted in parallel.
This is safe because each task writes to a new output file and only deletes its own input files. The `DataFileCollection`
uses atomic copy-on-write updates (`getAndUpdate` on an `AtomicReference<ImmutableIndexedObjectList>`) for adding and
removing file readers, so concurrent modifications are handled correctly.

This deferred evaluation model means that scanner-produced candidate lists are shared across all compaction
tasks for the same store. The scanner runs once (per flush, at most), and all level tasks read from the same cached result.
Since scanning is one to two orders of magnitude cheaper than compaction, the cost of a single scan amortized across multiple level tasks is negligible.

Multiple compaction tasks may run concurrently for the same store, each compacting a different level.
For example, level 0 and level 3 of IdToHashChunk may be compacted in parallel.
This is safe because each task writes to a new output file and only deletes its own input files. The `DataFileCollection`
uses atomic copy-on-write updates (`getAndUpdate` on an `AtomicReference<ImmutableIndexedObjectList>`) for adding and
removing file readers, so concurrent modifications are handled correctly.

### Compaction Execution

When a compaction task runs, it first evaluates cached scan results to decide whether compaction is needed for its level
(see [Compaction Task Submission](#compaction-task-submission)). If thresholds are exceeded, it creates a `DataFileCompactor`,
selects files from the current file list, and processes them. It creates a new output file at `level + 1`, traverses the index
to identify live items in the compaction set, copies them to the output file, updates the index, and deletes the old files.

The inner loop works as follows: for each index entry that points to a file in the compaction set, the data item is
read from the old file, written to the new file via `DataFileWriter.storeDataItemWithTag()`, and the index is atomically
updated via `CASableLongIndex.putIfEqual()`. The `putIfEqual` call ensures correctness under concurrency — if a concurrent
flush has already updated the index entry to point to an even newer file, the CAS fails and the compactor's copy is correctly skipped.

### Snapshot Interaction

Snapshots require all data files to be in a consistent, read-only state so they can be hard-linked to a target directory.
If compaction is in progress and a new file is being written to, that file must be flushed and finalized before the snapshot can proceed.

This is handled through `MerkleDbCompactionCoordinator.pauseCompactionAndRun(IORunnable)`, which coordinates with
each `DataFileCompactor`'s `snapshotCompactionLock`.

**When a snapshot is requested:**

`pauseCompactionAndRun()` is called with the snapshot action. This method:

1. Pauses all active compactors. Each `DataFileCompactor.pauseCompaction()` acquires its `snapshotCompactionLock`. If compaction is writing to a file, the file is flushed and closed. No further writes occur until the action completes.
2. Executes the snapshot action (hard links are created).
3. In a `finally` block, resumes all compactors. Each `DataFileCompactor.resumeCompaction()` opens a new output file and releases the lock. Compaction resumes where it left off.

If no compaction is in progress for a given compactor, `pauseCompaction()` simply acquires the lock (preventing a new compaction from starting),
and `resumeCompaction()` releases it.

When multiple compaction tasks run concurrently for the same store (on different levels), `pauseCompactionAndRun()`
iterates over all active compactors in `compactorsByName` and pauses each one. Each `DataFileCompactor` instance has its own `snapshotCompactionLock`, so pausing is independent per compactor.

## Implementation

### Thread Pool and Concurrency Model

All compaction-related tasks (both scanning and compaction) run on a shared fixed-size `ThreadPoolExecutor`,
managed by `MerkleDbCompactionCoordinator`. The pool size is configurable via `MerkleDbConfig.compactionThreads()` (default 6).

**The concurrency constraints are:**

- At most one scanner task per store at any time.
- At most one compaction task per store per level at any time.
- Multiple compaction tasks for the same store at different levels may run concurrently.
- Scanner and compaction tasks for the same store may run concurrently.
- Different stores are fully independent.

The `MerkleDbCompactionCoordinator` tracks running tasks using keys that encode the store name, task type, and (for compaction tasks) the level — for example,
`"IdToHashChunk_scan"` and `"IdToHashChunk_compact_2"`. Before submitting a task, the coordinator checks whether a task with the same key is already in the `tasks` set.

### Key Classes

**`MerkleDbCompactionCoordinator`** manages the lifecycle of scanner and compaction tasks. It tracks two categories of state:

- **Submitted tasks** (`tasks`): a single `Set<String>` that tracks all queued and running tasks — both scanners and compaction tasks — for deduplication. Task keys use distinct suffixes (`"_scan"` vs `"_compact_N"`) to ensure uniqueness across types. A task is in this set from submission until its `finally` block.
- **Active compactors** (`compactorsByName`): tracks tasks that have evaluated scan results, decided to compact, and created a `DataFileCompactor`. This is a subset of submitted tasks. Used for `pauseCompactionAndRun()` during snapshots and `interruptCompaction()` during shutdown.

On each flush, the data source calls `submitScanIfNotRunning()` to ensure a scan is in progress, then `submitCompactionTasks()` which discovers levels from `scanResultsByStore` and submits a per-level task for each level without an existing submitted task.

**`GarbageScanner`** is the background scanner. It accepts a `CASableLongIndex`, a `DataFileCollection`, a store name, and `MerkleDbConfig` (from which it reads `garbageThreshold`, `maxCompactionDataPerLevelInKB`, and `maxCompactionLevel`). It traverses the index, computes per-file garbage stats, applies threshold and size-cap filtering, and produces compaction candidates grouped by level (`Map<Integer, List<DataFileReader>>`). The scanner stores its results in `scanResultsByStore`; it does not submit compaction tasks. Scanner instances are created once per data source and reused across flushes.

**`DataFileCompactor`** performs the actual compaction for a given file collection and level. Its key responsibilities are:

- `compactSingleLevel()`: the entry point. It receives a pre-computed list of files at a single level and the target output level,
  creates a new output file, traverses the index to identify live items, copies them, updates the index, and deletes the old files.
  It also handles logging and metrics reporting (duration, saved space, file size by level).

- `compactFiles()`: the inner loop. For each index entry that points to a file in the compaction set, it reads the data item,
  writes it to the new file, and atomically updates the index via `putIfEqual()`. Each item copy is performed under the `snapshotCompactionLock` to coordinate with snapshots.

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

**`DataFileMetadata`** stores per-file metadata in the file header: file index, creation date, compaction level, and
total item count. The compaction level is a byte `(max 127)`, and the item count is set once at file creation.

**`MerkleDbDataSource`** is the top-level data source that ties everything together. It owns the three stores
(`hashChunkStore`, `keyValueStore`, `keyToPath`), the in-memory indices (`idToDiskLocationHashChunks`, `pathToDiskLocationLeafNodes`),
and the `MerkleDbCompactionCoordinator`. It also holds three `GarbageScanner` instances (`chunkStoreScanner`, `pathToKeyValueStoreScanner`, `objectkeyToPathScanner`),
created once during construction and reused across flushes. After each flush, it triggers scanner tasks and submits compaction tasks for eligible levels.

### Edge Cases

**No scan results available yet.** After the first few flushes, scanning tasks may not have completed.
Since `submitCompactionTasks` discovers levels from `scanResultsByStore`, no compaction tasks are submitted until
the first scan completes and populates results. This is correct — compaction simply doesn't start until the first scan finishes.
There is no harm in delaying compaction for a few seconds at startup.

**Compaction interrupted by snapshot.** If a snapshot is requested while compaction is writing to an output file,
the file is flushed and closed via `pauseCompaction()`. After the snapshot, `resumeCompaction()` opens a new output file
and compaction continues. This means a single compaction run may produce multiple output files (all at the same target level).
This is handled transparently — the `newCompactedFiles` list tracks all files produced during one compaction run.
When multiple compactors are active for the same store, each is paused independently.

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

**Multiple compaction tasks for the same store at different levels.** Each task creates its own `DataFileCompactor`
instance with its own `snapshotCompactionLock`, `currentWriter`, `currentReader`, and `newCompactedFiles`.
They operate on disjoint sets of files (different levels), so they do not interfere with each other's data.
They do share the `DataFileCollection`, but `addNewDataFileReader()` and `deleteFiles()` are both atomic CAS-loop
operations and are safe under concurrency.

**File with zero alive items.** A file where all items have been superseded by newer flushes has 100% garbage.
It qualifies for compaction at any threshold. During compaction, the index traversal finds no items pointing to this
file, so nothing is copied to the output. The file is simply deleted. This is correct behavior — the output file will
only contain items from other files in the compaction set.

**All files at a level below the garbage threshold.** If no file at a level exceeds `garbageThreshold`,
that level is not eligible for compaction, regardless of the aggregate garbage across all its files,
compaction is not scheduled for this level.

**New files created during compaction.** Flushes continue while compaction runs, producing new level 0 files.
These files are not included in the current compaction run (the compaction set is fixed at the start).
They will be evaluated in the next scan cycle.

**CAS failure during index update.** When the compactor calls `putIfEqual(path, oldLocation, newLocation)`, the CAS
may fail if a concurrent flush has already updated the index entry for that path to point to an even newer file.
This is correct: the flush's data is more recent, so the compactor's copy should be discarded.
The old file still gets deleted at the end of compaction, which is safe because the index no longer points to it
(it points to the flush's file instead).

**Compaction output file at maxCompactionLevel.** When files at `maxCompactionLevel` are compacted, the output file
stays at the same level (the cap prevents further promotion). This ensures a bounded number of levels and predictable metric cardinality.

### Configuration

The following configuration parameters in `MerkleDbConfig` control compaction behavior:

|            Parameter            | Default |                                              Description                                               |
|---------------------------------|---------|--------------------------------------------------------------------------------------------------------|
| `compactionThreads`             | 6       | Size of the shared thread pool for scanner and compaction tasks.                                       |
| `maxCompactionLevel`            | 5       | Maximum compaction level. Output files at this level stay at this level on subsequent compactions.     |
| `garbageThreshold`              | 0.3     | Garbage ratio that triggers compaction. Files exceeding this ratio are included in the compaction set. |
| `maxCompactionDataPerLevelInKB` | 1000000 | Maximum total size (KB) of files selected for one compaction run per level. 0 disables the cap.        |

### Observability

The following metrics are reported:

- **Compaction duration per level** (`compactionTimeMs`): how long each compaction task took, broken down by store and target compaction level.
- **Space saved per level** (`compactionSavedSpaceMb`): bytes reclaimed per compaction task, reported as the difference between input and output file sizes.
- **File size by level** (`fileSizeByLevelMb`): total disk space used by each compaction level, per store. This shows how data is distributed across levels and whether higher levels are growing.
- **Compaction efficiency ratio**: bytes reclaimed divided by bytes rewritten. This directly measures whether compaction is doing useful work.
- **Scanner duration**: time taken for each index traversal, per store. Tracks whether the scanner is keeping up with the flush rate.
- **Garbage ratio per level**: aggregated from per-file scan results. Shows where garbage is accumulating across the system.
