// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.synchronization.streams;

import static com.swirlds.common.test.fixtures.AssertionUtils.assertEventuallyEquals;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.swirlds.common.merkle.utility.SerializableLong;
import com.swirlds.common.test.fixtures.merkle.dummy.BlockingInputStream;
import com.swirlds.common.test.fixtures.merkle.dummy.BlockingOutputStream;
import com.swirlds.common.test.fixtures.merkle.util.PairedStreams;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;
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

    @Test
    @Tag(TestComponentTags.RECONNECT)
    @DisplayName("Basic Operation")
    void basicOperation() throws IOException, InterruptedException {
        try (final PairedStreams streams = new PairedStreams()) {
            final StandardWorkGroup workGroup = new StandardWorkGroup(getStaticThreadManager(), "test", null);

            final AsyncInputStream in = new AsyncInputStream(streams.getTeacherInput(), workGroup, reconnectConfig);

            final AsyncOutputStream out =
                    new AsyncOutputStream(streams.getLearnerOutput(), workGroup, in::isAlive, reconnectConfig);

            in.start();
            out.start();

            final int count = 100;

            for (int i = 0; i < count; i++) {
                out.sendAsync(new SerializableLong(i));
                final SerializableLong message = in.readAnticipatedMessageSync(SerializableLong::new);
                assertEquals(i, message.getValue(), "message should match the value that was serialized");
            }

            // Send reconnect completion marker
            streams.getLearnerOutput().writeInt(-1);
            streams.getLearnerOutput().flush();

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

            final AsyncOutputStream out =
                    new AsyncOutputStream(streams.getLearnerOutput(), workGroup, in::isAlive, reconnectConfig);

            in.start();
            out.start();

            final int count = 100;

            for (int i = 0; i < count; i++) {
                out.sendAsync(new SerializableLong(i));
                final SerializableLong message = in.readAnticipatedMessageSync(SerializableLong::new);
                assertEquals(i, message.getValue(), "message should match the value that was serialized");
            }

            // Send reconnect completion marker
            streams.getLearnerOutput().writeInt(-1);
            streams.getLearnerOutput().flush();

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

        final AtomicBoolean keepRunning = new AtomicBoolean(true);
        final AsyncOutputStream out = new AsyncOutputStream(
                new SerializableDataOutputStream(blockingOut), workGroup, keepRunning::get, reconnectConfig);

        out.start();

        final AtomicInteger messagesSent = new AtomicInteger(0);
        final Thread outputThread = new ThreadConfiguration(getStaticThreadManager())
                .setRunnable(() -> {
                    for (int i = 0; i < count; i++) {
                        try {
                            out.sendAsync(new SerializableLong(i));
                            messagesSent.getAndIncrement();
                        } catch (final InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            ex.printStackTrace(System.err);
                            break;
                        }
                    }
                    keepRunning.set(false);
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

        workGroup.waitForTermination();

        // Sanity check, make sure all the messages were written to the stream
        final byte[] bytes = byteOut.toByteArray();
        final SerializableDataInputStream in = new SerializableDataInputStream(new ByteArrayInputStream(bytes));
        for (int i = 0; i < count; i++) {
            assertEquals(Long.BYTES, in.readInt()); // SerializableLong length
            final SerializableLong value = new SerializableLong();
            value.deserialize(in, value.getVersion());
            assertEquals(i, value.getValue(), "deserialized value should match expected value");
        }
    }

    @Test
    @Tag(TestComponentTags.RECONNECT)
    @DisplayName("Max Input Queue Size")
    void maxInputQueueSize() throws IOException, InterruptedException {

        final int bufferSize = 100;
        final int count = 1_000;
        final StandardWorkGroup workGroup = new StandardWorkGroup(getStaticThreadManager(), "test", null);

        // Write a bunch of stuff into the stream
        final ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
        final SerializableDataOutputStream out = new SerializableDataOutputStream(byteOut);
        for (int i = 0; i < count; i++) {
            // This is the way that each object is written by the AsyncOutputStream, mimic that format
            out.writeInt(Long.BYTES); // size of SerializableLong
            new SerializableLong(i).serialize(out);
        }
        MILLISECONDS.sleep(100);
        final byte[] data = byteOut.toByteArray();

        final BlockingInputStream blockingIn = new BlockingInputStream(new ByteArrayInputStream(data));

        final AsyncInputStream in =
                new AsyncInputStream(new SerializableDataInputStream(blockingIn), workGroup, reconnectConfig);
        in.start();

        // Give the stream some time to accept as much data as it wants. Stream will stop accepting when queue fills up.
        MILLISECONDS.sleep(100);

        // Lock the stream, preventing further reads
        blockingIn.lock();

        final AtomicInteger messagesReceived = new AtomicInteger(0);
        final Thread inputThread = new ThreadConfiguration(getStaticThreadManager())
                .setRunnable(() -> {
                    for (int i = 0; i < count; i++) {
                        try {
                            final SerializableLong message = in.readAnticipatedMessageSync(SerializableLong::new);
                            assertEquals(i, message.getValue(), "value does not match expected");
                            messagesReceived.getAndIncrement();
                        } catch (final IOException ex) {
                            throw new UncheckedIOException(ex);
                        }
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

        // Send reconnect completion marker
        out.writeInt(-1);
        out.flush();

        workGroup.waitForTermination();
    }

    /**
     * This test verifies that a bug that once existed in AsyncInputStream has been fixed. This bug could case the
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
                    new AsyncOutputStream(pairedStreams.getTeacherOutput(), workGroup, () -> true, reconnectConfig);

            final AsyncInputStream learnerIn =
                    new AsyncInputStream(pairedStreams.getLearnerInput(), workGroup, reconnectConfig);

            final Runnable reader = () -> {
                try {
                    learnerIn.readAnticipatedMessageSync(ExplodingSelfSerializable::new);
                } catch (final Exception e) {
                    workGroup.handleError(e);
                }
            };
            workGroup.execute(reader);

            learnerIn.start();
            teacherOut.start();

            teacherOut.sendAsync(new ExplodingSelfSerializable());
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
}
