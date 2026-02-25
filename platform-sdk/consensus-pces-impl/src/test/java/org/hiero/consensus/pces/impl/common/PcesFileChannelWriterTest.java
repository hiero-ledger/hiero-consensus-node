// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pces.impl.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.platform.event.GossipEvent;
import com.swirlds.base.time.Time;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.hiero.consensus.test.fixtures.Randotron;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Test for {@link PcesFileChannelWriter}
 */
class PcesFileChannelWriterTest {
    // A large payload size that exceeds writer's buffer capacity
    private static final int BUFFER_CAPACITY = 1024;
    private static final int LARGE_PAYLOAD_SIZE = 1024;
    private final Randotron random = Randotron.create();

    @TempDir
    private Path tempDir;

    private Path testFile;
    private PcesFileChannelWriter writer;

    @BeforeEach
    void before() {
        final var pcesFile = PcesFile.of(Time.getCurrent().now(), 0, 0, Long.MAX_VALUE, 0, tempDir)
                .getFileName();
        testFile = tempDir.resolve(pcesFile);
    }

    @AfterEach
    void after() throws IOException {
        if (writer != null) {
            writer.close();
        }
    }

    @Test
    void testWriteEventExceedingBufferCapacity() throws IOException {

        // Create an event with a very large payload
        final GossipEvent largeEvent = new TestingEventBuilder(random)
                .setAppTransactionCount(2)
                .setSelfParent(new TestingEventBuilder(random).build())
                .setOtherParent(new TestingEventBuilder(random).build())
                .setTransactionSize(LARGE_PAYLOAD_SIZE)
                .build()
                .getGossipEvent();

        // Verify the event is actually large
        final int eventSize = GossipEvent.PROTOBUF.measureRecord(largeEvent);
        assertTrue(
                eventSize > BUFFER_CAPACITY,
                "Event size should exceed 10MB buffer capacity. Actual size: " + eventSize);

        writer = new PcesFileChannelWriter(testFile, List.of(), BUFFER_CAPACITY);
        writer.writeVersion(2);

        // This should trigger buffer expansion since event exceeds default 10MB buffer
        final long bytesWritten = writer.writeEvent(largeEvent);

        assertTrue(bytesWritten > 0, "Large event should be written successfully");
        assertTrue(
                bytesWritten > BUFFER_CAPACITY, "Bytes written should exceed BUFFER_CAPACITY. Actual: " + bytesWritten);
        assertTrue(Files.exists(testFile), "File should exist after writing large event");
        assertTrue(writer.fileSize() > 0, "File size should be greater than zero");

        assertEquals(
                largeEvent, PcesUtilities.parseFile(testFile).iterator(0).next().getGossipEvent());
    }

    @Test
    void testWriteMultipleEventsWithOneExceedingCapacity() throws IOException {

        // Create normal-sized event
        final GossipEvent normalEvent = new TestingEventBuilder(random)
                .setAppTransactionCount(3)
                .setSelfParent(new TestingEventBuilder(random).build())
                .setOtherParent(new TestingEventBuilder(random).build())
                .build()
                .getGossipEvent();

        final GossipEvent largeEvent = new TestingEventBuilder(random)
                .setAppTransactionCount(1)
                .setTransactionSize(LARGE_PAYLOAD_SIZE)
                .build()
                .getGossipEvent();

        writer = new PcesFileChannelWriter(testFile);
        writer.writeVersion(2);

        // Write normal event first
        final long normalBytes1 = writer.writeEvent(normalEvent);
        assertTrue(normalBytes1 > 0, "First normal event should be written");

        // Write large event - triggers buffer expansion
        final long largeBytes = writer.writeEvent(largeEvent);
        assertTrue(largeBytes > BUFFER_CAPACITY, "Large event should be written and exceed BUFFER_CAPACITY");

        // Write another normal event after buffer expansion
        final long normalBytes2 = writer.writeEvent(normalEvent);
        assertTrue(normalBytes2 > 0, "Second normal event should be written after buffer expansion");

        // Verify file size accounts for all writes
        final long expectedMinSize = normalBytes1 + largeBytes + normalBytes2 + 4; // +4 for version int
        assertTrue(
                writer.fileSize() >= expectedMinSize,
                "File size should account for all events. Expected >= " + expectedMinSize + ", actual: "
                        + writer.fileSize());
        final PcesFileIterator iterator = PcesUtilities.parseFile(testFile).iterator(0);
        assertEquals(normalEvent, iterator.next().getGossipEvent());
        assertEquals(largeEvent, iterator.next().getGossipEvent());
        assertEquals(normalEvent, iterator.next().getGossipEvent());
    }
}
