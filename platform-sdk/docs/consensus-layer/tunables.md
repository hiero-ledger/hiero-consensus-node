# Tunables — Catalog

Catalog of consensus-layer configurable parameters, referenced from other KB
entries by ID (`TUN-NNN`). Single file, parallel to `symptoms.md`.

A tunable here is **one `@ConfigProperty` of an `@ConfigData` record** in the
consensus-layer module. Sections are grouped by record (one heading per
class), in module then alphabetical order. Several records share a config
prefix (`state.*`, `event.intake.wiring.*`, `event.creation.wiring.*`); each
gets its own section so the source class is unambiguous.

Adding a value: append the next `TUN-NNN`, fill all columns, keep each table
in ID order. Never reuse or renumber IDs; retire by marking, not deleting.

Column conventions:

- **Key** — fully-qualified config key (`<prefix>.<property>`), as a node
  operator would set it. Records declared with bare `@ConfigData` (no
  prefix) expose just the property name.
- **Type** — Java type of the property.
- **Default** — `@ConfigProperty(defaultValue=...)` literal, verbatim.
- **Effect** — condensed from the property's Javadoc on the record; see the
  source file for the full text.
- **Range** — populated from `@Min` / `@Max` annotations where present;
  otherwise blank. Blank means "not constrained beyond the type."
- **Fragility** — reserved for SME curation; `—` until filled in.

## `state.*` — StateCommonConfig

Module: `swirlds-common`. Source: [StateCommonConfig.java](../../swirlds-common/src/main/java/com/swirlds/common/config/StateCommonConfig.java).

|   ID    |             Key             | Type |   Default    |                                       Effect                                       | Range | Fragility |
|---------|-----------------------------|------|--------------|------------------------------------------------------------------------------------|-------|-----------|
| TUN-001 | `state.savedStateDirectory` | Path | `data/saved` | Directory where states are saved; relative to CWD unless the path begins with `/`. |       | —         |

## `temporaryFiles.*` — TemporaryFileConfig

Module: `swirlds-common`. Source: [TemporaryFileConfig.java](../../swirlds-common/src/main/java/com/swirlds/common/io/config/TemporaryFileConfig.java).

|   ID    |                Key                 | Type |    Default    |                                   Effect                                   | Range | Fragility |
|---------|------------------------------------|------|---------------|----------------------------------------------------------------------------|-------|-----------|
| TUN-002 | `temporaryFiles.temporaryFilePath` | Path | `swirlds-tmp` | Directory where temporary files are created (relative to saved-state dir). |       | —         |

## `platform.wiring.*` — WiringConfig

Module: `swirlds-component-framework`. Source: [WiringConfig.java](../../swirlds-component-framework/src/main/java/com/swirlds/component/framework/WiringConfig.java).

|   ID    |                       Key                        |   Type   | Default |                                                  Effect                                                   | Range | Fragility |
|---------|--------------------------------------------------|----------|---------|-----------------------------------------------------------------------------------------------------------|-------|-----------|
| TUN-003 | `platform.wiring.healthMonitorEnabled`           | boolean  | `true`  | Whether the health monitor is enabled.                                                                    |       | —         |
| TUN-004 | `platform.wiring.hardBackpressureEnabled`        | boolean  | `false` | Whether hard backpressure is enabled.                                                                     |       | —         |
| TUN-005 | `platform.wiring.defaultPoolMultiplier`          | double   | `0`     | Multiplier when sizing the default platform fork-join pool: `max(1, multiplier × processors + constant)`. |       | —         |
| TUN-006 | `platform.wiring.defaultPoolConstant`            | int      | `8`     | Constant added when sizing the default platform fork-join pool; may be negative.                          |       | —         |
| TUN-007 | `platform.wiring.healthMonitorSchedulerCapacity` | int      | `500`   | Unhandled-task capacity of the health monitor's scheduler.                                                |       | —         |
| TUN-008 | `platform.wiring.healthMonitorHeartbeatPeriod`   | Duration | `1ms`   | Period between heartbeats sent to the health monitor.                                                     |       | —         |
| TUN-009 | `platform.wiring.healthLogThreshold`             | Duration | `1s`    | How long a scheduler may be unhealthy before the platform is considered unhealthy and logs warnings.      |       | —         |
| TUN-010 | `platform.wiring.healthLogPeriod`                | Duration | `10m`   | Minimum time between health log messages for the same scheduler.                                          |       | —         |
| TUN-011 | `platform.wiring.healthyReportThreshold`         | Duration | `1s`    | Period between consecutive reports while the system is healthy.                                           |       | —         |

## `uptime.*` — UptimeConfig

Module: `swirlds-platform-core`. Source: [UptimeConfig.java](../../swirlds-platform-core/src/main/java/com/swirlds/platform/uptime/UptimeConfig.java).

|   ID    |              Key              |   Type   | Default |                                            Effect                                             | Range | Fragility |
|---------|-------------------------------|----------|---------|-----------------------------------------------------------------------------------------------|-------|-----------|
| TUN-012 | `uptime.degradationThreshold` | Duration | `10s`   | If none of a node's events reach consensus within this time, the node is considered degraded. |       | —         |

## `modules.*` — ModulesConfig

Module: `swirlds-platform-core`. Source: [ModulesConfig.java](../../swirlds-platform-core/src/main/java/com/swirlds/platform/builder/ModulesConfig.java).

Selects consensus module implementations via ServiceLoader. Each value is a JPMS module name; when empty the sole available provider is used.

|   ID    |          Key           |  Type  |                 Default                  |                       Effect                       | Range | Fragility |
|---------|------------------------|--------|------------------------------------------|----------------------------------------------------|-------|-----------|
| TUN-013 | `modules.eventCreator` | String | `org.hiero.consensus.event.creator.impl` | JPMS module name of the `EventCreatorModule` impl. |       | —         |
| TUN-014 | `modules.eventIntake`  | String | `org.hiero.consensus.event.intake.impl`  | JPMS module name of the `EventIntakeModule` impl.  |       | —         |
| TUN-015 | `modules.pces`         | String | `org.hiero.consensus.pces.impl`          | JPMS module name of the `PcesModule` impl.         |       | —         |
| TUN-016 | `modules.hashgraph`    | String | `org.hiero.consensus.hashgraph.impl`     | JPMS module name of the `HashgraphModule` impl.    |       | —         |
| TUN-017 | `modules.gossip`       | String | `org.hiero.consensus.gossip.impl`        | JPMS module name of the `GossipModule` impl.       |       | —         |
| TUN-018 | `modules.reconnect`    | String | `org.hiero.consensus.reconnect.impl`     | JPMS module name of the `ReconnectModule` impl.    |       | —         |

## `platformStatus.*` — PlatformStatusConfig

Module: `swirlds-platform-core`. Source: [PlatformStatusConfig.java](../../swirlds-platform-core/src/main/java/com/swirlds/platform/system/status/PlatformStatusConfig.java).

