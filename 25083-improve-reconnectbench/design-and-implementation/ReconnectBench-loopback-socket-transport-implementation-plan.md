# ReconnectBench Loopback Socket Transport Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a benchmark-only `LOOPBACK_SOCKET` transport to `ReconnectBench` beside the existing simulated network transport, with `NetworkProfile` selecting raw loopback vs realistic latency/bandwidth shaping.

**Architecture:** Keep `PairedStreams` as the benchmark-facing stream provider. Add narrow main-source socket helpers in `com.swirlds.benchmark.reconnect.network` so unit tests can validate loopback behavior, shaping, byte counts, and diagnostics. Reuse `SocketFactory.configureAndBind` and `SocketFactory.configureAndConnect` via benchmark-side module export wiring; do not edit production gossip modules.

**Tech Stack:** Java 25, Gradle wrapper, JMH, JUnit 5, `java.net.ServerSocket`/`Socket`, benchmark-local stream wrappers.

## Global Constraints

- Java 25 is required; use `./gradlew`, not manual Gradle installation.
- Do not modify production/runtime consensus-node behavior.
- Production classes may be read and reused, but not changed.
- Allowed implementation edits are under `platform-sdk/swirlds-benchmarks/**`; docs edits are under `25083-improve-reconnectbench/**`.
- `LOOPBACK_SOCKET` must call `SocketFactory.configureAndBind` and `SocketFactory.configureAndConnect`.
- `NetworkTransport` selects `SIMULATED` vs `LOOPBACK_SOCKET`.
- Existing `NetworkProfile` selects `LOOPBACK` vs `REALISTIC` for both transports.
- `networkInflightBytesLimit` applies only to `SIMULATED + REALISTIC`; socket transport logs/diagnoses it as ignored.
- Socket shaping is write-side only; do not add read-side pacing.
- Keep this a lean MVP; do not extract a public transport interface or broad lifecycle refactor.
- Gradle commands require sandbox escalation in this workspace.

---

## File Structure

Create:

- `platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/NetworkTransport.java`  
  Enum for `SIMULATED` vs `LOOPBACK_SOCKET`.
- `platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/CountingInputStream.java`  
  Package-private byte-counting input wrapper for socket stats.
- `platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/CountingOutputStream.java`  
  Package-private byte-counting output wrapper for socket stats.
- `platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/ShapingOutputStream.java`  
  Package-private write-side latency/bandwidth shaping wrapper.
- `platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/SocketTransportDiagnostics.java`  
  Public record for socket buffer/shaping diagnostics.
- `platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/LoopbackSocketTransport.java`  
  Public final narrow helper used by `PairedStreams` and tests.
- `platform-sdk/swirlds-benchmarks/src/test/java/com/swirlds/benchmark/reconnect/network/LoopbackSocketTransportTest.java`  
  Unit tests for loopback socket helper behavior.

Modify:

- `platform-sdk/swirlds-benchmarks/build.gradle.kts`  
  Add benchmark-side `--add-exports`, reconnect task wiring, and shared JMH params.
- `platform-sdk/swirlds-benchmarks/src/main/java/module-info.java`  
  Add requires for config/gossip/model modules used by main-source socket helpers.
- `platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/BaseBench.java`  
  Register `SocketConfig` and `GossipConfig`.
- `platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/ReconnectBench.java`  
  Add `networkTransport`, logging, and call propagation.
- `platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/reconnect/MerkleBenchmarkUtils.java`  
  Accept/pass `NetworkTransport`; log socket diagnostics.
- `platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/reconnect/PairedStreams.java`  
  Switch internally between simulated channels and loopback socket helper.
- `25083-improve-reconnectbench/future-work/future-follow-ups.md`  
  Mark loopback TCP validation as revived/implemented after code lands.

---

### Task 1: Add Transport Selection Plumbing While Preserving Simulated Behavior

**Files:**
- Create: `platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/NetworkTransport.java`
- Modify: `platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/ReconnectBench.java`
- Modify: `platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/reconnect/MerkleBenchmarkUtils.java`
- Modify: `platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/reconnect/PairedStreams.java`

**Interfaces:**
- Produces: `enum NetworkTransport { SIMULATED, LOOPBACK_SOCKET }`
- Produces: `PairedStreams(NetworkTransport transport, NetworkSimulationConfig networkConfig, Configuration configuration)`
- Produces: `MerkleBenchmarkUtils.hashAndTestSynchronization(VirtualMap, VirtualMap, NetworkSimulationConfig, NetworkTransport, Configuration)`
- Consumes: existing simulated `PairedStreams` stream accessors and stats.

- [ ] **Step 1: Create transport enum**

Create `platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/NetworkTransport.java`:

```java
// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

/**
 * Selects the byte transport used by ReconnectBench.
 */
public enum NetworkTransport {
    /** Existing in-memory network model. */
    SIMULATED,

    /** Plain loopback TCP sockets configured through the gossip socket helper. */
    LOOPBACK_SOCKET
}
```

- [ ] **Step 2: Add the JMH parameter**

Modify `ReconnectBench.java` imports:

```java
import com.swirlds.benchmark.reconnect.network.NetworkTransport;
```

Add the parameter after `networkInflightBytesLimit`:

```java
    @Param({"SIMULATED"})
    public NetworkTransport networkTransport;
```

Update the run header log:

```java
        logger.info(
                "ReconnectBench transport={}, network profile={}, latencyNanos={}, bandwidthBytesPerSecond={}, inflightBytesLimit={}",
                networkTransport,
                networkConfig.profile(),
                networkConfig.latencyNanos(),
                networkConfig.bandwidthBytesPerSecond(),
                networkConfig.inflightBytesLimit());
```

Update the benchmark call:

```java
        reconnectResult = MerkleBenchmarkUtils.hashAndTestSynchronization(
                learnerMap, teacherMap, networkConfig, networkTransport, configuration);
```

- [ ] **Step 3: Propagate the parameter through MerkleBenchmarkUtils**

Modify `MerkleBenchmarkUtils.java` imports:

```java
import com.swirlds.benchmark.reconnect.network.NetworkTransport;
```

Change the public method signature:

```java
    public static ReconnectBenchmarkResult hashAndTestSynchronization(
            final VirtualMap startingTree,
            final VirtualMap desiredTree,
            final NetworkSimulationConfig networkConfig,
            final NetworkTransport transport,
            final Configuration configuration)
            throws Exception {
```

Change the return call:

```java
        return testSynchronization(startingTree, desiredTree, networkConfig, transport, configuration);
```

Change the private method signature:

```java
    private static ReconnectBenchmarkResult testSynchronization(
            final VirtualMap startingTree,
            final VirtualMap desiredTree,
            final NetworkSimulationConfig networkConfig,
            final NetworkTransport transport,
            final Configuration configuration)
            throws Exception {
```

Change the stream creation:

```java
        try (PairedStreams streams = new PairedStreams(transport, networkConfig, configuration)) {
```

- [ ] **Step 4: Add a transport-aware PairedStreams constructor that only supports SIMULATED initially**

Modify `PairedStreams.java` imports:

```java
import com.swirlds.benchmark.reconnect.network.NetworkTransport;
import com.swirlds.config.api.Configuration;
import java.util.Objects;
```

Replace the constructor with:

```java
    public PairedStreams(
            @NonNull final NetworkTransport transport,
            @NonNull final NetworkSimulationConfig networkConfig,
            @NonNull final Configuration configuration)
            throws IOException {
        Objects.requireNonNull(transport, "transport must not be null");
        Objects.requireNonNull(networkConfig, "networkConfig must not be null");
        Objects.requireNonNull(configuration, "configuration must not be null");

        if (transport != NetworkTransport.SIMULATED) {
            throw new UnsupportedOperationException("Transport is not implemented yet: " + transport);
        }

        teacherToLearner = new SimulatedNetworkChannel(networkConfig);
        learnerToTeacher = new SimulatedNetworkChannel(networkConfig);

        teacherOutputBuffer = new BufferedOutputStream(teacherToLearner.outputStream());
        teacherOutput = new DataOutputStream(teacherOutputBuffer);

        teacherInputBuffer = new BufferedInputStream(learnerToTeacher.inputStream());
        teacherInput = new DataInputStream(teacherInputBuffer);

        learnerOutputBuffer = new BufferedOutputStream(learnerToTeacher.outputStream());
        learnerOutput = new DataOutputStream(learnerOutputBuffer);

        learnerInputBuffer = new BufferedInputStream(teacherToLearner.inputStream());
        learnerInput = new DataInputStream(learnerInputBuffer);
    }
```

- [ ] **Step 5: Compile JMH to verify simulated behavior still wires**

Run:

```bash
./gradlew :swirlds-benchmarks:compileJmhJava --console=plain
```

Expected: `BUILD SUCCESSFUL`. If it fails because `networkTransport` is still missing from generated JMH code, run the same command again after confirming the source change was saved; JMH annotation generation should refresh.

- [ ] **Step 6: Commit Task 1**

```bash
git add \
  platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/NetworkTransport.java \
  platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/ReconnectBench.java \
  platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/reconnect/MerkleBenchmarkUtils.java \
  platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/reconnect/PairedStreams.java
git commit -m "feat: add reconnect network transport selection"
```

---

### Task 2: Add Socket Module Access And Unshaped Loopback Helper

**Files:**
- Modify: `platform-sdk/swirlds-benchmarks/build.gradle.kts`
- Modify: `platform-sdk/swirlds-benchmarks/src/main/java/module-info.java`
- Modify: `platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/BaseBench.java`
- Create: `platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/CountingInputStream.java`
- Create: `platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/CountingOutputStream.java`
- Create: `platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/SocketTransportDiagnostics.java`
- Create: `platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/LoopbackSocketTransport.java`
- Test: `platform-sdk/swirlds-benchmarks/src/test/java/com/swirlds/benchmark/reconnect/network/LoopbackSocketTransportTest.java`

**Interfaces:**
- Consumes: `NetworkTransport`, `NetworkSimulationConfig`, `NetworkProfile`.
- Produces: `LoopbackSocketTransport(NetworkSimulationConfig config, Configuration configuration)`.
- Produces: `DataOutputStream getTeacherOutput()`, `DataInputStream getTeacherInput()`, `DataOutputStream getLearnerOutput()`, `DataInputStream getLearnerInput()`.
- Produces: `SimulatedNetworkStats getTeacherToLearnerStats()`, `SimulatedNetworkStats getLearnerToTeacherStats()`.
- Produces: `SocketTransportDiagnostics diagnostics()`.

- [ ] **Step 1: Add the failing loopback round-trip and diagnostics tests**

Create or replace `platform-sdk/swirlds-benchmarks/src/test/java/com/swirlds/benchmark/reconnect/network/LoopbackSocketTransportTest.java` with tests for the helper:

```java
// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.sources.SimpleConfigSource;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.consensus.gossip.config.GossipConfig;
import org.hiero.consensus.gossip.config.SocketConfig;
import org.junit.jupiter.api.Test;

class LoopbackSocketTransportTest {

    private static Configuration configuration() {
        return ConfigurationBuilder.create()
                .withSource(new SimpleConfigSource("socket.tcpNoDelay", "true"))
                .withConfigDataType(SocketConfig.class)
                .withConfigDataType(GossipConfig.class)
                .build();
    }

    private static NetworkSimulationConfig loopbackConfig() {
        return NetworkSimulationConfig.resolve(NetworkProfile.LOOPBACK, 0, 1, 1);
    }

    @Test
    void loopbackRoundTripsFramedBytesAndCountsThem() throws Exception {
        try (LoopbackSocketTransport transport = new LoopbackSocketTransport(loopbackConfig(), configuration())) {
            final DataOutputStream out = transport.getTeacherOutput();
            out.writeInt(4);
            out.write(new byte[] {1, 2, 3, 4});
            out.flush();

            final DataInputStream in = transport.getLearnerInput();
            assertEquals(4, in.readInt());
            final byte[] data = new byte[4];
            in.readFully(data);

            assertArrayEquals(new byte[] {1, 2, 3, 4}, data);
            assertEquals(8, transport.getTeacherToLearnerStats().bytesWritten());
            assertEquals(8, transport.getTeacherToLearnerStats().bytesRead());
        }
    }

    @Test
    void diagnosticsExposeEffectiveSocketSettings() throws Exception {
        try (LoopbackSocketTransport transport = new LoopbackSocketTransport(loopbackConfig(), configuration())) {
            final SocketTransportDiagnostics diagnostics = transport.diagnostics();

            assertEquals(NetworkTransport.LOOPBACK_SOCKET, diagnostics.transport());
            assertEquals(NetworkProfile.LOOPBACK, diagnostics.profile());
            assertFalse(diagnostics.latencyShapingActive());
            assertFalse(diagnostics.bandwidthShapingActive());
            assertTrue(diagnostics.inflightBytesLimitIgnored());
            assertTrue(diagnostics.serverReceiveBufferBytes() > 0);
            assertTrue(diagnostics.clientSendBufferBytes() > 0);
            assertTrue(diagnostics.clientReceiveBufferBytes() > 0);
            assertTrue(diagnostics.acceptedSendBufferBytes() > 0);
            assertTrue(diagnostics.acceptedReceiveBufferBytes() > 0);
            assertTrue(diagnostics.clientTcpNoDelay());
            assertTrue(diagnostics.acceptedTcpNoDelay());
        }
    }

    @Test
    void disconnectWakesBlockedReader() throws Exception {
        final LoopbackSocketTransport transport = new LoopbackSocketTransport(loopbackConfig(), configuration());
        try {
            final AtomicReference<Throwable> thrown = new AtomicReference<>();
            final CountDownLatch entered = new CountDownLatch(1);
            final Thread reader = new Thread(() -> {
                entered.countDown();
                try {
                    transport.getTeacherInput().read();
                } catch (final Throwable t) {
                    thrown.set(t);
                }
            });
            reader.setDaemon(true);
            reader.start();

            assertTrue(entered.await(5, TimeUnit.SECONDS), "reader thread should start");
            Thread.sleep(200);
            transport.disconnect();
            reader.join(5_000);

            assertFalse(reader.isAlive(), "disconnect should unblock the reader");
            assertTrue(thrown.get() instanceof IOException, "reader should fail with an IOException");
        } finally {
            transport.close();
        }
    }
}
```

- [ ] **Step 2: Run the new test to verify it fails**

Run:

```bash
./gradlew :swirlds-benchmarks:test --tests com.swirlds.benchmark.reconnect.network.LoopbackSocketTransportTest --console=plain
```

Expected: FAIL because `LoopbackSocketTransport` and `SocketTransportDiagnostics` do not exist.

- [ ] **Step 3: Add benchmark-side module export wiring**

Modify `platform-sdk/swirlds-benchmarks/build.gradle.kts` near the top:

```kotlin
val gossipConnectivityExport =
    "--add-exports=org.hiero.consensus.gossip.impl/org.hiero.consensus.gossip.impl.network.connectivity=com.swirlds.benchmarks,ALL-UNNAMED"

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-static")
    options.compilerArgs.add(gossipConnectivityExport)
}

tasks.withType<Test>().configureEach {
    jvmArgs(gossipConnectivityExport)
}
```

Replace the existing one-line `tasks.withType<JavaCompile>()` block instead of adding a duplicate one.

- [ ] **Step 4: Add main module dependencies**

Modify `platform-sdk/swirlds-benchmarks/src/main/java/module-info.java`:

```java
// SPDX-License-Identifier: Apache-2.0
module com.swirlds.benchmarks {
    exports com.swirlds.benchmark.reconnect.network;

    requires com.swirlds.config.api;
    requires com.swirlds.metrics.api;
    requires org.hiero.consensus.gossip;
    requires org.hiero.consensus.gossip.impl;
    requires org.hiero.consensus.model;
}
```

- [ ] **Step 5: Register socket/gossip config in the benchmark loader**

Modify `BaseBench.java` imports:

```java
import org.hiero.consensus.gossip.config.GossipConfig;
import org.hiero.consensus.gossip.config.SocketConfig;
```

Modify `loadConfig()` so the builder includes:

```java
                .withConfigDataType(MetricsConfig.class)
                .withConfigDataType(CryptoConfig.class)
                .withConfigDataType(SocketConfig.class)
                .withConfigDataType(GossipConfig.class);
```

- [ ] **Step 6: Add counting streams**

Create `CountingInputStream.java`:

```java
// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

final class CountingInputStream extends FilterInputStream {

    private volatile long count;

    CountingInputStream(final InputStream in) {
        super(in);
    }

    @Override
    public int read() throws IOException {
        final int b = in.read();
        if (b >= 0) {
            count++;
        }
        return b;
    }

    @Override
    public int read(final byte[] b, final int off, final int len) throws IOException {
        final int n = in.read(b, off, len);
        if (n > 0) {
            count += n;
        }
        return n;
    }

    long count() {
        return count;
    }
}
```

Create `CountingOutputStream.java`:

```java
// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

final class CountingOutputStream extends FilterOutputStream {

    private volatile long count;

    CountingOutputStream(final OutputStream out) {
        super(out);
    }

    @Override
    public void write(final int b) throws IOException {
        out.write(b);
        count++;
    }

    @Override
    public void write(final byte[] b, final int off, final int len) throws IOException {
        out.write(b, off, len);
        count += len;
    }

    long count() {
        return count;
    }
}
```

- [ ] **Step 7: Add diagnostics record**

Create `SocketTransportDiagnostics.java`:

```java
// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

public record SocketTransportDiagnostics(
        NetworkTransport transport,
        NetworkProfile profile,
        boolean latencyShapingActive,
        boolean bandwidthShapingActive,
        long configuredLatencyNanos,
        long configuredBandwidthBytesPerSecond,
        boolean inflightBytesLimitIgnored,
        int serverReceiveBufferBytes,
        int clientSendBufferBytes,
        int clientReceiveBufferBytes,
        int acceptedSendBufferBytes,
        int acceptedReceiveBufferBytes,
        boolean clientTcpNoDelay,
        boolean acceptedTcpNoDelay) {}
```

- [ ] **Step 8: Add unshaped LoopbackSocketTransport**

Create `LoopbackSocketTransport.java`:

```java
// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.hiero.consensus.gossip.config.GossipConfig;
import org.hiero.consensus.gossip.config.SocketConfig;
import org.hiero.consensus.gossip.impl.network.connectivity.SocketFactory;
import org.hiero.consensus.model.node.NodeId;

public final class LoopbackSocketTransport implements AutoCloseable {

    private static final NodeId BENCHMARK_NODE_ID = NodeId.of(0);
    private static final String LOOPBACK_HOST = "127.0.0.1";

    private final ServerSocket serverSocket;
    private final Socket teacherSocket;
    private final Socket learnerSocket;

    private final CountingOutputStream teacherToLearnerWritten;
    private final CountingInputStream teacherToLearnerRead;
    private final CountingOutputStream learnerToTeacherWritten;
    private final CountingInputStream learnerToTeacherRead;

    private final DataOutputStream teacherOutput;
    private final DataInputStream teacherInput;
    private final DataOutputStream learnerOutput;
    private final DataInputStream learnerInput;
    private final SocketTransportDiagnostics diagnostics;

    public LoopbackSocketTransport(
            @NonNull final NetworkSimulationConfig config, @NonNull final Configuration configuration)
            throws IOException {
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(configuration, "configuration must not be null");

        final SocketConfig socketConfig = configuration.getConfigData(SocketConfig.class);
        serverSocket = new ServerSocket();
        SocketFactory.configureAndBind(
                BENCHMARK_NODE_ID, serverSocket, socketConfig, emptyGossipConfig(), 0);

        teacherSocket = new Socket();
        SocketFactory.configureAndConnect(
                teacherSocket, socketConfig, LOOPBACK_HOST, serverSocket.getLocalPort());

        learnerSocket = serverSocket.accept();
        learnerSocket.setTcpNoDelay(socketConfig.tcpNoDelay());
        learnerSocket.setSoTimeout(socketConfig.timeoutSyncClientSocket());

        teacherToLearnerWritten = new CountingOutputStream(teacherSocket.getOutputStream());
        teacherOutput = new DataOutputStream(
                new BufferedOutputStream(teacherToLearnerWritten, socketConfig.bufferSize()));
        teacherToLearnerRead = new CountingInputStream(learnerSocket.getInputStream());
        learnerInput = new DataInputStream(
                new BufferedInputStream(teacherToLearnerRead, socketConfig.bufferSize()));

        learnerToTeacherWritten = new CountingOutputStream(learnerSocket.getOutputStream());
        learnerOutput = new DataOutputStream(
                new BufferedOutputStream(learnerToTeacherWritten, socketConfig.bufferSize()));
        learnerToTeacherRead = new CountingInputStream(teacherSocket.getInputStream());
        teacherInput = new DataInputStream(
                new BufferedInputStream(learnerToTeacherRead, socketConfig.bufferSize()));

        diagnostics = new SocketTransportDiagnostics(
                NetworkTransport.LOOPBACK_SOCKET,
                config.profile(),
                false,
                false,
                config.latencyNanos(),
                config.bandwidthBytesPerSecond(),
                true,
                serverSocket.getReceiveBufferSize(),
                teacherSocket.getSendBufferSize(),
                teacherSocket.getReceiveBufferSize(),
                learnerSocket.getSendBufferSize(),
                learnerSocket.getReceiveBufferSize(),
                teacherSocket.getTcpNoDelay(),
                learnerSocket.getTcpNoDelay());
    }

    public DataOutputStream getTeacherOutput() {
        return teacherOutput;
    }

    public DataInputStream getTeacherInput() {
        return teacherInput;
    }

    public DataOutputStream getLearnerOutput() {
        return learnerOutput;
    }

    public DataInputStream getLearnerInput() {
        return learnerInput;
    }

    public SimulatedNetworkStats getTeacherToLearnerStats() {
        return new SimulatedNetworkStats(
                teacherToLearnerWritten.count(), teacherToLearnerRead.count(), 0);
    }

    public SimulatedNetworkStats getLearnerToTeacherStats() {
        return new SimulatedNetworkStats(
                learnerToTeacherWritten.count(), learnerToTeacherRead.count(), 0);
    }

    public SocketTransportDiagnostics diagnostics() {
        return diagnostics;
    }

    public void disconnect() {
        closeQuietly(teacherSocket);
        closeQuietly(learnerSocket);
        closeQuietly(serverSocket);
    }

    @Override
    public void close() {
        closeQuietly(teacherOutput);
        closeQuietly(learnerOutput);
        closeQuietly(teacherInput);
        closeQuietly(learnerInput);
        closeQuietly(teacherSocket);
        closeQuietly(learnerSocket);
        closeQuietly(serverSocket);
    }

    private static GossipConfig emptyGossipConfig() {
        return new GossipConfig(List.of(), List.of(), 5, Duration.ofSeconds(60));
    }

    private static void closeQuietly(final Closeable closeable) {
        try {
            closeable.close();
        } catch (final IOException ignored) {
        }
    }
}
```

