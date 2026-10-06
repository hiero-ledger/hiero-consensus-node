# Validators

After every CI test task, the framework runs a battery of validators that ensure the produced
streams, state, and logs are consistent. This doc inventories them so the right validator can be
found for a given failure, and so they can be extended when new behaviour is added.

There are three categories:

1. **Block-stream validators** — implement `BlockStreamValidator` and inspect produced blocks.
2. **Record-stream validators** — implement `RecordStreamValidator` and inspect produced record
   files.
3. **Log validators** — read node logs and reject unexpected error patterns.

The validation classes themselves (`LogValidationTest`, `StreamValidationTest`,
`ConcurrentSubprocessValidationTest`) are normal `@LeakyHapiTest`s that JUnit orders last; see
[`SUBPROCESS_CONCURRENT.md`](SUBPROCESS_CONCURRENT.md) for the ordering machinery.

## `BlockStreamValidator` interface

```java
public interface BlockStreamValidator {
    interface Factory {
        default boolean appliesTo(@NonNull HapiSpec spec) { return true; }
        @NonNull BlockStreamValidator create(@NonNull HapiSpec spec);
    }

    default Stream<Throwable> validationErrorsIn(
        @NonNull List<Block> blocks,
        @NonNull StreamFileAccess.RecordStreamData data) { … }

    default void validateBlockVsRecords(
        @NonNull List<Block> blocks,
        @NonNull StreamFileAccess.RecordStreamData data) { … }

    default void validateBlocks(@NonNull List<Block> blocks) { /* no-op */ }
}
```

Subclasses override `validateBlocks` for stream-only checks and `validateBlockVsRecords` when they
need both block and record streams.

## Block-stream validators (`support/validators/block/`)

|                             Validator                             | Embedded | Subprocess |                                                                                                                          What it checks                                                                                                                          |
|-------------------------------------------------------------------|----------|------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `BlockContentsValidator`                                          | yes      | yes        | Each block is structurally well-formed; works for both standard blocks and Wrapped Record Blocks (WRBs).                                                                                                                                                         |
| `BlockNumberSequenceValidator`                                    | yes      | yes        | Block numbers are sequential with no gaps or repeats.                                                                                                                                                                                                            |
| `StateChangesValidator`                                           | no       | yes        | Applies the block stream's state changes to a fresh state and asserts the resulting root hash matches the saved Merkle state on disk. Reads `hapi.spec.hintsThresholdDenominator` (default 3, or 4 for `hapiTestRestart`) and `hapi.spec.assertAtLeastOneWraps`. |
| `TransactionRecordParityValidator`                                | no       | yes        | Translates the block stream into legacy records via `BlockTransactionalUnitTranslator` and diffs the result against the recorded stream using `RcDiff`.                                                                                                          |
| `BinaryStateChangesValidator`                                     | —        | —          | Validates the binary state-changes encoding within blocks.                                                                                                                                                                                                       |
| `EventHashBlockStreamValidator`                                   | —        | —          | Verifies block-event hashes line up with the PCES events the platform produced.                                                                                                                                                                                  |
| `IndirectProofSequenceValidator`                                  | —        | —          | Validates indirect-proof ordering.                                                                                                                                                                                                                               |
| `RedactingEventHashBlockStreamValidator`                          | —        | —          | Variant of the event-hash validator that tolerates redacted entries.                                                                                                                                                                                             |
| `WrbRecordFileValidator`                                          | —        | —          | Validates Wrapped Record Block contents against record files.                                                                                                                                                                                                    |
| `RootHashUtils`, `PcesEventHashReader`, `BlockStreamEventBuilder` | —        | —          | Shared helpers used by the validators above.                                                                                                                                                                                                                     |

(Embedded/Subprocess columns mark the four validators registered by default in
[`SUBPROCESS_CONCURRENT.md`](SUBPROCESS_CONCURRENT.md). The remaining classes are run by specific
tests or PR-check tasks.)

