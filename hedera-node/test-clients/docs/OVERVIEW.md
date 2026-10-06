# test-clients Overview

`hedera-node/test-clients` is the end-to-end test harness for the Hedera consensus node. It hosts:

- A Java DSL (`HapiSpec` and `SpecOperation`) for expressing transactions, queries, and assertions
  against a Hedera network.
- Three network back-ends — remote, subprocess, and embedded — so the same spec can run against
  the real gRPC surface or against an in-process fake.
- A JUnit Jupiter harness that turns each `HapiSpec` into a dynamic test, with annotations and
  tags that decide which test task picks the test up.
- A validator pipeline that ensures the produced block stream, record stream, state, and logs are
  internally consistent and consistent with each other.
- Two standalone command-line jars built from the same sources: `SuiteRunner.jar` (legacy
  orchestration entry point) and `rcdiff.jar` (record-stream diff tool).

This document is the entry point to the rest of the documentation. New contributors should read
the top-level [`../README.md`](../README.md) first for the conceptual walk-through, then return
here to navigate the deeper references below.

## Quick start

```bash
# Run the full default test (embedded, fast)
./gradlew :test-clients:test

# Run a specific HAPI test class against a subprocess network
./gradlew :test-clients:testSubprocess --tests "com.hedera.services.bdd.suites.crypto.MyTest"

# Run the embedded concurrent or repeatable variants
./gradlew :test-clients:testEmbedded   --tests "com.hedera.services.bdd.suites.MyTest"
./gradlew :test-clients:testRepeatable --tests "com.hedera.services.bdd.suites.MyTest"

# Reproduce a CI PR check locally
./gradlew :test-clients:hapiTestCrypto
./gradlew :test-clients:hapiTestRestart

# Build the rcdiff jar
./gradlew :test-clients:rcdiffJar
```

Common command-line overrides live in [`SYSTEM_PROPERTIES.md`](SYSTEM_PROPERTIES.md); the full
task taxonomy lives in [`GRADLE_TASKS.md`](GRADLE_TASKS.md).

## Mental model

```
         ┌────────────────────────────────────────────────┐
         │                  HapiSpec                      │
         │  (sequence of SpecOperations + registry)       │
         └───────────────┬────────────────────────────────┘
                         │ runs against
                         ▼
┌────────────────────────────────────────────────────────┐
│                   HederaNetwork                        │
│  RemoteNetwork  │ SubProcessNetwork │ EmbeddedNetwork  │
└────────┬───────────────────┬────────────────┬──────────┘
         │                   │                │
         │ gRPC              │ gRPC + logs    │ direct Hedera+FakeState
         ▼                   ▼                ▼
    real network       child JVMs        in-process fake
         │                   │                │
         └─────── block stream + record stream + state + logs ────┐
                                                                  │
                                                                  ▼
                                                      ┌───────────────────────┐
                                                      │      Validators       │
                                                      │ block / record / log  │
                                                      └───────────────────────┘
```

Four moving parts. Most CI work flows through `SubProcessNetwork` + the validator pipeline;
embedded networks exist for tests that need direct state access, deterministic time, or both.

## Documentation map

### Authoring tests

|                         Doc                          |                                                                        Purpose                                                                        |
|------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------|
| [`../README.md`](../README.md)                       | Conceptual walk-through: `HapiSpec`, network implementations, block-node testing, JUnit integrations, style guide. **Start here.**                    |
| [`../SpecCookbook.md`](../SpecCookbook.md)           | Patterns and anti-patterns for writing specs (feature flags, template scenarios, object-oriented DSL, `@BeforeAll` overrides, block-node verbs).      |
| [`HAPITEST_ANNOTATIONS.md`](HAPITEST_ANNOTATIONS.md) | Every `@*HapiTest*` annotation, `@HapiTestLifecycle`, `@OrderedInIsolation`, plus `ContextRequirement` / `EmbeddedReason` / `RepeatableReason` enums. |

### Running and configuring

|                          Doc                           |                                                                  Purpose                                                                   |
|--------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------|
| [`GRADLE_TASKS.md`](GRADLE_TASKS.md)                   | Every Gradle task — base tasks, `hapiTest*` PR checks, `remoteTest*`, embedded/repeatable PR checks, property overrides, resource scaling. |
| [`SYSTEM_PROPERTIES.md`](SYSTEM_PROPERTIES.md)         | Reference for every `hapi.spec.*` system property.                                                                                         |
| [`TEST_TAGS.md`](TEST_TAGS.md)                         | Every `TestTags` constant plus the inline validation tags, and how task tag-expressions compose them.                                      |
| [`SUBPROCESS_CONCURRENT.md`](SUBPROCESS_CONCURRENT.md) | How `testSubprocessConcurrent` schedules tests and runs validation last via a latch.                                                       |

