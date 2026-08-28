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
- The configuration default remains one writer per LongList. Higher values are
  available for storage where the measured benefit justifies them.
- Removing the final LongList `force(true)` makes `writeToFile()` return
  earlier and is included in this PR's scope. Its effect on complete snapshot
  time has not yet been measured, and the production path still needs to be
  updated.
- The diagnostics closed the physical-preallocation and direct-I/O hypotheses
  without prototypes.
- Hash-cache pre-flush overlap is being investigated.
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
| Remove the final LongList force | [`remove-final-force.md`](03-remove-final-force/remove-final-force.md) | Earlier LongList return confirmed; included in this PR; complete-snapshot effect not yet measured |
| Unforced parallel-write baseline | [`linux-benchmark-results-without-force.md`](03-remove-final-force/linux-benchmark-results-without-force.md) | Complete equal-sample comparison across all five implementations |

Each result document owns its detailed method, measurements, caveats, raw
archive name, and checksum.
