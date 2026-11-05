# Gradle Tasks

`test-clients/build.gradle.kts` defines base `Test` tasks plus dozens of `hapiTest*` and
`remoteTest*` PR-check tasks that delegate to them with specific tag/network/property combinations.

## Base test tasks

|            Task            |            Targets             |             Tag filter             |                                                                                                                                                       Notes                                                                                                                                                        |
|----------------------------|--------------------------------|------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `unitTest`                 | Unit tests from `src/test`     | none                               | Runs all test-client unit tests with JUnit Platform. The `test` task depends on it, so CI's test aggregation runs it automatically. Run directly with `./gradlew :test-clients:unitTest`.                                                                                                                          |
| `test`                     | Embedded (per-class)           | `(INTEGRATION\|STREAM_VALIDATION)` | Default Gradle `test` task. Uses `hapi.spec.embedded.mode=per-class` so each class picks its mode via `@TargetEmbeddedMode`. Skips standard log validation after the suite, because the embedded tests intentionally corrupt state to exercise `FAIL_INVALID` paths.                                               |
| `testSubprocess`           | `SubProcessNetwork` (serial)   | varies by caller                   | Runs HAPI tests in subprocess mode, one test class at a time within JUnit (`mode.classes.default=same_thread`). Used by all serial `hapiTest*Serial` checks.                                                                                                                                                       |
| `testSubprocessConcurrent` | `SubProcessNetwork` (parallel) | varies by caller                   | Same as `testSubprocess` but runs test classes concurrently (`mode.classes.default=concurrent`) with `fixed` parallelism — 3 threads when `hapi.spec.network.size ≤ 3`, else 2. Excludes `SERIAL` tests other than `CONCURRENT_SUBPROCESS_VALIDATION`. See [`SUBPROCESS_CONCURRENT.md`](SUBPROCESS_CONCURRENT.md). |
| `testRemote`               | `RemoteNetwork`                | varies by caller                   | Runs HAPI tests against a remote network whose YAML lives at the path in `hapi.spec.nodes.remoteYml` (overridable via `REMOTE_TARGET` env var).                                                                                                                                                                    |
| `testEmbedded`             | `EmbeddedNetwork` (CONCURRENT) | varies by caller                   | Embedded concurrent. Always sets `hapi.spec.embedded.mode=concurrent` and forces shard/realm to 0.                                                                                                                                                                                                                 |
| `testRepeatable`           | `EmbeddedNetwork` (REPEATABLE) | varies by caller                   | Embedded repeatable; disables JUnit parallelism.                                                                                                                                                                                                                                                                   |

## `hapiTest*` PR check tasks

Each entry in the `prCheckTags` map in `build.gradle.kts` becomes a Gradle task that depends on
either `testSubprocess` or `testSubprocessConcurrent` (the latter is used for the area tags that
have a concurrent variant — Crypto, Token, Misc, TimeConsuming, SimpleFees, AtomicBatch,
SmartContract). Each task forces a different starting port (`hapi.spec.initial.port`) so
sibling tasks can run in parallel without clashing.