- [ ] **Step 9: Run loopback helper tests**

Run:

```bash
./gradlew :swirlds-benchmarks:test --tests com.swirlds.benchmark.reconnect.network.LoopbackSocketTransportTest --console=plain
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 10: Run simulator tests to guard current transport behavior**

Run:

```bash
./gradlew :swirlds-benchmarks:test --tests com.swirlds.benchmark.reconnect.network.SimulatedNetworkChannelTest --console=plain
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 11: Commit Task 2**

```bash
git add \
  platform-sdk/swirlds-benchmarks/build.gradle.kts \
  platform-sdk/swirlds-benchmarks/src/main/java/module-info.java \
  platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/BaseBench.java \
  platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/CountingInputStream.java \
  platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/CountingOutputStream.java \
  platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/SocketTransportDiagnostics.java \
  platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/LoopbackSocketTransport.java \
  platform-sdk/swirlds-benchmarks/src/test/java/com/swirlds/benchmark/reconnect/network/LoopbackSocketTransportTest.java
git commit -m "feat: add ReconnectBench loopback socket helper"
```

---

### Task 3: Route LOOPBACK_SOCKET Through PairedStreams

**Files:**
- Modify: `platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/reconnect/PairedStreams.java`
- Modify: `platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/reconnect/MerkleBenchmarkUtils.java`

**Interfaces:**
- Consumes: `LoopbackSocketTransport`.
- Produces: `PairedStreams.getSocketDiagnostics(): Optional<SocketTransportDiagnostics>`.

- [ ] **Step 1: Update PairedStreams fields and constructor**

Modify imports:

```java
import com.swirlds.benchmark.reconnect.network.LoopbackSocketTransport;
import com.swirlds.benchmark.reconnect.network.SocketTransportDiagnostics;
import java.util.Optional;
```

Change fields so simulator channels and socket transport are nullable-by-transport:

```java
    private final NetworkTransport transport;
    private final SimulatedNetworkChannel teacherToLearner;
    private final SimulatedNetworkChannel learnerToTeacher;
    private final LoopbackSocketTransport socketTransport;
```

Replace constructor body with:

```java
        this.transport = transport;

        if (transport == NetworkTransport.SIMULATED) {
            socketTransport = null;
            teacherToLearner = new SimulatedNetworkChannel(networkConfig);
            learnerToTeacher = new SimulatedNetworkChannel(networkConfig);

            teacherOutputBuffer = new BufferedOutputStream(teacherToLearner.outputStream());
            teacherOutput = new DataOutputStream(teacherOutputBuffer);

            teacherInputBuffer = new BufferedInputStream(learnerToTeacher.inputStream());
            teacherInput = new DataInputStream(teacherInputBuffer);

            learnerOutputBuffer = new BufferedOutputStream(learnerToTeacher.outputStream());
            learnerOutput = new DataOutputStream(learnerOutputBuffer);

            learnerInputBuffer = new BufferedInputStream(teacherToLearner.inputStream());
            learnerInput = new DataInputStream(learnerInputBuffer);
            return;
        }

        teacherToLearner = null;
        learnerToTeacher = null;
        socketTransport = new LoopbackSocketTransport(networkConfig, configuration);

        teacherOutput = socketTransport.getTeacherOutput();
        teacherInput = socketTransport.getTeacherInput();
        learnerOutput = socketTransport.getLearnerOutput();
        learnerInput = socketTransport.getLearnerInput();
```

- [ ] **Step 2: Update stats, diagnostics, disconnect, and close**

Replace `getTeacherToLearnerStats()`:

```java
    public SimulatedNetworkStats getTeacherToLearnerStats() {
        return switch (transport) {
            case SIMULATED -> teacherToLearner.snapshotStats();
            case LOOPBACK_SOCKET -> socketTransport.getTeacherToLearnerStats();
        };
    }
```

Replace `getLearnerToTeacherStats()`:

```java
    public SimulatedNetworkStats getLearnerToTeacherStats() {
        return switch (transport) {
            case SIMULATED -> learnerToTeacher.snapshotStats();
            case LOOPBACK_SOCKET -> socketTransport.getLearnerToTeacherStats();
        };
    }
```

Add diagnostics accessor:

```java
    public Optional<SocketTransportDiagnostics> getSocketDiagnostics() {
        return transport == NetworkTransport.LOOPBACK_SOCKET
                ? Optional.of(socketTransport.diagnostics())
                : Optional.empty();
    }
```

Update `close()` to branch:

```java
    @Override
    public void close() throws IOException {
        if (transport == NetworkTransport.LOOPBACK_SOCKET) {
            socketTransport.close();
            return;
        }

        final List<Closeable> toClose = List.of(
                teacherOutput,
                teacherInput,
                learnerOutput,
                learnerInput,
                teacherOutputBuffer,
                teacherInputBuffer,
                learnerOutputBuffer,
                learnerInputBuffer);
        for (final Closeable c : toClose) {
            try {
                c.close();
            } catch (final Exception e) {
                logger.error("Error while closing resources", e);
            }
        }
    }
```

Update `disconnect()`:

```java
    public void disconnect() {
        if (transport == NetworkTransport.LOOPBACK_SOCKET) {
            socketTransport.disconnect();
            return;
        }
        teacherToLearner.disconnect();
        learnerToTeacher.disconnect();
    }
```

- [ ] **Step 3: Log socket diagnostics**

In `MerkleBenchmarkUtils.testSynchronization`, immediately after stream creation, add:

```java
            streams.getSocketDiagnostics()
                    .ifPresent(diagnostics -> logger.info("Socket transport diagnostics: {}", diagnostics));
```

- [ ] **Step 4: Compile JMH to verify routing**

Run:

```bash
./gradlew :swirlds-benchmarks:compileJmhJava --console=plain
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Run loopback helper and simulator tests**

Run:

```bash
./gradlew :swirlds-benchmarks:test --tests com.swirlds.benchmark.reconnect.network.LoopbackSocketTransportTest --console=plain
./gradlew :swirlds-benchmarks:test --tests com.swirlds.benchmark.reconnect.network.SimulatedNetworkChannelTest --console=plain
```

Expected: both commands report `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit Task 3**

```bash
git add \
  platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/reconnect/PairedStreams.java \
  platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/reconnect/MerkleBenchmarkUtils.java
git commit -m "feat: route ReconnectBench through loopback socket transport"
```

---

### Task 4: Add REALISTIC Socket Latency And Bandwidth Shaping

**Files:**
- Create: `platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/ShapingOutputStream.java`
- Modify: `platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/LoopbackSocketTransport.java`
- Test: `platform-sdk/swirlds-benchmarks/src/test/java/com/swirlds/benchmark/reconnect/network/LoopbackSocketTransportTest.java`

**Interfaces:**
- Produces: write-side shaping below `BufferedOutputStream`.
- Consumes: `NetworkSimulationConfig.latencyNanos()` and `bandwidthBytesPerSecond()`.

- [ ] **Step 1: Add failing latency and bandwidth tests**

Append to `LoopbackSocketTransportTest.java`:

```java
    private static NetworkSimulationConfig realisticConfig(
            final long latencyMicroseconds, final long bandwidthMegabitsPerSecond) {
        return NetworkSimulationConfig.resolve(
                NetworkProfile.REALISTIC, latencyMicroseconds, bandwidthMegabitsPerSecond, 1);
    }

    @Test
    void realisticProfileDelaysFirstBytes() throws Exception {
        try (LoopbackSocketTransport transport =
                new LoopbackSocketTransport(realisticConfig(100_000, 1_000), configuration())) {
            final long start = System.nanoTime();
            transport.getTeacherOutput().writeInt(1234);
            transport.getTeacherOutput().flush();
            assertEquals(1234, transport.getLearnerInput().readInt());
            final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertTrue(elapsedMillis >= 70, "latency shaping should delay visible bytes");
            assertTrue(transport.diagnostics().latencyShapingActive());
        }
    }

    @Test
    void realisticProfilePacesLargeWrites() throws Exception {
        final byte[] payload = new byte[64 * 1024];
        try (LoopbackSocketTransport transport =
                new LoopbackSocketTransport(realisticConfig(0, 1), configuration())) {
            final long start = System.nanoTime();
            transport.getTeacherOutput().writeInt(payload.length);
            transport.getTeacherOutput().write(payload);
            transport.getTeacherOutput().flush();
            assertEquals(payload.length, transport.getLearnerInput().readInt());
            transport.getLearnerInput().readFully(new byte[payload.length]);
            final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertTrue(elapsedMillis >= 300, "bandwidth shaping should pace a 64 KiB transfer at 1 Mbps");
            assertTrue(transport.diagnostics().bandwidthShapingActive());
        }
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
./gradlew :swirlds-benchmarks:test --tests com.swirlds.benchmark.reconnect.network.LoopbackSocketTransportTest --console=plain
```

Expected: FAIL because shaping diagnostics remain false and transfers complete too quickly.

- [ ] **Step 3: Create ShapingOutputStream**

Create `ShapingOutputStream.java`:

```java
// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;

final class ShapingOutputStream extends FilterOutputStream {

    private static final int MAX_CHUNK_BYTES = 8192;

    private final long latencyNanos;
    private final long bandwidthBytesPerSecond;

    ShapingOutputStream(
            final OutputStream out, final long latencyNanos, final long bandwidthBytesPerSecond) {
        super(Objects.requireNonNull(out, "out must not be null"));
        if (latencyNanos < 0) {
            throw new IllegalArgumentException("latencyNanos must be non-negative");
        }
        if (bandwidthBytesPerSecond <= 0) {
            throw new IllegalArgumentException("bandwidthBytesPerSecond must be positive");
        }
        this.latencyNanos = latencyNanos;
        this.bandwidthBytesPerSecond = bandwidthBytesPerSecond;
    }

    @Override
    public void write(final int b) throws IOException {
        write(new byte[] {(byte) b}, 0, 1);
    }

    @Override
    public void write(final byte[] b, final int off, final int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        if (len == 0) {
            return;
        }

        park(latencyNanos);

        int offset = off;
        int remaining = len;
        while (remaining > 0) {
            final int chunkBytes = Math.min(remaining, MAX_CHUNK_BYTES);
            out.write(b, offset, chunkBytes);
            park(transmitDurationNanos(chunkBytes));
            offset += chunkBytes;
            remaining -= chunkBytes;
        }
    }

    private long transmitDurationNanos(final int byteCount) {
        if (bandwidthBytesPerSecond == Long.MAX_VALUE) {
            return 0;
        }
        return Math.max(1, (long) Math.ceil(byteCount * 1_000_000_000.0 / bandwidthBytesPerSecond));
    }

    private static void park(final long nanos) throws IOException {
        if (nanos <= 0) {
            return;
        }
        LockSupport.parkNanos(nanos);
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("Interrupted while shaping socket output");
        }
    }
}
```

- [ ] **Step 4: Wrap socket output streams when profile is REALISTIC**

Modify `LoopbackSocketTransport.java` imports:

```java
import java.io.OutputStream;
```

Add helper methods:

```java
    private static boolean isLatencyShapingActive(final NetworkSimulationConfig config) {
        return config.profile() == NetworkProfile.REALISTIC && config.latencyNanos() > 0;
    }

    private static boolean isBandwidthShapingActive(final NetworkSimulationConfig config) {
        return config.profile() == NetworkProfile.REALISTIC
                && config.bandwidthBytesPerSecond() != Long.MAX_VALUE;
    }

    private static OutputStream maybeShape(final OutputStream out, final NetworkSimulationConfig config) {
        if (config.profile() != NetworkProfile.REALISTIC) {
            return out;
        }
        return new ShapingOutputStream(out, config.latencyNanos(), config.bandwidthBytesPerSecond());
    }
```

Change output wrapper construction:

```java
        teacherToLearnerWritten = new CountingOutputStream(maybeShape(teacherSocket.getOutputStream(), config));
```

and:

```java
        learnerToTeacherWritten = new CountingOutputStream(maybeShape(learnerSocket.getOutputStream(), config));
```

Change diagnostics booleans:

```java
                isLatencyShapingActive(config),
                isBandwidthShapingActive(config),
```

- [ ] **Step 5: Run loopback transport tests**

Run:

```bash
./gradlew :swirlds-benchmarks:test --tests com.swirlds.benchmark.reconnect.network.LoopbackSocketTransportTest --console=plain
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit Task 4**

```bash
git add \
  platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/ShapingOutputStream.java \
  platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/LoopbackSocketTransport.java \
  platform-sdk/swirlds-benchmarks/src/test/java/com/swirlds/benchmark/reconnect/network/LoopbackSocketTransportTest.java
git commit -m "feat: add loopback socket latency and bandwidth shaping"
```

---

### Task 5: Make Gradle Reconnect Tasks Comparable

**Files:**
- Modify: `platform-sdk/swirlds-benchmarks/build.gradle.kts`

**Interfaces:**
- Consumes: existing `jmhParamProperty`.
- Produces: `jmhReconnect`, `jmhReconnectSimulated`, and `jmhReconnectLoopbackSocket` with consistent reconnect params.

- [ ] **Step 1: Refactor reconnect task parameter wiring**

Add helper functions near `jmhParamProperty`:

```kotlin
fun JMHTask.configureReconnectJvmArgs() {
    jvmArgs.set(
        listOf(
            gossipConnectivityExport,
            "-Xms24g",
            "-Xmx24g",
            "-XX:+AlwaysPreTouch",
            "-Xlog:gc*:file=/Users/thenswan/Work/LimeChain/playground/hiero-consensus-node/platform-sdk/swirlds-benchmarks/data/reconnectbench-gc.log:time,uptime,level,tags",
        )
    )
}