|   ID    |                        Key                         |   Type   | Default |                                                Effect                                                 | Range | Fragility |
|---------|----------------------------------------------------|----------|---------|-------------------------------------------------------------------------------------------------------|-------|-----------|
| TUN-019 | `platformStatus.observingStatusDelay`              | Duration | `10s`   | Wall-clock time to wait before transitioning out of the `OBSERVING` status.                           |       | —         |
| TUN-020 | `platformStatus.activeStatusDelay`                 | Duration | `10s`   | Wall-clock time required to transition out of `ACTIVE` (no self event reached, or quiescence change). |       | —         |
| TUN-021 | `platformStatus.statusStateMachineHeartbeatPeriod` | Duration | `100ms` | Wall-clock interval between heartbeats sent to the status state machine.                              |       | —         |

## `platform.metrics.*` — PlatformMetricsConfig

Module: `swirlds-platform-core`. Source: [PlatformMetricsConfig.java](../../swirlds-platform-core/src/main/java/com/swirlds/platform/metrics/PlatformMetricsConfig.java).

|   ID    |                      Key                       |  Type   | Default |                                    Effect                                    | Range | Fragility |
|---------|------------------------------------------------|---------|---------|------------------------------------------------------------------------------|-------|-----------|
| TUN-022 | `platform.metrics.eventPipelineMetricsEnabled` | boolean | `true`  | If true, the platform collects and reports metrics about the event pipeline. |       | —         |

## `platformSchedulers.*` — PlatformSchedulersConfig

Module: `swirlds-platform-core`. Source: [PlatformSchedulersConfig.java](../../swirlds-platform-core/src/main/java/com/swirlds/platform/wiring/PlatformSchedulersConfig.java).

Per-component `TaskSchedulerConfiguration` values that shape the platform wiring (scheduler type, queue capacity, flushable / squelchable flags, metric publication).

|   ID    |                            Key                            |            Type            |                                                Default                                                |                               Effect                                | Range | Fragility |
|---------|-----------------------------------------------------------|----------------------------|-------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------|-------|-----------|
| TUN-023 | `platformSchedulers.consensusEngine`                      | TaskSchedulerConfiguration | `SEQUENTIAL_THREAD CAPACITY(500) FLUSHABLE SQUELCHABLE UNHANDLED_TASK_METRIC BUSY_FRACTION_METRIC`    | Scheduler configuration for the consensus engine.                   |       | —         |
| TUN-024 | `platformSchedulers.stateSnapshotManager`                 | TaskSchedulerConfiguration | `SEQUENTIAL_THREAD CAPACITY(20) UNHANDLED_TASK_METRIC`                                                | Scheduler configuration for the state snapshot manager.             |       | —         |
| TUN-025 | `platformSchedulers.stateSigner`                          | TaskSchedulerConfiguration | `SEQUENTIAL CAPACITY(10) UNHANDLED_TASK_METRIC`                                                       | Scheduler configuration for the state signer.                       |       | —         |
| TUN-026 | `platformSchedulers.futureEventBuffer`                    | TaskSchedulerConfiguration | `SEQUENTIAL CAPACITY(500) FLUSHABLE UNHANDLED_TASK_METRIC BUSY_FRACTION_METRIC`                       | Scheduler configuration for the future-event buffer.                |       | —         |
| TUN-027 | `platformSchedulers.pcesSequencer`                        | TaskSchedulerConfiguration | `DIRECT`                                                                                              | Scheduler configuration for the preconsensus event sequencer.       |       | —         |
| TUN-028 | `platformSchedulers.applicationTransactionPrehandler`     | TaskSchedulerConfiguration | `CONCURRENT CAPACITY(500) FLUSHABLE UNHANDLED_TASK_METRIC`                                            | Scheduler configuration for the application transaction prehandler. |       | —         |
| TUN-029 | `platformSchedulers.stateSignatureCollector`              | TaskSchedulerConfiguration | `SEQUENTIAL CAPACITY(500) FLUSHABLE UNHANDLED_TASK_METRIC`                                            | Scheduler configuration for the state signature collector.          |       | —         |
| TUN-030 | `platformSchedulers.transactionHandler`                   | TaskSchedulerConfiguration | `SEQUENTIAL_THREAD CAPACITY(100000) FLUSHABLE SQUELCHABLE UNHANDLED_TASK_METRIC BUSY_FRACTION_METRIC` | Scheduler configuration for the transaction handler.                |       | —         |
| TUN-031 | `platformSchedulers.issDetector`                          | TaskSchedulerConfiguration | `SEQUENTIAL CAPACITY(500) UNHANDLED_TASK_METRIC`                                                      | Scheduler configuration for the ISS detector.                       |       | —         |
| TUN-032 | `platformSchedulers.issHandler`                           | TaskSchedulerConfiguration | `DIRECT`                                                                                              | Scheduler configuration for the ISS handler.                        |       | —         |
| TUN-033 | `platformSchedulers.hashLogger`                           | TaskSchedulerConfiguration | `SEQUENTIAL CAPACITY(100) UNHANDLED_TASK_METRIC`                                                      | Scheduler configuration for the hash logger.                        |       | —         |
| TUN-034 | `platformSchedulers.stateHasher`                          | TaskSchedulerConfiguration | `SEQUENTIAL_THREAD CAPACITY(100000) FLUSHABLE UNHANDLED_TASK_METRIC BUSY_FRACTION_METRIC`             | Scheduler configuration for the state hasher.                       |       | —         |
| TUN-035 | `platformSchedulers.stateGarbageCollector`                | TaskSchedulerConfiguration | `SEQUENTIAL CAPACITY(60) UNHANDLED_TASK_METRIC`                                                       | Scheduler configuration for the state garbage collector.            |       | —         |
| TUN-036 | `platformSchedulers.stateGarbageCollectorHeartbeatPeriod` | Duration                   | `200ms`                                                                                               | Heartbeat frequency sent to the state garbage collector.            |       | —         |
| TUN-037 | `platformSchedulers.signedStateSentinel`                  | TaskSchedulerConfiguration | `SEQUENTIAL UNHANDLED_TASK_METRIC`                                                                    | Scheduler configuration for the signed-state sentinel.              |       | —         |
| TUN-038 | `platformSchedulers.signedStateSentinelHeartbeatPeriod`   | Duration                   | `10s`                                                                                                 | Heartbeat frequency sent to the signed-state sentinel.              |       | —         |
| TUN-039 | `platformSchedulers.consensusEventStream`                 | TaskSchedulerConfiguration | `DIRECT_THREADSAFE`                                                                                   | Scheduler configuration for the consensus event stream.             |       | —         |
| TUN-040 | `platformSchedulers.roundDurabilityBuffer`                | TaskSchedulerConfiguration | `SEQUENTIAL CAPACITY(5) FLUSHABLE UNHANDLED_TASK_METRIC`                                              | Scheduler configuration for the round durability buffer.            |       | —         |
| TUN-041 | `platformSchedulers.platformMonitor`                      | TaskSchedulerConfiguration | `SEQUENTIAL CAPACITY(500) FLUSHABLE UNHANDLED_TASK_METRIC`                                            | Scheduler configuration for the platform monitor.                   |       | —         |
| TUN-042 | `platformSchedulers.transactionPool`                      | TaskSchedulerConfiguration | `DIRECT_THREADSAFE`                                                                                   | Scheduler configuration for the transaction pool.                   |       | —         |
| TUN-043 | `platformSchedulers.branchDetector`                       | TaskSchedulerConfiguration | `SEQUENTIAL CAPACITY(500) FLUSHABLE UNHANDLED_TASK_METRIC`                                            | Scheduler configuration for the branch detector.                    |       | —         |
| TUN-044 | `platformSchedulers.branchReporter`                       | TaskSchedulerConfiguration | `SEQUENTIAL CAPACITY(500) FLUSHABLE UNHANDLED_TASK_METRIC`                                            | Scheduler configuration for the branch reporter.                    |       | —         |

