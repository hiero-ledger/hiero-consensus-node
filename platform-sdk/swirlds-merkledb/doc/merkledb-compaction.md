# MerkleDb Data File Compaction

---

## Summary

MerkleDb stores data in append-only files. When data items are updated or deleted, old versions remain in their original files and become garbage. Compaction is the background process that reclaims this wasted space by copying still-alive data items into new files and deleting the old ones. This document describes how compaction works, the algorithms that drive it, and the classes that implement it.

## What is Compaction and Why It is Needed

MerkleDb is the storage engine behind `VirtualMap`. It persists three categories of data, each in its own file collection (store):

- **HashStoreDisk** — hashes of internal nodes in the virtual Merkle tree.
- **PathToKeyValue** — leaf node data (keys and values), indexed by tree path.
- **ObjectKeyToPath** — a mapping from application keys to their current tree paths.

Each store follows an append-only file model. During a flush, all new or updated data items for that store are written sequentially to a fresh data file. The in-memory index is updated to point to the new locations. The old data items in previous files are not modified or deleted — they simply become unreachable from the index. These unreachable items are garbage.

Without compaction, the number of data files would grow without bound, and the fraction of live data in each file would shrink over time. Disk usage would far exceed the actual state size. Compaction solves this by periodically identifying files with significant garbage, copying their live data into new files, updating the index to point to the new locations, and deleting the old files.

Compaction is a lower-priority background process. It must not interfere with transaction handling or the main MerkleDb operations (insertions, updates, and deletions). Achieving a high degree of compaction parallelism is explicitly a non-goal, as it would contend with main operations for CPU and disk resources.

## How Compaction Works

### Data Files and Compaction Levels

Every data file has a compaction level, stored in the file's metadata (`DataFileMetadata.compactionLevel`). Files produced by flushes are level 0. When files at level N are compacted, the output file is promoted to level N + 1, capped at `maxCompactionLevel` (configurable, default 5).

Levels serve as a stability proxy. Data that has survived multiple compaction rounds and reached a higher level tends to change less frequently than freshly flushed data at level 0. This property is used to avoid mixing data of different stability — compaction processes files at a single level only, never mixing levels in the same compaction task. The output is always promoted to the next level, keeping stable data separated from volatile data.

### Total Item Count Per File

Every data file records the total number of data items it contains. This count is set once when the file is finalized — in `DataFileCollection.endWriting()` for flush files and in `DataFileCompactor.finishCurrentCompactionFile()` for compaction output files — and never changes. The count is stored in the file header via `DataFileMetadata.itemsCount` and is available at runtime through `DataFileReader`.

This immutable count serves as the denominator for garbage ratio calculations: it tells us how many items the file started with, regardless of how many have since been superseded.

### Garbage Estimation via Index Scanning

The system needs to know how much garbage each file contains in order to make compaction decisions. Rather than maintaining real-time counters on the hot path, garbage is estimated by a background scanner task.

The scanner traverses the in-memory (off-heap) index for a given file collection. For each index entry, the scanner checks which file the entry points to and increments an alive counter for that file. After the full traversal, the scanner knows how many items in each file are still referenced by the index. The garbage ratio for a file is then:

```
garbageRatio(file) = 1 - (aliveItems / totalItems)
```

A critical property makes periodic scanning viable rather than continuous tracking: **garbage only grows between compactions**. A file's alive count can only decrease (as flushes write newer versions of its items) or stay the same. It never increases, because compaction always writes to new files, not existing ones. A scan result that says "file X has 30% garbage" is therefore a conservative underestimate by the time it is consumed. Stale results lead to compacting slightly more than strictly necessary, never less — a safe direction to err in.

The scanner is read-only with respect to data files. It only reads the index, which resides in off-heap memory. There is no disk I/O involved, making the scanner a lightweight background task that does not compete with flushes for disk bandwidth.

### Compaction Triggering

Compaction decisions are driven by garbage thresholds, not file counts. Two configuration parameters control triggering:

- `minGarbageThreshold` (default 0.2): files above this ratio are included in compaction for a level, provided that compaction is triggered for that level.
- `maxGarbageThreshold` (default 0.4): if at least one file at a given level exceeds this ratio, compaction is triggered for that level.

