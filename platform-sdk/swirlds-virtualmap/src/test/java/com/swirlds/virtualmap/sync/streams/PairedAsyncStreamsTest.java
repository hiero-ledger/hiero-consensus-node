// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.sync.streams;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hiero.base.utility.test.fixtures.assertions.AssertionUtils.assertEventuallyTrue;
import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.hedera.pbj.runtime.io.buffer.BufferedData;
import com.swirlds.virtualmap.sync.MerkleSynchronizationException;
import com.swirlds.virtualmap.test.fixtures.sync.PairedStreams;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests that exercise {@link AsyncInputStream} and {@link AsyncOutputStream} together
 * over a real loopback socket via {@link PairedStreams}. The individual unit tests for each class
 * already cover constructor validation, framing, backpressure, flushing, error propagation, and
 * ordering in isolation, so this suite focuses on scenarios that can only be observed when both
 * background threads are alive on opposite ends of a real socket: a basic round-trip,
 * asymmetric socket teardown from each side, and producer/consumer speed mismatch across the pair.
 */
@Tag(TestComponentTags.RECONNECT)
@DisplayName("Paired AsyncInputStream / AsyncOutputStream Test")
class PairedAsyncStreamsTest {

    private static final int DEFAULT_QUEUE_SIZE = 100;
    private static final Duration DEFAULT_FLUSH_INTERVAL = Duration.ofMillis(50);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    @Test
    @DisplayName("Basic Operation: messages round-trip through the socket and done() terminates cleanly")
    void basicOperation() throws IOException, InterruptedException {
        try (final PairedStreams streams = new PairedStreams()) {
            final StandardWorkGroup workGroup = new StandardWorkGroup(getStaticThreadManager(), "basic", null);

            final AsyncInputStream in = new AsyncInputStream(streams.getTeacherInput(), workGroup, DEFAULT_QUEUE_SIZE);
            final AsyncOutputStream out = new AsyncOutputStream(
                    streams.getLearnerOutput(), workGroup, DEFAULT_QUEUE_SIZE, DEFAULT_FLUSH_INTERVAL, DEFAULT_TIMEOUT);

            in.start();
            out.start();

            final int count = 100;
            for (int i = 0; i < count; i++) {
                out.sendAsync(serializeLong(i));
                final byte[] message = in.readOrWait(YieldStrategy.SPIN);
                assertNotNull(message);
                assertEquals(i, parseLong(message), "message should match the value that was serialized");
            }

            out.done();
            workGroup.waitForTermination();
        }
    }

    @Test
    @DisplayName("Teacher disconnect mid-stream propagates error to work group")
    void teacherDisconnectMidStream() throws IOException, InterruptedException {
        final PairedStreams streams = new PairedStreams();
        try {
            final StandardWorkGroup workGroup =
                    new StandardWorkGroup(getStaticThreadManager(), "teacher-disconnect", null);

            final AsyncInputStream in = new AsyncInputStream(streams.getLearnerInput(), workGroup, DEFAULT_QUEUE_SIZE);
            final AsyncOutputStream out = new AsyncOutputStream(
                    streams.getTeacherOutput(), workGroup, DEFAULT_QUEUE_SIZE, DEFAULT_FLUSH_INTERVAL, DEFAULT_TIMEOUT);

            in.start();
            out.start();

            // Send one message so the writer flushes through the socket, then pull the plug
            // on the teacher side. The learner-side reader will see EOF/SocketException.
            out.sendAsync(serializeLong(1));
            MILLISECONDS.sleep(50);
            streams.disconnectTeacher();

            assertEventuallyTrue(
                    workGroup::hasExceptions,
                    Duration.ofSeconds(5),
                    "Work group should record an exception after teacher disconnect");
        } finally {
            closeIgnoringIoException(streams);
        }
    }

