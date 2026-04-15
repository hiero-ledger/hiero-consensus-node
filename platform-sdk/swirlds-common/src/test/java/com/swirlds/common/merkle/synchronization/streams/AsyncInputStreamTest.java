// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.synchronization.streams;

import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyEquals;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.common.test.fixtures.merkle.dummy.BlockingInputStream;
import com.swirlds.common.test.fixtures.merkle.dummy.BlockingOutputStream;
import com.swirlds.common.test.fixtures.merkle.util.PairedStreams;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.hiero.consensus.concurrent.framework.config.ThreadConfiguration;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;
import org.hiero.consensus.reconnect.config.ReconnectConfig;
import org.hiero.consensus.reconnect.config.ReconnectConfig_;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@DisplayName("Async Stream Test")
class AsyncInputStreamTest {

    private final Configuration configuration = new TestConfigBuilder()
            .withConfigDataType(ReconnectConfig.class)
            .withValue(ReconnectConfig_.ASYNC_STREAM_BUFFER_SIZE, 100)
            .withValue(ReconnectConfig_.ASYNC_OUTPUT_STREAM_FLUSH, "50ms")
            .getOrCreateConfig();
    private final ReconnectConfig reconnectConfig = configuration.getConfigData(ReconnectConfig.class);

    /** Serialize a long value into a byte array. */
    private static byte[] serializeLong(final long value) {
        final byte[] bytes = new byte[Long.BYTES];
        BufferedData.wrap(bytes).writeLong(value);
        return bytes;
    }

    /** Parse a long value from raw message bytes. */
    private static long parseLong(final byte[] bytes) {
        return BufferedData.wrap(bytes).readLong();
    }

    @Test
    @Tag(TestComponentTags.RECONNECT)
    @DisplayName("Basic Operation")
    void basicOperation() throws IOException, InterruptedException {
        try (final PairedStreams streams = new PairedStreams()) {
            final StandardWorkGroup workGroup = new StandardWorkGroup(getStaticThreadManager(), "test", null);

            final AsyncInputStream in = new AsyncInputStream(streams.getTeacherInput(), workGroup, reconnectConfig);
            final AsyncOutputStream out = new AsyncOutputStream(streams.getLearnerOutput(), workGroup, reconnectConfig);

            in.start();
            out.start();

            final int count = 100;

            for (int i = 0; i < count; i++) {
                out.sendAsync(serializeLong(i));
                final byte[] message = in.readAnticipatedMessageSync();
                assertNotNull(message);
                assertEquals(i, parseLong(message), "message should match the value that was serialized");
            }

            out.done();
            workGroup.waitForTermination();
        }
    }

    @Test
    @Tag(TestComponentTags.RECONNECT)
    @DisplayName("Pre-Anticipation")
    void preAnticipation() throws IOException, InterruptedException {
        try (final PairedStreams streams = new PairedStreams()) {
            final StandardWorkGroup workGroup = new StandardWorkGroup(getStaticThreadManager(), "test", null);

            final AsyncInputStream in = new AsyncInputStream(streams.getTeacherInput(), workGroup, reconnectConfig);
            final AsyncOutputStream out = new AsyncOutputStream(streams.getLearnerOutput(), workGroup, reconnectConfig);

            in.start();
            out.start();

            final int count = 100;

            for (int i = 0; i < count; i++) {
                out.sendAsync(serializeLong(i));
                final byte[] message = in.readAnticipatedMessageSync();
                assertNotNull(message);
                assertEquals(i, parseLong(message), "message should match the value that was serialized");
            }

            out.done();
            workGroup.waitForTermination();
        }
    }

