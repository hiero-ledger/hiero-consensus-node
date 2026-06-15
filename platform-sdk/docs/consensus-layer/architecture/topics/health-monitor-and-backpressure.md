---
title: Health monitor and backpressure
kind: architecture-topic
last_reviewed: TBD
---

# Health monitor and backpressure

## Responsibilities

The health monitor watches the queues of every component in the wiring model and publishes a single "unhealthy duration" signal — the longest time any one queue has been continuously over its preferred size. It is a detector, not an enforcer: reaction sites elsewhere in the system (event creation, gossip, transaction acceptance, PCES replay) read the signal and decide what to do with it.

What the health monitor does:

- Polls each watched scheduler's queue size against its capacity on a heartbeat.
- Tracks how long each scheduler has been continuously unhealthy.
- Publishes the longest such duration on a wire and exposes it for polling.

What it does not do:

- It does not block enqueues. Insertion into a component's queue never applies hard backpressure (`platform.wiring.hardBackpressureEnabled` is `false` by default).
- It does not throttle, revoke permits, or reject transactions. Each reaction site below owns its own threshold and response.

The detection mechanism sits on top of the wiring substrate; for the underlying primitives, see [wiring-framework.md](wiring-framework.md).

## Detection

Detection lives in `swirlds-component-framework`, in `com.swirlds.component.framework.model.internal.monitor.HealthMonitor`. The model wires a heartbeat to `HealthMonitor.checkSystemHealth(Instant)` at construction time (`StandardWiringModel#StandardWiringModel`, around line 124: `buildHeartbeatWire(builder.getHealthMonitorPeriod()).solderTo(healthMonitorInputWire)`). The heartbeat period is `platform.wiring.healthMonitorHeartbeatPeriod` (TUN-008).

For each watched scheduler the monitor compares `TaskScheduler.getUnprocessedTaskCount()` with `TaskScheduler.getCapacity()` (`HealthMonitor.checkSystemHealth`, line 123). A scheduler is "unhealthy" when its unprocessed-task count exceeds its capacity. Capacity is set per component via `TaskSchedulerBuilder.withUnhandledTaskCapacity(long)`; schedulers built with `UNLIMITED_CAPACITY` are skipped at registration time (`HealthMonitor` constructor, line 93) and are never reported as unhealthy.

The monitor records, per scheduler, the time of the first unhealthy observation in the current run. From that it computes how long the scheduler has been continuously unhealthy and reports the **longest** such duration across all watched schedulers — that is, the worst single offender (`HealthMonitor.checkSystemHealth`, line 134). When a previously unhealthy scheduler is observed healthy, its timer is reset (line 125), so transient blips do not persist in the signal.

Output reaches consumers two ways:

- A wire publication via `StandardWiringModel.getHealthMonitorWire()` returning `OutputWire<Duration>` (line 165). Soldered to consumers in `swirlds-platform-core/.../PlatformWiring` (around lines 98–110): event creator, gossip module, and the execution layer (which forwards to `TransactionPoolNexus`).
- A polled accessor `WiringModel.getUnhealthyDuration()` (line 174), used by PCES replay.

A reported `Duration.ZERO` means all watched schedulers are healthy. A non-zero value means at least one scheduler is over capacity, with the magnitude indicating how long. To avoid spamming consumers, the monitor suppresses repeat reports of the same duration unless the system has been continuously healthy for `platform.wiring.healthyReportThreshold` (TUN-011), at which point a healthy state is re-asserted (`HealthMonitor.checkSystemHealth`, lines 142–157).

The wire-soldered consumers (event creator, gossip, transaction acceptance) are therefore **edge-driven**: only deltas arrive, and each consumer's `reportUnhealthyDuration` handler is responsible for tracking the last value it saw. PCES is the exception — it polls `getUnhealthyDuration()` directly and always sees the current level.

## Reactions

Each reaction site reads the unhealthy-duration signal independently and applies its own threshold. Reactions are not coordinated.

### Event-creation throttling

