# Reuse Production Sync Streams in ReconnectBench Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the ReconnectBench socket transport honor `SocketConfig.gzipCompression()` by constructing its Java stream stack through the production `SyncInputStream` and `SyncOutputStream` factories.

**Architecture:** Keep the benchmark's real loopback sockets and optional read-side `PacingInputStream`. Pass each raw output and raw-or-paced input into the production sync-stream factories, then use the factories' connection byte counters for benchmark transfer statistics. Grant the benchmark module qualified access to the production sync package in the production module descriptor; do not add another Gradle `--add-exports` workaround.

**Tech Stack:** Java 25, JPMS, Gradle wrapper, JUnit 5, JMH benchmark support.

## Global Constraints

- Do not change production/runtime consensus-node behavior; the only approved production-scope edit is the qualified export in `platform-sdk/consensus-gossip-impl/src/main/java/module-info.java`.
- Preserve the existing `PacingInputStream` position directly above the raw socket input so pacing applies to wire bytes, including compressed bytes.
- Preserve byte statistics as bytes transferred through the socket-facing counting streams.
- Use the Gradle wrapper and run Gradle with sandbox escalation.
- Validate JPMS and published dependency metadata with `./gradlew checkAllModuleInfo validatePomFiles`.
- Do not create a commit unless the user requests one.

---

### Task 1: Add a compression regression test

**Files:**
- Modify: `platform-sdk/swirlds-benchmarks/src/test/java/com/swirlds/benchmark/reconnect/network/LoopbackSocketTransportTest.java`

**Interfaces:**
- Consumes: `LoopbackSocketTransport(SocketNetworkConfig, Configuration)` and `NetworkTransferStats.bytesWritten()`.
- Produces: a regression test proving that `socket.gzipCompression=true` changes wire behavior while preserving the payload.

- [x] **Step 1: Extend the test configuration helper**

Keep `configuration()` as the uncompressed default and add an overload that sets both socket properties explicitly:

```java
private static Configuration configuration() {
    return configuration(false);
}

private static Configuration configuration(final boolean gzipCompression) {
    return ConfigurationBuilder.create()
            .withSource(new SimpleConfigSource()
                    .withValue("socket.tcpNoDelay", true)
                    .withValue("socket.gzipCompression", gzipCompression))
            .withConfigDataType(SocketConfig.class)
            .withConfigDataType(GossipConfig.class)
            .build();
}
```

- [x] **Step 2: Write the failing behavior test**

Add a test using a zero-filled 64 KiB payload. Write and flush the framed payload, read and compare it, then assert that wire bytes written are smaller than the uncompressed payload:

```java
@Test
void gzipCompressionUsesCompressedWireBytes() throws Exception {
    final byte[] payload = new byte[64 * 1024];
    try (LoopbackSocketTransport transport =
            new LoopbackSocketTransport(loopbackConfig(), configuration(true))) {
        transport.getTeacherOutput().writeInt(payload.length);
        transport.getTeacherOutput().write(payload);
        transport.getTeacherOutput().flush();

        assertEquals(payload.length, transport.getLearnerInput().readInt());
        final byte[] received = new byte[payload.length];
        transport.getLearnerInput().readFully(received);
        assertArrayEquals(payload, received);

        assertTrue(
                transport.getTeacherToLearnerStats().bytesWritten() < payload.length,
                "compressible payload should use fewer wire bytes than its uncompressed size");
    }
}
```

- [x] **Step 3: Run the test and verify RED**

Run:

```shell
./gradlew :swirlds-benchmarks:test \
  --tests com.swirlds.benchmark.reconnect.network.LoopbackSocketTransportTest.gzipCompressionUsesCompressedWireBytes \
  --console=plain
```

Expected: the payload round-trips, but the wire-byte assertion fails because the current benchmark always uses `BufferedOutputStream` and reports at least 65,540 written bytes.

---

### Task 2: Reuse production sync-stream factories

**Files:**
- Modify: `platform-sdk/consensus-gossip-impl/src/main/java/module-info.java`
- Modify: `platform-sdk/swirlds-benchmarks/build.gradle.kts`
- Modify: `platform-sdk/swirlds-benchmarks/src/main/java/module-info.java`
- Modify: `platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/LoopbackSocketTransport.java`
- Delete: `platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/CountingInputStream.java`
- Delete: `platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/CountingOutputStream.java`

**Interfaces:**
- Consumes: `SyncInputStream.createSyncInputStream(Configuration, InputStream, int)`, `SyncOutputStream.createSyncOutputStream(Configuration, OutputStream, int)`, `SyncInputStream.byteCounter()`, and `SyncOutputStream.connectionByteCounter()`.
- Produces: the existing `DataInputStream`/`DataOutputStream` getter contract and `NetworkTransferStats` values backed by production wire counters.

- [x] **Step 1: Grant qualified JPMS access**

Add `com.swirlds.benchmarks` to the existing qualified export:

```java
exports org.hiero.consensus.gossip.impl.gossip.sync to
        com.swirlds.benchmarks,
        org.hiero.consensus.gossip.impl.test.fixtures,
        org.hiero.consensus.reconnect.impl;
```