    @Test
    @Tag(TestComponentTags.RECONNECT)
    @DisplayName("Max Output Queue Size")
    void maxOutputQueueSize() throws InterruptedException, IOException {

        final int bufferSize = 100;
        final int count = 1_000;

        final StandardWorkGroup workGroup = new StandardWorkGroup(getStaticThreadManager(), "test", null);

        final ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        final BlockingOutputStream blockingOut = new BlockingOutputStream(byteOut);

        // Block all bytes from this stream, data can only sit in async stream buffer
        blockingOut.lock();

        final AsyncOutputStream out =
                new AsyncOutputStream(new DataOutputStream(blockingOut), workGroup, reconnectConfig);

        out.start();

        final AtomicInteger messagesSent = new AtomicInteger(0);
        final Thread outputThread = new ThreadConfiguration(getStaticThreadManager())
                .setRunnable(() -> {
                    for (int i = 0; i < count; i++) {
                        try {
                            out.sendAsync(serializeLong(i));
                            messagesSent.getAndIncrement();
                        } catch (final InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            ex.printStackTrace(System.err);
                            break;
                        }
                    }
                })
                .setThreadName("output-thread")
                .build();
        outputThread.start();

        // Sender will send until the sender buffer is full.
        MILLISECONDS.sleep(100);

        final int messageCount = messagesSent.get();
        // The buffer will fill up, and one or more messages will be held by the sending thread (which is
        // blocked), up to double buffer size
        assertTrue(messageCount >= bufferSize + 1, "incorrect message count");
        assertTrue(messageCount <= 2 * bufferSize, "incorrect message count");

        // Unblock the buffer, allowing remaining messages to be sent
        blockingOut.unlock();

        assertEventuallyEquals(count, messagesSent::get, Duration.ofSeconds(5), "all messages should have been sent");

        out.done();

        workGroup.waitForTermination();

        // Sanity check, make sure all the messages were written to the stream
        final byte[] bytes = byteOut.toByteArray();
        final DataInputStream dataIn = new DataInputStream(new ByteArrayInputStream(bytes));
        for (int i = 0; i < count; i++) {
            assertEquals(Long.BYTES, dataIn.readInt(), "message length should be Long.BYTES");
            assertEquals(i, dataIn.readLong(), "deserialized value should match expected value");
        }
    }

    @Test
    @Tag(TestComponentTags.RECONNECT)
    @DisplayName("Max Input Queue Size")
    void maxInputQueueSize() throws IOException, InterruptedException {

        final int bufferSize = 100;
        final int count = 1_000;
        final StandardWorkGroup workGroup = new StandardWorkGroup(getStaticThreadManager(), "test", null);

        // Write messages in the async stream wire format: int32 length prefix + message bytes
        final ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        final DataOutputStream dataOut = new DataOutputStream(byteOut);
        for (int i = 0; i < count; i++) {
            dataOut.writeInt(Long.BYTES);
            dataOut.writeLong(i);
        }
        // Write the termination marker
        dataOut.writeInt(-1);
        dataOut.flush();
        MILLISECONDS.sleep(100);
        final byte[] data = byteOut.toByteArray();

        final BlockingInputStream blockingIn = new BlockingInputStream(new ByteArrayInputStream(data));

        final AsyncInputStream in = new AsyncInputStream(new DataInputStream(blockingIn), workGroup, reconnectConfig);
        in.start();

        // Give the stream some time to accept as much data as it wants. Stream will stop accepting when queue fills up.
        MILLISECONDS.sleep(100);

        // Lock the stream, preventing further reads
        blockingIn.lock();

        final AtomicInteger messagesReceived = new AtomicInteger(0);
        final Thread inputThread = new ThreadConfiguration(getStaticThreadManager())
                .setRunnable(() -> {
                    for (int i = 0; i < count; i++) {
                        final byte[] message = in.readAnticipatedMessageSync();
                        assertNotNull(message);
                        assertEquals(i, parseLong(message), "value does not match expected");
                        messagesReceived.getAndIncrement();
                    }
                })
                .setThreadName("output-thread")
                .build();
        inputThread.start();

        // Let the input thread read from the buffer
        MILLISECONDS.sleep(100);

        // The number of messages should equal the buffer size
        // plus one message that was blocked from entering the buffer
        assertEquals(bufferSize + 1, messagesReceived.get(), "incorrect number of messages received");

        // Unblock the stream, remainder of messages should be read
        blockingIn.unlock();

        assertEventuallyEquals(count, messagesReceived::get, Duration.ofSeconds(5), "all messages should be read");

        workGroup.waitForTermination();
    }

    /**
     * This test verifies that a bug that once existed in AsyncInputStream has been fixed. This bug could cause the
     * stream to deadlock during an abort.
     */
    @Test()
    @Tag(TestComponentTags.MERKLE)
    @DisplayName("AsyncInputStream Deadlock")
    void asyncInputStreamAbortDeadlock() throws InterruptedException {
        try (final PairedStreams pairedStreams = new PairedStreams()) {

            final StandardWorkGroup workGroup =
                    new StandardWorkGroup(getStaticThreadManager(), "input-stream-abort-deadlock", null);

            final AsyncOutputStream teacherOut =
                    new AsyncOutputStream(pairedStreams.getTeacherOutput(), workGroup, reconnectConfig);

            final AsyncInputStream learnerIn =
                    new AsyncInputStream(pairedStreams.getLearnerInput(), workGroup, reconnectConfig);

            final Runnable reader = () -> {
                try {
                    final byte[] bytes = learnerIn.readAnticipatedMessageSync();
                    // Force an error to simulate deserialization failure
                    throw new RuntimeException("Intentional deserialization failure");
                } catch (final Exception e) {
                    workGroup.handleError(e);
                }
            };
            workGroup.execute(reader);

            learnerIn.start();
            teacherOut.start();

            // Send a dummy message to trigger the reader
            teacherOut.sendAsync(serializeLong(42));
            Thread.sleep(100);

            workGroup.waitForTermination();
            assertTrue(workGroup.hasExceptions(), "work group is expected to have an exception");

            final Thread abortThread = new Thread(learnerIn::abort);

            abortThread.start();
            abortThread.join(1_000);
            if (abortThread.isAlive()) {
                abortThread.interrupt();
                fail("abort should have finished");
            }

            abortThread.join();

        } catch (final IOException e) {
            e.printStackTrace(System.err);
            fail("exception encountered", e);
        }
    }

