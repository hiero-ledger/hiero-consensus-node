# System Properties (`hapi.spec.*`)

Test-clients tasks are configured almost entirely through JVM system properties whose names start
with `hapi.spec.`. Most are set by `build.gradle.kts` per task, but they can also be passed on the
command line via `-D<key>=<value>` for ad-hoc runs.

This reference lists every property the framework reads, what it controls, who sets it, and what
the default is.

## Network selection & sizing

|          Property           |                                       Read in                                       |               Values               |                              Default                               |                                        Set by                                        |
|-----------------------------|-------------------------------------------------------------------------------------|------------------------------------|--------------------------------------------------------------------|--------------------------------------------------------------------------------------|
| `hapi.spec.remote`          | `SharedNetworkLauncherSessionListener`                                              | `true` / `false`                   | `false`                                                            | `testRemote` task                                                                    |
| `hapi.spec.nodes.remoteYml` | same                                                                                | path to a remote-network YAML file | unset (uses default in `getDefaultInstance().remoteNodesYmlLoc()`) | `testRemote` (from `REMOTE_TARGET` env var)                                          |
| `hapi.spec.network.size`    | `SharedNetworkLauncherSessionListener`, `ProcessUtils`, `SteadyStateThrottlingTest` | positive int                       | `CLASSIC_HAPI_TEST_NETWORK_SIZE = 4`                               | several `hapiTest*` tasks override to `3` (see [`GRADLE_TASKS.md`](GRADLE_TASKS.md)) |
| `hapi.spec.initial.port`    | `SharedNetworkLauncherSessionListener`                                              | base port number                   | unset                                                              | per-task in `build.gradle.kts` so parallel PR checks don't collide                   |
| `hapi.spec.subtask.name`    | `WorkingDirUtils` (`SUBTASK_NAME_PROPERTY`)                                         | task name string                   | unset                                                              | per-task; isolates the working dir so logs aren't overwritten                        |

## Embedded mode

|             Property              |                       Read in                       |                 Values                  |          Default          |                                        Set by                                        |
|-----------------------------------|-----------------------------------------------------|-----------------------------------------|---------------------------|--------------------------------------------------------------------------------------|
| `hapi.spec.embedded.mode`         | `SharedNetworkLauncherSessionListener`, `UtilVerbs` | `concurrent`, `repeatable`, `per-class` | unset (subprocess/remote) | `test` → `per-class`, `testEmbedded` → `concurrent`, `testRepeatable` → `repeatable` |
| `hapi.spec.subprocess.concurrent` | `SharedNetworkLauncherSessionListener`              | `true` / `false`                        | `false`                   | `testSubprocessConcurrent`                                                           |

## Sharding / realm

|         Property          |       Read in        |      Values       |         Default         |                                     Set by                                      |
|---------------------------|----------------------|-------------------|-------------------------|---------------------------------------------------------------------------------|
| `hapi.spec.default.shard` | `HapiPropertySource` | non-negative long | unset → service default | `testSubprocess` / `testSubprocessConcurrent` use `11`; `testEmbedded` uses `0` |
| `hapi.spec.default.realm` | same                 | non-negative long | unset → service default | `testSubprocess` / `testSubprocessConcurrent` use `12`; `testEmbedded` uses `0` |

## Block-node integration

|                 Property                  |                             Read in                             |                  Values                   |                                                      Default                                                      |             Set by              |
|-------------------------------------------|-----------------------------------------------------------------|-------------------------------------------|-------------------------------------------------------------------------------------------------------------------|---------------------------------|
| `hapi.spec.blocknode.mode`                | `SharedNetworkLauncherSessionListener`, `BlockNodeMode` Javadoc | `REAL`, `SIMULATOR`, `LOCAL_NODE`, `NONE` | falls back to `REAL` in the listener when `blockStream.writerMode` is `FILE_AND_GRPC` or `GRPC`; otherwise `NONE` | unset by default; pass via `-D` |
| `hapi.spec.blocknode.simulator.manyToOne` | `SubProcessNetwork`                                             | `true` / `false`                          | `false`                                                                                                           | unset by default; pass via `-D` |

See [`../README.md`](../README.md) § *Block Node Testing*.

## Test-time overrides