The two-threshold design avoids two failure modes. Without the max threshold, borderline files (e.g. just above 20%) would trigger compaction prematurely. Without the min threshold, only files above max would be included, leaving moderately garbage-filled files at the same level to accumulate into a larger problem.

When a level is selected for compaction, all files at that level exceeding `minGarbageThreshold` are collected as the compaction set. These files are compacted together into one (or, in rare cases involving snapshot interrupts, multiple) output file at the next level. The index is traversed, and for each data item whose current location points into one of the files being compacted, the item is read from the old file, written to the new file, and the index is updated atomically via `CASableLongIndex.putIfEqual()`. Files at the same level with garbage below the min threshold are left untouched.

### Level Selection and Starvation Prevention

Each scan cycle selects one level for compaction per store. The default policy is **lowest eligible level first**, since level 0 files accumulate garbage fastest and compacting them yields the quickest space recovery.

However, a strict lowest-level-first policy can starve higher levels if level 0 is continuously eligible. To prevent this, each level maintains an aging counter (`skippedCount`) that tracks how many consecutive scan cycles the level has been eligible but not selected.

The effective starvation threshold scales linearly with level:

```
effectiveThreshold(level) = starvationThreshold * (level + 1)
```

Where `starvationThreshold` is a single configurable base value (default 3). This means level 0 is preempted after 3 skips, level 1 after 6, level 2 after 9, and so on. The linear scaling reflects the fact that higher levels accumulate garbage more slowly and can tolerate longer waits.

The aging counter is naturally adaptive to state growth. As state size increases from 100M to 1B entries, each compaction round takes longer, scans take longer, and the entire cycle stretches out. The counter accounts for this because it measures cycles, not wall-clock time. A threshold of 3 means "3 cycles" regardless of whether each cycle takes 2 seconds or 2 minutes.

The full level selection algorithm per scan cycle:

1. Evaluate all levels. A level is eligible if at least one of its files exceeds `maxGarbageThreshold`.
2. Among eligible levels, check if any has `skippedCount >= starvationThreshold * (level + 1)`.
3. If yes, pick the lowest level among those that exceeded the starvation threshold.
4. If no, pick the lowest eligible level.
5. Increment `skippedCount` for all eligible levels that were not picked.
6. Reset `skippedCount` for the picked level and for any level that drops below eligibility.

### The Scanner-Compaction Cycle

The scanner and compactor form a self-sustaining cycle:

1. After a flush, a scanner task is submitted for each store (if one is not already running).
2. The scanner traverses the index and computes per-file alive counts.
3. The scanner evaluates levels using the threshold and starvation logic described above.
4. If an eligible level is found, the scanner submits a compaction task for that store (if one is not already running).
5. The scanner task exits, freeing its thread.
6. The compaction task runs, processes the selected level, produces an output file at level + 1, and deletes the old files.
7. When the compaction task completes, it submits a new scanner task for its store.
8. The cycle repeats until a scanner finds no level worth compacting.

This cycle is self-regulating. Under heavy write load, garbage accumulates fast, and the cycle runs frequently. Under light load, scans find little garbage and no compaction tasks are submitted. The system naturally reaches equilibrium where garbage ratios stay within the configured bounds.

### Snapshot Interaction

Snapshots require all data files to be in a consistent, read-only state so they can be hard-linked to a target directory. If compaction is in progress and a new file is being written to, that file must be flushed and finalized before the snapshot can proceed.

This is handled through `DataFileCompactor.pauseCompaction()` and `resumeCompaction()`, coordinated via a `snapshotCompactionLock`. When a snapshot is requested:

1. `pauseCompaction()` acquires the lock. If compaction is writing to a file, the file is flushed and closed. No further writes occur until the snapshot completes.
2. The snapshot is taken (hard links are created).
3. `resumeCompaction()` opens a new output file and releases the lock. Compaction resumes where it left off.

If no compaction is in progress, `pauseCompaction()` simply acquires the lock (preventing a new compaction from starting), and `resumeCompaction()` releases it.

## Implementation

### Thread Pool and Concurrency Model