    @Test
    @Tag(TestComponentTags.RECONNECT)
    @DisplayName("readAnticipatedMessage returns null when queue is empty")
    void readReturnsNullWhenEmpty() throws IOException {
        try (final PairedStreams streams = new PairedStreams()) {
            final StandardWorkGroup workGroup = new StandardWorkGroup(getStaticThreadManager(), "test", null);
            final AsyncInputStream in = new AsyncInputStream(streams.getTeacherInput(), workGroup, reconnectConfig);
            // Don't start the background reader — queue stays empty
            final byte[] msg = in.readAnticipatedMessage();
            assertNull(msg, "Should return null when no messages are queued");
        }
    }

    @Test
    @Tag(TestComponentTags.RECONNECT)
    @DisplayName("isAlive returns false after termination marker is received")
    void isAliveAfterTermination() throws IOException, InterruptedException {
        try (final PairedStreams streams = new PairedStreams()) {
            final StandardWorkGroup workGroup = new StandardWorkGroup(getStaticThreadManager(), "test", null);

            final AsyncInputStream in = new AsyncInputStream(streams.getTeacherInput(), workGroup, reconnectConfig);
            final AsyncOutputStream out = new AsyncOutputStream(streams.getLearnerOutput(), workGroup, reconnectConfig);

            in.start();
            out.start();

            out.sendAsync(serializeLong(1));
            out.done();

            workGroup.waitForTermination();

            // Drain the queue
            in.readAnticipatedMessage();

            assertTrue(!in.isAlive(), "Stream should not be alive after termination marker is received");
        }
    }

    @Test
    @Tag(TestComponentTags.RECONNECT)
    @DisplayName("Empty byte array message round-trips")
    void emptyMessage() throws IOException, InterruptedException {
        try (final PairedStreams streams = new PairedStreams()) {
            final StandardWorkGroup workGroup = new StandardWorkGroup(getStaticThreadManager(), "test", null);

            final AsyncInputStream in = new AsyncInputStream(streams.getTeacherInput(), workGroup, reconnectConfig);
            final AsyncOutputStream out = new AsyncOutputStream(streams.getLearnerOutput(), workGroup, reconnectConfig);

            in.start();
            out.start();

            out.sendAsync(new byte[0]);
            out.done();

            workGroup.waitForTermination();

            final byte[] result = in.readAnticipatedMessage();
            assertNotNull(result);
            assertEquals(0, result.length, "Empty message should have zero bytes");
        }
    }

    @Test
    @Tag(TestComponentTags.RECONNECT)
    @DisplayName("Messages of different sizes are correctly delimited")
    void parserReceivesBoundedData() throws IOException, InterruptedException {
        try (final PairedStreams streams = new PairedStreams()) {
            final StandardWorkGroup workGroup = new StandardWorkGroup(getStaticThreadManager(), "test", null);

            final AsyncInputStream in = new AsyncInputStream(streams.getTeacherInput(), workGroup, reconnectConfig);
            final AsyncOutputStream out = new AsyncOutputStream(streams.getLearnerOutput(), workGroup, reconnectConfig);

            in.start();
            out.start();

            // Send two messages of different sizes
            final byte[] small = new byte[] {1, 2, 3};
            final byte[] large = new byte[] {10, 20, 30, 40, 50};
            out.sendAsync(small);
            out.sendAsync(large);
            out.done();

            workGroup.waitForTermination();

            // First message should have exactly 3 bytes
            final byte[] read1 = in.readAnticipatedMessage();
            assertNotNull(read1);
            assertArrayEquals(small, read1);

            // Second message should have exactly 5 bytes
            final byte[] read2 = in.readAnticipatedMessage();
            assertNotNull(read2);
            assertArrayEquals(large, read2);
        }
    }
}
