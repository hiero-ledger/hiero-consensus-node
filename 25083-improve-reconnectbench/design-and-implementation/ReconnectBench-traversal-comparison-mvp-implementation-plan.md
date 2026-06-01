# ReconnectBench Traversal-Comparison MVP Implementation Plan

> Historical note: this implementation plan has already been executed. Keep this page as an archive of the original
> task breakdown and worker instructions, not as an active checklist for current ReconnectBench work.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current misleading reconnect delay knobs with a testable byte-level network simulator and make `ReconnectBench` useful for comparing `VirtualMapConfig.reconnectMode` traversal modes.

**Architecture:** Testable simulator code lives in `src/main/java` so standard unit tests can validate it. JMH-only benchmark wiring stays in `src/jmh/java`. `ReconnectBench` keeps existing state generation/restore behavior and traversal remains configured by `VirtualMapConfig`.

**Tech Stack:** Java 25, JUnit 5, Gradle, JMH, existing `ReconnectMapStats`, existing benchmark lifecycle hooks.

---

## Execution Rules

- Use TDD for each behavior-bearing task: write or update the focused test first, verify the intended failure, then implement.
- Keep each task commit small. Do not mix unrelated cleanup.
- Do not modify production/runtime consensus-node behavior.
- Do not change `VirtualMapConfig`; traversal order remains configured there.
- Use `./gradlew :swirlds-benchmarks:test --tests com.swirlds.benchmark.reconnect.network.SimulatedNetworkChannelTest` for simulator tasks.
- Use `./gradlew :swirlds-benchmarks:compileJmhJava` after JMH wiring tasks.
- Use `./gradlew :swirlds-benchmarks:jmhReconnect` only after wiring and with small test parameters first.
- Agent sandbox note: run every `./gradlew ...` command with sandbox escalation. Non-escalated Gradle fails in this
  workspace due `~/.gradle` lock-file access and Gradle's local file-lock listener socket. Do not spend task time
  debugging Java code when the error is `Operation not permitted` for Gradle locks or local sockets; rerun the same
  Gradle command with escalation.
- Use `25083-improve-reconnectbench` directory for any docs.

---

## File Structure

Create:

- `platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/NetworkProfile.java`
  Defines `REALISTIC` and `LOOPBACK`.
- `platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/NetworkSimulationConfig.java`
  Immutable resolved network configuration used by channels.
- `platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/SimulatedNetworkStats.java`
  Immutable snapshot of channel byte counters.
- `platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/SimulatedNetworkChannel.java`
  Connected `InputStream`/`OutputStream` implementation with latency, bandwidth, and backpressure.
- `platform-sdk/swirlds-benchmarks/src/test/java/com/swirlds/benchmark/reconnect/network/SimulatedNetworkChannelTest.java`
  Focused unit tests for simulator behavior.
- `platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/reconnect/AtomicReconnectMapStats.java`
  Atomic implementation of `ReconnectMapStats` for benchmark summary logging.
- `platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/reconnect/ReconnectBenchmarkResult.java`
  Return object containing the reconnected map and final stats.

Modify:

- `platform-sdk/swirlds-benchmarks/src/main/java/module-info.java`
  Export the simulator package for tests and JMH code.
- `platform-sdk/swirlds-benchmarks/build.gradle.kts`
  Add test module access to JUnit before introducing `src/test` tests.
- `platform-sdk/swirlds-benchmarks/build.gradle.kts`
  Add `jmhReconnect` property passthrough for network and state-size JMH params.
- `platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/reconnect/PairedStreams.java`
  Replace loopback sockets with simulated network channels.
- `platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/reconnect/MerkleBenchmarkUtils.java`
  Remove slow-synchronizer branch and return benchmark result data.
- `platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/ReconnectBench.java`
  Add network params, switch to `SingleShotTime`, remove delay params, log summaries.

Delete:

- `platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/reconnect/lag/BenchmarkSlowAsyncOutputStream.java`
- `platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/reconnect/lag/BenchmarkSlowLearningSynchronizer.java`
- `platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/reconnect/lag/BenchmarkSlowTeachingSynchronizer.java`
- `platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/reconnect/lag/LongFuzzer.java`

---

## Task 1: Network Config Types

**Files:**
- Modify: `platform-sdk/swirlds-benchmarks/build.gradle.kts`
- Create: `platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/NetworkProfile.java`
- Create: `platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/NetworkSimulationConfig.java`
- Create: `platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/SimulatedNetworkStats.java`
- Modify: `platform-sdk/swirlds-benchmarks/src/main/java/module-info.java`
- Test: `platform-sdk/swirlds-benchmarks/src/test/java/com/swirlds/benchmark/reconnect/network/SimulatedNetworkChannelTest.java`

- [ ] **Step 1: Add JUnit access for the new test source set**

`swirlds-benchmarks` did not previously have `src/test` tests. Add this block to `platform-sdk/swirlds-benchmarks/build.gradle.kts` before writing JUnit tests:

```kotlin
testModuleInfo {
    requires("org.junit.jupiter.api")
}
```

- [ ] **Step 2: Write failing config tests**

Create `SimulatedNetworkChannelTest.java` with these first tests:

```java
// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SimulatedNetworkChannelTest {

    @Test
    void loopbackProfileIgnoresTimingAndBackpressure() {
        final NetworkSimulationConfig config =
                NetworkSimulationConfig.resolve(NetworkProfile.LOOPBACK, 500, 1_000, 131_072);

        assertEquals(NetworkProfile.LOOPBACK, config.profile());
        assertEquals(0, config.latencyNanos());
        assertEquals(Long.MAX_VALUE, config.bandwidthBytesPerSecond());
        assertEquals(Integer.MAX_VALUE, config.inflightBytesLimit());
    }

    @Test
    void realisticProfileConvertsUnits() {
        final NetworkSimulationConfig config =
                NetworkSimulationConfig.resolve(NetworkProfile.REALISTIC, 500, 1_000, 131_072);

        assertEquals(NetworkProfile.REALISTIC, config.profile());
        assertEquals(500_000, config.latencyNanos());
        assertEquals(125_000_000L, config.bandwidthBytesPerSecond());
        assertEquals(131_072, config.inflightBytesLimit());
    }

    @Test
    void realisticProfileRejectsInvalidValues() {
        assertThrows(
                IllegalArgumentException.class,
                () -> NetworkSimulationConfig.resolve(NetworkProfile.REALISTIC, -1, 1_000, 131_072));
        assertThrows(
                IllegalArgumentException.class,
                () -> NetworkSimulationConfig.resolve(NetworkProfile.REALISTIC, 500, 0, 131_072));
        assertThrows(
                IllegalArgumentException.class,
                () -> NetworkSimulationConfig.resolve(NetworkProfile.REALISTIC, 500, 1_000, 0));
    }

    @Test
    void statsExposeCounterSnapshot() {
        final SimulatedNetworkStats stats = new SimulatedNetworkStats(10, 7, 4);

        assertEquals(10, stats.bytesWritten());
        assertEquals(7, stats.bytesRead());
        assertEquals(4, stats.maxInflightBytes());
        assertTrue(stats.toString().contains("bytesWritten=10"));
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run:

```bash
./gradlew :swirlds-benchmarks:test --tests com.swirlds.benchmark.reconnect.network.SimulatedNetworkChannelTest
```

Expected: FAIL because `NetworkProfile`, `NetworkSimulationConfig`, and `SimulatedNetworkStats` do not exist. If it fails because JUnit is unavailable, the `testModuleInfo` setup is wrong and must be fixed before implementation.

- [ ] **Step 4: Implement config classes**

Create `NetworkProfile.java`:

```java
// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

public enum NetworkProfile {
    REALISTIC,
    LOOPBACK
}
```

Create `NetworkSimulationConfig.java`:

```java
// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

import java.util.Objects;

public record NetworkSimulationConfig(
        NetworkProfile profile,
        long latencyNanos,
        long bandwidthBytesPerSecond,
        int inflightBytesLimit) {

    public NetworkSimulationConfig {
        Objects.requireNonNull(profile, "profile must not be null");
        if (latencyNanos < 0) {
            throw new IllegalArgumentException("latencyNanos must be non-negative");
        }
        if (bandwidthBytesPerSecond <= 0) {
            throw new IllegalArgumentException("bandwidthBytesPerSecond must be positive");
        }
        if (inflightBytesLimit <= 0) {
            throw new IllegalArgumentException("inflightBytesLimit must be positive");
        }
    }

    public static NetworkSimulationConfig resolve(
            final NetworkProfile profile,
            final long latencyMicroseconds,
            final long bandwidthMegabitsPerSecond,
            final int inflightBytesLimit) {
        Objects.requireNonNull(profile, "profile must not be null");
        if (profile == NetworkProfile.LOOPBACK) {
            return new NetworkSimulationConfig(profile, 0, Long.MAX_VALUE, Integer.MAX_VALUE);
        }
        if (latencyMicroseconds < 0) {
            throw new IllegalArgumentException("latencyMicroseconds must be non-negative");
        }
        if (bandwidthMegabitsPerSecond <= 0) {
            throw new IllegalArgumentException("bandwidthMegabitsPerSecond must be positive");
        }
        if (inflightBytesLimit <= 0) {
            throw new IllegalArgumentException("inflightBytesLimit must be positive");
        }

        final long latencyNanos = Math.multiplyExact(latencyMicroseconds, 1_000L);
        final long bandwidthBytesPerSecond = Math.multiplyExact(bandwidthMegabitsPerSecond, 1_000_000L) / 8L;
        return new NetworkSimulationConfig(profile, latencyNanos, bandwidthBytesPerSecond, inflightBytesLimit);
    }
}
```

Create `SimulatedNetworkStats.java`:

```java
// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

public record SimulatedNetworkStats(long bytesWritten, long bytesRead, long maxInflightBytes) {}
```

Update `module-info.java`:

```java
// SPDX-License-Identifier: Apache-2.0
module com.swirlds.benchmarks {
    exports com.swirlds.benchmark.reconnect.network;
}
```

- [ ] **Step 5: Run test to verify it passes**

Run:

```bash
./gradlew :swirlds-benchmarks:test --tests com.swirlds.benchmark.reconnect.network.SimulatedNetworkChannelTest
```

Expected: PASS for the four config tests.

- [ ] **Step 6: Commit**

```bash
git add platform-sdk/swirlds-benchmarks/build.gradle.kts \
  platform-sdk/swirlds-benchmarks/src/main/java/module-info.java \
  platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network \
  platform-sdk/swirlds-benchmarks/src/test/java/com/swirlds/benchmark/reconnect/network/SimulatedNetworkChannelTest.java
