# LongList snapshot optimization investigation

This directory contains the design, measurements, and decisions for improving
MerkleDb snapshot index writes.

Start with:

1. [`assessment-go-no-go.md`](assessment-go-no-go.md) for the current
   recommendation on this PR.
2. [`snapshot-optimization-report.md`](snapshot-optimization-report.md) for
   the evidence gathered so far and the remaining investigation.
3. [`proposal.md`](01-parallel-chunk-writes/proposal.md) for the parallel-writer
   design and correctness reasoning.

## Current state

- Parallel LongList chunk writing is implemented, correctness-tested, and
  measured with the isolated Linux LongList benchmark.
- Removing the final LongList `force(true)` improves both isolated LongList and
  complete MerkleDB snapshot return time. It remains part of the combined
  production candidate.
- Overlapping the hash-cache pre-flush with independent snapshot tasks passed
  the 100-million-leaf Linux gate in every tested configuration.
- The current combined candidate is two writers per LongList, no final
  LongList force, and hash-cache pre-flush overlap. A larger-state confirmation
  remains before selecting final production defaults.
- The diagnostics closed the physical-preallocation and direct-I/O hypotheses
  without prototypes.
- Compression remains an idea for team discussion because it changes the file
  format and shifts work from storage to the CPU.

## Documents

| Experiment or record | Document | Result |
|---|---|---|
| Prepared-memory FileChannel reference | [`filechannel-write-reference.md`](00-filechannel-write-reference/filechannel-write-reference.md) | Complete; the fastest measured durable 8 GB write is about 12.62 seconds |
| Parallel-writer design | [`proposal.md`](01-parallel-chunk-writes/proposal.md) | Implemented and tested |
| Linux parallel-write baseline | [`linux-benchmark-results.md`](01-parallel-chunk-writes/linux-benchmark-results.md) | Complete equal-sample comparison across all five implementations |
| `LongListDisk` source-cache diagnostic | [`disk-cache-diagnostic.md`](01-parallel-chunk-writes/disk-cache-diagnostic.md) | Complete warm/cold sensitivity check |
| Snapshot phase breakdown | [`phase-breakdown.md`](02-reduce-durable-write-time/phase-breakdown.md) | Complete; the remaining gap occurs before the final force |
| Pre-force write-path diagnostic | [`write-path-diagnostic.md`](02-reduce-durable-write-time/write-path-diagnostic.md) | Complete; no material idle gap exists between target writes |
| Remove the final LongList force | [`remove-final-force.md`](03-remove-final-force/remove-final-force.md) | Earlier LongList return confirmed in isolated and complete-snapshot measurements |
| Unforced parallel-write baseline | [`linux-benchmark-results-without-force.md`](03-remove-final-force/linux-benchmark-results-without-force.md) | Complete equal-sample comparison across all five implementations |
| Hash-cache pre-flush overlap | [`hash-cache-pre-flush-overlap.md`](04-hash-cache-pre-flush-overlap/hash-cache-pre-flush-overlap.md) | 100-million-leaf gate passed; `P=2` combined candidate selected for larger-state confirmation |

Each result document owns its detailed method, measurements, caveats, raw
archive name, and checksum.
