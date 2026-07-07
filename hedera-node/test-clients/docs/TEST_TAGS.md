# JUnit Tags

A `@HapiTest` (or one of its variants) is included or excluded from a Gradle test task by JUnit's
tag filter. The tag string constants live in
`src/main/java/com/hedera/services/bdd/junit/TestTags.java`; the tag expressions that decide what
each task runs live in `build.gradle.kts` (see [`GRADLE_TASKS.md`](GRADLE_TASKS.md)).

This doc lists every tag and what it means.

## Functional/area tags

These tags partition the suite by feature area. PR-check tasks use them to keep CI shards roughly
balanced; for example, `hapiTestCrypto` includes only `CRYPTO`-tagged tests.

|        Tag         |                                      Used by                                      |                    Meaning                    |
|--------------------|-----------------------------------------------------------------------------------|-----------------------------------------------|
| `CRYPTO`           | `hapiTestCrypto`, `hapiTestCryptoSerial`, `hapiTestCryptoEmbedded`                | Crypto-service tests                          |
| `TOKEN`            | `hapiTestToken`, `hapiTestTokenSerial`                                            | Token-service tests                           |
| `SMART_CONTRACT`   | `hapiTestSmartContract`, `hapiTestSmartContractSerial`                            | Smart-contract / EVM tests                    |
| `SIMPLE_FEES`      | `hapiTestSimpleFees`, `hapiTestSimpleFeesSerial`, `hapiTestSimpleFeesEmbedded`    | "Simple fees" subsystem tests                 |
| `ATOMIC_BATCH`     | `hapiTestAtomicBatch`, `hapiTestAtomicBatchSerial`, `hapiTestAtomicBatchEmbedded` | Atomic-batch transaction tests                |
| `RESTART`          | `hapiTestRestart`                                                                 | Restart-cycle tests                           |
| `UPGRADE`          | `hapiTestRestart`                                                                 | NMT-upgrade tests (runs alongside `RESTART`)  |
| `ND_RECONNECT`     | `hapiTestNDReconnect`                                                             | Node-death reconnect tests                    |
| `BLOCK_NODE`       | `hapiTestBlockNodeCommunication`                                                  | Block-node integration tests                  |
| `WRAPS`            | `hapiTestWraps`, `hapiTestCutover`                                                | TSS `wraps` / weighted re-signing tests       |
| `WRAPS_DOWNLOAD`   | `hapiTestWrapsDownload`                                                           | Variant of WRAPS that downloads a proving key |
| `CUTOVER`          | `hapiTestCutover`                                                                 | TSS cutover scenarios                         |
| `LONG_RUNNING`     | `hapiTestTimeConsuming`, `hapiTestTimeConsumingSerial`                            | Tests that take a long time                   |
| `STATE_THROTTLING` | `hapiTestStateThrottling`                                                         | State-rate-limit tests                        |
| `ISS`              | `hapiTestIss`                                                                     | Intentional Inconsistent State scenarios      |

`hapiTestMisc` and `hapiTestMiscSerial` catch everything not covered by the area tags above (the
expression in `build.gradle.kts` is `!(INTEGRATION|CRYPTO|TOKEN|RESTART|…)`).

## Execution-target tags

These tags force a test onto a particular network type.

|        Tag        | Constant in `TestTags` |                                                                                     Meaning                                                                                      |
|-------------------|------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `EMBEDDED`        | `ONLY_EMBEDDED`        | The test must run in embedded mode. Direct state access or invalid-txn submission requires this. Carried automatically by `@EmbeddedHapiTest` / `@LeakyEmbeddedHapiTest`.        |
| `REPEATABLE`      | `ONLY_REPEATABLE`      | The test must run in repeatable embedded mode (single-threaded, virtual time). Carried automatically by `@RepeatableHapiTest` / `@LeakyRepeatableHapiTest` / `@RestartHapiTest`. |
| `ONLY_SUBPROCESS` | same                   | The test must run in subprocess mode (e.g., requires real gossip).                                                                                                               |
| `NOT_REPEATABLE`  | same                   | The test cannot run under `testRepeatable` (uses real time, randomness, or ECDSA keys).                                                                                          |

`testEmbedded` excludes `RESTART|ND_RECONNECT|UPGRADE|REPEATABLE|ONLY_SUBPROCESS|ISS`.
`testRepeatable` excludes `RESTART|ND_RECONNECT|UPGRADE|EMBEDDED|NOT_REPEATABLE|ONLY_SUBPROCESS|ISS`.

## Execution-mode tags

|      Tag      | Constant in `TestTags` |                                                                                                    Meaning                                                                                                    |
|---------------|------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `SERIAL`      | same                   | Test must run serially, not in parallel with others. Carried automatically by `@LeakyHapiTest` and `@OrderedInIsolation`. Excluded from `testSubprocessConcurrent` except `CONCURRENT_SUBPROCESS_VALIDATION`. |
| `ADHOC`       | same                   | Test can be run alone. Used by `hapiTestAdhoc`.                                                                                                                                                               |
| `INTEGRATION` | same                   | Embedded tests run as part of the default `test` task to exercise app workflows (ingest, pre-handle, handle).                                                                                                 |

## Validation tags

These tags select the validation tests that gate CI runs. See
[`VALIDATORS.md`](VALIDATORS.md) and [`SUBPROCESS_CONCURRENT.md`](SUBPROCESS_CONCURRENT.md).

|                Tag                 |                Where defined                |                                  Meaning                                  |
|------------------------------------|---------------------------------------------|---------------------------------------------------------------------------|
| `LOG_VALIDATION`                   | `LogValidationTest` `@Tag`                  | Tag the log-validation test; included by `testSubprocess`/`testEmbedded`. |
| `STREAM_VALIDATION`                | `StreamValidationTest` `@Tag`               | Tag the stream-validation test; freezes the network, so always runs last. |
| `CONCURRENT_SUBPROCESS_VALIDATION` | `ConcurrentSubprocessValidationTest` `@Tag` | Single class wrapping both validations; bypasses `SERIAL` exclusion.      |

## How tags compose

Gradle task expressions combine tags with `|` (or), `&` (and), and `!` (not). Examples:

- `(CRYPTO&SERIAL)` — `hapiTestCryptoSerial` runs crypto tests that are serial.
- `(CRYPTO|STREAM_VALIDATION|LOG_VALIDATION)&!(EMBEDDED|REPEATABLE)` — `hapiTestCrypto` adds the
  two validation classes and excludes embedded/repeatable tests.
- `SERIAL&!CONCURRENT_SUBPROCESS_VALIDATION` — `testSubprocessConcurrent` excludes serial tests
  *unless* they are the concurrent validation wrapper.

## See also

- [HAPITEST_ANNOTATIONS.md](HAPITEST_ANNOTATIONS.md) — which annotations carry which tags.
- [GRADLE_TASKS.md](GRADLE_TASKS.md) — full tag expression per task.
- [VALIDATORS.md](VALIDATORS.md) — what the validation tags do.
