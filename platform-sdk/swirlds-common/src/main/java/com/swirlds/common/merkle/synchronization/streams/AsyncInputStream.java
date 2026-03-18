// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.synchronization.streams;

import static com.swirlds.logging.legacy.LogMarker.RECONNECT;

import com.swirlds.common.merkle.synchronization.utility.MerkleSynchronizationException;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.io.SelfSerializable;
import org.hiero.base.io.streams.SerializableDataInputStream;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;
import org.hiero.consensus.reconnect.config.ReconnectConfig;

/**
 * <p>
 * Allows a thread to asynchronously read data from a SerializableDataInputStream.
 * </p>
 *
 * <p>
 * Only one type of message is allowed to be read using an instance of this class. Originally this class was capable of
 * supporting arbitrary message types, but there was a significant memory footprint optimization that was made possible
 * by switching to single message type.
 * </p>
 *
 * <p>
 * This object is not thread safe. Only one thread should attempt to read data from stream at any point in time.
 * </p>
 */
public class AsyncInputStream {

    private static final Logger logger = LogManager.getLogger(AsyncInputStream.class);

    private static final String THREAD_NAME = "async-input-stream";

    private final SerializableDataInputStream inputStream;

    private final Queue<byte[]> inputQueue = new ConcurrentLinkedQueue<>();

    // Checking queue size on every received message may be expensive. Instead, track the
    // size manually using an atomic
    private final AtomicInteger inputQueueSize = new AtomicInteger(0);

    /**
     * The maximum amount of time to wait when reading a message.
     */
    private final Duration pollTimeout;

    /**
     * Becomes 0 when the input thread is finished.
     */
    private final CountDownLatch finishedLatch;

    private final AtomicBoolean alive = new AtomicBoolean(true);

    private final StandardWorkGroup workGroup;

    private final int sharedQueueSizeThreshold;

    /**
     * Create a new async input stream.
     *
     * @param inputStream the base stream to read from
     * @param workGroup the work group that is managing this stream's thread
     * @param reconnectConfig the configuration to use
     */
    public AsyncInputStream(
            @NonNull final SerializableDataInputStream inputStream,
            @NonNull final StandardWorkGroup workGroup,
            @NonNull final ReconnectConfig reconnectConfig) {
        Objects.requireNonNull(reconnectConfig, "reconnectConfig must not be null");

        this.inputStream = Objects.requireNonNull(inputStream, "inputStream must not be null");
        this.workGroup = Objects.requireNonNull(workGroup, "workGroup must not be null");
        this.finishedLatch = new CountDownLatch(1);
        this.pollTimeout = reconnectConfig.asyncStreamTimeout();

        this.sharedQueueSizeThreshold = reconnectConfig.asyncStreamBufferSize();
    }

    /**
     * Start the thread that writes to the output stream.
     */
    public void start() {
        workGroup.execute(THREAD_NAME, this::run);
    }

    /**
     * This method is run on a background thread. Continuously reads things from the stream and puts them into the
     * queue.
     */
    private void run() {
        logger.debug(RECONNECT.getMarker(), Thread.currentThread().getName() + " run");
        try {
            while (!Thread.currentThread().isInterrupted()) {
                final int len = inputStream.readInt();
                if (len < 0) {
                    logger.info(RECONNECT.getMarker(), "Async input stream is done");
                    alive.set(false);
                    break;
                }
                final byte[] messageBytes = new byte[len];
                inputStream.readNBytes(messageBytes, 0, len);
                final boolean accepted = inputQueue.add(messageBytes);
                if (!accepted) {
                    throw new MerkleSynchronizationException(
                            "Timed out waiting to add message to received messages queue");
                }
                if (inputQueueSize.incrementAndGet() > sharedQueueSizeThreshold) {
                    // Slow down reading from the stream if handling threads can't keep up
                    while (inputQueueSize.get() > sharedQueueSizeThreshold) {
                        Thread.onSpinWait();
                    }
                }
            }
        } catch (final IOException e) {
            workGroup.handleError(e);
        } finally {
            finishedLatch.countDown();
        }
        logger.debug(RECONNECT.getMarker(), Thread.currentThread().getName() + " done");
    }

    public boolean isAlive() {
        return alive.get();
    }

    private <T extends SelfSerializable> T deserializeMessage(final byte[] messageBytes, final T message)
            throws IOException {
        try (final ByteArrayInputStream bin = new ByteArrayInputStream(messageBytes);
                final SerializableDataInputStream in = new SerializableDataInputStream(bin)) {
            message.deserialize(in, message.getVersion());
        }
        return message;
    }

    public <T extends SelfSerializable> T readAnticipatedMessage(@NonNull final Supplier<T> messageFactory)
            throws IOException {
        final byte[] itemBytes = inputQueue.poll();
        if (itemBytes != null) {
            inputQueueSize.decrementAndGet();
            return deserializeMessage(itemBytes, messageFactory.get());
        }
        return null;
    }

    public <T extends SelfSerializable> T readAnticipatedMessageSync(@NonNull final Supplier<T> messageFactory)
            throws IOException {
        T message = readAnticipatedMessage(messageFactory);
        if (message != null) {
            return message;
        }
        final long start = System.currentTimeMillis();
        final Thread currentThread = Thread.currentThread();
        while (true) {
            message = readAnticipatedMessage(messageFactory);
            if (message != null) {
                return message;
            }
            final long now = System.currentTimeMillis();
            if (currentThread.isInterrupted() || (now - start > pollTimeout.toMillis())) {
                break;
            }
        }
        throw new MerkleSynchronizationException("Timed out waiting for data");
    }

    /**
     * This method should be called when the reader decides to stop reading from the stream (for example, if the reader
     * encounters an exception). This method ensures that any resources used by the buffered messages are released.
     */
    public void abort() {
        alive.set(false);
        try {
            finishedLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