fun JMHTask.configureReconnectParameters(
    defaultTransport: String,
    defaultProfile: String,
) {
    includes.set(listOf("ReconnectBench"))
    benchmarkParameters.put("networkTransport", jmhParamProperty("networkTransport", defaultTransport))
    benchmarkParameters.put("networkProfile", jmhParamProperty("networkProfile", defaultProfile))
    benchmarkParameters.put(
        "networkLatencyMicroseconds",
        jmhParamProperty("networkLatencyMicroseconds", "75000"),
    )
    benchmarkParameters.put(
        "networkBandwidthMegabitsPerSecond",
        jmhParamProperty("networkBandwidthMegabitsPerSecond", "200"),
    )
    benchmarkParameters.put(
        "networkInflightBytesLimit",
        jmhParamProperty("networkInflightBytesLimit", "16777216"),
    )
    benchmarkParameters.put("randomSeed", jmhParamProperty("randomSeed", "9823452658"))
    benchmarkParameters.put("teacherAddProbability", jmhParamProperty("teacherAddProbability", "0.1"))
    benchmarkParameters.put("teacherRemoveProbability", jmhParamProperty("teacherRemoveProbability", "0.0"))
    benchmarkParameters.put("teacherModifyProbability", jmhParamProperty("teacherModifyProbability", "0.3"))
    benchmarkParameters.put("numFiles", jmhParamProperty("numFiles", "1000"))
    benchmarkParameters.put("numRecords", jmhParamProperty("numRecords", "10000"))
    benchmarkParameters.put("maxKey", jmhParamProperty("maxKey", "10000000"))
    benchmarkParameters.put("keySize", jmhParamProperty("keySize", "32"))
    benchmarkParameters.put("recordSize", jmhParamProperty("recordSize", "128"))
    benchmarkParameters.put("numThreads", jmhParamProperty("numThreads", "32"))
    resultsFile.convention(layout.buildDirectory.file("results/jmh/results-reconnect.txt"))
}
```

Replace the three reconnect task bodies with:

```kotlin
tasks.register<JMHTask>("jmhReconnect") {
    configureReconnectJvmArgs()
    configureReconnectParameters(defaultTransport = "SIMULATED", defaultProfile = "REALISTIC")
}

tasks.register<JMHTask>("jmhReconnectLoopbackSocket") {
    configureReconnectJvmArgs()
    configureReconnectParameters(defaultTransport = "LOOPBACK_SOCKET", defaultProfile = "LOOPBACK")
}

tasks.register<JMHTask>("jmhReconnectSimulated") {
    configureReconnectJvmArgs()
    configureReconnectParameters(defaultTransport = "SIMULATED", defaultProfile = "LOOPBACK")
}
```

- [ ] **Step 2: Compile JMH**

Run:

```bash
./gradlew :swirlds-benchmarks:compileJmhJava --console=plain
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit Task 5**

```bash
git add platform-sdk/swirlds-benchmarks/build.gradle.kts
git commit -m "build: align ReconnectBench transport task parameters"
```

---

### Task 6: Update Task Docs And Run Final Verification

**Files:**
- Modify: `25083-improve-reconnectbench/future-work/future-follow-ups.md`
- Modify: `25083-improve-reconnectbench/design-and-implementation/ReconnectBench-loopback-socket-transport-design.md`

**Interfaces:**
- Consumes: implemented behavior from Tasks 1-5.
- Produces: task docs that describe current state and final verification evidence.

- [ ] **Step 1: Update the follow-up item status**

In `future-follow-ups.md`, change item `3. Loopback TCP transport validation` status to:

```text
Status: revived as benchmark-only validation option on branch `codex/25083-loopback-socket-transport`.
```

Add implementation note under the future issue bullets:

```text
Implementation note:

- `ReconnectBench` now supports `NetworkTransport.SIMULATED` and `NetworkTransport.LOOPBACK_SOCKET`.
- `NetworkProfile.LOOPBACK` keeps each transport at its loopback/no-shaping floor.
- `NetworkProfile.REALISTIC` applies the configured latency/bandwidth model; for socket transport this is write-side
  shaping only.
- `networkInflightBytesLimit` remains simulator-only and is ignored by socket transport.
```

- [ ] **Step 2: Add final implementation status to the design doc**

In `ReconnectBench-loopback-socket-transport-design.md`, append a short implementation status section:

```markdown
## Implementation Status

Implemented on `codex/25083-loopback-socket-transport`.

Verification commands:

```bash
./gradlew :swirlds-benchmarks:test --tests com.swirlds.benchmark.reconnect.network.LoopbackSocketTransportTest --console=plain
./gradlew :swirlds-benchmarks:test --tests com.swirlds.benchmark.reconnect.network.SimulatedNetworkChannelTest --console=plain
./gradlew :swirlds-benchmarks:compileJmhJava --console=plain
```
```

- [ ] **Step 3: Run final verification commands**

Run:

```bash
./gradlew :swirlds-benchmarks:test --tests com.swirlds.benchmark.reconnect.network.LoopbackSocketTransportTest --console=plain
./gradlew :swirlds-benchmarks:test --tests com.swirlds.benchmark.reconnect.network.SimulatedNetworkChannelTest --console=plain
./gradlew :swirlds-benchmarks:compileJmhJava --console=plain
```

Expected: all three commands report `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit Task 6**

```bash
git add \
  25083-improve-reconnectbench/future-work/future-follow-ups.md \
  25083-improve-reconnectbench/design-and-implementation/ReconnectBench-loopback-socket-transport-design.md
git commit -m "docs: record ReconnectBench loopback socket implementation"
```

---

## Self-Review Checklist

- Spec coverage:
  - `NetworkTransport` plus existing `NetworkProfile` matrix is covered by Tasks 1, 3, 4, and 5.
  - `SocketFactory.configure*` reuse is covered by Task 2.
  - No socket in-flight cap is covered by Tasks 2 and 4 through diagnostics and absence of cap logic.
  - Write-side-only shaping is covered by Task 4.
  - Lean MVP scope is enforced by the narrow helper layout and coarse tests in Tasks 2-4.
  - Gradle comparability is covered by Task 5.
  - Task docs update is covered by Task 6.
- Red-flag scan: no unresolved gap markers are intentionally present.
- Type consistency:
  - `NetworkTransport` is defined in Task 1 and consumed in Tasks 2-5.
  - `LoopbackSocketTransport` is defined in Task 2 and consumed in Task 3.
  - `SocketTransportDiagnostics` is defined in Task 2 and consumed in Tasks 3-4.
  - `ShapingOutputStream` is defined and integrated in Task 4.
