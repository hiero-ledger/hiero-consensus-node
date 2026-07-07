# `rcdiff`

`rcdiff` is a standalone CLI for diffing two Hedera record streams. It's useful when:

- A `TransactionRecordParityValidator` failure dumps a "translated" record-stream that must be
  compared byte-by-byte to the recorded one.
- A divergence between two networks or two software versions is under investigation and a bounded,
  time-windowed diff is required rather than a full sweep.

The tool produces a `diffs.txt` report describing each difference (transaction mismatch, record
mismatch, or consensus-time mismatch) in the order encountered.

## Build

`rcdiff` lives in its own Gradle source set (`sourceSets { create("rcdiff") }` in
`test-clients/build.gradle.kts`). The build produces a shaded jar via the `rcdiffJar` task:

```bash
./gradlew :test-clients:rcdiffJar
```

The output is `hedera-node/test-clients/rcdiff/rcdiff.jar`, with manifest entry
`Main-Class: com.hedera.services.rcdiff.RcDiffCmdWrapper`.

## Code layout

```
test-clients/
├── src/rcdiff/java/
│   ├── module-info.java                       (module: com.hedera.node.test.clients.rcdiff)
│   └── com/hedera/services/rcdiff/
│       └── RcDiffCmdWrapper.java              (picocli CLI shell)
└── src/main/java/
    └── com/hedera/services/bdd/utils/
        └── RcDiff.java                        (the actual diff engine)
```

`RcDiffCmdWrapper` is a thin picocli `@Command` that parses CLI arguments and delegates to the
`RcDiff` class living in the main source set. This split is what allows the same diff engine to be
used both as a standalone jar **and** by `TransactionRecordParityValidator` inside test runs.

## CLI usage

```
java -jar rcdiff.jar \
  --expected-stream <dir of expected record files> \
  --actual-stream   <dir of actual record files> \
  [--diffs diffs.txt] \
  [--len-of-diff-secs 300] \
  [--max-diffs-to-export 10]
```

Short flags:

|          Long           | Short |   Default   |                                                                   Meaning                                                                    |
|-------------------------|-------|-------------|----------------------------------------------------------------------------------------------------------------------------------------------|
| `--expected-stream`     | `-e`  | —           | Directory containing the *expected* record-stream files.                                                                                     |
| `--actual-stream`       | `-a`  | —           | Directory containing the *actual* record-stream files.                                                                                       |
| `--diffs`               | `-d`  | `diffs.txt` | Output report path.                                                                                                                          |
| `--len-of-diff-secs`    | `-l`  | `300`       | Length of the time window (seconds) to diff, starting at the earliest record present in either stream. Records past this window are ignored. |
| `--max-diffs-to-export` | `-m`  | `10`        | Maximum number of diffs to write to the report.                                                                                              |

The window comes from `min(expected.first.consensusTimestamp, actual.first.consensusTimestamp)`
through `+ lenOfDiffSecs`. Choose `--len-of-diff-secs` smaller than the full run to do quick
spot-checks; choose it larger to cover the entire stream.

## Diff types

`RcDiff` uses `OrderedComparison.findDifferencesBetweenV6` under the hood, which classifies
differences as one of `DifferingEntries.FirstEncounteredDifference`:

- `CONSENSUS_TIME_MISMATCH` — the same logical transaction was assigned a different consensus
  time in each stream.
- `TRANSACTION_MISMATCH` — the transaction bodies differ at the same consensus time.
- `TRANSACTION_RECORD_MISMATCH` — bodies match but the resulting records (status, transfers,
  fees, etc.) differ.

The report includes the first `--max-diffs-to-export` differences in encounter order; if there
are more, the count is recorded in the summary.

## Programmatic use

In tests, `RcDiff` is constructed directly with the lists of `RecordStreamEntry`s already in
memory, then `call()` returns an exit code (and exposes `summarizeDiffs()` for assertion
messages). The `RcDiff.fromDirs(...)` factory mirrors the CLI shape and is what
`RcDiffCmdWrapper.call()` invokes.

## When to reach for `rcdiff` vs. running the parity test

- **Investigating a flaky parity failure** → look at the JUnit message first; it already invokes
  `RcDiff` and embeds a summary.
- **Comparing two whole-network captures** (e.g., prerelease vs. mainnet shadow run) → build the
  jar and run it from the shell with `--len-of-diff-secs` covering the full window.
- **Spot-checking a single block** → pass narrow `--len-of-diff-secs` to keep the run fast.

## See also

- [VALIDATORS.md](VALIDATORS.md) — `TransactionRecordParityValidator` is the main in-test caller.
- [BLOCK_STREAM_TRANSLATORS.md](BLOCK_STREAM_TRANSLATORS.md) — produces the "translated" records
  that `RcDiff` compares.
- [GRADLE_TASKS.md](GRADLE_TASKS.md) — `rcdiffJar` task.