Site: `consensus-event-creator-impl/.../rules/PlatformHealthRule.isEventCreationPermitted()` (line 40). Installed as one of the rules consulted by `DefaultEventCreationManager` (line 111: `rules.add(new PlatformHealthRule(config.maximumPermissibleUnhealthyDuration(), this::getUnhealthyDuration))`). The signal flows in through `EventCreationManager.reportUnhealthyDuration(Duration)`, soldered in `DefaultEventCreatorModule` (line 136).

Trigger: when the reported unhealthy duration exceeds `event.creation.maximumPermissibleUnhealthyDuration` (TUN-138), the rule returns `false` from `isEventCreationPermitted()` and the event creator stops minting new self events. When the duration drops back to or below the threshold, event creation resumes; the rule reports `EventCreationStatus.OVERLOADED` while engaged.

See [event-creator.md](event-creator.md) for the rest of the rules and the event-creation lifecycle.

### Gossip permits

Site: `consensus-gossip-impl/.../gossip/permits/SyncPermitProvider.reportUnhealthyDuration(Duration)` (line 161), with permit accounting in the private `computeRevokedPermits()` (line 247). Sync gossip requires a permit per in-flight sync; revoking a permit prevents new syncs from starting but does not interrupt syncs already in progress.

Triggers and rates:

- Reactions are gated by a grace period: `SyncPermitProvider` only enters the unhealthy branch once the reported duration reaches `sync.unhealthyGracePeriod` (TUN-181). Brief blips below the grace period leave permits untouched.
- While unhealthy, permits are revoked at `sync.permitsRevokedPerSecond` (TUN-182).
- When the system returns to healthy, permits are returned at `sync.permitsReturnedPerSecond` (TUN-183).
- A floor of `sync.minimumHealthyUnrevokedPermitCount` (TUN-184) un-revoked permits is enforced as soon as the system becomes healthy, so a recovered system is not stuck at zero permits while it waits for the gradual return rate.
- `sync.keepSendingEventsWhenUnhealthy` (TUN-190) softens the reaction further: when set, the node continues to send its own events during an unhealthy period and only stops receiving and processing remote events. Continuing to send self events lets the rest of the network build on them, which prevents the local node's recent events — and the user transactions they carry — from going stale and expiring before they reach consensus.

When `sync.keepSendingEventsWhenUnhealthy` is `true`, the health-driven permit revoke path is bypassed entirely: `SyncPermitProvider.computeRevokedPermits()` (line 274) guards the unhealthy-branch delta computation with `if (!keepSendingEventsWhenUnhealthy)`, so permit accounting and the keep-sending mode are mutually exclusive — only one of them reacts to an unhealthy report. Permit accounting still applies on reconnect: `SyncPermitProvider.revokeAll()` (line 194) is invoked when a reconnect begins, and the configured `permitsReturnedPerSecond` then slowly restores permits once the reconnect finishes.

See [gossip.md](gossip.md) for the sync protocol and the broader role of permits.

### Transaction acceptance gate

Site: `consensus-utility/.../transaction/TransactionPoolNexus.submitApplicationTransaction(Bytes)` (line 129). The gate consults a private `healthy` flag set by `TransactionPoolNexus.reportUnhealthyDuration(Duration)` (line 299), which compares the reported duration against the `maximumPermissibleUnhealthyDuration` passed in at construction time.

Trigger: when the unhealthy duration meets or exceeds the configured threshold, `submitApplicationTransaction` returns `false` immediately, rejecting the application transaction. Priority transactions (system transactions, submitted via `submitPriorityTransaction`) are not gated.

Wiring: the signal reaches `TransactionPoolNexus` through the execution layer rather than directly off the platform wire. `swirlds-platform-core/.../PlatformWiring` (line 110) solders the health-monitor output to `ExecutionLayer.reportUnhealthyDuration(Duration)`; the Hedera implementation forwards to its `TransactionPoolNexus` (`DefaultSwirldMain.reportUnhealthyDuration`, line 66). The threshold is a Hedera-side config: `transaction.maximumPermissibleUnhealthySeconds` (`HederaConfig`, default `1`), converted to a `Duration` when the nexus is built.

Note: this is a **separate** config key from the event-creator threshold, even though both currently default to one second. The two reactions are intentionally tunable independently, and operators are not expected to keep them in lockstep. In practice it is preferable to set `transaction.maximumPermissibleUnhealthySeconds` lower than `event.creation.maximumPermissibleUnhealthyDuration` so that transaction acceptance closes before event creation does — otherwise transactions admitted into the pool can expire there with no events left to drain them.