All compaction-related tasks (both scanning and compaction) run on a shared fixed-size `ThreadPoolExecutor`, managed by `MerkleDbCompactionCoordinator`. The pool size is 6, accommodating two task types (scanner and compactor) for each of the three stores.

The concurrency constraints are:

- At most one scanner task per store at any time.
- At most one compaction task per store at any time.
- Scanner and compactor for the same store may run concurrently (they occupy separate slots).
- Different stores are fully independent and may run tasks in parallel.

The `MerkleDbCompactionCoordinator` tracks running tasks with keys that encode both the store name and the task type (e.g. `"HashStoreDisk_scan"` and `"HashStoreDisk_compact"`). The `compactIfNotRunningYet()` method checks whether a task with the given key is already running before submitting a new one. When a task completes, it removes its key from the running-tasks map and calls `notifyAll()` to wake any threads waiting on completion.

### Key Classes

**`MerkleDbCompactionCoordinator`** is the central orchestrator. It owns the shared thread pool, tracks which tasks are running, and provides the API for submitting scanner and compaction tasks. It also provides `pauseCompaction()` and `resumeCompaction()` methods that delegate to all active `DataFileCompactor` instances, enabling snapshots to put all compaction on hold.

**`GarbageScannerTask`** is the background scanner. It accepts a `CASableLongIndex` and a `DataFileCollection`, traverses the index, and produces a map from file index to alive item count. It then evaluates compaction eligibility per level using the threshold and starvation logic. If a level qualifies, the scanner submits a compaction task via `MerkleDbCompactionCoordinator`. The scanner also maintains the per-level `skippedCount` counters.

**`DataFileCompactor`** performs the actual compaction for a given file collection. Its key responsibilities are:

- `compact()`: the entry point. It receives a pre-computed list of files at a single level, creates a new output file at the next level, traverses the index to identify live items in those files, copies them to the output file, updates the index, and deletes the old files.
- `compactFiles()`: the inner loop. For each index entry that points to a file in the compaction set, it reads the data item from the old file, writes it to the new file via `DataFileWriter.storeDataItemWithTag()`, and atomically updates the index via `CASableLongIndex.putIfEqual()`.
- `pauseCompaction()` / `resumeCompaction()`: coordinate with snapshots. `pauseCompaction()` acquires the `snapshotCompactionLock`, flushes and closes the current output file if compaction is in progress. `resumeCompaction()` opens a new output file and releases the lock.
- `interruptCompaction()`: sets a volatile flag that the main compaction loop checks periodically, providing a non-invasive way to stop a running compaction without `Thread.interrupt()` side effects.

**`DataFileCollection`** manages the set of data files for a single store. It provides methods to create new files (`startWriting()` / `endWriting()`), add readers for compaction output files (`addNewDataFileReader()`), delete compacted files (`deleteFiles()`), and retrieve the list of all completed files (`getAllCompletedFiles()`). The file list is stored as an `AtomicReference<ImmutableIndexedObjectList<DataFileReader>>`, which is safe for concurrent reads from the flush and compaction threads.

**`DataFileReader`** represents a single data file and provides read access to its data items. It holds the file's `DataFileMetadata` (including compaction level and item count) and tracks whether the file has been fully written (`setFileCompleted()`). Only completed files are eligible for compaction.

**`DataFileMetadata`** stores per-file metadata in the file header: file index, creation date, compaction level, and total item count. The compaction level is a byte (max 127), and the item count is set once at file creation.

**`MerkleDbDataSource`** is the top-level data source that ties everything together. It owns the three stores (`hashStoreDisk`, `pathToKeyValue`, `keyToPath`), the in-memory indices (`pathToDiskLocationInternalNodes`, `pathToDiskLocationLeafNodes`), and the `MerkleDbCompactionCoordinator`. After each flush, it triggers compaction by calling `runHashStoreCompaction()`, `runPathToKeyStoreCompaction()`, and `runKeyToPathStoreCompaction()`, each of which submits a scanner task for the corresponding store.

### Edge Cases