Declare the benchmark main module's direct use of the sync streams' utility counter API:

```java
requires org.hiero.consensus.utility;
```

Keep source-set module declarations exact: add `org.hiero.consensus.gossip` to `jmhModuleInfo`, and remove the redundant `com.swirlds.config.api`, `com.swirlds.metrics.api`, `org.hiero.consensus.gossip`, and `org.hiero.consensus.gossip.impl` declarations from `testModuleInfo` as required by `checkAllModuleInfo`.

- [x] **Step 2: Replace benchmark-owned stream wrappers**

Import `SyncInputStream` and `SyncOutputStream`; remove the benchmark counting-stream fields, classes, and buffered-stream imports. Retain production sync-stream fields for wire counters and outer data-stream fields for the virtual-map synchronizers, matching production reconnect.

Construct outputs directly from raw socket outputs:

```java
teacherSyncOutput = SyncOutputStream.createSyncOutputStream(
        configuration, teacherSocket.getOutputStream(), socketConfig.bufferSize());
teacherOutput = new DataOutputStream(teacherSyncOutput);

learnerSyncOutput = SyncOutputStream.createSyncOutputStream(
        configuration, learnerSocket.getOutputStream(), socketConfig.bufferSize());
learnerOutput = new DataOutputStream(learnerSyncOutput);
```

Keep each optional pacer under the production input factory:

```java
final InputStream teacherToLearnerInput =
        teacherToLearnerPacer != null ? teacherToLearnerPacer : rawTeacherToLearner;
learnerSyncInput = SyncInputStream.createSyncInputStream(
        configuration, teacherToLearnerInput, socketConfig.bufferSize());
learnerInput = new DataInputStream(learnerSyncInput);
```

Apply the same construction to learner-to-teacher input.

- [x] **Step 3: Switch statistics to production counters**

```java
public NetworkTransferStats getTeacherToLearnerStats() {
    return new NetworkTransferStats(
            teacherSyncOutput.connectionByteCounter().getCount(),
            learnerSyncInput.byteCounter().getCount());
}

public NetworkTransferStats getLearnerToTeacherStats() {
    return new NetworkTransferStats(
            learnerSyncOutput.connectionByteCounter().getCount(),
            teacherSyncInput.byteCounter().getCount());
}
```

- [x] **Step 4: Run the compression test and verify GREEN**

Run the Task 1 command again.

Expected: PASS; the 64 KiB payload round-trips and the production output counter reports fewer than 64 KiB of wire data.

---

### Task 3: Verify transport behavior and repository metadata

**Files:**
- Verify all files modified in Tasks 1 and 2.
- Modify: `platform-sdk/swirlds-benchmarks/docs/ReconnectBench.md`
- Modify: `25083-improve-reconnectbench/Index.md`

**Interfaces:**
- Consumes: completed implementation and regression test.
- Produces: fresh evidence for transport correctness, benchmark compilation, formatting, JPMS consistency, and POM metadata consistency.

- [x] **Step 1: Run the complete transport test class**

```shell
./gradlew :swirlds-benchmarks:test \
  --tests com.swirlds.benchmark.reconnect.network.LoopbackSocketTransportTest \
  --console=plain
```

Expected: all transport tests pass.

- [x] **Step 2: Compile the JMH source set**

```shell
./gradlew :swirlds-benchmarks:compileJmhJava --console=plain
```

Expected: build successful.

- [x] **Step 3: Apply and verify formatting**

```shell
./gradlew :swirlds-benchmarks:spotlessApply --console=plain
./gradlew :swirlds-benchmarks:spotlessCheck --console=plain
```

Expected: build successful and no formatting violations.

- [x] **Step 4: Validate module descriptors and POM files**

```shell
./gradlew checkAllModuleInfo validatePomFiles --console=plain
```

Expected: build successful with all module-info and POM validation tasks passing.

- [x] **Step 5: Review the final diff and scope**

```shell
git diff --check
git diff -- \
  platform-sdk/consensus-gossip-impl/src/main/java/module-info.java \
  platform-sdk/swirlds-benchmarks/build.gradle.kts \
  platform-sdk/swirlds-benchmarks/src/main/java/module-info.java \
  platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/LoopbackSocketTransport.java \
  platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/CountingInputStream.java \
  platform-sdk/swirlds-benchmarks/src/main/java/com/swirlds/benchmark/reconnect/network/CountingOutputStream.java \
  platform-sdk/swirlds-benchmarks/src/test/java/com/swirlds/benchmark/reconnect/network/LoopbackSocketTransportTest.java \
  platform-sdk/swirlds-benchmarks/docs/ReconnectBench.md \
  25083-improve-reconnectbench/Index.md \
  25083-improve-reconnectbench/design-and-implementation/2026-07-16-reuse-production-sync-streams-implementation-plan.md
```

Expected: only the approved qualified export, benchmark module/source-set declarations, benchmark stream reuse, regression test, and this task-local implementation plan are present; no unrelated user files are changed.
