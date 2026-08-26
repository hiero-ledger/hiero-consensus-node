# LongList snapshot optimization investigation

This directory tracks the evidence, experiments, and final decision for
optimizing MerkleDb snapshot index writes. Start with the
[`snapshot-optimization-report.md`](snapshot-optimization-report.md) execution
plan. Update this index whenever an experiment produces a result.

This index and `snapshot-optimization-report.md` define the current plan and
status. Documents under `01-parallel-chunk-writes/` retain design history and
earlier evidence; their execution recommendations are not current unless this
index or the master plan repeats them.

## Main documents

| Document | Purpose | Status |
|---|---|---|
| [`snapshot-optimization-report.md`](snapshot-optimization-report.md) | Hypotheses, decision gates, and execution order | Active plan |
| [`assessment-go-no-go.md`](assessment-go-no-go.md) | Final cross-experiment recommendation | Placeholder; rewrite after the experiments |

## Existing parallel-write evidence

| Document | Purpose | Current verdict |
|---|---|---|
| [`proposal.md`](01-parallel-chunk-writes/proposal.md) | Design of the parallel LongList chunk writer implemented on this branch | Implemented candidate; final configuration remains undecided |
| [`macos-benchmark-results.md`](01-parallel-chunk-writes/macos-benchmark-results.md) | Development-machine measurements | Historical diagnostic evidence; not used for production decisions |
| [`linux-benchmark-results.md`](01-parallel-chunk-writes/linux-benchmark-results.md) | Complete corrected Linux/NVMe campaign | Equal-sample comparison of all five implementations; supplemental Segment/Disk stability data is labeled separately |
| [`disk-cache-diagnostic.md`](01-parallel-chunk-writes/disk-cache-diagnostic.md) | Focused warm/cold `LongListDisk` comparison | Complete sensitivity check; it does not justify rerunning the complete baseline cold |
| [`20260825T103909Z-3524645.tar.gz`](01-parallel-chunk-writes/raw/20260825T103909Z-3524645.tar.gz) | Raw output behind the corrected Linux report and cache diagnostic | Complete campaign; 45 successful launches |
| [`long-list-snapshot-partial.tar.gz`](01-parallel-chunk-writes/raw/long-list-snapshot-partial.tar.gz) | Raw output behind the first Linux report | Valid partial campaign; stopped during the 5B matrix |

## Experiment index

| Order | Experiment | Result document | Gate and current verdict |
|---:|---|---|---|
| 00 | Prepared-memory FileChannel write reference | [`filechannel-write-reference.md`](00-filechannel-write-reference/filechannel-write-reference.md) | Complete; durable plateau is approximately 12.62 s for 8 GB |
| 01 | Corrected parallel-chunk baseline | [`linux-benchmark-results.md`](01-parallel-chunk-writes/linux-benchmark-results.md) | Complete equal-sample matrix for all five implementations; production thread count remains undecided |
| 01 | `LongListDisk` cache diagnostic | [`disk-cache-diagnostic.md`](01-parallel-chunk-writes/disk-cache-diagnostic.md) | Complete; cold residency increases the parallel benefit but is not the default periodic-snapshot condition |
| 02 | LongList phase breakdown | [`phase-breakdown.md`](02-reduce-durable-write-time/phase-breakdown.md) | Complete; the same-campaign OffHeap, Segment, Disk, and DiskSegment `P=8` gap is 3.18–4.84% and occurs before the final force |
| 02 | Pre-force write-path diagnostic | [`write-path-diagnostic.md`](02-reduce-durable-write-time/write-path-diagnostic.md) | Planned; separate target-write calls from LongList source/preparation time at `P=8` |
| 02 | Physical block preallocation | `02-reduce-durable-write-time/physical-block-preallocation.md` | Run only if profiling implicates file growth |
| 02 | Direct I/O | `02-reduce-durable-write-time/direct-io.md` | Run only if profiling implicates the buffered path |
| 02 | Compression | `02-reduce-durable-write-time/compression.md` | Requires team approval and favorable ratio/load-cost evidence |
| 03 | Remove the final LongList force | `03-remove-final-force/remove-final-force.md` | Required independent experiment; not run |
| 04 | No-force plus preallocation | `04-combine-measured-wins/no-force-plus-preallocation.md` | Run only if both changes independently win |
| 04 | No-force plus compression | `04-combine-measured-wins/no-force-plus-compression.md` | Requires team approval and two independent wins |
| 05 | Overlap the hash-cache pre-flush | `05-overlap-hash-cache-flush/overlap-hash-cache-flush.md` | Requires team approval, then a significant pre-flush measurement |

Planned result paths remain plain text until their documents exist. Large future
raw archives may remain outside Git; each result document must record the
archive name and checksum.
