# ReconnectBench Loopback Socket Transport Design

Date: `2026-07-03`

## Status

Approved brainstorming design for reviving follow-up item
`3. Loopback TCP transport validation` from
`25083-improve-reconnectbench/future-work/future-follow-ups.md`.

This design is benchmark-only. It must not change production/runtime consensus-node behavior. Production gossip socket
code may be read and reused by the benchmark, but the implementation scope is limited to
`platform-sdk/swirlds-benchmarks/**` and task-local documentation.

## Goal

`ReconnectBench` should support two transport implementations side by side:

- the current in-memory simulated network, for calibrated latency, bandwidth, and in-flight backpressure studies;
- a plain loopback TCP socket transport, for testing whether real socket configuration changes affect reconnect wall
  clock time.

The specific motivating case is local changes in
`platform-sdk/consensus-gossip-impl/src/main/java/org/hiero/consensus/gossip/impl/network/connectivity/SocketFactory.java`,
such as send/receive buffer sizing. A loopback socket run must therefore configure sockets through
`SocketFactory.configureAndBind(...)` and `SocketFactory.configureAndConnect(...)`, not by raw `new ServerSocket(...)`
and `new Socket(...)` setup alone.

## Non-Goals

- No TLS transport in this iteration. The purpose is socket configuration and buffering, not encryption or handshake
  overhead.
- No benchmark-level in-flight cap for socket transport. Real TCP already has kernel send buffers, receive buffers, and
  advertised windows.
- No production code changes.
- No full benchmark execution requirement for unit tests.
- No replacement of the current simulated network model.
- No cleanup pass to make this a general-purpose transport framework.

## Lean MVP Guardrails

This work should stay small and practical. The benchmark needs to run the correct transport, shape it correctly when
`NetworkProfile.REALISTIC` is selected, and log enough evidence to trust the run. It does not need an elegant final
abstraction yet.

Implementation should prefer:

- one straightforward bidirectional loopback socket pair;
- minimal package-local helpers where they keep `PairedStreams` readable;
- coarse, stable tests that prove behavior without depending on exact scheduler timing;
- diagnostics focused on socket buffer sizes, shaping active/inactive state, and byte counts.

Defer:

- extracting a public transport interface;
- rich socket telemetry or JFR integration;
- precise TCP/window modeling above the socket;
- broad refactors of benchmark lifecycle or reconnect utility code.

## Design Choice

Use the simpler `PairedStreams`-centric approach for this iteration.

`PairedStreams` remains the single object used by `MerkleBenchmarkUtils` to provide:

- teacher input/output streams;
- learner input/output streams;
- per-direction network stats;
- disconnect and close behavior.

`PairedStreams` will internally choose between the current simulated channels and the new socket transport. This keeps
the code shape easy to compare with earlier `ReconnectBench` code and with the prior local `loopback a/b test` commit.
A later cleanup may extract a transport interface if the mixed implementation becomes too busy.

`PairedStreams` is the benchmark-facing object, but the socket implementation may use package-local helpers under
`com.swirlds.benchmark.reconnect.network` so the socket behavior is unit-testable from `src/test`. Keep helpers narrow:
they exist to avoid a bloated `PairedStreams`, not to create a polished transport subsystem. This does not add a public
transport abstraction to the benchmark flow: `MerkleBenchmarkUtils` should still deal with `PairedStreams`.

## Parameters

Add a `NetworkTransport` enum in the benchmark network package:

```java
public enum NetworkTransport {
    SIMULATED,
    LOOPBACK_SOCKET
}
```

Keep the existing `NetworkProfile` enum:

```java
public enum NetworkProfile {
    LOOPBACK,
    REALISTIC
}
```

`ReconnectBench` gains:

```java
@Param({"SIMULATED"})
public NetworkTransport networkTransport;
```

Existing network params stay:

```text
networkProfile
networkLatencyMicroseconds
networkBandwidthMegabitsPerSecond
networkInflightBytesLimit
```

The selected transport chooses the wire implementation. The selected profile chooses whether the transport runs at its
loopback floor or applies the realistic shaping settings.

## API Propagation

`ReconnectBench.reconnect()` should pass the selected transport through the existing reconnect utility path:

```java
MerkleBenchmarkUtils.hashAndTestSynchronization(
        learnerMap,
        teacherMap,
        networkConfig,
        networkTransport,
        configuration);
```

`MerkleBenchmarkUtils` should pass the same values into `PairedStreams`:

```java
try (PairedStreams streams = new PairedStreams(networkTransport, networkConfig, configuration)) {
    ...
}
```

`PairedStreams` should keep the existing reconnect-facing accessors. It may add a diagnostics accessor for socket mode,
but reconnect code should continue using only:

```text
getTeacherInput()
getTeacherOutput()
getLearnerInput()
getLearnerOutput()
disconnect()
close()
```

The benchmark configuration loader must register the socket/gossip config records used by the socket path:

```java
.withConfigDataType(SocketConfig.class)
.withConfigDataType(GossipConfig.class)
```

This lets `socket.*` settings in `settings.txt` affect the loopback socket transport.

## Behavior Matrix

```text
SIMULATED + LOOPBACK
  Current simulator loopback behavior.
  No latency.
  Unlimited bandwidth.
  No in-flight cap.

SIMULATED + REALISTIC
  Current simulator realistic behavior.
  Uses networkLatencyMicroseconds.
  Uses networkBandwidthMegabitsPerSecond.
  Uses networkInflightBytesLimit.

LOOPBACK_SOCKET + LOOPBACK
  Plain loopback TCP sockets configured through SocketFactory.configure*.
  No artificial latency.
  No artificial bandwidth shaping.
  Ignores networkInflightBytesLimit.

LOOPBACK_SOCKET + REALISTIC
  Plain loopback TCP sockets configured through SocketFactory.configure*.
  Applies artificial one-way latency from networkLatencyMicroseconds.
  Applies artificial bandwidth shaping from networkBandwidthMegabitsPerSecond.
  Ignores networkInflightBytesLimit.
```

`networkInflightBytesLimit` applies only to `SIMULATED + REALISTIC`. Socket runs must log that the in-flight cap is
ignored for `LOOPBACK_SOCKET`, so benchmark output cannot be mistaken for simulator-style backpressure.

## Socket Transport Mechanics

For `LOOPBACK_SOCKET`, `PairedStreams` creates one bidirectional loopback TCP connection:

1. Create an unbound `ServerSocket`.
2. Configure and bind it with `SocketFactory.configureAndBind(...)`, using:
   - synthetic benchmark node ID `NodeId.of(0)`;
   - the real `SocketConfig` from benchmark configuration;
   - a benchmark-local empty `GossipConfig` with no interface bindings or endpoint overrides;
   - port `0`.
3. Create an unconnected client `Socket`.
4. Configure and connect it with `SocketFactory.configureAndConnect(...)`, using host `127.0.0.1` and
   `serverSocket.getLocalPort()`.
5. Accept the server side.
6. Configure the accepted server-side socket for inbound behavior equivalent to production inbound handling:
   `tcpNoDelay` and socket timeout from `SocketConfig`, but do not set accepted-socket send/receive buffers directly.
7. Wrap both directions in `BufferedInputStream` / `BufferedOutputStream` and
   `DataInputStream` / `DataOutputStream`, matching the stream shape expected by the reconnect synchronizers.

The benchmark should use the real `SocketConfig` from benchmark configuration for socket options and stream buffer
sizes. The benchmark-local empty `GossipConfig` avoids unrelated `gossip.interfaceBindings` settings accidentally
redirecting a local validation run, while still exercising the real `SocketFactory.configureAndBind(...)` code path.

After socket configuration, the benchmark should log effective socket buffer diagnostics, including:

- server socket receive buffer size;
- client socket send and receive buffer sizes;
- accepted socket send and receive buffer sizes;
- `tcpNoDelay` state where available.

These diagnostics are central to comparing local `SocketFactory.java` experiments.

## Source Set And Module Placement

Place `NetworkTransport`, socket helper classes, shaping wrappers, counting streams, and socket diagnostics in
`platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network`. This keeps them available to
unit tests and to the JMH source set, matching the existing simulator support layout.

`PairedStreams` remains in `src/jmh/java/com/swirlds/benchmark/reconnect`.

The main benchmark module descriptor must require the modules used by the main-source socket helpers:

```java
requires com.swirlds.config.api;
requires org.hiero.consensus.gossip;
requires org.hiero.consensus.gossip.impl;
requires org.hiero.consensus.model;
```

The JMH module already has the relevant gossip dependencies; keep it aligned with the main source set.

## Socket Shaping

Socket shaping is controlled by `NetworkProfile`, not by extra booleans.

For `LOOPBACK_SOCKET + LOOPBACK`, no shaping wrappers are applied. The benchmark measures the natural loopback/socket
floor with sockets configured by `SocketFactory`.

For `LOOPBACK_SOCKET + REALISTIC`, benchmark-only stream wrappers apply:

- one-way latency from `networkLatencyMicroseconds`;
- bandwidth pacing from `networkBandwidthMegabitsPerSecond`.

The wrappers should be write-side wrappers below the reconnect-facing buffered/data streams and above the raw socket
output streams:

```text
DataOutputStream -> BufferedOutputStream -> socket shaping/counting OutputStream -> Socket.getOutputStream()
```

Do not add read-side pacing. Read-side pacing would let the kernel socket buffers absorb bytes earlier and could mask
the `SocketFactory` buffer behavior this transport is meant to validate.

