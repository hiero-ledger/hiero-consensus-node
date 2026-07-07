# `@HapiTest` Annotation Family

A `@HapiTest`-family annotation turns a `Stream<DynamicTest>` factory method into JUnit Jupiter
dynamic tests that target the shared `HederaNetwork`. Each variant carries a fixed `@Tag` and a
`@ResourceLock` (or `@Isolated` / `@Execution(SAME_THREAD)`) that lets Gradle and JUnit decide
which test task runs it and whether it can run concurrently with others.

All variants register `NetworkTargetingExtension` (which binds the shared network to the test
thread) and `SpecNamingExtension` (which names the spec after the factory method).

## Annotations

### `@HapiTest`

The default. Method-level, runs against the shared network, holds a `READ` lock on `"NETWORK"` so
it can run concurrently with other `@HapiTest`s but is mutually exclusive with `@LeakyHapiTest`.

```java
@HapiTest
final Stream<DynamicTest> myTest() { return hapiTest(/* … */); }
```

### `@LeakyHapiTest`

For tests that leak side effects into network state (or are vulnerable to leakage from other
tests). Tagged `SERIAL`, holds a `READ_WRITE` lock on `"NETWORK"`, so it runs serially.

Attributes:

|    Attribute    | Default |                                             Effect                                              |
|-----------------|---------|-------------------------------------------------------------------------------------------------|
| `requirement()` | `{}`    | Array of `ContextRequirement` enum values explaining *why* the test must run serially.          |
| `overrides()`   | `{}`    | Names of properties this test changes; framework restores their original values after the test. |
| `throttles()`   | `""`    | Path to a JSON file of throttle definitions; original throttles are restored after.             |
| `fees()`        | `""`    | Path to a JSON file of fee schedules; original schedules are restored after.                    |

Prefer `@HapiTestLifecycle` + `@BeforeAll` overrides over `@LeakyHapiTest` whenever possible — see
[`../SpecCookbook.md`](../SpecCookbook.md) § *DO opt for `@BeforeAll` property overrides whenever
possible*.

### `@EmbeddedHapiTest`

Test must run in embedded mode (tagged `EMBEDDED`). Holds `READ` lock on `"NETWORK"`. Required when
the test needs to skip the ingest workflow, directly access state, or manipulate event versions.

Attribute: `EmbeddedReason[] value()` — required. The reason explains *why* the test needs
embedded mode: skipping ingest, accessing internal state, manipulating the simulated event
version, and similar. See `EmbeddedReason.java` for the current enum.

### `@LeakyEmbeddedHapiTest`

Embedded variant that also leaks. Tagged `EMBEDDED`, holds `READ_WRITE` lock. Attributes combine
both: `reason()` (required, `EmbeddedReason[]`), `requirement()` (`ContextRequirement[]`),
`overrides()`, `throttles()`, `fees()`.

### `@RepeatableHapiTest`

Test must run in repeatable embedded mode (tagged `REPEATABLE`, executes `SAME_THREAD`). Required
when the test needs virtual time, deterministic ordering, or synchronous handling.

Attribute: `RepeatableReason[] value()` — required. The reason explains *why* the test needs
repeatable mode: virtual time, deterministic ordering, synchronous handling, TSS control, and
similar. See `RepeatableReason.java` for the current enum.

### `@LeakyRepeatableHapiTest`

Repeatable variant that also leaks. Same tag/execution mode as `@RepeatableHapiTest`. Attributes:
`value()` (`RepeatableReason[]`), `overrides()`, `throttles()`, `fees()`.

### `@GenesisHapiTest`

A variant that spins up a *separate* embedded network (not the shared one) so the test can see the
genesis transaction. Tagged `EMBEDDED` and `@Isolated`, because each embedded network is configured
through JVM system properties.

Attribute: `ConfigOverride[] bootstrapOverrides()` — bootstrap properties applied to the genesis
network.

### `@RestartHapiTest`

Repeatable variant for restart-cycle tests. Lives in
`com.hedera.services.bdd.junit.restart`. See [`RESTART_TESTING.md`](RESTART_TESTING.md) for
the full attribute set.

## Class-level annotations

### `@HapiTestLifecycle`

Marks a test class whose `@BeforeAll` / `@AfterAll` methods want an injected `TestLifecycle`
parameter for setting up shared entities and property overrides:

```java
@HapiTestLifecycle
class MyTests {
    @BeforeAll
    static void setUp(@NonNull final TestLifecycle lifecycle) {
        lifecycle.overrideInClass(Map.of("feature.isEnabled", "true"));
    }
}
```

The framework restores the original property values after the class finishes. Registers
`TestLifecycleExtension`.

### `@OrderedInIsolation`

Class-level. Marks a test class as `@Isolated` (cannot run alongside any other class), tagged
`SERIAL`, with method order `MethodOrderer.OrderAnnotation` and class order
`ClassOrderer.OrderAnnotation`. Use when a class needs strict sequencing both internally and
relative to other classes.

### `@TargetEmbeddedMode(EmbeddedMode)`

Class-level. Tells the framework that the class's `@HapiTest`s must run against an embedded network
in the specified `EmbeddedMode` (`CONCURRENT` or `REPEATABLE`). Honored when the default `test`
task uses `hapi.spec.embedded.mode=per-class`.

## `ContextRequirement` (leak reasons)

Enum in `com.hedera.services.bdd.junit.ContextRequirement` listing the typical reasons a test
must run serially. They fall into a few groups: ordering-sensitive expectations
(`NO_CONCURRENT_CREATIONS`, staking-boundary requirements), in-test mutations of network-wide
configuration (properties, permissions, throttles, fee schedules, upgrade files), and assertions
on system-account state that other tests may also touch. See `ContextRequirement.java` for the
current set and the per-value Javadoc.

## `ConfigOverride`

Simple `key()` / `value()` pair used by `@GenesisHapiTest` and `@RestartHapiTest` to apply network
configuration overrides during setup.

## Picking the right annotation

```
need to test the network?
├── normal happy path that doesn't change state                → @HapiTest
├── changes state that would affect other tests                → @LeakyHapiTest
├── needs to bypass ingest or read state directly              → @EmbeddedHapiTest
│   └── …and also leaks                                        → @LeakyEmbeddedHapiTest
├── needs virtual time or strict determinism                   → @RepeatableHapiTest
│   └── …and also leaks                                        → @LeakyRepeatableHapiTest
├── must see the genesis transaction                           → @GenesisHapiTest
└── exercises the restart cycle                                → @RestartHapiTest (RESTART_TESTING.md)
```

## See also

- [TEST_TAGS.md](TEST_TAGS.md) — what each tag does.
- [GRADLE_TASKS.md](GRADLE_TASKS.md) — which task picks up which annotation.
- [RESTART_TESTING.md](RESTART_TESTING.md) — `@RestartHapiTest` in depth.
- [`../SpecCookbook.md`](../SpecCookbook.md) — patterns for choosing between leaky tests and
  `@HapiTestLifecycle`.
