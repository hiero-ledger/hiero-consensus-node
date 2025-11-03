// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus;

import static com.swirlds.platform.event.preconsensus.PcesFileChannelWriter.BUFFER_CAPACITY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

/**
 * Unit tests for {@link PcesFileChannelWriter}, specifically testing buffer capacity handling.
 */
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

    /**
     * Tests that a normal-sized event (smaller than buffer capacity) is written successfully.
     */
    @Test
    void testWriteNormalSizedEvent() throws IOException {
        final Randotron r = Randotron.create();
        final GossipEvent event = new TestingEventBuilder(r)
                .setAppTransactionCount(3)
                .setSelfParent(new TestingEventBuilder(r).build())
                .setOtherParent(new TestingEventBuilder(r).build())
                .build()
                .getGossipEvent();

        writer = new PcesFileChannelWriter(testFile);
        writer.writeVersion(1);

        final long bytesWritten = writer.writeEvent(event);

        assertTrue(bytesWritten > 0, "Event should be written with non-zero size");
        assertTrue(Files.exists(testFile), "File should exist after writing");
        assertTrue(writer.fileSize() > 0, "File size should be greater than zero");
    }

    /**
     * Tests that when an event exceeds the buffer capacity (10MB), the buffer is expanded
     * and the event is written successfully.
     * <p>
     * This test creates a very large event by adding a large payload that exceeds the
     * default buffer capacity of 10MB (10 * 1024 * 1024 bytes).
     */
    @Test
    void testWriteEventExceedingBufferCapacity() throws IOException {
        final Randotron r = Randotron.create();

        // Create a large payload that exceeds 10MB buffer capacity
        // 12MB = 12 * 1024 * 1024 bytes
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

    /**
     * Tests writing multiple events where one exceeds buffer capacity.
     * Ensures that after buffer expansion, subsequent normal-sized events can still be written.
     */
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

        final int largePayloadSize = BUFFER_CAPACITY + A_MEGA; // 11MB

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

    /**
     * Tests that the version is written correctly before events.
     */
    @Test
    void testWriteVersion() throws IOException {
        writer = new PcesFileChannelWriter(testFile);
        writer.writeVersion(42);

        assertEquals(4, writer.fileSize(), "Version should be 4 bytes (int)");
        assertTrue(Files.exists(testFile), "File should exist after writing version");
    }

    /**
     * Tests that fileSize() accurately tracks the cumulative size of all writes.
     */
    @Test
    void testFileSizeTracking() throws IOException {
        final Randotron r = Randotron.create();
        final GossipEvent event =
                new TestingEventBuilder(r).setAppTransactionCount(2).build().getGossipEvent();

        writer = new PcesFileChannelWriter(testFile);

        assertEquals(0, writer.fileSize(), "Initial file size should be 0");

        writer.writeVersion(1);
        assertEquals(4, writer.fileSize(), "File size should be 4 after writing version");

        final long previousSize = writer.fileSize();
        writer.writeEvent(event);
        assertTrue(writer.fileSize() > previousSize, "File size should increase after writing event");
    }

    /**
     * Tests that close() properly closes the file channel.
     */
    @Test
    void testClose() throws IOException {
        writer = new PcesFileChannelWriter(testFile);
        writer.writeVersion(1);
        writer.close();

        assertTrue(Files.exists(testFile), "File should exist after closing");
        // Writer is closed, so we set it to null to prevent double-close in tearDown
        writer = null;
    }

    /**
     * Tests that sync() forces data to disk without throwing exceptions.
     */
    @Test
    void testSync() throws IOException {
        final Randotron r = Randotron.create();
        final GossipEvent event =
                new TestingEventBuilder(r).setAppTransactionCount(1).build().getGossipEvent();

        writer = new PcesFileChannelWriter(testFile);
        writer.writeVersion(1);
        writer.writeEvent(event);

        // Sync should not throw
        writer.sync();

        assertNotNull(writer, "Writer should still be valid after sync");
    }

    /**
     * Tests that flush() can be called without errors (even though it's a no-op).
     */
    @Test
    void testFlush() throws IOException {
        writer = new PcesFileChannelWriter(testFile);
        writer.writeVersion(1);

        // Flush should not throw even though it does nothing
        writer.flush();

        assertNotNull(writer, "Writer should still be valid after flush");
    }
}
