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
| [`linux-benchmark-results.md`](01-parallel-chunk-writes/linux-benchmark-results.md) | First representative Linux/NVMe campaign | Disk improved modestly; Segment was neutral; corrected baseline is pending |
| [`long-list-snapshot-partial.tar.gz`](01-parallel-chunk-writes/raw/long-list-snapshot-partial.tar.gz) | Raw output behind the first Linux report | Valid partial campaign; stopped during the 5B matrix |

## Experiment index

| Order | Experiment | Result document | Gate and current verdict |
|---:|---|---|---|
| 00 | Prepared-memory FileChannel write reference | `00-filechannel-write-reference/filechannel-write-reference.md` | Required next; not run |
| 01 | Corrected parallel-chunk baseline | [`linux-benchmark-results.md`](01-parallel-chunk-writes/linux-benchmark-results.md) | Required; existing report will be rewritten |
| 01 | `LongListDisk` cache diagnostic | `01-parallel-chunk-writes/disk-cache-diagnostic.md` | Required as part of the baseline; not run |
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
