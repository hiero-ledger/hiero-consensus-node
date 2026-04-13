// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.synchronization.streams;

import static com.swirlds.logging.legacy.LogMarker.RECONNECT;

import com.swirlds.common.merkle.synchronization.utility.MerkleSynchronizationException;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.DataInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;
import org.hiero.consensus.reconnect.config.ReconnectConfig;

/**
 * <p>
 * Allows a thread to asynchronously read length-prefixed byte array messages from a stream.
 * </p>
 *
 * <p>
 * A background thread continuously reads messages from the underlying {@link DataInputStream}
 * and enqueues them as raw {@code byte[]} arrays. Consumers retrieve messages via
 * {@link #readAnticipatedMessage()} (non-blocking) or {@link #readAnticipatedMessageSync()}
 * (blocking with timeout). Callers are responsible for parsing the raw bytes into domain objects.
 * </p>
 *
 * <p>
 * This object is not thread safe. Only one thread should attempt to read messages at any point
 * in time.
 * </p>
 */
public class AsyncInputStream {

    private static final Logger logger = LogManager.getLogger(AsyncInputStream.class);

    private static final String THREAD_NAME = "async-input-stream";

    private final DataInputStream inputStream;

    private final Queue<byte[]> inputQueue = new ConcurrentLinkedQueue<>();

    // Checking queue size on every received message may be expensive. Instead, track the
    // size manually using an atomic
    private final AtomicInteger inputQueueSize = new AtomicInteger(0);

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
            @NonNull final DataInputStream inputStream,
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
     * Start the background thread that reads from the input stream and populates the internal queue.
     */
    public void start() {
        workGroup.execute(THREAD_NAME, this::run);
    }

    /**
     * Background thread loop. Continuously reads length-prefixed messages from the stream and
     * enqueues them. A negative length value serves as a termination marker.
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
                inputStream.readFully(messageBytes, 0, len);
                inputQueue.add(messageBytes);
                if (inputQueueSize.incrementAndGet() > sharedQueueSizeThreshold) {
                    while (inputQueueSize.get() > sharedQueueSizeThreshold
                            && !Thread.currentThread().isInterrupted()) {
                        Thread.onSpinWait();
                    }
                }
            }
        } catch (final IOException e) {
            logger.warn(RECONNECT.getMarker(), "Async input stream failed due to I/O error", e);
            workGroup.handleError(e);
        } finally {
            finishedLatch.countDown();
        }
        logger.debug(RECONNECT.getMarker(), Thread.currentThread().getName() + " done");
    }

    /**
     * Returns {@code true} if the background reader thread has not yet encountered the termination
     * marker or an error.
     *
     * @return whether the stream is still alive
     */
    public boolean isAlive() {
        return alive.get();
    }

    /**
     * Read the next raw message bytes from the queue (non-blocking).
     *
     * @return the message bytes, or {@code null} if no message is available
     */
    @Nullable
    public byte[] readAnticipatedMessage() {
        final byte[] itemBytes = inputQueue.poll();
        if (itemBytes != null) {
            inputQueueSize.decrementAndGet();
        }
        return itemBytes;
    }

    /**
     * Read the next raw message bytes from the queue, blocking until one is available or the
     * configured timeout expires.
     *
     * @return the message bytes, or {@code null} if the stream is no longer alive
     * @throws MerkleSynchronizationException if the operation times out
     */
    @Nullable
    public byte[] readAnticipatedMessageSync() {
        byte[] message = readAnticipatedMessage();
        if (message != null) {
            return message;
        }
        final long start = System.currentTimeMillis();
        final Thread currentThread = Thread.currentThread();
        while (true) {
            message = readAnticipatedMessage();
            if (message != null) {
                return message;
            }
            if (!isAlive()) {
                return null;
            }
            final long now = System.currentTimeMillis();
            if (currentThread.isInterrupted() || (now - start > pollTimeout.toMillis())) {
                break;
            }
        }
        if (currentThread.isInterrupted()) {
            throw new MerkleSynchronizationException("Interrupted while waiting for data");
        } else {
            throw new MerkleSynchronizationException("Timed out waiting for data");
        }
    }

    /**
     * Signals the background reader to stop and waits for it to finish. This method should be
     * called when the consumer decides to stop reading from the stream, for example after
     * encountering an exception.
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
