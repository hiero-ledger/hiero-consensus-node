// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkle.test;

import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.common.merkle.synchronization.config.ReconnectConfig_;
import com.swirlds.common.merkle.synchronization.streams.AsyncInputStream;
import com.swirlds.common.merkle.synchronization.streams.AsyncOutputStream;
import com.swirlds.common.merkle.utility.SerializableLong;
import com.swirlds.common.test.fixtures.merkle.dummy.BlockingInputStream;
import com.swirlds.common.test.fixtures.merkle.dummy.BlockingOutputStream;
import com.swirlds.common.test.fixtures.merkle.util.PairedStreams;
import com.swirlds.common.threading.framework.config.ThreadConfiguration;
import com.swirlds.common.threading.pool.StandardWorkGroup;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.base.io.streams.SerializableDataOutputStream;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@DisplayName("Async Stream Test")
class AsyncStreamTest {
    private final Configuration configuration = new TestConfigBuilder()
            .withValue("reconnect.asyncOutputStream", 10000) // FUTURE: Looks like that property is not defined
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

            final AsyncInputStream<SerializableLong> in = new AsyncInputStream<>(
                    streams.getTeacherInput(), workGroup, SerializableLong::new, reconnectConfig);

            final AsyncOutputStream<SerializableLong> out =
                    new AsyncOutputStream<>(streams.getLearnerOutput(), workGroup, reconnectConfig);

            in.start();
            out.start();

            final int count = 100;

            for (int i = 0; i < count; i++) {
                out.sendAsync(new SerializableLong(i));
                in.anticipateMessage();
                final SerializableLong message = in.readAnticipatedMessage();
                assertEquals(i, message.getValue(), "message should match the value that was serialized");
            }

            in.close();
            out.close();
            workGroup.waitForTermination();
        }
    }

    @Test
    @Tag(TestComponentTags.RECONNECT)
    @DisplayName("Pre-Anticipation")
    void preAnticipation() throws IOException, InterruptedException {
        try (final PairedStreams streams = new PairedStreams()) {
            final StandardWorkGroup workGroup = new StandardWorkGroup(getStaticThreadManager(), "test", null);

            final AsyncInputStream<SerializableLong> in = new AsyncInputStream<>(
                    streams.getTeacherInput(), workGroup, SerializableLong::new, reconnectConfig);

            final AsyncOutputStream<SerializableLong> out =
                    new AsyncOutputStream<>(streams.getLearnerOutput(), workGroup, reconnectConfig);

            in.start();
            out.start();

            final int count = 100;

            for (int i = 0; i < count; i++) {
                in.anticipateMessage();
            }

            for (int i = 0; i < count; i++) {
                out.sendAsync(new SerializableLong(i));
                final SerializableLong message = in.readAnticipatedMessage();
                assertEquals(i, message.getValue(), "message should match the value that was serialized");
            }

            in.close();
            out.close();
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

        final AsyncOutputStream<SerializableLong> out =
                new AsyncOutputStream<>(new SerializableDataOutputStream(blockingOut), workGroup, reconnectConfig);

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
                            ex.printStackTrace();
                            break;
                        }
                    }
                })
                .setThreadName("output-thread")
                .build();
        outputThread.start();

        // Sender will send until the sender buffer is full.
        MILLISECONDS.sleep(100);

        // The buffer will fill up, and one message will be held by the sending thread (which is blocked)
        assertEquals(bufferSize + 1, messagesSent.get(), "incorrect message count");

        // Unblock the buffer, allowing remaining messages to be sent
        blockingOut.unlock();
        MILLISECONDS.sleep(100);

        assertEquals(count, messagesSent.get(), "all messages should have been sent");

        out.close();
        workGroup.waitForTermination();

        // Sanity check, make sure all the messages were written to the stream
        final byte[] bytes = byteOut.toByteArray();
        final SerializableDataInputStream in = new SerializableDataInputStream(new ByteArrayInputStream(bytes));
        for (int i = 0; i < count; i++) {
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
            new SerializableLong(i).serialize(out);
        }
        MILLISECONDS.sleep(100);
        final byte[] data = byteOut.toByteArray();

        final BlockingInputStream blockingIn = new BlockingInputStream(new ByteArrayInputStream(data));

        final AsyncInputStream<SerializableLong> in = new AsyncInputStream<>(
                new SerializableDataInputStream(blockingIn), workGroup, SerializableLong::new, reconnectConfig);
        in.start();

        for (int i = 0; i < count; i++) {
            in.anticipateMessage();
        }

        // Give the stream some time to accept as much data as it wants. Stream will stop accepting when queue fills up.
        MILLISECONDS.sleep(100);

        // Lock the stream, preventing further reads
        blockingIn.lock();

        final AtomicInteger messagesReceived = new AtomicInteger(0);
        final Thread inputThread = new ThreadConfiguration(getStaticThreadManager())
                .setRunnable(() -> {
                    for (int i = 0; i < count; i++) {
                        try {
                            assertEquals(i, in.readAnticipatedMessage().getValue(), "value does not match expected");
                            messagesReceived.getAndIncrement();
                        } catch (final InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            ex.printStackTrace();
                            break;
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
        MILLISECONDS.sleep(100);

        assertEquals(count, messagesReceived.get(), "all messages should be read");

        in.close();
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

            final AsyncOutputStream<ExplodingSelfSerializable> teacherOut =
                    new AsyncOutputStream<>(pairedStreams.getTeacherOutput(), workGroup, reconnectConfig);

            final AsyncInputStream<ExplodingSelfSerializable> learnerIn = new AsyncInputStream<>(
                    pairedStreams.getLearnerInput(), workGroup, ExplodingSelfSerializable::new, reconnectConfig);

            learnerIn.start();
            teacherOut.start();

            teacherOut.sendAsync(new ExplodingSelfSerializable());
            learnerIn.anticipateMessage();
            Thread.sleep(100);

            teacherOut.close();
            learnerIn.close();

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
            e.printStackTrace();
            fail("exception encountered", e);
        }
    }
}