git commit -m "test: add network simulation config"
```

---

## Task 2: Simulated Byte Channel Core

**Files:**
- Create: `platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/SimulatedNetworkChannel.java`
- Modify: `platform-sdk/swirlds-benchmarks/src/test/java/com/swirlds/benchmark/reconnect/network/SimulatedNetworkChannelTest.java`

- [ ] **Step 1: Add failing stream behavior tests**

Append these tests to `SimulatedNetworkChannelTest`:

```java
@Test
void loopbackPreservesDataStreamFraming() throws Exception {
    final SimulatedNetworkChannel channel = new SimulatedNetworkChannel(
            NetworkSimulationConfig.resolve(NetworkProfile.LOOPBACK, 0, 1, 1));

    try (DataOutputStream out = new DataOutputStream(channel.outputStream());
            DataInputStream in = new DataInputStream(channel.inputStream())) {
        out.writeInt(4);
        out.write(new byte[] {1, 2, 3, 4});
        out.flush();

        final int length = in.readInt();
        final byte[] data = new byte[length];
        in.readFully(data);

        assertEquals(4, length);
        assertArrayEquals(new byte[] {1, 2, 3, 4}, data);
        assertEquals(8, channel.snapshotStats().bytesWritten());
        assertEquals(8, channel.snapshotStats().bytesRead());
    }
}

@Test
void closeDrainsQueuedBytesThenReturnsEof() throws Exception {
    final SimulatedNetworkChannel channel = new SimulatedNetworkChannel(
            NetworkSimulationConfig.resolve(NetworkProfile.LOOPBACK, 0, 1, 1));

    final OutputStream out = channel.outputStream();
    final InputStream in = channel.inputStream();
    out.write(new byte[] {9, 8, 7});
    out.close();

    assertEquals(9, in.read());
    assertEquals(8, in.read());
    assertEquals(7, in.read());
    assertEquals(-1, in.read());
}

@Test
void disconnectWakesBlockedReader() throws Exception {
    final SimulatedNetworkChannel channel = new SimulatedNetworkChannel(
            NetworkSimulationConfig.resolve(NetworkProfile.REALISTIC, 1_000_000, 1, 1024));

    final AtomicReference<Throwable> thrown = new AtomicReference<>();
    final Thread reader = new Thread(() -> {
        try {
            channel.inputStream().read();
        } catch (final Throwable t) {
            thrown.set(t);
        }
    });
    reader.start();

    awaitThreadState(reader, Thread.State.WAITING, Thread.State.TIMED_WAITING);
    channel.disconnect();
    reader.join(5_000);

    assertFalse(reader.isAlive());
    assertTrue(thrown.get() instanceof IOException);
}
```

Also add these imports and helper method:

```java
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicReference;