|            Property            |                       Read in                       |                     Format                     | Default |                 Set by                 |
|--------------------------------|-----------------------------------------------------|------------------------------------------------|---------|----------------------------------------|
| `hapi.spec.test.overrides`     | `ProcessUtils`                                      | comma-separated `key=value` pairs              | empty   | per-task in `prCheckPropOverrides`     |
| `hapi.spec.platform.overrides` | `SubProcessNetwork` (`PLATFORM_OVERRIDES_PROPERTY`) | comma-separated `key=value` for `settings.txt` | empty   | per-task in `prCheckPlatformOverrides` |

## TSS (`wraps` / hinTS / history)

|               Property                |                        Read in                        |      Values      |              Default               |                                  Set by                                  |
|---------------------------------------|-------------------------------------------------------|------------------|------------------------------------|--------------------------------------------------------------------------|
| `hapi.spec.assertAtLeastOneWraps`     | `StateChangesValidator`                               | `true` / `false` | `false`                            | `hapiTestWraps`, `hapiTestCutover`                                       |
| `hapi.spec.tssLibWrapsArtifactsPath`  | `ProcessUtils`, `WrapsHandoffsTest`, `TssCutoverTest` | filesystem path  | empty                              | per-task; exported into subprocess env as `TSS_LIB_WRAPS_ARTIFACTS_PATH` |
| `hapi.spec.hintsThresholdDenominator` | `StateChangesValidator`                               | integer          | `3` (or `4` for `hapiTestRestart`) | per-task                                                                 |

## Lifecycle / upgrade scheduling

|             Property              |                Read in                 |               Format               | Default |                  Set by                   |
|-----------------------------------|----------------------------------------|------------------------------------|---------|-------------------------------------------|
| `hapi.spec.prepareUpgradeOffsets` | `SharedNetworkLauncherSessionListener` | comma-separated ISO-8601 durations | unset   | currently only `hapiTestAdhoc` (`PT300S`) |

## Stream / block-stream behaviour

|                 Property                  |         Read in         |      Values      | Default |                                                                                        Set by                                                                                         |
|-------------------------------------------|-------------------------|------------------|---------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `hapi.spec.block.stateproof.verification` | `build.gradle.kts` only | `true` / `false` | `false` | always set to `false` in `testSubprocess` / `testSubprocessConcurrent`; some PR checks set `block.stateproof.verification.enabled` via the `hapi.spec.test.overrides` channel instead |

## Logging / quietness

|        Property        |                  Read in                  |      Values      |                             Default                             |  Set by  |
|------------------------|-------------------------------------------|------------------|-----------------------------------------------------------------|----------|
| `hapi.spec.quiet.mode` | `HapiSpec` (`QUIET_MODE_SYSTEM_PROPERTY`) | `true` / `false` | `true` in CI / when running a known PR-check task, else `false` | per-task |

## Resource sizing

|         Property         |    Read in     |                Values                |                            Default                             |                        Set by                         |
|--------------------------|----------------|--------------------------------------|----------------------------------------------------------------|-------------------------------------------------------|
| `hapi.spec.node.poolMib` | `ProcessUtils` | total MiB pool to split across nodes | `Integer.getInteger("hapi.spec.network.size", 4) * <fallback>` | `TestResourceArgumentsProvider` in `build.gradle.kts` |

## How to override on the command line

```bash
# Run a specific test against a 5-node subprocess network with simulator block nodes
./gradlew testSubprocess \
  --tests "com.hedera.services.bdd.suites.crypto.MyTest" \
  -Dhapi.spec.network.size=5 \
  -Dhapi.spec.blocknode.mode=SIMULATOR
```

Properties set via `-D` on the Gradle command line propagate to the test JVM only if the task
itself forwards them (`testSubprocess` and friends do, because they call `systemProperty(...)` for
each `hapi.spec.*` value they care about). When in doubt, also pass `-Dorg.gradle.jvmargs=...` or
add to `~/.gradle/gradle.properties`.

## See also

- [GRADLE_TASKS.md](GRADLE_TASKS.md) — which task sets which properties.
- [TEST_TAGS.md](TEST_TAGS.md) — JUnit tag side of the same configuration.
- [`../README.md`](../README.md) — Block Node Testing section ties the block-node properties
  together.
