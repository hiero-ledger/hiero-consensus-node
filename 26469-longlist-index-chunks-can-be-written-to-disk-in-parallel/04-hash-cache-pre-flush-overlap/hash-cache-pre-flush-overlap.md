# Hash-cache pre-flush overlap

> **Status:** The 100-million-leaf Linux gate passed. Overlap reduced
> `MerkleDbDataSource.snapshot()` time in every tested configuration and every
> reordered block. The combined candidate is unforced overlap with two writer
> threads per LongList; a larger-state confirmation remains before selecting
> final production defaults.

## Question

Can a snapshot return earlier if the four tasks that do not use the hash store
start while the in-memory hash cache is flushed, instead of waiting for that
flush to finish?

Only the hash-store and hash-index snapshots depend on the flush:

```text
Serial:   [ hash-cache flush ][ all six snapshot tasks ............. ]

Overlap:  [ four independent snapshot tasks ....................... ]
          [ hash-cache flush ]--[ hash-store + hash-index tasks .... ]
```

## Method

The benchmark created a 100-million-leaf MerkleDB fixture and restored it into
a disposable data source for each trial. It loaded all 262,144 configured hash
chunks into that source's cache before measuring snapshots.

| Parameter | Value |
|---|---|
| Leaf records | `100,000,000` (`1,000` files of `100,000` records) |
| Provisioned capacity | Default `1,000,000,000` keys |
| Key / record size | 32 / 128 bytes |
| Cached hash chunks | `262,144` |
| Index modes | Segment and Disk |
| LongList chunk size | Default `1,048,576` longs |
| LongList writers | `P={1,2,8}` per list |
| Snapshot modes | Forced serial, forced overlap, unforced serial, unforced overlap |
| Sampling | Three reordered blocks; one warmup and three measurements per cell |

Every mean below is calculated from the nine raw measurements for that cell.
After each measured return, teardown forced all snapshot files, validated the
snapshot, and deleted it. That post-return work was outside the measured time,
so pending writeback from one invocation could not leak into the next.

## Results

All values are mean `MerkleDbDataSource.snapshot()` return times in seconds.

### Segment indices

| Writers per LongList | Forced, serial flush | Forced, overlap | Unforced, serial flush | Unforced, overlap |
|---:|---:|---:|---:|---:|
| `P=1` | 2.853 | 2.046 | 1.580 | 1.054 |
| `P=2` | 2.958 | 1.989 | 1.511 | **1.037** |
| `P=8` | 3.250 | 1.906 | 1.434 | 1.042 |

### Disk indices

| Writers per LongList | Forced, serial flush | Forced, overlap | Unforced, serial flush | Unforced, overlap |
|---:|---:|---:|---:|---:|
| `P=1` | 3.510 | 2.483 | 2.128 | 1.439 |
| `P=2` | 3.822 | **1.974** | 1.891 | **1.394** |
| `P=8` | 3.600 | 1.997 | 1.844 | 1.404 |

### Effect of overlap alone

Each percentage compares overlap with the serial-flush result at the same
index mode, writer count, and force setting.

| Index mode | Writers | With final force | Without final force |
|---|---:|---:|---:|
| Segment | `P=1` | 28.3% faster | 33.3% faster |
| Segment | `P=2` | 32.8% faster | 31.4% faster |
| Segment | `P=8` | 41.3% faster | 27.3% faster |
| Disk | `P=1` | 29.3% faster | 32.4% faster |
| Disk | `P=2` | 48.3% faster | 26.3% faster |
| Disk | `P=8` | 44.5% faster | 23.9% faster |

The forced path was noisier, particularly Disk at `P=2`, so its exact larger
percentages should not be treated as precise estimates. The direction is not
in doubt: overlap won all 18 block-level comparisons, and the smallest
block-level reduction was 22.7%.

### Combined candidate against the branch baseline

The branch baseline is one writer per LongList, the final LongList force, and
the serial hash-cache flush. The candidate uses two writers, no final LongList
force, and the overlapping flush schedule.

| Index mode | Baseline mean / slowest | Candidate mean / slowest | Mean reduction |
|---|---:|---:|---:|
| Segment | 2.853 / 3.200 s | 1.037 / 1.059 s | 63.7% |
| Disk | 3.510 / 3.715 s | 1.394 / 1.409 s | 60.3% |

The candidate beat the baseline in every reordered block. Its block-level
reductions were 61.7-65.1% for Segment and 58.9-62.1% for Disk.

## Conclusions

1. **Hash-cache overlap passed the gate.** It adds a substantial improvement
   both with and without the final LongList force. The result is much larger
   than the measured variation and reproduced in every block.
2. **Removing the final LongList force also improves complete-snapshot return
   time.** With overlap already enabled, removing the force reduced the mean
   by 45.3-48.5% for Segment and 29.4-42.0% for Disk. The earlier LongList
   experiments established that this moves storage waiting past the return;
   it does not eliminate the work.
3. **Two writers are the useful candidate.** In the combined unforced-overlap
   mode, `P=2` had the lowest mean for both index modes and beat `P=1` in every
   block. `P=8` provided no further benefit, so its additional threads and
   buffers are not justified by this workload.
4. **Parallel writing is not independently beneficial in this complete
   snapshot.** With the force and serial flush retained, both higher writer
   counts were slower than `P=1`. Its small final benefit appears after the
   final force is removed and the cache flush is overlapped.

This fixture has a 100-million-leaf index while the cache flush is already at
its configured 262,144-chunk threshold. At a larger state the leaf index takes
longer to write while the cache threshold remains fixed, so the percentage
saved by overlap may shrink. The next confirmation should therefore compare
the forced `P=1` baseline with unforced overlap at `P={1,2}` on a larger state,
for both index modes. `P=8` does not need another run unless that confirmation
changes the ranking.

## Raw evidence

- Git revision: `bebb2190892744d91350ee12917d20344438f727`
- Environment: Temurin 25.0.2, AMD EPYC 9124, 125 GiB RAM, ext4 on a
  Micron 7450 NVMe
- Archive:
  [`20260828T113202Z-1105376.tar.gz`](raw/20260828T113202Z-1105376.tar.gz)
- Archive SHA-256:
  `2aa1c8dbfa521e02cb9853f2ef89e7acc862e3129024d4d05f292114822f7980`
- Console log:
  [`20260828T113202Z-1105376-console.log`](raw/20260828T113202Z-1105376-console.log)
- Console SHA-256:
  `607624d0e32661d9fc7c50e429d222ab99ecd9fd11fbc6dbd9bf7fffed58a0cf`

The archive contains the exact runner, environment, settings, build log, phase
logs, and all three JSON result files. All 72 JSON rows are present, each has
three finite raw measurements, and all 24 planned cells appear in every block.