### Specialised testing flavours

|                       Doc                        |                                                     Purpose                                                      |
|--------------------------------------------------|------------------------------------------------------------------------------------------------------------------|
| [`EMBEDDED_INTERNALS.md`](EMBEDDED_INTERNALS.md) | What's inside an `EmbeddedNetwork`: `EmbeddedHedera`, `EmbeddedMode`, all the fakes.                             |
| [`RESTART_TESTING.md`](RESTART_TESTING.md)       | The `@RestartHapiTest` annotation: `RestartType`, `StartupAssets`, `SavedStateSpec`, setup-vs-restart overrides. |

### Validation and stream tooling

|                             Doc                              |                                              Purpose                                              |
|--------------------------------------------------------------|---------------------------------------------------------------------------------------------------|
| [`VALIDATORS.md`](VALIDATORS.md)                             | Every block-stream, record-stream, and log validator; how they're wired into a run.               |
| [`BLOCK_STREAM_TRANSLATORS.md`](BLOCK_STREAM_TRANSLATORS.md) | How block-stream items are translated back into legacy record-stream entries for parity checking. |
| [`RCDIFF.md`](RCDIFF.md)                                     | The `rcdiff` CLI — diffing two record streams.                                                    |

## "I want to…" recipes

|                                Goal                                |                                                                                  Read                                                                                   |
|--------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Write a new HAPI test                                              | [`../README.md`](../README.md) § *Structure of a `HapiSpec`* → [`HAPITEST_ANNOTATIONS.md`](HAPITEST_ANNOTATIONS.md) → [`../SpecCookbook.md`](../SpecCookbook.md)        |
| Pick the right annotation for a leaky / embedded / repeatable test | [`HAPITEST_ANNOTATIONS.md`](HAPITEST_ANNOTATIONS.md) § *Picking the right annotation*                                                                                   |
| Add a property override that other tests must not see              | [`../SpecCookbook.md`](../SpecCookbook.md) § *DO opt for `@BeforeAll` property overrides* + [`HAPITEST_ANNOTATIONS.md`](HAPITEST_ANNOTATIONS.md) § `@HapiTestLifecycle` |
| Reproduce a CI failure locally                                     | [`GRADLE_TASKS.md`](GRADLE_TASKS.md) → run the corresponding `hapiTest*` task                                                                                           |
| Tune resources or override `hapi.spec.*` knobs                     | [`SYSTEM_PROPERTIES.md`](SYSTEM_PROPERTIES.md)                                                                                                                          |
| Test something only an embedded network can do                     | [`HAPITEST_ANNOTATIONS.md`](HAPITEST_ANNOTATIONS.md) § `@EmbeddedHapiTest` → [`EMBEDDED_INTERNALS.md`](EMBEDDED_INTERNALS.md)                                           |
| Test a restart-time migration                                      | [`RESTART_TESTING.md`](RESTART_TESTING.md)                                                                                                                              |
| Test against a block node simulator                                | [`../README.md`](../README.md) § *Block Node Testing* → [`../SpecCookbook.md`](../SpecCookbook.md) § *Working with Block Nodes*                                         |
| Diagnose a stream-validation failure                               | [`VALIDATORS.md`](VALIDATORS.md) → [`BLOCK_STREAM_TRANSLATORS.md`](BLOCK_STREAM_TRANSLATORS.md) → [`RCDIFF.md`](RCDIFF.md)                                              |
| Add a translator for a new transaction type                        | [`BLOCK_STREAM_TRANSLATORS.md`](BLOCK_STREAM_TRANSLATORS.md) § *Adding a translator for a new transaction type*                                                         |
| Add or extend a stream validator                                   | [`VALIDATORS.md`](VALIDATORS.md) § *Adding a new validator*                                                                                                             |
| Compare two record streams from the shell                          | [`RCDIFF.md`](RCDIFF.md)                                                                                                                                                |

## Related (outside `test-clients/`)

- [`hedera-node/yahcli/README.md`](../../yahcli/README.md) — DevOps / acceptance-test CLI built on
  the same `HapiSpec` foundation. Hosts the `ivy` command (formerly the `ValidationScenarios.jar`
  in `test-clients/validation-scenarios/`).
- [`hedera-node/docs/dev/JRS-GettingStarted.md`](../../docs/dev/JRS-GettingStarted.md) — JRS
  regression test setup.
- [`hedera-node/docs/dev/e2eTesting-GettingStarted.md`](../../docs/dev/e2eTesting-GettingStarted.md)
  — pre-flight environment setup for running end-to-end tests.
- `platform-sdk/consensus-otter-tests/docs/` — Otter, the separate test framework that lives in
  the platform SDK and exercises consensus-layer behaviour. Not in scope for this documentation
  set.
