// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus;

import static com.swirlds.platform.event.preconsensus.PcesFileChannelWriter.BUFFER_CAPACITY;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.platform.event.GossipEvent;
import com.swirlds.common.test.fixtures.Randotron;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PcesFileChannelWriterTest {

    public static final int A_MEGA = 1024 * 1024;

    @TempDir
    private Path tempDir;

    private Path testFile;
    private PcesFileChannelWriter writer;

    @BeforeEach
    void setUp() {
        testFile = tempDir.resolve("test-events.pces");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (writer != null) {
            writer.close();
        }
    }

    @Test
    void testWriteEventExceedingBufferCapacity() throws IOException {
        final Randotron r = Randotron.create();

        // Create a large payload that exceeds buffer capacity
        final int largePayloadSize = BUFFER_CAPACITY + 2 * A_MEGA;

        // Create an event with a very large payload
        final GossipEvent largeEvent = new TestingEventBuilder(r)
                .setAppTransactionCount(2)
                .setSelfParent(new TestingEventBuilder(r).build())
                .setOtherParent(new TestingEventBuilder(r).build())
                .setTransactionSize(largePayloadSize)
                .build()
                .getGossipEvent();

        // Verify the event is actually large
        final int eventSize = GossipEvent.PROTOBUF.measureRecord(largeEvent);
        assertTrue(
                eventSize > BUFFER_CAPACITY,
                "Event size should exceed 10MB buffer capacity. Actual size: " + eventSize);

        writer = new PcesFileChannelWriter(testFile);
        writer.writeVersion(1);

        // This should trigger buffer expansion since event exceeds default 10MB buffer
        final long bytesWritten = writer.writeEvent(largeEvent);

        assertTrue(bytesWritten > 0, "Large event should be written successfully");
        assertTrue(bytesWritten > BUFFER_CAPACITY, "Bytes written should exceed 10MB. Actual: " + bytesWritten);
        assertTrue(Files.exists(testFile), "File should exist after writing large event");
        assertTrue(writer.fileSize() > 0, "File size should be greater than zero");
    }

    @Test
    void testWriteMultipleEventsWithOneExceedingCapacity() throws IOException {
        final Randotron r = Randotron.create();

        // Create normal-sized event
        final GossipEvent normalEvent = new TestingEventBuilder(r)
                .setAppTransactionCount(3)
                .setSelfParent(new TestingEventBuilder(r).build())
                .setOtherParent(new TestingEventBuilder(r).build())
                .build()
                .getGossipEvent();

        final int largePayloadSize = BUFFER_CAPACITY + A_MEGA;

        final GossipEvent largeEvent = new TestingEventBuilder(r)
                .setAppTransactionCount(1)
                .setTransactionSize(largePayloadSize)
                .build()
                .getGossipEvent();

        writer = new PcesFileChannelWriter(testFile);
        writer.writeVersion(1);

        // Write normal event first
        final long normalBytes1 = writer.writeEvent(normalEvent);
        assertTrue(normalBytes1 > 0, "First normal event should be written");

        // Write large event - triggers buffer expansion
        final long largeBytes = writer.writeEvent(largeEvent);
        assertTrue(largeBytes > BUFFER_CAPACITY, "Large event should be written and exceed 10MB");

        // Write another normal event after buffer expansion
        final long normalBytes2 = writer.writeEvent(normalEvent);
        assertTrue(normalBytes2 > 0, "Second normal event should be written after buffer expansion");

        // Verify file size accounts for all writes
        final long expectedMinSize = normalBytes1 + largeBytes + normalBytes2 + 4; // +4 for version int
        assertTrue(
                writer.fileSize() >= expectedMinSize,
                "File size should account for all events. Expected >= " + expectedMinSize + ", actual: "
                        + writer.fileSize());
    }
}