|               Task               |                  Tag filter                   |        Delegates to        | Network size override |
|----------------------------------|-----------------------------------------------|----------------------------|-----------------------|
| `hapiTestAdhoc`                  | `ADHOC`                                       | `testSubprocess`           | 3                     |
| `hapiTestCrypto`                 | `CRYPTO`                                      | `testSubprocessConcurrent` | 3                     |
| `hapiTestCryptoSerial`           | `(CRYPTO&SERIAL)`                             | `testSubprocess`           | 3                     |
| `hapiTestToken`                  | `TOKEN`                                       | `testSubprocessConcurrent` | 3                     |
| `hapiTestTokenSerial`            | `(TOKEN&SERIAL)`                              | `testSubprocess`           | 3                     |
| `hapiTestRestart`                | `(RESTART\|UPGRADE)&!PRE_CUTOVER`             | `testSubprocess`           | default (4)           |
| `hapiTestPreCutover`             | `PRE_CUTOVER\|BLOCK_HASHING`                  | `testSubprocess`           | default (4)           |
| `hapiTestSmartContract`          | `SMART_CONTRACT`                              | `testSubprocessConcurrent` | 3                     |
| `hapiTestSmartContractSerial`    | `(SMART_CONTRACT&SERIAL)`                     | `testSubprocess`           | 3                     |
| `hapiTestNDReconnect`            | `ND_RECONNECT`                                | `testSubprocess`           | default               |
| `hapiTestWraps`                  | `WRAPS`                                       | `testSubprocess`           | default               |
| `hapiTestCutover`                | `CUTOVER`                                     | `testSubprocess`           | default               |
| `hapiTestTimeConsuming`          | `LONG_RUNNING`                                | `testSubprocessConcurrent` | default               |
| `hapiTestTimeConsumingSerial`    | `(LONG_RUNNING&SERIAL)`                       | `testSubprocess`           | default               |
| `hapiTestNodeStaking`            | `(NODE_STAKING)&!(SERIAL)`                    | `testSubprocess`           | 3                     |
| `hapiTestNodeStakingSerial`      | `(NODE_STAKING&SERIAL)`                       | `testSubprocess`           | default               |
| `hapiTestIss`                    | `ISS`                                         | `testSubprocess`           | default               |
| `hapiTestBlockNodeCommunication` | `BLOCK_NODE`                                  | `testSubprocess`           | default               |
| `hapiTestMisc`                   | everything outside the area tags (see source) | `testSubprocessConcurrent` | default               |
| `hapiTestGenesisSubProcess`      | `GENESIS_SUBPROCESS`                          | `testSubprocess`           | default               |
| `hapiTestMiscSerial`             | `<miscTags>&SERIAL`                           | `testSubprocess`           | default               |
| `hapiTestMiscRecords`            | misc + records mode                           | `testSubprocessConcurrent` | default               |
| `hapiTestMiscRecordsSerial`      | misc + records mode + SERIAL                  | `testSubprocess`           | default               |
| `hapiTestSimpleFees`             | `SIMPLE_FEES`                                 | `testSubprocessConcurrent` | 3                     |
| `hapiTestSimpleFeesSerial`       | `(SIMPLE_FEES&SERIAL)`                        | `testSubprocess`           | 3                     |
| `hapiTestAtomicBatch`            | `ATOMIC_BATCH`                                | `testSubprocessConcurrent` | 3                     |
| `hapiTestAtomicBatchSerial`      | `(ATOMIC_BATCH&SERIAL)`                       | `testSubprocess`           | 3                     |
| `hapiTestStateThrottling`        | `(STATE_THROTTLING&SERIAL)`                   | `testSubprocess`           | 3                     |

For `testSubprocess` / `testSubprocessConcurrent`, the resulting JUnit `includeTags` expression is

```
(<task's expression>|STREAM_VALIDATION|LOG_VALIDATION)&!(EMBEDDED|REPEATABLE)
```

except for the ISS and BLOCK_NODE tasks, which skip validation:

```
(<task's expression>)&!(EMBEDDED|REPEATABLE)
```

## `remoteTest*` PR check tasks

For every entry in `prCheckTags` other than `hapiTestIss`, `hapiTestRestart`, `hapiTestPreCutover`,
`hapiTestNodeStaking`, `hapiTestToken`, `hapiTestTokenSerial` and the CLPR tasks, a sibling `remoteTest…` task is generated that
delegates to `testRemote` with the same tag expression.

## `hapiTest*Embedded` and `hapiTest*Repeatable` PR check tasks

|             Task              |                    Tag filter                     |   Delegates to   |
|-------------------------------|---------------------------------------------------|------------------|
| `hapiTestMiscEmbedded`        | `(EMBEDDED&!(SIMPLE_FEES\|CRYPTO\|ATOMIC_BATCH))` | `testEmbedded`   |
| `hapiTestCryptoEmbedded`      | `(EMBEDDED&CRYPTO)`                               | `testEmbedded`   |
| `hapiTestSimpleFeesEmbedded`  | `(EMBEDDED&SIMPLE_FEES)`                          | `testEmbedded`   |
| `hapiTestAtomicBatchEmbedded` | `(EMBEDDED&ATOMIC_BATCH)`                         | `testEmbedded`   |
| `hapiTestMiscRepeatable`      | `(REPEATABLE&!CRYPTO)`                            | `testRepeatable` |

## Property overrides (`prCheckPropOverrides`)

Each task that needs to ship configuration changes adds them to a comma-separated
`hapi.spec.test.overrides` string consumed at network startup. Examples:

- `hapiTestRestart` enables `tss.hintsEnabled`, sets `quiescence.enabled=true`,
  and shrinks `hedera.transaction.maximumPermissibleUnhealthySeconds`.
- `hapiTestMisc` enables `quiescence.enabled` and turns off node rewards.
- `hapiTestCutover` starts from the pre-cutover block stream (`streamMode=BOTH` with wrapped record blocks)
  and configures TSS to start with `tss.hintsEnabled=false` to exercise the cutover path.