**Compaction interrupted by snapshot.** If a snapshot is requested while compaction is writing to an output file, the file is flushed and closed via `pauseCompaction()`. After the snapshot, `resumeCompaction()` opens a new output file and compaction continues. This means a single compaction run may produce multiple output files (all at the same target level). This is handled transparently — the `newCompactedFiles` list tracks all files produced during one compaction run.

**Compaction interrupted by shutdown.** The `interruptCompaction()` method sets a volatile flag that the main loop checks between data items. If the flag is set, compaction stops, and any files that were not fully processed are left in place for the next compaction run. The partially written output file is finalized and included in future compactions. The unprocessed input files are not deleted.

**Scanner runs concurrently with compaction for the same store.** This is allowed. The scanner reads the index, which is being atomically updated by the compactor. The scanner might see some entries pointing to old files and others pointing to new files. This is harmless: the worst outcome is a slightly imprecise alive count, which is acceptable since garbage only grows between compactions.

**Scanner runs concurrently with flush for the same store.** Also allowed and harmless for the same reason. The flush writes new data items and updates the index. The scanner might miss some updates, leading to a slight overcount of alive items in old files. The next scan will correct this.

**File with zero alive items.** A file where all items have been superseded by newer flushes has 100% garbage. It qualifies for compaction at any threshold. During compaction, the index traversal finds no items pointing to this file, so nothing is copied to the output. The file is simply deleted. This is correct behavior — the output file will only contain items from other files in the compaction set.

**All files at a level below the min threshold.** If no file at a level exceeds `maxGarbageThreshold`, that level is not eligible for compaction, regardless of the aggregate garbage across all its files. This is intentional — it prevents premature compaction when garbage is spread thinly across many files.

**Starvation counter overflow.** The `skippedCount` is a simple integer. In practice, it cannot overflow because the starvation threshold kicks in well before that point. Even with `starvationThreshold = 3` and `maxCompactionLevel = 5`, the highest effective threshold is `3 * 6 = 18`, meaning no level can accumulate more than 18 skips before being served.

**New files created during compaction.** Flushes continue while compaction runs, producing new level 0 files. These files are not included in the current compaction run (the compaction set is fixed at the start). They will be evaluated in the next scanner cycle.

**CAS failure during index update.** When the compactor calls `putIfEqual(path, oldLocation, newLocation)`, the CAS may fail if a concurrent flush has already updated the index entry for that path to point to an even newer file. This is correct: the flush's data is more recent, so the compactor's copy should be discarded. The old file still gets deleted at the end of compaction, which is safe because the index no longer points to it (it points to the flush's file instead).

### Configuration

The following configuration parameters in `MerkleDbConfig` control compaction behavior:

| Parameter | Default | Description |
|---|---|---|
| `compactionThreads` | 6 | Size of the shared thread pool for scanner and compaction tasks. |
| `maxCompactionLevel` | 5 | Maximum compaction level. Output files at this level stay at this level on subsequent compactions. |
| `minGarbageThreshold` | 0.2 | Minimum garbage ratio for a file to be included in compaction for an eligible level. |
| `maxGarbageThreshold` | 0.4 | Garbage ratio that triggers compaction for a level (at least one file must exceed this). |
| `starvationThreshold` | 3 | Base value for the per-level starvation counter. Effective threshold for level N is `starvationThreshold * (N + 1)`. |

### Observability

The following metrics are reported:

- **Compaction duration per level** (`compactionTimeMs`): how long each compaction task took, broken down by store and target compaction level.
- **Space saved per level** (`compactionSavedSpaceMb`): bytes reclaimed per compaction task, reported as the difference between input and output file sizes.
- **File size by level** (`fileSizeByLevelMb`): total disk space used by each compaction level, per store. This shows how data is distributed across levels and whether higher levels are growing.
- **Compaction efficiency ratio**: bytes reclaimed divided by bytes rewritten. This directly measures whether compaction is doing useful work.
- **Scanner duration**: time taken for each index traversal, per store. Tracks whether the scanner is keeping up with the flush rate.
- **Garbage ratio per level**: aggregated from per-file scan results. Shows where garbage is accumulating across the system.
- **Skipped count per level**: the current starvation counter value, per store and level. Enables operators to detect if higher levels are being starved.