## `RecordStreamValidator` interface

```java
public interface RecordStreamValidator {
    default void validateFiles(List<RecordStreamFile> files) { /* no-op */ }
    default void validateRecordsAndSidecars(List<RecordWithSidecars> records) { /* no-op */ }
}
```

Implementations live under `support/validators/`. They cover the legacy record-stream side of
the parity contract:

|                  Validator                  |                          What it checks                          |
|---------------------------------------------|------------------------------------------------------------------|
| `BalanceReconciliationValidator`            | Account balance deltas match transfer entries in the record.     |
| `BlockNoValidator`                          | Record-file block numbers line up.                               |
| `ContractExistenceValidator`                | Contracts referenced by results actually exist in state.         |
| `ExpiryRecordsValidator`                    | Expiry-driven records have valid `consensusTimestamp`s.          |
| `HighVolumePricingValidator`                | High-volume fee scaling matches the configured curve.            |
| `RunningHashChainValidator`                 | Running-hash continuity across record files.                     |
| `TokenReconciliationValidator`              | Token-supply changes match mint/burn/wipe operations.            |
| `TransactionBodyValidator`                  | Every record's transaction body deserializes and is well-formed. |
| `WrappedRecordHashesByRecordFilesValidator` | WRB hashes match the hashes computed from record files.          |
| `AccountNumTokenNum`, `utils/`              | Helpers for the above.                                           |

## Log validators (`support/validators/`)

|       Validator       |                                          What it checks                                           |
|-----------------------|---------------------------------------------------------------------------------------------------|
| `HgcaaLogValidator`   | Scans `hgcaa.log` for unexpected error lines; allows known/expected messages from a curated list. |
| `SwirldsLogValidator` | Same idea for `swirlds.log`.                                                                      |
| `QueryLogValidator`   | Validates query workflow log entries.                                                             |

The `validateAllLogsAfter(Duration)` `SpecOperation` (used by `LogValidationTest`) is what drives
the log validators after a delay long enough for in-flight log writes to flush.

## How validators are wired into a test run

For subprocess tasks the chain is:

1. `StreamValidationTest.streamsAreValid()` (or the concurrent wrapper) calls
   `UtilVerbs.validateStreams()`.
2. `validateStreams()` collects every registered `BlockStreamValidator.Factory` whose
   `appliesTo(spec)` returns true and every `RecordStreamValidator`.
3. Each validator's `validationErrorsIn(...)` is invoked; the operation fails if any error stream
   is non-empty.

For embedded tasks only `BlockContentsValidator` and `BlockNumberSequenceValidator` apply, since
there is no persistent Merkle state to root-hash against and the recorded stream is not produced.

## Adding a new validator

- **Block stream:** implement `BlockStreamValidator` plus a `Factory`, drop it under
  `support/validators/block/`, and register the factory in the place that wires validators (most
  validators expose a `FACTORY` constant; `validateStreams()` discovers them). Use
  `appliesTo(spec)` to limit it to relevant network types or tags.
- **Record stream:** implement `RecordStreamValidator` and register similarly.
- **Log:** add a new validator class and call it from the appropriate `*LogValidator` orchestrator.
- Either way, add a unit test for the validator itself if its logic is non-trivial — several
  validators have a `main(String[] args)` that runs them against a saved working directory for
  ad-hoc debugging.

## See also

- [SUBPROCESS_CONCURRENT.md](SUBPROCESS_CONCURRENT.md) — how the validation classes are ordered
  in subprocess concurrent mode.
- [BLOCK_STREAM_TRANSLATORS.md](BLOCK_STREAM_TRANSLATORS.md) — feeds
  `TransactionRecordParityValidator`.
- [RCDIFF.md](RCDIFF.md) — the diff engine the parity validator uses.
- [SYSTEM_PROPERTIES.md](SYSTEM_PROPERTIES.md) — `hapi.spec.hintsThresholdDenominator`,
  `hapi.spec.assertAtLeastOneWraps`.
