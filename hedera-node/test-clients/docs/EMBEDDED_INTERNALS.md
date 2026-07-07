# Embedded Network Internals

When a test runs against an `EmbeddedNetwork`, the framework instantiates a real
`com.hedera.node.app.Hedera` in the test process and drives it through fake versions of the
`Platform`, `NotificationEngine`, and supporting services. This document describes those fakes —
useful when an embedded test fails in a non-obvious way or when the framework needs to be extended.

For the user-facing description of *when* to use embedded mode, see [`../README.md`](../README.md)
§ *Embedded networks*.

## Class map

The embedded framework lives under `junit/hedera/embedded/`, with two top-level groupings:

- The directory itself contains the `EmbeddedNetwork` / `EmbeddedNode` adapters and the
  `EmbeddedHedera` family — an interface plus `Concurrent` and `Repeatable` variants (selected
  by the `EmbeddedMode` enum) sharing an `AbstractEmbeddedHedera` base.
- `fakes/` contains the stand-in `Platform`, `NotificationEngine`, `PlatformContext`, hints and
  history services, and a `LapsingBlockHashSigner` that lets tests simulate a stuck signer.

See `junit/hedera/embedded/` for the current contents; the sections below describe the classes
that are routinely extended or observed from tests.

## `EmbeddedMode`

The enum is the externally-visible knob:

```java
public enum EmbeddedMode {
    CONCURRENT,  // multiple specs in parallel, real-time clock, ECDSA allowed
    REPEATABLE,  // single-threaded, virtual time, Ed25519 only
}
```

The Gradle task picks the mode by setting `hapi.spec.embedded.mode`. The
`SharedNetworkLauncherSessionListener` constructs `EmbeddedNetwork.newSharedNetwork(mode)` from
that value.

## `EmbeddedHedera`

The interface in `EmbeddedHedera.java` is what `EmbeddedNetwork` calls to drive the node. It
exposes lifecycle (`start`, `restart(FakeState)`, `stop`), direct state access (`state()`),
synthetic-time control (`now()` / `tick(Duration)`), monotonic `nextValidStart()` for ingest, and
direct `submit(...)` / `send(...)` entry points that bypass gRPC. See `EmbeddedHedera.java` for
the current method set.

`AbstractEmbeddedHedera` owns the lifecycle, bootstraps `Hedera` against a fake
`FakeServicesRegistry` / `FakeServiceMigrator`, and wires in the fakes below.

## `ConcurrentEmbeddedHedera`

Used when `EmbeddedMode == CONCURRENT`. It maintains a `ConcurrentFakePlatform` and a real-time
clock; rounds are simulated with a 1ms duration. Multiple specs can submit transactions in
parallel because the platform processes them on an internal executor.

## `RepeatableEmbeddedHedera`

Used when `EmbeddedMode == REPEATABLE`. Differences from the concurrent variant:

- Time is a `FakeTime` anchored at `2024-06-24T12:05:41.487328Z`. Synthetic time only advances
  when a test calls `tick(...)` or submits a transaction (default round duration is one second).
- Handles ingest synchronously, so the test thread sees state changes immediately.
- Holds pending node submissions in an `ArrayDeque` rather than a concurrent queue.

These constraints make state and stream output byte-identical across runs as long as the test
sticks to Ed25519 keys (ECDSA signing is inherently nondeterministic).

## `AbstractFakePlatform`

Implements `com.swirlds.platform.system.Platform` without any real hashgraph machinery. Key
points:

- Roster, self-id, and `PlatformContext` are injected at construction.
- `roundNo` and `consensusOrder` are atomic counters; `lastRoundNo()` returns the current round.
- A `FakeNotificationEngine` is created and lets `EmbeddedHedera` `notifyListeners` of platform
  status changes (e.g., `ACTIVE`, `FREEZE_COMPLETE`).
- A single `TOY_SIGNATURE` constant satisfies signature requirements.

`ConcurrentFakePlatform` and `SynchronousFakePlatform` (defined inside the respective
`EmbeddedHedera` subclasses) extend `AbstractFakePlatform` to add round-handling behaviour.

## `FakePlatformContext`

Builds a `PlatformContext` from a minimal `Configuration` (just `MetricsConfig`, `CryptoConfig`,
`BasicConfig`, `VirtualMapConfig`, `MerkleDbConfig`, `TemporaryFileConfig`, `StateCommonConfig`,
`PathsConfig`). The `FILE_SYSTEM_MANAGER` constant is a `FileSystemManager` shared across embedded
networks.

## `FakeNotificationEngine`

Stub `NotificationEngine` that only supports `PlatformStatusChangeListener` registration. The
other methods throw `UnsupportedOperationException("Not used by Hedera")` — if a service ever
starts using them, an immediate failure points at this file.

## `FakeHintsService`

Wraps a real `HintsServiceImpl` but redirects "submit a TSS transaction" calls into an internal
queue (`pendingHintsSubmissions`). The test framework can then drain or assert on those
submissions instead of routing them through a real consensus round.

## `FakeHistoryService`

Same idea for the history (chain-of-trust) service: wraps `HistoryServiceImpl`, captures
submissions into a `pendingHintsSubmissions` queue (yes, the field is misnamed in source —
historical artifact).

## `LapsingBlockHashSigner`

Wraps a real `BlockHashSigner` (typically `TssBlockHashSigner` constructed with the fake hints /
history services). The "lapsing" behaviour: call `startIgnoringRequests()` to make the signer
silently drop ledger-signature requests. Used by tests that want to simulate a stuck or
unresponsive signer without altering the rest of the embedded stack.

## When to extend a fake vs. patch a test

- **Need a different platform behaviour** (e.g., reorder events, fail a round) → subclass
  `AbstractFakePlatform` and instantiate via a new `EmbeddedHedera` mode.
- **Need to assert TSS submissions** → drain the queues from `FakeHintsService` /
  `FakeHistoryService` directly.
- **Need a new notification type** → add the missing dispatch branch in `FakeNotificationEngine`
  rather than throwing.

## See also

- [`../README.md`](../README.md) § *Embedded networks* — when to pick embedded mode.
- [HAPITEST_ANNOTATIONS.md](HAPITEST_ANNOTATIONS.md) — `@EmbeddedHapiTest`, `@RepeatableHapiTest`.
- [RESTART_TESTING.md](RESTART_TESTING.md) — uses `EmbeddedHedera.restart(FakeState)`.
- [BLOCK_STREAM_TRANSLATORS.md](BLOCK_STREAM_TRANSLATORS.md) — consumes the block stream produced
  by embedded handling.
