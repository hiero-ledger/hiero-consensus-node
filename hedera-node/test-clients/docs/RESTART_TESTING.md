# Restart / Upgrade Testing

`@RestartHapiTest` exercises the restart phase of the node lifecycle: shutting a node down with a
given saved state and on-disk configuration, then bringing it back up — possibly on a newer
software version, possibly with an override network roster, possibly from genesis. The framework
provisions these scenarios through a separate embedded network so the main shared network is left
alone.

Source: `src/main/java/com/hedera/services/bdd/junit/restart/`.

## The annotation

```java
@Target({ElementType.METHOD})
@TestFactory
@Execution(SAME_THREAD)
@Tag(ONLY_REPEATABLE)
public @interface RestartHapiTest {
    RestartType restartType() default RestartType.GENESIS;
    ConfigOverride[] setupOverrides() default {};
    ConfigOverride[] restartOverrides() default {};
    StartupAssets setupAssets() default StartupAssets.NONE;
    StartupAssets restartAssets() default StartupAssets.NONE;
    Class<? extends SavedStateSpec> savedStateSpec() default NoopSavedStateSpec.class;
}
```

`@RestartHapiTest` is tagged `ONLY_REPEATABLE` and forces `SAME_THREAD` execution, so it runs
under `testRepeatable`. Each test gets a fresh embedded network seeded according to the
annotation's attributes.

## `RestartType`

Three restart shapes are supported:

|       Value        |       Setup state        | Restart software version |                           Typical use                           |
|--------------------|--------------------------|--------------------------|-----------------------------------------------------------------|
| `GENESIS`          | none                     | current                  | Cold-start scenarios; default.                                  |
| `SAME_VERSION`     | saved (current version)  | current                  | Tests that the node correctly resumes from its own saved state. |
| `UPGRADE_BOUNDARY` | saved (previous version) | current                  | Tests the migration / schema upgrade path.                      |

## `StartupAssets`

Controls the on-disk override network file presented to the node at start time:

|     Value     |                                                  What's on disk                                                  |
|---------------|------------------------------------------------------------------------------------------------------------------|
| `NONE`        | No override network present.                                                                                     |
| `ROSTER_ONLY` | An override network containing only roster information (used to test transplanting rosters between deployments). |

Both `setupAssets()` and `restartAssets()` accept this enum so the *initial* run and the *restart*
run can have different assets in place.

## `SavedStateSpec`

When `restartType()` is `SAME_VERSION` or `UPGRADE_BOUNDARY`, the framework needs an initial
`FakeState` to write down before the restart. `SavedStateSpec` is a `Consumer<FakeState>` that the
test provides to customise that state:

```java
@FunctionalInterface
public interface SavedStateSpec extends Consumer<FakeState> {}
```

The spec class is supplied via `savedStateSpec = MyCustomSpec.class`. The default,
`NoopSavedStateSpec`, leaves the state untouched (appropriate when `GENESIS` is the restart
type).

A custom spec is just a small class:

```java
public class WithStakingPeriodInFuture implements SavedStateSpec {
    @Override
    public void accept(@NonNull FakeState state) {
        // Mutate `state` here — write singleton blocks, prime entity ids, etc.
    }
}

@RestartHapiTest(
    restartType = RestartType.SAME_VERSION,
    savedStateSpec = WithStakingPeriodInFuture.class,
    setupOverrides = @ConfigOverride(key = "…", value = "…"),
    restartOverrides = @ConfigOverride(key = "…", value = "…")
)
final Stream<DynamicTest> nodeResumesAfterStakeRollback() { … }
```

## `setupOverrides` vs. `restartOverrides`

Both arrays are applied as bootstrap properties, but at different points:

- `setupOverrides` — in effect while the framework is *creating* the initial saved state. Used
  when the initial state must be built with a non-default configuration (e.g., a feature flag
  that gates state-schema changes).
- `restartOverrides` — in effect when the node is *restarted* for the test itself. Used to
  exercise behaviour that differs at restart-time (e.g., enabling a new feature on the upgraded
  node).

When `restartType == GENESIS`, `setupOverrides` is ignored.

## What the test sees

The test method is a normal `Stream<DynamicTest>` factory that runs against the freshly-restarted
embedded network. Inside the test, the `EmbeddedNetwork`'s post-restart state is fully accessible
via the usual `HapiSpec` operations, and `EmbeddedHedera.state()` can be called to inspect or
mutate it directly (see [`EMBEDDED_INTERNALS.md`](EMBEDDED_INTERNALS.md)).

## Why repeatable only

Restart tests depend on deterministic state and stream byte-for-byte output to verify migration
behaviour. Real-time embedded mode could produce timing variations that make the saved-state
assertions flaky, so `@RestartHapiTest` is locked to repeatable mode.

## Related Gradle tasks

`hapiTestRestart` is the PR-check task. It runs in subprocess mode (filter `RESTART|UPGRADE`) and
also receives `quiescence.enabled=true`, `tss.forceHandoffs=true`, and a 1s `blockStream.blockPeriod`
via the per-task overrides — see `prCheckPropOverrides` in `build.gradle.kts`.

> Note: `hapiTestRestart` (subprocess) and `@RestartHapiTest` (repeatable embedded) are two
> different testing approaches sharing the word "restart." Subprocess restart tests run real node
> processes through real shutdowns; embedded `@RestartHapiTest`s exercise the
> migration/initialization logic against fake state. Both are useful; the choice depends on what
> must be asserted.

## See also

- [HAPITEST_ANNOTATIONS.md](HAPITEST_ANNOTATIONS.md) — `@RestartHapiTest` in the annotation family.
- [EMBEDDED_INTERNALS.md](EMBEDDED_INTERNALS.md) — `EmbeddedHedera.restart(FakeState)`.
- [GRADLE_TASKS.md](GRADLE_TASKS.md) — `hapiTestRestart` configuration.