## `os.health.*` — OSHealthCheckConfig

Module: `swirlds-platform-core`. Source: [OSHealthCheckConfig.java](../../swirlds-platform-core/src/main/java/com/swirlds/platform/health/OSHealthCheckConfig.java).

Startup-time OS health probes; values exceeded at startup produce warning logs but do not block startup.

|   ID    |                     Key                     | Type |  Default  |                                    Effect                                    | Range | Fragility |
|---------|---------------------------------------------|------|-----------|------------------------------------------------------------------------------|-------|-----------|
| TUN-045 | `os.health.minClockCallsPerSec`             | long | `5000000` | Minimum required calls per second to the OS clock source.                    |       | —         |
| TUN-046 | `os.health.entropyTimeoutMillis`            | long | `10`      | Maximum milliseconds to wait for the OS entropy check to complete.           |       | —         |
| TUN-047 | `os.health.maxRandomNumberGenerationMillis` | long | `10`      | Maximum milliseconds allowed for a single random number to be generated.     |       | —         |
| TUN-048 | `os.health.fileReadTimeoutMillis`           | long | `50`      | Maximum milliseconds to wait for a file to be opened and a single byte read. |       | —         |
| TUN-049 | `os.health.maxFileReadMillis`               | long | `10`      | Maximum milliseconds allowed to open a file and read the first byte.         |       | —         |

## `crypto.*` — CryptoConfig

Module: `base-crypto`. Source: [CryptoConfig.java](../../base-crypto/src/main/java/org/hiero/base/crypto/config/CryptoConfig.java).

|   ID    |            Key            |  Type  |  Default   |                                         Effect                                         | Range | Fragility |
|---------|---------------------------|--------|------------|----------------------------------------------------------------------------------------|-------|-----------|
| TUN-050 | `crypto.keystorePassword` | String | `password` | Password protecting the PKCS12 key stores that hold node RSA public/private key pairs. |       | —         |

## BasicCommonConfig (no prefix)

Module: `consensus-concurrent`. Source: [BasicCommonConfig.java](../../consensus-concurrent/src/main/java/org/hiero/consensus/concurrent/config/BasicCommonConfig.java).

General properties that don't belong to a specific subsystem. Keys are bare property names.

|   ID    |         Key         |  Type   | Default |                             Effect                             | Range | Fragility |
|---------|---------------------|---------|---------|----------------------------------------------------------------|-------|-----------|
| TUN-051 | `showInternalStats` | boolean | `true`  | Show all statistics, including those with category `internal`. |       | —         |
| TUN-052 | `verboseStatistics` | boolean | `false` | Show expanded statistics values (mean, min, max, stdDev).      |       | —         |

## BasicConfig (no prefix)

Module: `consensus-utility`. Source: [BasicConfig.java](../../consensus-utility/src/main/java/org/hiero/consensus/config/BasicConfig.java).

General properties that don't belong to a specific subsystem. Keys are bare property names.

|   ID    |            Key            | Type | Default |                                         Effect                                          | Range | Fragility |
|---------|---------------------------|------|---------|-----------------------------------------------------------------------------------------|-------|-----------|
| TUN-053 | `jvmPauseDetectorSleepMs` | int  | `1000`  | Sleep period (ms) of the JVMPauseDetector thread between checks.                        |       | —         |
| TUN-054 | `jvmPauseReportMs`        | int  | `1000`  | Log an error when JVMPauseDetector detects a pause greater than this many milliseconds. |       | —         |

## `event.*` — EventConfig

Module: `consensus-utility`. Source: [EventConfig.java](../../consensus-utility/src/main/java/org/hiero/consensus/config/EventConfig.java).

|   ID    |               Key                |  Type   |           Default           |                                            Effect                                            | Range | Fragility |
|---------|----------------------------------|---------|-----------------------------|----------------------------------------------------------------------------------------------|-------|-----------|
| TUN-055 | `event.eventStreamQueueCapacity` | int     | `5000`                      | Capacity of the blocking queue from which events are taken and written to EventStream files. |       | —         |
| TUN-056 | `event.eventsLogPeriod`          | long    | `5`                         | Period (seconds) for generating eventStream files.                                           |       | —         |
| TUN-057 | `event.eventsLogDir`             | String  | `/opt/hgcapp/eventsStreams` | Directory where eventStream files are written.                                               |       | —         |
| TUN-058 | `event.enableEventStreaming`     | boolean | `true`                      | Enable streaming of events to the event-stream server.                                       |       | —         |

## `fallen.behind.*` — FallenBehindConfig

Module: `consensus-utility`. Source: [FallenBehindConfig.java](../../consensus-utility/src/main/java/org/hiero/consensus/config/FallenBehindConfig.java).

|   ID    |                  Key                  |  Type  | Default |                                           Effect                                            | Range | Fragility |
|---------|---------------------------------------|--------|---------|---------------------------------------------------------------------------------------------|-------|-----------|
| TUN-059 | `fallen.behind.fallenBehindThreshold` | double | `0.50`  | Fraction of neighbours that must report us as fallen-behind before we initiate a reconnect. |       | —         |

## `paths.*` — PathsConfig

Module: `consensus-utility`. Source: [PathsConfig.java](../../consensus-utility/src/main/java/org/hiero/consensus/config/PathsConfig.java).

|   ID    |           Key           | Type |    Default    |                                             Effect                                              | Range | Fragility |
|---------|-------------------------|------|---------------|-------------------------------------------------------------------------------------------------|-------|-----------|
| TUN-060 | `paths.settingsUsedDir` | Path | `.`           | Directory where the "settings used" file is created on startup (only if `settings.txt` exists). |       | —         |
| TUN-061 | `paths.keysDirPath`     | Path | `data/keys`   | Path to the keys directory.                                                                     |       | —         |
| TUN-062 | `paths.savedStateDir`   | Path | `data/saved`  | Path to where saved states and other state-related files are stored.                            |       | —         |
| TUN-063 | `paths.tmpDir`          | Path | `swirlds-tmp` | Path to the temporary-files directory, relative to `savedStateDir`.                             |       | —         |