    @Test
    @DisplayName("Learner disconnect mid-stream propagates error to work group")
    void learnerDisconnectMidStream() throws IOException {
        final PairedStreams streams = new PairedStreams();
        try {
            final StandardWorkGroup workGroup =
                    new StandardWorkGroup(getStaticThreadManager(), "learner-disconnect", null);

            final AsyncInputStream in = new AsyncInputStream(streams.getLearnerInput(), workGroup, DEFAULT_QUEUE_SIZE);
            final AsyncOutputStream out = new AsyncOutputStream(
                    streams.getTeacherOutput(), workGroup, DEFAULT_QUEUE_SIZE, DEFAULT_FLUSH_INTERVAL, DEFAULT_TIMEOUT);

            in.start();
            out.start();

            // Close the learner socket so the teacher's write/flush fails with an IOException.
            // Keep pumping messages on a background thread until the work group records the error
            // — the OS may buffer the first few writes before reporting the broken pipe.
            streams.disconnectLearner();

            final Thread sender = new Thread(() -> {
                for (int i = 0; i < 10_000 && !workGroup.hasExceptions(); i++) {
                    try {
                        out.sendAsync(serializeLong(i));
                    } catch (final InterruptedException
                            | MerkleSynchronizationException
                            | IllegalStateException ignored) {
                        break;
                    }
                }
            });
            sender.setDaemon(true);
            sender.start();

            assertEventuallyTrue(
                    workGroup::hasExceptions,
                    Duration.ofSeconds(5),
                    "Work group should record an exception after learner disconnect");
        } finally {
            closeIgnoringIoException(streams);
        }
    }

    @Test
    @DisplayName("Producer outpaces a slow consumer: all messages arrive in order, done() terminates")
    void slowConsumerBackpressuresProducer() throws IOException, InterruptedException {
        try (final PairedStreams streams = new PairedStreams()) {
            final StandardWorkGroup workGroup = new StandardWorkGroup(getStaticThreadManager(), "slow-consumer", null);

            // Small queue so the producer saturates quickly. Generous sendAsync timeout so
            // backpressure blocks the producer instead of throwing while the consumer catches up.
            final int bufferSize = 8;
            final int count = 1000;
            final AsyncInputStream in = new AsyncInputStream(streams.getTeacherInput(), workGroup, bufferSize);
            final AsyncOutputStream out = new AsyncOutputStream(
                    streams.getLearnerOutput(), workGroup, bufferSize, DEFAULT_FLUSH_INTERVAL, Duration.ofSeconds(30));

            in.start();
            out.start();

            final AtomicInteger nextExpected = new AtomicInteger(0);
            final Thread consumer = new Thread(() -> {
                for (int i = 0; i < count; i++) {
                    final byte[] msg = in.readOrWait(YieldStrategy.SLEEP);
                    if (msg == null) {
                        return;
                    }
                    if (parseLong(msg) != i) {
                        throw new AssertionError("Out of order at index " + i + ", got " + parseLong(msg));
                    }
                    nextExpected.set(i + 1);
                    try {
                        Thread.sleep(1); // slow the consumer to force the producer to wait
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            });
            consumer.setDaemon(true);
            consumer.start();

            // Producer runs on the test thread; sendAsync will block when the queue saturates.
            for (int i = 0; i < count; i++) {
                out.sendAsync(serializeLong(i));
            }

            out.done();
            consumer.join(Duration.ofSeconds(30).toMillis());
            workGroup.waitForTermination();

            assertEquals(count, nextExpected.get(), "All messages should have been received in order");
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static byte[] serializeLong(final long value) {
        final byte[] bytes = new byte[Long.BYTES];
        BufferedData.wrap(bytes).writeLong(value);
        return bytes;
    }

    private static long parseLong(final byte[] bytes) {
        return BufferedData.wrap(bytes).readLong();
    }

    /**
     * After an intentional asymmetric disconnect, {@link PairedStreams#close()} can throw because
     * flushing a buffered stream over a closed socket fails. Swallow that expected failure during
     * cleanup.
     */
    private static void closeIgnoringIoException(final PairedStreams streams) {
        try {
            streams.close();
        } catch (final IOException ignored) {
            // expected after disconnectTeacher() / disconnectLearner()
        }
    }
}