private static void awaitThreadState(final Thread thread, final Thread.State... states) throws InterruptedException {
    final long deadline = System.nanoTime() + 5_000_000_000L;
    while (System.nanoTime() < deadline) {
        for (final Thread.State state : states) {
            if (thread.getState() == state) {
                return;
            }
        }
        Thread.sleep(10);
    }
    throw new AssertionError("Thread did not reach expected state, actual=" + thread.getState());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :swirlds-benchmarks:test --tests com.swirlds.benchmark.reconnect.network.SimulatedNetworkChannelTest
```

Expected: FAIL because `SimulatedNetworkChannel` does not exist.

- [ ] **Step 3: Implement loopback-capable channel core**

Create `SimulatedNetworkChannel.java` with:

```java
// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class SimulatedNetworkChannel {

    private static final int DEFAULT_RANGE_SIZE = 8192;

    private final NetworkSimulationConfig config;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition stateChanged = lock.newCondition();
    private final Queue<ByteRange> ranges = new ArrayDeque<>();
    private final InputStream inputStream = new ChannelInputStream();
    private final OutputStream outputStream = new ChannelOutputStream();

    private long bytesWritten;
    private long bytesRead;
    private long inflightBytes;
    private long maxInflightBytes;
    private long nextTransmissionAvailableAtNanos;
    private boolean closed;
    private boolean disconnected;

    public SimulatedNetworkChannel(final NetworkSimulationConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    public InputStream inputStream() {
        return inputStream;
    }

    public OutputStream outputStream() {
        return outputStream;
    }

    public SimulatedNetworkStats snapshotStats() {
        lock.lock();
        try {
            return new SimulatedNetworkStats(bytesWritten, bytesRead, maxInflightBytes);
        } finally {
            lock.unlock();
        }
    }

    public void disconnect() {
        lock.lock();
        try {
            disconnected = true;
            stateChanged.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private void ensureConnected() throws IOException {
        if (disconnected) {
            throw new IOException("Simulated network channel is disconnected");
        }
    }

    private final class ChannelOutputStream extends OutputStream {
        @Override
        public void write(final int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(final byte[] b, final int off, final int len) throws IOException {
            Objects.checkFromIndexSize(off, len, b.length);
            int offset = off;
            int remaining = len;
            while (remaining > 0) {
                final int accepted = Math.min(remaining, Math.min(DEFAULT_RANGE_SIZE, config.inflightBytesLimit()));
                writeRange(Arrays.copyOfRange(b, offset, offset + accepted));
                offset += accepted;
                remaining -= accepted;
            }
        }

        @Override
        public void close() {
            lock.lock();
            try {
                closed = true;
                stateChanged.signalAll();
            } finally {
                lock.unlock();
            }
        }

        private void writeRange(final byte[] bytes) throws IOException {
            lock.lock();
            try {
                ensureConnected();
                while (inflightBytes + bytes.length > config.inflightBytesLimit()) {
                    try {
                        stateChanged.await();
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted while waiting for simulated network capacity", e);
                    }
                    ensureConnected();
                }
                final long now = System.nanoTime();
                final long sendStart = Math.max(now, nextTransmissionAvailableAtNanos);
                final long sendEnd = sendStart + transmitDurationNanos(bytes.length);
                ranges.add(new ByteRange(bytes, sendStart + config.latencyNanos(), sendEnd + config.latencyNanos()));
                nextTransmissionAvailableAtNanos = sendEnd;
                bytesWritten += bytes.length;
                inflightBytes += bytes.length;
                maxInflightBytes = Math.max(maxInflightBytes, inflightBytes);
                stateChanged.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }

    private long transmitDurationNanos(final int byteCount) {
        if (config.bandwidthBytesPerSecond() == Long.MAX_VALUE) {
            return 0;
        }
        return Math.max(1, (long) Math.ceil(byteCount * 1_000_000_000.0 / config.bandwidthBytesPerSecond()));
    }

    private final class ChannelInputStream extends InputStream {
        @Override
        public int read() throws IOException {
            final byte[] one = new byte[1];
            final int count = read(one, 0, 1);
            return count < 0 ? -1 : Byte.toUnsignedInt(one[0]);
        }

        @Override
        public int read(final byte[] b, final int off, final int len) throws IOException {
            Objects.checkFromIndexSize(off, len, b.length);
            if (len == 0) {
                return 0;
            }
            lock.lock();
            try {
                while (true) {
                    ensureConnected();
                    final ByteRange range = ranges.peek();
                    if (range == null) {
                        if (closed) {
                            return -1;
                        }
                        awaitStateChange();
                        continue;
                    }
                    final long now = System.nanoTime();
                    final int available = range.availableBytes(now);
                    if (available == 0) {
                        awaitNanos(Math.max(1, range.nextReadableAtNanos(now) - now));
                        continue;
                    }
                    final int toRead = Math.min(len, available);
                    range.readInto(b, off, toRead);
                    bytesRead += toRead;
                    inflightBytes -= toRead;
                    if (range.isFullyRead()) {
                        ranges.remove();
                    }
                    stateChanged.signalAll();
                    return toRead;
                }
            } finally {
                lock.unlock();
            }
        }

        private void awaitStateChange() throws IOException {
            try {
                stateChanged.await();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for simulated network data", e);
            }
        }

        private void awaitNanos(final long nanos) throws IOException {
            try {
                stateChanged.awaitNanos(nanos);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for simulated network timing", e);
            }
        }
    }

    private static final class ByteRange {
        private final byte[] bytes;
        private final long arrivalStartNanos;
        private final long arrivalEndNanos;
        private int readOffset;

        private ByteRange(final byte[] bytes, final long arrivalStartNanos, final long arrivalEndNanos) {
            this.bytes = bytes;
            this.arrivalStartNanos = arrivalStartNanos;
            this.arrivalEndNanos = arrivalEndNanos;
        }

        private int availableBytes(final long now) {
            if (now < arrivalStartNanos) {
                return 0;
            }
            if (arrivalEndNanos <= arrivalStartNanos || now >= arrivalEndNanos) {
                return bytes.length - readOffset;
            }
            final double progress = (double) (now - arrivalStartNanos) / (arrivalEndNanos - arrivalStartNanos);
            final int arrived = Math.max(1, Math.min(bytes.length, (int) Math.floor(progress * bytes.length)));
            return Math.max(0, arrived - readOffset);
        }

        private long nextReadableAtNanos(final long now) {
            if (readOffset == 0) {
                return arrivalStartNanos;
            }
            if (arrivalEndNanos <= arrivalStartNanos) {
                return now;
            }
            final double nextByteFraction = (double) (readOffset + 1) / bytes.length;
            return arrivalStartNanos + (long) Math.ceil(nextByteFraction * (arrivalEndNanos - arrivalStartNanos));
        }

        private void readInto(final byte[] destination, final int destinationOffset, final int count) {
            System.arraycopy(bytes, readOffset, destination, destinationOffset, count);
            readOffset += count;
        }

        private boolean isFullyRead() {
            return readOffset == bytes.length;
        }
    }
}
```

- [ ] **Step 4: Run tests**

Run:

```bash
./gradlew :swirlds-benchmarks:test --tests com.swirlds.benchmark.reconnect.network.SimulatedNetworkChannelTest
```

Expected: PASS for config, framing, close, and disconnect tests.

- [ ] **Step 5: Commit**

```bash
git add platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/SimulatedNetworkChannel.java \
  platform-sdk/swirlds-benchmarks/src/test/java/com/swirlds/benchmark/reconnect/network/SimulatedNetworkChannelTest.java
git commit -m "feat: add simulated network channel core"
```

---

## Task 3: Timing And Backpressure Tests

**Files:**
- Modify: `platform-sdk/swirlds-benchmarks/src/test/java/com/swirlds/benchmark/reconnect/network/SimulatedNetworkChannelTest.java`
- Modify: `platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/SimulatedNetworkChannel.java`

- [ ] **Step 1: Add failing latency, bandwidth, and backpressure tests**

Append these tests:

```java
@Test
void latencyDelaysFirstByteVisibility() throws Exception {
    final SimulatedNetworkChannel channel = new SimulatedNetworkChannel(
            NetworkSimulationConfig.resolve(NetworkProfile.REALISTIC, 50_000, 1_000, 1024));

    channel.outputStream().write(123);
    final long start = System.nanoTime();
    assertEquals(123, channel.inputStream().read());
    final long elapsedNanos = System.nanoTime() - start;

    assertTrue(elapsedNanos >= 40_000_000L, "first byte should be delayed by configured latency");
}

@Test
void bandwidthLimitsFullStreamDelivery() throws Exception {
    final SimulatedNetworkChannel channel = new SimulatedNetworkChannel(
            NetworkSimulationConfig.resolve(NetworkProfile.REALISTIC, 0, 8, 500_000));
    final byte[] payload = new byte[250_000];

    final long start = System.nanoTime();
    channel.outputStream().write(payload);
    channel.inputStream().readNBytes(payload.length);
    final long elapsedNanos = System.nanoTime() - start;

    assertTrue(elapsedNanos >= 150_000_000L, "250 KB at 1 MB/s should take noticeable time");
}

@Test
void backpressureBlocksWriterUntilReaderConsumesBytes() throws Exception {
    final SimulatedNetworkChannel channel = new SimulatedNetworkChannel(
            NetworkSimulationConfig.resolve(NetworkProfile.REALISTIC, 0, 1_000, 16));
    final AtomicReference<Throwable> thrown = new AtomicReference<>();
    final Thread writer = new Thread(() -> {
        try {
            channel.outputStream().write(new byte[64]);
        } catch (final Throwable t) {
            thrown.set(t);
        }
    });

    writer.start();
    awaitThreadState(writer, Thread.State.WAITING);
    assertEquals(16, channel.snapshotStats().maxInflightBytes());

    channel.inputStream().readNBytes(64);
    writer.join(5_000);

    assertFalse(writer.isAlive());
    assertNull(thrown.get());
    assertEquals(64, channel.snapshotStats().bytesWritten());
    assertEquals(64, channel.snapshotStats().bytesRead());
}
```

- [ ] **Step 2: Run test**

Run:

```bash
./gradlew :swirlds-benchmarks:test --tests com.swirlds.benchmark.reconnect.network.SimulatedNetworkChannelTest
```

Expected: PASS if Task 2 implementation already satisfies timing and backpressure; FAIL if timing math or large-write splitting needs correction.

- [ ] **Step 3: Correct timing/backpressure implementation when needed**

If tests fail, update `SimulatedNetworkChannel` so:

```java
final int accepted = Math.min(remaining, Math.min(DEFAULT_RANGE_SIZE, config.inflightBytesLimit()));
```

never accepts more than the cap, and `ChannelInputStream.read()` calls:

```java
awaitNanos(Math.max(1, range.nextReadableAtNanos(now) - now));
```

when the next queued range exists but no byte from it has arrived yet.

- [ ] **Step 4: Run test again**

Run:

```bash
./gradlew :swirlds-benchmarks:test --tests com.swirlds.benchmark.reconnect.network.SimulatedNetworkChannelTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/SimulatedNetworkChannel.java \
  platform-sdk/swirlds-benchmarks/src/test/java/com/swirlds/benchmark/reconnect/network/SimulatedNetworkChannelTest.java
git commit -m "test: cover simulated network timing"
```

---

## Task 4: Simulated Paired Streams

**Files:**
- Modify: `platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/reconnect/PairedStreams.java`

- [ ] **Step 1: Replace socket fields with channels**

Update fields to:

```java
private final SimulatedNetworkChannel teacherToLearner;
private final SimulatedNetworkChannel learnerToTeacher;

private BufferedOutputStream teacherOutputBuffer;
private DataOutputStream teacherOutput;
private BufferedInputStream teacherInputBuffer;
private DataInputStream teacherInput;
private BufferedOutputStream learnerOutputBuffer;
private DataOutputStream learnerOutput;
private BufferedInputStream learnerInputBuffer;
private DataInputStream learnerInput;
```

- [ ] **Step 2: Replace constructor**

Use this constructor:

```java
public PairedStreams(@NonNull final NetworkSimulationConfig networkConfig) {
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

- [ ] **Step 3: Add network stats accessors**

Add:

```java
public SimulatedNetworkStats getTeacherToLearnerStats() {
    return teacherToLearner.snapshotStats();
}

public SimulatedNetworkStats getLearnerToTeacherStats() {
    return learnerToTeacher.snapshotStats();
}
```

- [ ] **Step 4: Update disconnect and close**

`disconnect()` should call:

```java
teacherToLearner.disconnect();
learnerToTeacher.disconnect();
```

`close()` should close the four data streams and four buffer streams, preserving the current best-effort close behavior.

- [ ] **Step 5: Compile JMH code**

Run:

```bash
./gradlew :swirlds-benchmarks:compileJmhJava
```

Expected: FAIL because `MerkleBenchmarkUtils` still calls the old socket-based constructor.

- [ ] **Step 6: Commit after Task 5 wires callers**

Do not commit yet; this task intentionally leaves callers broken until Task 5.

---

## Task 5: Simplify MerkleBenchmarkUtils

**Files:**
- Create: `platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/reconnect/ReconnectBenchmarkResult.java`
- Create: `platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/reconnect/AtomicReconnectMapStats.java`
- Modify: `platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/reconnect/MerkleBenchmarkUtils.java`

- [ ] **Step 1: Add result record**

Create:

```java
// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect;

import com.swirlds.benchmark.reconnect.network.SimulatedNetworkStats;
import com.swirlds.virtualmap.VirtualMap;

public record ReconnectBenchmarkResult(
        VirtualMap reconnectedMap,
        AtomicReconnectMapStats reconnectStats,
        SimulatedNetworkStats teacherToLearnerStats,
        SimulatedNetworkStats learnerToTeacherStats) {}
```

- [ ] **Step 2: Add atomic reconnect stats**

Create `AtomicReconnectMapStats.java`:

```java
// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect;

import com.swirlds.common.merkle.synchronization.stats.ReconnectMapStats;
import java.util.concurrent.atomic.AtomicLong;

public class AtomicReconnectMapStats implements ReconnectMapStats {

    private final AtomicLong transfersFromTeacher = new AtomicLong();
    private final AtomicLong transfersFromLearner = new AtomicLong();
    private final AtomicLong internalHashes = new AtomicLong();
    private final AtomicLong internalCleanHashes = new AtomicLong();
    private final AtomicLong internalData = new AtomicLong();
    private final AtomicLong internalCleanData = new AtomicLong();
    private final AtomicLong leafHashes = new AtomicLong();
    private final AtomicLong leafCleanHashes = new AtomicLong();
    private final AtomicLong leafData = new AtomicLong();
    private final AtomicLong leafCleanData = new AtomicLong();

    @Override
    public void incrementTransfersFromTeacher() {
        transfersFromTeacher.incrementAndGet();
    }

    @Override
    public void incrementTransfersFromLearner() {
        transfersFromLearner.incrementAndGet();
    }

    @Override
    public void incrementInternalHashes(final int hashNum, final int cleanHashNum) {
        internalHashes.addAndGet(hashNum);
        internalCleanHashes.addAndGet(cleanHashNum);
    }

    @Override
    public void incrementInternalData(final int dataNum, final int cleanDataNum) {
        internalData.addAndGet(dataNum);
        internalCleanData.addAndGet(cleanDataNum);
    }

    @Override
    public void incrementLeafHashes(final int hashNum, final int cleanHashNum) {
        leafHashes.addAndGet(hashNum);
        leafCleanHashes.addAndGet(cleanHashNum);
    }

    @Override
    public void incrementLeafData(final int dataNum, final int cleanDataNum) {
        leafData.addAndGet(dataNum);
        leafCleanData.addAndGet(cleanDataNum);
    }

    public long transfersFromTeacher() {
        return transfersFromTeacher.get();
    }

    public long transfersFromLearner() {
        return transfersFromLearner.get();
    }

    @Override
    public String format() {
        return "AtomicReconnectMapStats: "
                + "transfersFromTeacher=" + transfersFromTeacher.get()
                + "; transfersFromLearner=" + transfersFromLearner.get()
                + "; internalHashes=" + internalHashes.get()
                + "; internalCleanHashes=" + internalCleanHashes.get()
                + "; internalData=" + internalData.get()
                + "; internalCleanData=" + internalCleanData.get()
                + "; leafHashes=" + leafHashes.get()
                + "; leafCleanHashes=" + leafCleanHashes.get()
                + "; leafData=" + leafData.get()
                + "; leafCleanData=" + leafCleanData.get();
    }
}
```

- [ ] **Step 3: Update utility method signature**

Change `hashAndTestSynchronization(...)` to:

```java
public static ReconnectBenchmarkResult hashAndTestSynchronization(
        final VirtualMap startingTree,
        final VirtualMap desiredTree,
        final NetworkSimulationConfig networkConfig,
        final Configuration configuration)
        throws Exception
```

Remove all delay and `NodeId` parameters.

- [ ] **Step 4: Remove slow synchronizer branch**

Inside `testSynchronization`, create:

```java
final AtomicReconnectMapStats reconnectStats = new AtomicReconnectMapStats();
final ReconnectMapStats mapStats = new ReconnectMapMetrics(metrics, null, reconnectStats);
```

Always construct:

```java
learner = new LearningSynchronizer(...);
teacher = new TeachingSynchronizer(...);
```

Remove imports of:

```java
com.swirlds.benchmark.reconnect.lag.BenchmarkSlowLearningSynchronizer
com.swirlds.benchmark.reconnect.lag.BenchmarkSlowTeachingSynchronizer
org.hiero.consensus.gossip.config.GossipConfig
org.hiero.consensus.gossip.config.SocketConfig
org.hiero.consensus.model.node.NodeId
```

- [ ] **Step 5: Return result with stream stats**

Before leaving the `try (PairedStreams streams = ...)` block, return:

```java
return new ReconnectBenchmarkResult(
        vmapLearner.getVirtualMap(),
        reconnectStats,
        streams.getTeacherToLearnerStats(),
        streams.getLearnerToTeacherStats());
```

- [ ] **Step 6: Compile JMH code**

Run:

```bash
./gradlew :swirlds-benchmarks:compileJmhJava
```

Expected: FAIL because `ReconnectBench` still expects a `VirtualMap` return value and old delay parameters.

- [ ] **Step 7: Commit after Task 6 updates ReconnectBench**

Do not commit yet; this task intentionally leaves `ReconnectBench` broken until Task 6.

---

## Task 6: Update ReconnectBench

**Files:**
- Modify: `platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/ReconnectBench.java`

- [ ] **Step 1: Update annotations**

Change class annotations to:

```java
@BenchmarkMode(Mode.SingleShotTime)
@Fork(value = 1)
@Warmup(iterations = 0)
@Measurement(iterations = 1)
public class ReconnectBench extends VirtualMapBaseBench {
```

- [ ] **Step 2: Replace delay params with network params**

Remove:

```java
delayStorageMicroseconds
delayStorageFuzzRangePercent
delayNetworkMicroseconds
delayNetworkFuzzRangePercent
```

Add:

```java
@Param({"REALISTIC"})
public NetworkProfile networkProfile;

@Param({"500"})
public long networkLatencyMicroseconds;

@Param({"1000"})
public long networkBandwidthMegabitsPerSecond;

@Param({"131072"})
public int networkInflightBytesLimit;
```

- [ ] **Step 3: Store benchmark result**

Replace `private VirtualMap reconnectedMap;` with:

```java
private ReconnectBenchmarkResult reconnectResult;
```

Update teardown:

```java
if (reconnectResult != null && reconnectResult.reconnectedMap() != null) {
    reconnectResult.reconnectedMap().release();
}
reconnectResult = null;
```

- [ ] **Step 4: Resolve network config in benchmark method**

At the start of `reconnect()`:

```java
final NetworkSimulationConfig networkConfig = NetworkSimulationConfig.resolve(
        networkProfile,
        networkLatencyMicroseconds,
        networkBandwidthMegabitsPerSecond,
        networkInflightBytesLimit);
logger.info(
        "ReconnectBench network profile={}, latencyNanos={}, bandwidthBytesPerSecond={}, inflightBytesLimit={}",
        networkConfig.profile(),
        networkConfig.latencyNanos(),
        networkConfig.bandwidthBytesPerSecond(),
        networkConfig.inflightBytesLimit());
```

- [ ] **Step 5: Call new utility result**

Replace the old call with:

```java
reconnectResult = MerkleBenchmarkUtils.hashAndTestSynchronization(
        learnerMap,
        teacherMap,
        networkConfig,
        configuration);

verifyMap(teacherData, reconnectResult.reconnectedMap());
logger.info("Reconnect stats: {}", reconnectResult.reconnectStats().format());
logger.info("Network teacherToLearner: {}", reconnectResult.teacherToLearnerStats());
logger.info("Network learnerToTeacher: {}", reconnectResult.learnerToTeacherStats());
```

- [ ] **Step 6: Compile JMH code**

Run:

```bash
./gradlew :swirlds-benchmarks:compileJmhJava
```

Expected: PASS.

- [ ] **Step 7: Run simulator tests**

Run:

```bash
./gradlew :swirlds-benchmarks:test --tests com.swirlds.benchmark.reconnect.network.SimulatedNetworkChannelTest
```

Expected: PASS.

- [ ] **Step 8: Commit Tasks 4-6 together**

```bash
git add platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/ReconnectBench.java \
  platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/reconnect/PairedStreams.java \
  platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/reconnect/MerkleBenchmarkUtils.java \
  platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/reconnect/AtomicReconnectMapStats.java \
  platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/reconnect/ReconnectBenchmarkResult.java
git commit -m "feat: wire ReconnectBench to simulated network"
```

---

## Task 7: Gradle JMH Parameter Passthrough

**Files:**
- Modify: `platform-sdk/swirlds-benchmarks/build.gradle.kts`

- [ ] **Step 1: Add helper for project-backed JMH params**

Add near existing `listProperty` helper:

```kotlin
fun jmhParamProperty(name: String, defaultValue: String) =
    objects.listProperty<String>().value(listOf(providers.gradleProperty(name).orElse(defaultValue).get()))
```

- [ ] **Step 2: Wire reconnect params**

Update `jmhReconnect`:

```kotlin
tasks.register<JMHTask>("jmhReconnect") {
    includes.set(listOf("ReconnectBench"))
    jvmArgs.set(listOf("-Xmx16g"))
    benchmarkParameters.put("networkProfile", jmhParamProperty("networkProfile", "REALISTIC"))
    benchmarkParameters.put("networkLatencyMicroseconds", jmhParamProperty("networkLatencyMicroseconds", "500"))
    benchmarkParameters.put("networkBandwidthMegabitsPerSecond", jmhParamProperty("networkBandwidthMegabitsPerSecond", "1000"))
    benchmarkParameters.put("networkInflightBytesLimit", jmhParamProperty("networkInflightBytesLimit", "131072"))
    benchmarkParameters.put("numFiles", jmhParamProperty("numFiles", "100"))
    benchmarkParameters.put("numRecords", jmhParamProperty("numRecords", "100000"))
    resultsFile.convention(layout.buildDirectory.file("results/jmh/results-reconnect.txt"))
}
```

- [ ] **Step 3: Verify task configuration compiles**

Run:

```bash
./gradlew :swirlds-benchmarks:tasks --all
```

Expected: PASS and output includes `jmhReconnect`.

- [ ] **Step 4: Commit**

```bash
git add platform-sdk/swirlds-benchmarks/build.gradle.kts
git commit -m "build: expose ReconnectBench JMH parameters"
```

---

## Task 8: Remove Lag Package

**Files:**
- Delete all files under `platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/reconnect/lag`

- [ ] **Step 1: Delete obsolete slow classes**

Run:

```bash
git rm platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/reconnect/lag/BenchmarkSlowAsyncOutputStream.java \
  platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/reconnect/lag/BenchmarkSlowLearningSynchronizer.java \
  platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/reconnect/lag/BenchmarkSlowTeachingSynchronizer.java \
  platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/reconnect/lag/LongFuzzer.java
```

- [ ] **Step 2: Verify no references remain**

Run:

```bash
git grep -n "BenchmarkSlow\\|LongFuzzer\\|delayStorageMicroseconds\\|delayNetworkMicroseconds" -- platform-sdk/swirlds-benchmarks
```

Expected: no output.

- [ ] **Step 3: Compile and test**

Run:

```bash
./gradlew :swirlds-benchmarks:test :swirlds-benchmarks:compileJmhJava
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add platform-sdk/swirlds-benchmarks/src/jmh/java/com/swirlds/benchmark/reconnect/lag
git commit -m "chore: remove obsolete reconnect delay simulation"
```

---

## Task 9: Smoke Runs And Calibration Notes

**Files:**
- Modify: `25083-improve-reconnectbench/design-and-implementation/ReconnectBench-traversal-comparison-mvp-design.md`

- [ ] **Step 1: Run loopback smoke benchmark**

Use a small state so the smoke test finishes quickly:

```bash
./gradlew :swirlds-benchmarks:jmhReconnect -PnetworkProfile=LOOPBACK -PnumFiles=1 -PnumRecords=1000
```

Expected: PASS. Output includes `ReconnectBench.reconnect`, `network profile=LOOPBACK`, reconnect stats, and network stats.

- [ ] **Step 2: Run realistic smoke benchmark**

```bash
./gradlew :swirlds-benchmarks:jmhReconnect -PnetworkProfile=REALISTIC -PnetworkLatencyMicroseconds=500 -PnetworkBandwidthMegabitsPerSecond=1000 -PnetworkInflightBytesLimit=131072 -PnumFiles=1 -PnumRecords=1000
```

Expected: PASS. Output includes `network profile=REALISTIC`, reconnect stats, and network stats.

- [ ] **Step 3: Run traversal sanity comparison**

Use existing `settings.txt` or project settings mechanism to set:

```text
benchmark.saveDataDirectory=true
benchmark.benchmarkData=/tmp/reconnectbench-comparison
virtualMap.reconnectMode=pullTopToBottom
```

Run:

```bash
./gradlew :swirlds-benchmarks:jmhReconnect -PnetworkProfile=REALISTIC -PnumFiles=1 -PnumRecords=10000
```

Then change only:

```text
virtualMap.reconnectMode=pullTwoPhasePessimistic
```

Run the same command again.

Expected: both runs pass, restore or generate state is clearly logged, and `pullTopToBottom` is faster than `pullTwoPhasePessimistic` for the same state.

- [ ] **Step 4: Record calibration findings**

Append a `## Calibration Notes` section to the design document if it does not already exist. Under a `### 2026-05-04` subsection, record the exact machine model, JVM version, command, state size, network profile, wall-time result, and any relevant notes from the smoke and traversal sanity runs. Do not commit empty calibration fields.

- [ ] **Step 5: Commit**

```bash
git add 25083-improve-reconnectbench/design-and-implementation/ReconnectBench-traversal-comparison-mvp-design.md
git commit -m "docs: record ReconnectBench calibration notes"
```

---

## Final Verification

- [ ] **Step 1: Run focused simulator tests**

```bash
./gradlew :swirlds-benchmarks:test --tests com.swirlds.benchmark.reconnect.network.SimulatedNetworkChannelTest
```

Expected: PASS.

- [ ] **Step 2: Compile benchmark code**

```bash
./gradlew :swirlds-benchmarks:compileJmhJava
```

Expected: PASS.

- [ ] **Step 3: Run style check for touched module**

```bash
./gradlew :swirlds-benchmarks:spotlessCheck
```

Expected: PASS.

- [ ] **Step 4: Check working tree**

```bash
git status --short
```

Expected: only pre-existing unrelated untracked files remain.