## `recycleBin.*` — RecycleBinConfig

Module: `consensus-utility`. Source: [RecycleBinConfig.java](../../consensus-utility/src/main/java/org/hiero/consensus/config/RecycleBinConfig.java).

|   ID    |              Key              |   Type   |        Default        |                                Effect                                 | Range | Fragility |
|---------|-------------------------------|----------|-----------------------|-----------------------------------------------------------------------|-------|-----------|
| TUN-064 | `recycleBin.dirName`          | Path     | `swirlds-recycle-bin` | Name of the recycle-bin directory, relative to `paths.savedStateDir`. |       | —         |
| TUN-065 | `recycleBin.maximumFileAge`   | Duration | `7d`                  | Maximum age of a file in the recycle bin before it is deleted.        |       | —         |
| TUN-066 | `recycleBin.collectionPeriod` | Duration | `1d`                  | Period between recycle-bin collection runs.                           |       | —         |

## `reconnect.*` — ReconnectConfig

Module: `consensus-reconnect`. Source: [ReconnectConfig.java](../../consensus-reconnect/src/main/java/org/hiero/consensus/reconnect/config/ReconnectConfig.java).

|   ID    |                        Key                         |   Type   | Default |                                                                Effect                                                                | Range | Fragility |
|---------|----------------------------------------------------|----------|---------|--------------------------------------------------------------------------------------------------------------------------------------|-------|-----------|
| TUN-067 | `reconnect.active`                                 | boolean  | `true`  | If true, a node that falls behind attempts to reconnect; if false, it dies.                                                          |       | —         |
| TUN-068 | `reconnect.reconnectWindowSeconds`                 | int      | `-1`    | Window of time after startup during which reconnect is allowed; `-1` means always (still respects `reconnect.active`).               |       | —         |
| TUN-069 | `reconnect.asyncStreamTimeout`                     | Duration | `300s`  | Time an `AsyncInputStream` / `AsyncOutputStream` waits before throwing a timeout.                                                    |       | —         |
| TUN-070 | `reconnect.asyncOutputStreamFlush`                 | Duration | `8ms`   | Period of the periodic flush that drains the async output stream buffer.                                                             |       | —         |
| TUN-071 | `reconnect.asyncStreamBufferSize`                  | int      | `10000` | Size of the buffers for async input and output streams.                                                                              |       | —         |
| TUN-072 | `reconnect.maxAckDelay`                            | Duration | `10ms`  | Maximum time to wait for an ACK message before sending a potentially redundant node.                                                 |       | —         |
| TUN-073 | `reconnect.maximumReconnectFailuresBeforeShutdown` | int      | `10`    | Maximum number of failed reconnects in a row before shutdown.                                                                        |       | —         |
| TUN-074 | `reconnect.minimumTimeBetweenReconnects`           | Duration | `10m`   | Minimum time that must pass before a node is willing to help another node reconnect again.                                           |       | —         |
| TUN-075 | `reconnect.teacherMaxNodesPerSecond`               | int      | `0`     | Maximum number of nodes a teacher will send per second; `0` means no limit.                                                          |       | —         |
| TUN-076 | `reconnect.teacherRateLimiterSleep`                | Duration | `1us`   | Sleep applied by the teacher when throttling is engaged.                                                                             |       | —         |
| TUN-077 | `reconnect.pullLearnerRootResponseTimeout`         | Duration | `60s`   | Pull-based reconnect: learner-side timeout to receive a virtual root-node response from the teacher.                                 |       | —         |
| TUN-078 | `reconnect.allMessagesReceivedTimeout`             | Duration | `300s`  | Pull-based reconnect: learner-side timeout to wait until all virtual-view messages are processed after the teacher's final response. |       | —         |

## `state.*` — StateConfig

Module: `consensus-state`. Source: [StateConfig.java](../../consensus-state/src/main/java/org/hiero/consensus/state/config/StateConfig.java).