### PCES replay throttling

Site: `consensus-pces-impl/.../replayer/PcesReplayer.waitUntilHealthy()` (line 206). The replay loop calls `waitUntilHealthy()` before each event (`PcesReplayer.replayPces`, line 172) and blocks (sleeping 100 ms at a time) until the supplied health check returns true.

The health check is constructed in `consensus-pces-impl/.../DefaultPcesModule` (lines 124–133) as `() -> isLessThan(model.getUnhealthyDuration(), replayHealthThreshold)`. Trigger: when the polled unhealthy duration meets or exceeds `event.preconsensus.replayHealthThreshold` (TUN-126) — a much tighter threshold than the gossip-side grace period (TUN-181).

In addition to the health gate, replay is rate-limited by a `RateLimiter` constructed with `event.preconsensus.maxEventReplayFrequency` (TUN-128), checked at `PcesReplayer.replayPces` line 174. The rate limiter can be disabled via `event.preconsensus.limitReplayFrequency` (TUN-127). The rate limiter exists because replay can vastly outstrip the rate at which the system can ingest events — fast enough that the health monitor cannot detect the overload before the system is flooded, so a separate frequency cap is required.

PCES is the only reaction site that polls the signal rather than receiving it on a wire; this is appropriate because the replay loop is naturally a polling site and benefits from the freshest possible value.

See [restart-and-pces.md](restart-and-pces.md) for the replay lifecycle.

## Tunables

The detection cadence and per-reaction thresholds are all configured
parameters; their defaults, types, and full effects live in the canonical
catalog [../../tunables.md](../../tunables.md) and are not repeated here. The
relevant entries, grouped by where they act:

- **Detection** (`WiringConfig`, `platform.wiring.*`): TUN-003
  (`healthMonitorEnabled`), TUN-004 (`hardBackpressureEnabled`), TUN-007
  (`healthMonitorSchedulerCapacity`), TUN-008 (`healthMonitorHeartbeatPeriod`),
  TUN-009 (`healthLogThreshold`), TUN-010 (`healthLogPeriod`), TUN-011
  (`healthyReportThreshold`).
- **Event-creation throttling** (`EventCreationConfig`): TUN-138
  (`maximumPermissibleUnhealthyDuration`).
- **Gossip permits** (`SyncConfig`): TUN-181 (`unhealthyGracePeriod`), TUN-182
  (`permitsRevokedPerSecond`), TUN-183 (`permitsReturnedPerSecond`), TUN-184
  (`minimumHealthyUnrevokedPermitCount`), TUN-190
  (`keepSendingEventsWhenUnhealthy`).
- **PCES replay** (`PcesConfig`): TUN-126 (`replayHealthThreshold`), TUN-127
  (`limitReplayFrequency`), TUN-128 (`maxEventReplayFrequency`).
- **Transaction acceptance**: `transaction.maximumPermissibleUnhealthySeconds`
  is a Hedera-side `HederaConfig` key, outside the consensus-layer tunables
  catalog; see the [Transaction acceptance gate](#transaction-acceptance-gate)
  reaction above for its behaviour.

## Cross-references

- Topics: [wiring-framework.md](wiring-framework.md), [event-creator.md](event-creator.md), [gossip.md](gossip.md), [restart-and-pces.md](restart-and-pces.md), [reasons-not-to-gossip.md](reasons-not-to-gossip.md).
- Tunables: [../../tunables.md](../../tunables.md).
- Invariants: [TBD: INV-NNN once invariants.md catalog populates].
- Decisions: [TBD: ADR-NNN once decisions/ catalog populates].
- Source doc: [../../../core/health-monitor.md](../../../core/health-monitor.md).

## Future state (sidebar)

The proposal introduces a coarser, module-API-level backpressure (the `nextRound` pull; see the [overview's Future state](../overview.md#future-state)): Execution drives the rate at which `nextRound` is called, and the Hashgraph module never advances faster than Execution requests. This is an overlay on top of the wire-level / queue-saturation mechanism described above — a per-round sliding window in addition to the per-queue health signal, not a replacement for it.
