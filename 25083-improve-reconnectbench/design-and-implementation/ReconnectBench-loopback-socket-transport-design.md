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
2. Configure and bind it with `SocketFactory.configureAndBind(...)`.
3. Create an unconnected client `Socket`.
4. Configure and connect it with `SocketFactory.configureAndConnect(...)`.
5. Accept the server side.
6. Wrap both directions in `BufferedInputStream` / `BufferedOutputStream` and
   `DataInputStream` / `DataOutputStream`, matching the stream shape expected by the reconnect synchronizers.

The benchmark should use the real `SocketConfig` from benchmark configuration for socket options and stream buffer
sizes. The server bind should use a benchmark-local all-interfaces ephemeral port, then the client should connect via
`127.0.0.1`. This avoids unrelated `gossip.interfaceBindings` settings accidentally redirecting a local validation run.

After socket configuration, the benchmark should log effective socket buffer diagnostics, including:

- server socket receive buffer size;
- client socket send and receive buffer sizes;
- accepted socket send and receive buffer sizes;
- `tcpNoDelay` state where available.

These diagnostics are central to comparing local `SocketFactory.java` experiments.

## Socket Shaping

Socket shaping is controlled by `NetworkProfile`, not by extra booleans.

For `LOOPBACK_SOCKET + LOOPBACK`, no shaping wrappers are applied. The benchmark measures the natural loopback/socket
floor with sockets configured by `SocketFactory`.

For `LOOPBACK_SOCKET + REALISTIC`, benchmark-only stream wrappers apply:

- one-way latency from `networkLatencyMicroseconds`;
- bandwidth pacing from `networkBandwidthMegabitsPerSecond`.

The wrappers should sit below the reconnect-facing buffered/data streams so reconnect code still sees the same stream
types. Shaping should avoid sleeping once per reconnect message; it should work at byte-buffer or byte-range level so
the benchmark measures transport behavior rather than scheduler noise from per-message sleeps.

No socket shaping wrapper should impose `networkInflightBytesLimit`.

## Stats

`SimulatedNetworkStats` remains the shared per-direction stats record.

For simulated transport, all existing counters keep their current meanings.

For socket transport:

- `bytesWritten` and `bytesRead` report counted socket-stream bytes per direction;
- simulator-specific counters such as max in-flight bytes, write ranges, capacity waits, empty-read waits, and arrival
  waits are `0` or otherwise documented as not applicable;
- logs identify which stats are socket observations and which fields are simulator-only.

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

## Tests

Unit tests should target the benchmark transport layer:

- `LOOPBACK_SOCKET + LOOPBACK` round-trips framed bytes and reports bytes written/read.
- `disconnect()` wakes a blocked socket reader.
- socket mode exposes effective buffer diagnostics after `SocketFactory.configure*`; assertions should tolerate OS
  clamping.
- `LOOPBACK_SOCKET + REALISTIC` bandwidth shaping makes a sufficiently large transfer slower than `LOOPBACK`.
- `LOOPBACK_SOCKET + REALISTIC` latency shaping delays first-byte visibility compared with `LOOPBACK`.
- existing `SimulatedNetworkChannelTest` coverage remains valid for simulator behavior.

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