Shares the `state.*` prefix with [StateCommonConfig](#state---statecommonconfig); the keys below come from `StateConfig` (SignedStateManager / SignedStateFileManager behavior).

|   ID    |                  Key                  |   Type   | Default |                                                                    Effect                                                                     | Range | Fragility |
|---------|---------------------------------------|----------|---------|-----------------------------------------------------------------------------------------------------------------------------------------------|-------|-----------|
| TUN-079 | `state.mainClassNameOverride`         | String   | (empty) | Override for the main class name used in signed states; empty means use the default derived from `SwirldMain`.                                |       | —         |
| TUN-080 | `state.saveStatePeriod`               | int      | `900`   | Period (seconds) between writes of a state to disk; `0` to never write.                                                                       |       | —         |
| TUN-081 | `state.saveStateAsync`                | boolean  | `true`  | If true, periodic state snapshots are created asynchronously (perf optimization for high TPS / large state).                                  |       | —         |
| TUN-082 | `state.asyncSnapshotTimeout`          | long     | `750`   | Maximum time (seconds) to wait for an async snapshot to complete; on timeout an error is logged. Only relevant when `saveStateAsync` is true. |       | —         |
| TUN-083 | `state.signedStateDisk`               | int      | `5`     | Keep at least this many old complete signed states on disk (should be ≥ 2; `0` keeps none).                                                   |       | —         |
| TUN-084 | `state.haltOnAnyIss`                  | boolean  | `false` | Halt this node whenever any ISS in the network is detected (debug only — DoS vector in production).                                           |       | —         |
| TUN-085 | `state.automatedSelfIssRecovery`      | boolean  | `false` | Attempt to recover automatically when a self ISS is detected.                                                                                 |       | —         |
| TUN-086 | `state.haltOnCatastrophicIss`         | boolean  | `false` | Halt this node when a catastrophic ISS is detected.                                                                                           |       | —         |
| TUN-087 | `state.secondsBetweenIssLogs`         | long     | `300`   | Minimum time between log messages about ISS events; higher-frequency events are squelched.                                                    |       | —         |
| TUN-088 | `state.enableHashStreamLogging`       | boolean  | `true`  | When enabled, per-round node hashes are logged.                                                                                               |       | —         |
| TUN-089 | `state.debugHashDepth`                | int      | `5`     | When logging hash debug info, do not display nodes deeper than this in the merkle tree.                                                       |       | —         |
| TUN-090 | `state.maxAgeOfFutureStateSignatures` | int      | `1000`  | Maximum number of rounds in the future for which a node will accept a state signature.                                                        |       | —         |
| TUN-091 | `state.roundsToKeepForSigning`        | int      | `26`    | Maximum number of rounds a state is kept in memory while waiting to gather signatures.                                                        |       | —         |
| TUN-092 | `state.roundsToKeepAfterSigning`      | int      | `0`     | Number of rounds to keep states after signing and after a newer state has become fully signed; `0` GCs immediately.                           |       | —         |
| TUN-093 | `state.suspiciousSignedStateAgeGap`   | Duration | `5m`    | Age gap between newest and oldest signed state considered suspicious (triggers debug logging of potential state leak).                        |       | —         |
| TUN-094 | `state.signedStateAgeNotifyRateLimit` | Duration | `10m`   | Minimum period between notifications of suspiciously old signed states.                                                                       |       | —         |
| TUN-095 | `state.stateHistoryEnabled`           | boolean  | `false` | Keep a history of operations that modify signed-state reference counts (debug).                                                               |       | —         |
| TUN-096 | `state.debugStackTracesEnabled`       | boolean  | `false` | With `stateHistoryEnabled`, capture stack traces on each refcount change; logged if a refcount bug is detected.                               |       | —         |
| TUN-097 | `state.deleteInvalidStateFiles`       | boolean  | `false` | At startup, delete state files that can't be deserialized and try the next one; be very careful enabling network-wide.                        |       | —         |
| TUN-098 | `state.validateInitialState`          | boolean  | `true`  | If false, skip ISS validation on the state loaded from disk at startup (test-only).                                                           |       | —         |
| TUN-099 | `state.periodicSnapshotsEnabled`      | boolean  | `true`  | Create periodic snapshots of the signed state.                                                                                                |       | —         |

## `consensus.*` — ConsensusConfig

Module: `consensus-hashgraph`. Source: [ConsensusConfig.java](../../consensus-hashgraph/src/main/java/org/hiero/consensus/hashgraph/config/ConsensusConfig.java).

The canonical consensus tunables. Events older than the non-ancient window become ancient; ancient events without consensus are stale.

|   ID    |             Key              | Type | Default |                                                Effect                                                 | Range | Fragility |
|---------|------------------------------|------|---------|-------------------------------------------------------------------------------------------------------|-------|-----------|
| TUN-100 | `consensus.roundsNonAncient` | int  | `26`    | Number of consensus rounds defined as non-ancient.                                                    |       | —         |
| TUN-101 | `consensus.roundsExpired`    | int  | `1000`  | Events this many rounds old are expired and can be deleted from memory.                               |       | —         |
| TUN-102 | `consensus.coinFreq`         | int  | `12`    | A coin round happens every `coinFreq` rounds during an election (every other coin round is all-true). |       | —         |

## `hashgraph.wiring.*` — HashgraphWiringConfig

Module: `consensus-hashgraph`. Source: [HashgraphWiringConfig.java](../../consensus-hashgraph/src/main/java/org/hiero/consensus/hashgraph/config/HashgraphWiringConfig.java).

|   ID    |                Key                 |            Type            |                                              Default                                               |                           Effect                            | Range | Fragility |
|---------|------------------------------------|----------------------------|----------------------------------------------------------------------------------------------------|-------------------------------------------------------------|-------|-----------|
| TUN-103 | `hashgraph.wiring.consensusEngine` | TaskSchedulerConfiguration | `SEQUENTIAL_THREAD CAPACITY(500) FLUSHABLE SQUELCHABLE UNHANDLED_TASK_METRIC BUSY_FRACTION_METRIC` | Scheduler configuration for the hashgraph consensus engine. |       | —         |

## `metrics.*` — MetricsConfig

Module: `consensus-metrics`. Source: [MetricsConfig.java](../../consensus-metrics/src/main/java/org/hiero/consensus/metrics/config/MetricsConfig.java).

|   ID    |                 Key                 |  Type   |    Default     |                                             Effect                                              | Range | Fragility |
|---------|-------------------------------------|---------|----------------|-------------------------------------------------------------------------------------------------|-------|-----------|
| TUN-104 | `metrics.metricsUpdatePeriodMillis` | long    | `1000`         | Metrics update period, in milliseconds.                                                         | ≥0    | —         |
| TUN-105 | `metrics.disableMetricsOutput`      | boolean | `false`        | Disable all metrics outputs; overrides all other specific metrics-output settings when true.    |       | —         |
| TUN-106 | `metrics.csvOutputFolder`           | String  | `data/stats`   | Absolute or relative folder path where statistics CSV files are written.                        |       | —         |
| TUN-107 | `metrics.csvFileName`               | String  | `MainNetStats` | Prefix of the CSV file the platform writes statistics to (empty disables CSV statistics).       |       | —         |
| TUN-108 | `metrics.csvOverwrite`              | boolean | `true`         | If true, the statistics CSV is overwritten on each run.                                         |       | —         |
| TUN-109 | `metrics.csvWriteFrequency`         | int     | `3000`         | Frequency (ms) at which values are written to the statistics CSV file.                          | ≥0    | —         |
| TUN-110 | `metrics.halfLife`                  | double  | `10`           | Half-life of some statistics (seconds): the last `halfLife` seconds contribute half the weight. |       | —         |

## `prometheus.*` — PrometheusConfig

Module: `consensus-metrics`. Source: [PrometheusConfig.java](../../consensus-metrics/src/main/java/org/hiero/consensus/metrics/platform/prometheus/PrometheusConfig.java).

|   ID    |                  Key                   |  Type   | Default |                                               Effect                                                |  Range   | Fragility |
|---------|----------------------------------------|---------|---------|-----------------------------------------------------------------------------------------------------|----------|-----------|
| TUN-111 | `prometheus.endpointEnabled`           | boolean | `true`  | If true, the Prometheus endpoint is enabled.                                                        |          | —         |
| TUN-112 | `prometheus.endpointPortNumber`        | int     | `9999`  | Port of the Prometheus endpoint.                                                                    | 0..65535 | —         |
| TUN-113 | `prometheus.endpointMaxBacklogAllowed` | int     | `1`     | Maximum number of incoming TCP connections queued internally; may be `1` to use the system default. | ≥0       | —         |

## `event.preconsensus.*` — PcesConfig

Module: `consensus-pces`. Source: [PcesConfig.java](../../consensus-pces/src/main/java/org/hiero/consensus/pces/config/PcesConfig.java).

Preconsensus event storage (PCES).

|   ID    |                           Key                            |        Type        |        Default        |                                                    Effect                                                     | Range | Fragility |
|---------|----------------------------------------------------------|--------------------|-----------------------|---------------------------------------------------------------------------------------------------------------|-------|-----------|
| TUN-114 | `event.preconsensus.minimumRetentionPeriod`              | Duration           | `1h`                  | Minimum time PCES events are stored on disk (only matters when state snapshots are disabled).                 |       | —         |
| TUN-115 | `event.preconsensus.preferredFileSizeMegabytes`          | int                | `10`                  | Preferred file size for PCES files (advisory, not strict).                                                    |       | —         |
| TUN-116 | `event.preconsensus.bootstrapSpan`                       | int                | `50`                  | Value used for span utilization at first startup, before the running-average has real data.                   |       | —         |
| TUN-117 | `event.preconsensus.spanUtilizationRunningAverageLength` | int                | `5`                   | Number of recent files included in the span-utilization running average.                                      |       | —         |
| TUN-118 | `event.preconsensus.bootstrapSpanOverlapFactor`          | double             | `10`                  | Multiplier on the previous-file-span running average during bootstrap.                                        | ≥1    | —         |
| TUN-119 | `event.preconsensus.spanOverlapFactor`                   | double             | `1.2`                 | Multiplier on the previous-file-span running average during steady state.                                     | ≥1    | —         |
| TUN-120 | `event.preconsensus.minimumSpan`                         | int                | `5`                   | Floor on the available span when creating a new file (sanity floor on the heuristic).                         |       | —         |
| TUN-121 | `event.preconsensus.permitGaps`                          | boolean            | `false`               | If false, throw on detected gaps in the PCES file sequence (only relevant if files were deleted out of band). |       | —         |
| TUN-122 | `event.preconsensus.databaseDirectory`                   | Path               | `preconsensus-events` | Directory where PCES events are stored, relative to `StateCommonConfig.savedStateDirectory`.                  |       | —         |
| TUN-123 | `event.preconsensus.copyRecentStreamToStateSnapshots`    | boolean            | `true`                | If true, copy recent PCES files into the saved-state snapshot directory whenever a snapshot is taken.         |       | —         |
| TUN-124 | `event.preconsensus.compactLastFileOnStartup`            | boolean            | `true`                | If true, compact the last file's span at startup.                                                             |       | —         |
| TUN-125 | `event.preconsensus.forceIgnorePcesSignatures`           | boolean            | `false`               | If true, ignore PCES event signatures. **TEST ONLY** — must never be enabled in production.                   |       | —         |
| TUN-126 | `event.preconsensus.replayHealthThreshold`               | Duration           | `1ms`                 | If the system is unhealthy for more than this time, pause PCES replay until it catches up.                    |       | —         |
| TUN-127 | `event.preconsensus.limitReplayFrequency`                | boolean            | `true`                | If true, directly limit the replay frequency of PCES events.                                                  |       | —         |
| TUN-128 | `event.preconsensus.maxEventReplayFrequency`             | int                | `5000`                | Maximum number of events that can be replayed per second.                                                     |       | —         |
| TUN-129 | `event.preconsensus.inlinePcesSyncOption`                | FileSyncOption     | `DONT_SYNC`           | When to fsync the PCES file (inline writer only).                                                             |       | —         |
| TUN-130 | `event.preconsensus.pcesFileWriterType`                  | PcesFileWriterType | `FILE_CHANNEL`        | PCES writer used in the default environment (Linux).                                                          |       | —         |
| TUN-131 | `event.preconsensus.macPcesFileWriterType`               | PcesFileWriterType | `OUTPUT_STREAM`       | Override for `pcesFileWriterType` on macOS (FileChannel is ~150× slower there).                               |       | —         |

## `event.intake.wiring.*` — PcesWiringConfig

Module: `consensus-pces`. Source: [PcesWiringConfig.java](../../consensus-pces/src/main/java/org/hiero/consensus/pces/config/PcesWiringConfig.java).

Shares the `event.intake.wiring.*` prefix with [EventIntakeWiringConfig](#eventintakewiring---eventintakewiringconfig); the key below comes from `PcesWiringConfig`.

|   ID    |                  Key                   |            Type            |                                     Default                                     |                       Effect                        | Range | Fragility |
|---------|----------------------------------------|----------------------------|---------------------------------------------------------------------------------|-----------------------------------------------------|-------|-----------|
| TUN-132 | `event.intake.wiring.pcesInlineWriter` | TaskSchedulerConfiguration | `SEQUENTIAL CAPACITY(500) FLUSHABLE UNHANDLED_TASK_METRIC BUSY_FRACTION_METRIC` | Scheduler configuration for the PCES inline writer. |       | —         |

## `event.creation.*` — EventCreationConfig

Module: `consensus-event-creator`. Source: [EventCreationConfig.java](../../consensus-event-creator/src/main/java/org/hiero/consensus/event/creator/config/EventCreationConfig.java).

|   ID    |                         Key                          |   Type   | Default |                                                                 Effect                                                                  | Range | Fragility |
|---------|------------------------------------------------------|----------|---------|-----------------------------------------------------------------------------------------------------------------------------------------|-------|-----------|
| TUN-133 | `event.creation.maxCreationRate`                     | double   | `20`    | Maximum per-node event creation rate (Hz); `0` removes the limit. (LAYOUT.md's `max_event_creation_frequency`.)                         |       | —         |
| TUN-134 | `event.creation.period`                              | Duration | `10ms`  | Period at which the node attempts to create new events (still capped by `maxCreationRate`).                                             |       | —         |
| TUN-135 | `event.creation.antiSelfishnessFactor`               | double   | `10`    | Lower values make it more likely to create events that reduce the selfishness score; too low harms topology, too high enables ignoring. |       | —         |
| TUN-136 | `event.creation.tipsetSnapshotHistorySize`           | int      | `10`    | Number of tipsets kept in the snapshot history (used to compute selfishness scores).                                                    |       | —         |
| TUN-137 | `event.creation.eventIntakeThrottle`                 | int      | `1024`  | When the event intake queue equals or exceeds this size, new self-event creation is suspended.                                          |       | —         |
| TUN-138 | `event.creation.maximumPermissibleUnhealthyDuration` | Duration | `1s`    | Maximum time the system can be unhealthy before event creation stops.                                                                   |       | —         |
| TUN-139 | `event.creation.maxAllowedSyncLag`                   | int      | `15`    | If the node is lagging more than this many rounds on average, stop creating events; very large values effectively disable the rule.     |       | —         |
| TUN-140 | `event.creation.maxOtherParents`                     | int      | `4`     | Maximum number of other parents an event may reference.                                                                                 |       | —         |

## `event.creation.wiring.*` — EventCreationWiringConfig

Module: `consensus-event-creator`. Source: [EventCreationWiringConfig.java](../../consensus-event-creator/src/main/java/org/hiero/consensus/event/creator/config/EventCreationWiringConfig.java).

Shares the `event.creation.wiring.*` prefix with [GossipWiringConfig](#eventcreationwiring---gossipwiringconfig); the key below comes from `EventCreationWiringConfig`.

|   ID    |                     Key                      |            Type            |                                Default                                 |                         Effect                          | Range | Fragility |
|---------|----------------------------------------------|----------------------------|------------------------------------------------------------------------|---------------------------------------------------------|-------|-----------|
| TUN-141 | `event.creation.wiring.eventCreationManager` | TaskSchedulerConfiguration | `SEQUENTIAL CAPACITY(500) FLUSHABLE SQUELCHABLE UNHANDLED_TASK_METRIC` | Scheduler configuration for the event-creation manager. |       | —         |

## `event.intake.wiring.*` — EventIntakeWiringConfig

Module: `consensus-event-intake`. Source: [EventIntakeWiringConfig.java](../../consensus-event-intake/src/main/java/org/hiero/consensus/event/intake/config/EventIntakeWiringConfig.java).

Shares the `event.intake.wiring.*` prefix with [PcesWiringConfig](#eventintakewiring---pceswiringconfig); the keys below come from `EventIntakeWiringConfig`.

|   ID    |                      Key                      |            Type            |                                     Default                                      |                           Effect                           | Range | Fragility |
|---------|-----------------------------------------------|----------------------------|----------------------------------------------------------------------------------|------------------------------------------------------------|-------|-----------|
| TUN-142 | `event.intake.wiring.eventHasher`             | TaskSchedulerConfiguration | `CONCURRENT CAPACITY(500) FLUSHABLE UNHANDLED_TASK_METRIC`                       | Scheduler configuration for the event hasher.              |       | —         |
| TUN-143 | `event.intake.wiring.internalEventValidator`  | TaskSchedulerConfiguration | `CONCURRENT CAPACITY(500) FLUSHABLE UNHANDLED_TASK_METRIC`                       | Scheduler configuration for the internal event validator.  |       | —         |
| TUN-144 | `event.intake.wiring.eventDeduplicator`       | TaskSchedulerConfiguration | `SEQUENTIAL CAPACITY(5000) FLUSHABLE UNHANDLED_TASK_METRIC BUSY_FRACTION_METRIC` | Scheduler configuration for the event deduplicator.        |       | —         |
| TUN-145 | `event.intake.wiring.eventSignatureValidator` | TaskSchedulerConfiguration | `CONCURRENT CAPACITY(500) FLUSHABLE UNHANDLED_TASK_METRIC`                       | Scheduler configuration for the event signature validator. |       | —         |
| TUN-146 | `event.intake.wiring.orphanBuffer`            | TaskSchedulerConfiguration | `SEQUENTIAL CAPACITY(500) FLUSHABLE UNHANDLED_TASK_METRIC BUSY_FRACTION_METRIC`  | Scheduler configuration for the orphan buffer.             |       | —         |

## `gossip.*` — GossipConfig

Module: `consensus-gossip`. Source: [GossipConfig.java](../../consensus-gossip/src/main/java/org/hiero/consensus/gossip/config/GossipConfig.java).

|   ID    |                   Key                   |            Type             | Default |                                                                 Effect                                                                  | Range | Fragility |
|---------|-----------------------------------------|-----------------------------|---------|-----------------------------------------------------------------------------------------------------------------------------------------|-------|-----------|
| TUN-147 | `gossip.interfaceBindings`              | List&lt;NetworkEndpoint&gt; | (empty) | Per-node interface bindings used in `SocketFactory`; overrides default network behavior in specialized environments (containers, etc.). |       | —         |
| TUN-148 | `gossip.endpointOverrides`              | List&lt;NetworkEndpoint&gt; | (empty) | Per-node endpoint overrides used in `OutboundConnectionCreator`; replaces roster IP/port when network config diverges from the roster.  |       | —         |
| TUN-149 | `gossip.connectionServerThreadPriority` | int                         | `5`     | Priority for threads listening for incoming gossip connections.                                                                         |       | —         |
| TUN-150 | `gossip.hangingThreadDuration`          | Duration                    | `60s`   | How long a gossip thread is allowed to wait on shutdown before logging an error.                                                        |       | —         |

## `event.creation.wiring.*` — GossipWiringConfig

Module: `consensus-gossip`. Source: [GossipWiringConfig.java](../../consensus-gossip/src/main/java/org/hiero/consensus/gossip/config/GossipWiringConfig.java).

Shares the `event.creation.wiring.*` prefix with [EventCreationWiringConfig](#eventcreationwiring---eventcreationwiringconfig); the key below comes from `GossipWiringConfig`.

|   ID    |              Key               |            Type            |                          Default                           |                      Effect                       | Range | Fragility |
|---------|--------------------------------|----------------------------|------------------------------------------------------------|---------------------------------------------------|-------|-----------|
| TUN-151 | `event.creation.wiring.gossip` | TaskSchedulerConfiguration | `SEQUENTIAL CAPACITY(500) FLUSHABLE UNHANDLED_TASK_METRIC` | Scheduler configuration for the gossip scheduler. |       | —         |

## `broadcast.*` — BroadcastConfig

Module: `consensus-gossip`. Source: [BroadcastConfig.java](../../consensus-gossip/src/main/java/org/hiero/consensus/gossip/config/BroadcastConfig.java).

|   ID    |                      Key                       |   Type   | Default |                                                      Effect                                                       | Range | Fragility |
|---------|------------------------------------------------|----------|---------|-------------------------------------------------------------------------------------------------------------------|-------|-----------|
| TUN-152 | `broadcast.enableBroadcast`                    | boolean  | `true`  | Enable simplistic broadcast (every self-event sent to every neighbour).                                           |       | —         |
| TUN-153 | `broadcast.disablePingThreshold`               | Duration | `900ms` | If ping against a peer breaches this level, disable broadcast for a while (sync is more efficient at that point). |       | —         |
| TUN-154 | `broadcast.throttleOutputQueueThreshold`       | int      | `200`   | If the RPC output queue exceeds this size, disable broadcast temporarily to avoid additional network load.        |       | —         |
| TUN-155 | `broadcast.pauseOnLag`                         | Duration | `30s`   | Duration broadcast is paused once communication overload is detected.                                             |       | —         |
| TUN-156 | `broadcast.rpcSleepAfterSyncWhileBroadcasting` | Duration | `100ms` | Override for `sync.rpcSleepAfterSync` while broadcast is running.                                                 |       | —         |

## `protocol.*` — ProtocolConfig

Module: `consensus-gossip`. Source: [ProtocolConfig.java](../../consensus-gossip/src/main/java/org/hiero/consensus/gossip/config/ProtocolConfig.java).

|   ID    |                  Key                   |  Type   | Default |                                            Effect                                             | Range | Fragility |
|---------|----------------------------------------|---------|---------|-----------------------------------------------------------------------------------------------|-------|-----------|
| TUN-157 | `protocol.tolerateMismatchedVersion`   | boolean | `false` | If true, tolerate peers with a different software version; if false, sever those connections. |       | —         |
| TUN-158 | `protocol.tolerateMismatchedEpochHash` | boolean | `false` | If true, tolerate peers with a different epoch hash; if false, sever those connections.       |       | —         |

## `socket.*` — SocketConfig

Module: `consensus-gossip`. Source: [SocketConfig.java](../../consensus-gossip/src/main/java/org/hiero/consensus/gossip/config/SocketConfig.java).

|   ID    |                  Key                  |  Type   | Default |                                                  Effect                                                  |  Range  | Fragility |
|---------|---------------------------------------|---------|---------|----------------------------------------------------------------------------------------------------------|---------|-----------|
| TUN-159 | `socket.ipTos`                        | int     | `-1`    | IP_TOS for the socket (0–255), or `-1` to not set; can influence QoS handling on cooperating routers.    | -1..255 | —         |
| TUN-160 | `socket.bufferSize`                   | int     | `8192`  | BufferedInputStream / BufferedOutputStream buffer size used for syncing.                                 |         | —         |
| TUN-161 | `socket.timeoutSyncClientSocket`      | int     | `5000`  | Timeout (ms) when waiting for data on the sync client socket.                                            |         | —         |
| TUN-162 | `socket.timeoutSyncClientConnect`     | int     | `5000`  | Timeout (ms) when establishing a sync client connection.                                                 |         | —         |
| TUN-163 | `socket.timeoutServerAcceptConnect`   | int     | `5000`  | Timeout (ms) when the server is waiting for another member to create a connection.                       |         | —         |
| TUN-164 | `socket.useLoopbackIp`                | boolean | `false` | Set to true when using the internet simulator.                                                           |         | —         |
| TUN-165 | `socket.tcpNoDelay`                   | boolean | `true`  | If true, Nagle's algorithm is disabled (helps latency, costs bandwidth).                                 |         | —         |
| TUN-166 | `socket.gzipCompression`              | boolean | `false` | Whether to gzip-compress network traffic.                                                                |         | —         |
| TUN-167 | `socket.waitBetweenConnectionRetries` | int     | `10`    | Milliseconds to wait before retrying a broken connection; `≤0` means no sleep.                           |         | —         |
| TUN-168 | `socket.maxSocketAcceptThreads`       | int     | `30`    | Max threads spawned to handle incoming SSL socket accepts (capped to limit DoS-style thread exhaustion). |         | —         |

## `sync.*` — SyncConfig

Module: `consensus-gossip`. Source: [SyncConfig.java](../../consensus-gossip/src/main/java/org/hiero/consensus/gossip/config/SyncConfig.java).

|   ID    |                    Key                    |   Type   | Default |                                                                      Effect                                                                      | Range | Fragility |
|---------|-------------------------------------------|----------|---------|--------------------------------------------------------------------------------------------------------------------------------------------------|-------|-----------|
| TUN-169 | `sync.syncSleepAfterFailedNegotiation`    | int      | `25`    | Sleep (ms) after a failed negotiation in the sync-as-protocol algorithm.                                                                         |       | —         |
| TUN-170 | `sync.syncProtocolPermitCount`            | int      | `17`    | Number of permits used when running the sync algorithm.                                                                                          |       | —         |
| TUN-171 | `sync.onePermitPerPeer`                   | boolean  | `true`  | If true, allocate exactly one sync permit per peer (overrides `syncProtocolPermitCount`).                                                        |       | —         |
| TUN-172 | `sync.syncProtocolHeartbeatPeriod`        | int      | `1000`  | Heartbeat period (ms) for the sync protocol when the algorithm is active.                                                                        |       | —         |
| TUN-173 | `sync.waitForEventsInIntake`              | boolean  | `true`  | (no Javadoc; controls whether sync waits for events to be processed by intake before responding).                                                |       | —         |
| TUN-174 | `sync.filterLikelyDuplicates`             | boolean  | `true`  | If true, suppress sending events likely to be duplicates from the peer's perspective.                                                            |       | —         |
| TUN-175 | `sync.nonAncestorFilterThreshold`         | Duration | `3s`    | Minimum age before a non-self, non-ancestor event is eligible to be sent (ignored when `filterLikelyDuplicates=false`).                          |       | —         |
| TUN-176 | `sync.ancestorFilterThreshold`            | Duration | `250ms` | Minimum age before a non-self ancestor event is eligible to be sent (ignored unless `filterLikelyDuplicates` and broadcast are enabled).         |       | —         |
| TUN-177 | `sync.selfFilterThreshold`                | Duration | `1s`    | Minimum age before a self event is eligible to be sent (ignored unless `filterLikelyDuplicates` and broadcast are enabled).                      |       | —         |
| TUN-178 | `sync.syncKeepalivePeriod`                | Duration | `500ms` | Send a keepalive every this many ms when reading events during a sync.                                                                           |       | —         |
| TUN-179 | `sync.maxSyncTime`                        | Duration | `1m`    | Maximum time spent syncing with a peer; longer syncs are aborted.                                                                                |       | —         |
| TUN-180 | `sync.maxSyncEventCount`                  | int      | `5000`  | Maximum events sent in a sync; `0` means no limit.                                                                                               |       | —         |
| TUN-181 | `sync.unhealthyGracePeriod`               | Duration | `1s`    | How long the system can be unhealthy before sync permits start being revoked.                                                                    |       | —         |
| TUN-182 | `sync.permitsRevokedPerSecond`            | double   | `5`     | Permits revoked per second when the system is unhealthy past the grace period.                                                                   |       | —         |
| TUN-183 | `sync.permitsReturnedPerSecond`           | double   | `1`     | Permits returned per second when the system is healthy.                                                                                          |       | —         |
| TUN-184 | `sync.minimumHealthyUnrevokedPermitCount` | int      | `1`     | Minimum permits that must remain unrevoked while healthy; non-zero means that many are returned immediately on becoming healthy.                 |       | —         |
| TUN-185 | `sync.rpcSleepAfterSync`                  | Duration | `5ms`   | RPC sync: time after finishing a sync during which a new sync is not attempted; see `broadcast.rpcSleepAfterSyncWhileBroadcasting` for override. |       | —         |
| TUN-186 | `sync.rpcIdleWritePollTimeout`            | Duration | `5ms`   | How long the gossip RPC mechanism waits between piggybacking actions on write threads (e.g. ping) when no events are ready to send.              |       | —         |
| TUN-187 | `sync.rpcIdleDispatchPollTimeout`         | Duration | `5ms`   | How long the gossip RPC mechanism waits between dispatch actions when no events are ready to process.                                            |       | —         |
| TUN-188 | `sync.fairMaxConcurrentSyncs`             | double   | `-1`    | Max concurrent syncs (`≤0` disables; `(0,1]` is a fraction of network size; `>1` is the absolute ceiling).                                       |       | —         |
| TUN-189 | `sync.fairMinimalRoundRobinSize`          | double   | `0.3`   | Minimum past-syncs-against-different-peers before re-syncing the same peer (`(0,1]` fraction of network; `>1` absolute count).                   |       | —         |
| TUN-190 | `sync.keepSendingEventsWhenUnhealthy`     | boolean  | `true`  | When unhealthy, stop receiving remote events but keep sending our own (instead of fully throttling syncs).                                       |       | —         |
| TUN-191 | `sync.pingPeriod`                         | Duration | `1s`    | Period at which ping messages are sent to peers during syncs.                                                                                    |       | —         |