- `hapiTestPreCutover` runs the pre-cutover block stream that mainnet runs until it cuts over, for
  the `PRE_CUTOVER` jumpstart and wrapped record hash tests. It also runs the `BLOCK_HASHING` tests
  in that stream mode; `hapiTestMisc` and `hapiTestMiscRecords` run them in BLOCKS and RECORDS.
- `hapiTestGenesisSubProcess` and `hapiTestClprMultinetwork` pin a `FILE` writer, since block nodes attach
  only to the shared network.

See `build.gradle.kts` for the full table.

Every other subprocess task except the `RECORDS`-only `hapiTestMiscRecords*` runs the production block stream
defaults (`streamMode=BLOCKS`, `writerMode=FILE_AND_GRPC`, real TSS signatures), so it starts one real block node
shared by all the nodes. Stream assertions follow the effective stream mode: with no record stream (`BLOCKS`),
record-stream checks read the block stream instead.

### Pre-cutover configuration profile

`-PhapiTestConfigProfile=pre-cutover` (or `HAPI_TEST_CONFIG_PROFILE=pre-cutover`) runs the HAPI tasks in the
pre-cutover world that MATS ran before the defaults switched: `streamMode=BOTH` with wrapped record blocks, the
cutover disabled, no buffer persistence and mock signatures:

- each task then gets the pins it had in that world (`preCutoverTaskOverrides`, e.g. real signatures for
  `hapiTestCrypto`), and its `prCheckPropOverrides` on top;
- every task whose writer streams over gRPC still gets a block node, which in `BOTH` mode receives the wrapped
  record blocks;
- embedded and repeatable nodes get the profile as system properties, and keep the `FILE` writer and mock
  signatures;
- `hapiTestMiscRecords*`, `hapiTestCutover`, `hapiTestPreCutover` and `hapiTestBlockNodeCommunication` ignore it.

XTS reruns the MATS HAPI panels, the CLPR tasks and `hapiTestWraps` this way
(`829-call-execute-xts-hapi-testnet.yaml`), so the pre-cutover world mainnet runs until it cuts over stays covered.

```bash
./gradlew :test-clients:hapiTestCrypto -PhapiTestConfigProfile=pre-cutover
```

## Platform overrides (`prCheckPlatformOverrides`)

`hapiTestRestart` and `hapiTestPreCutover` append `platformStatus.observingStatusDelay=10s` and
`reconnect.minimumTimeBetweenReconnects=10s` to the platform `settings.txt` for their subprocess nodes;
`hapiTestClprMultinetwork` appends the observing delay.

## Helper / utility tasks

|      Task       |                                                  What it does                                                  |
|-----------------|----------------------------------------------------------------------------------------------------------------|
| `runTestClient` | Runs a single class with `main(String[])` against the full test-clients runtime classpath.                     |
| `shadowJar`     | Builds the fat `SuiteRunner.jar` (legacy orchestration entry point; new tests should be `@HapiTest`s instead). |
| `rcdiffJar`     | Builds the standalone `rcdiff.jar`. See [`RCDIFF.md`](RCDIFF.md).                                              |

### `runTestClient`

`runTestClient` is a `JavaExec` task that takes the FQCN of a class via the `-PtestClient=` Gradle
property and runs it as a one-off, with the full test-clients runtime classpath. Useful for ad-hoc
utilities under `c.h.s.bdd.utils` or perf clients that should not be baked into JUnit.

```bash
./gradlew :test-clients:runTestClient -PtestClient=com.hedera.services.bdd.utils.MyClient
```

Equivalent to running the class with `java --module-path … -m
com.hedera.node.test.clients/<FQCN>`.

## Resource scaling

`TestResourceArgumentsProvider` (top of `build.gradle.kts`) detects CPUs and memory at task-launch
time and sets:

- JVM heap (`-Xmx<size>g`) — half of total memory, clamped to `[4, 8]` GiB.
- `-XX:ActiveProcessorCount` — `min(cpus, 8)`.
- `-Dhapi.spec.node.poolMib` — total memory minus the test-client heap, scaled by 0.8 and divided
  per node at runtime by `ProcessUtils`.

## See also

- [TEST_TAGS.md](TEST_TAGS.md) — what each tag means.
- [SYSTEM_PROPERTIES.md](SYSTEM_PROPERTIES.md) — all `hapi.spec.*` knobs.
- [SUBPROCESS_CONCURRENT.md](SUBPROCESS_CONCURRENT.md) — concurrent class execution + validation.
- [HAPITEST_ANNOTATIONS.md](HAPITEST_ANNOTATIONS.md) — what annotation a test must use to be
  picked up by each task.