Latency shaping should delay a written byte range before the first byte of that range enters the socket output stream.
Bandwidth shaping should pace bytes into the socket output stream at the configured rate. It may split large writes into
bounded chunks if needed for stable pacing, but it should avoid complex scheduling machinery. The reconnect-facing
`BufferedOutputStream` keeps this from sleeping once per reconnect message in the common path; shaping should operate on
the buffered writes it receives.

Byte counters should count bytes accepted by the transport wrapper for writing and bytes returned to the receiving
side. Socket diagnostics should separately identify whether shaping was active.

No socket shaping wrapper should impose `networkInflightBytesLimit`.

## Stats

`SimulatedNetworkStats` remains the shared per-direction stats record.

For simulated transport, all existing counters keep their current meanings.

For socket transport:

- `bytesWritten` and `bytesRead` report counted socket-stream bytes per direction;
- simulator-specific counters such as max in-flight bytes, write ranges, capacity waits, empty-read waits, and arrival
  waits are `0` or otherwise documented as not applicable;
- logs identify which stats are socket observations and which fields are simulator-only.

Socket transport should expose a small diagnostics snapshot for logging and tests. Keep this as small as possible while
making benchmark runs auditable. A record such as `SocketTransportDiagnostics` is sufficient, with fields such as:

```text
transport
profile
latencyShapingActive
bandwidthShapingActive
configuredLatencyNanos
configuredBandwidthBytesPerSecond
inflightBytesLimitIgnored
serverReceiveBufferBytes
clientSendBufferBytes
clientReceiveBufferBytes
acceptedSendBufferBytes
acceptedReceiveBufferBytes
clientTcpNoDelay
acceptedTcpNoDelay
```

The diagnostics should distinguish Java stream buffer sizing (`SocketConfig.bufferSize()`) from actual socket
send/receive buffer sizes. Tests may assert lower bounds or non-zero values, but must tolerate OS clamping and platform
rounding.

## Gradle Tasks

Keep or add these benchmark tasks:

```text
jmhReconnect
  Default transport remains SIMULATED.

jmhReconnectSimulated
  Explicit SIMULATED transport.

jmhReconnectLoopbackSocket
  Explicit LOOPBACK_SOCKET transport.
```

All tasks should pass through the existing network parameters. `jmhReconnectLoopbackSocket` can use
`networkProfile=LOOPBACK` by default for the raw socket baseline, while still allowing:

```bash
./gradlew :swirlds-benchmarks:jmhReconnectLoopbackSocket \
  -PnetworkProfile=REALISTIC \
  -PnetworkLatencyMicroseconds=500 \
  -PnetworkBandwidthMegabitsPerSecond=200
```

For side-by-side comparison, `jmhReconnect`, `jmhReconnectSimulated`, and `jmhReconnectLoopbackSocket` should share the
same reconnect benchmark parameter wiring for state size, divergence probabilities, seed, thread count, heap/JVM args,
and result path unless a task deliberately overrides only `networkTransport` or the default `networkProfile`.

## Tests

Unit tests should target the benchmark transport layer:

- `LOOPBACK_SOCKET + LOOPBACK` round-trips framed bytes and reports bytes written/read.
- `disconnect()` wakes a blocked socket reader.
- socket mode exposes effective buffer diagnostics after `SocketFactory.configure*`; assertions should tolerate OS
  clamping.
- `LOOPBACK_SOCKET + REALISTIC` bandwidth shaping makes a sufficiently large transfer slower than `LOOPBACK`.
- `LOOPBACK_SOCKET + REALISTIC` latency shaping delays first-byte visibility compared with `LOOPBACK`.
- existing `SimulatedNetworkChannelTest` coverage remains valid for simulator behavior.

Tests may target package-local socket helpers rather than `PairedStreams` directly if source-set boundaries make that
cleaner. The benchmark-facing path should still route through `PairedStreams`.

Timing tests should use generous thresholds and small synthetic transfers. They should prove the shaping path is active
and directionally correct, not assert exact wall-clock durations.

Verification should include:

```bash
./gradlew :swirlds-benchmarks:test --tests com.swirlds.benchmark.reconnect.network.SimulatedNetworkChannelTest --console=plain
./gradlew :swirlds-benchmarks:test --tests com.swirlds.benchmark.reconnect.network.LoopbackSocketTransportTest --console=plain
./gradlew :swirlds-benchmarks:compileJmhJava --console=plain
```

Gradle commands require sandbox escalation in this workspace.

## Documentation Updates

After implementation:

- update `25083-improve-reconnectbench/future-work/future-follow-ups.md` so item
  `3. Loopback TCP transport validation` reflects the implemented validation option;
- document the `NetworkTransport` by `NetworkProfile` matrix in nearby benchmark docs or run notes;
- explicitly state that `networkInflightBytesLimit` is simulator-only.

## Branch Strategy

Implementation work should continue on a normal branch created from `25083-improve-reconnectbench-synced`, not a
worktree. The branch created for this design is `codex/25083-loopback-socket-transport`.
