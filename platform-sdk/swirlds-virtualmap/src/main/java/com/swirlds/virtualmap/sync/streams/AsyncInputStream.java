// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.sync.streams;

import static com.swirlds.logging.legacy.LogMarker.RECONNECT;

import com.swirlds.virtualmap.sync.MerkleSynchronizationException;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;

/**
 * <p>
 * Allows a thread to asynchronously read length-prefixed byte array messages from a stream.
 * </p>
 *
 * <p>
 * A background thread continuously reads messages from the underlying {@link DataInputStream}
 * and enqueues them as raw {@code byte[]} arrays. Consumers retrieve messages via
 * {@link #readOrWait(YieldStrategy)}, which blocks (with the caller-chosen yield strategy)
 * until a message is available or the stream is permanently done. There is no timeout for readers,
 * because this class intended to be used with socket input stream, which should have a timeout.
 * </p>
 *
 * <p>
 * This object is thread safe. Multiple consumers may call {@link #readOrWait(YieldStrategy)} in parallel.
 * </p>
 *
 * <p>
 * Lifecycle is tracked by {@link Status}: {@link Status#NOT_STARTED} → {@link Status#RUNNING} (set by a successful
 * {@link #start(StandardWorkGroup)}) → {@link Status#DONE} (set by the background thread when it exits — normal EOF marker, I/O error,
 * or interrupt). Shutdown is driven by either an {@link IOException} (EOF marker, socket timeout, etc.) on the stream
 * or by an interrupt delivered through {@link StandardWorkGroup} (e.g. {@code handleError} → {@code shutdownNow}). An
 * I/O error is reported through the work group rather than the status, so {@link Status#DONE} does not distinguish
 * clean shutdown from a failure.
 * </p>
 */
public class AsyncInputStream {

    private static final Logger logger = LogManager.getLogger(AsyncInputStream.class);

    private static final String THREAD_NAME = "async-input-stream";

    // maximum message size in bytes - 8mb
    static final int MAX_MESSAGE_SIZE = 8 * 1024 * 1024;

    /** Lifecycle states of the background reader thread. Transitions are monotonic. */
    public enum Status {
        /** {@link #start(StandardWorkGroup)} has not been called yet. */
        NOT_STARTED,
        /** The background reader thread is running and may be enqueuing messages. */
        RUNNING,
        /** The background reader thread has exited (clean EOF, I/O error, or interrupt). */
        DONE
    }

    private final DataInputStream inputStream;

    private final Queue<byte[]> inputQueue = new ConcurrentLinkedQueue<>();

    // Tracking queue size with an atomic avoids the O(n) walk that
    // ConcurrentLinkedQueue.size() performs on every enqueue.
    private final AtomicInteger inputQueueSize = new AtomicInteger(0);

    // Single writer per transition: start() sets RUNNING atomically; the background
    // thread sets DONE on exit.
    private final AtomicReference<Status> status = new AtomicReference<>(Status.NOT_STARTED);

    private final int queueSizeThreshold;

    private final long timeoutNanos;

    /**
     * Create a new async input stream.
     *
     * @param inputStream           the base stream to read from
     * @param queueSizeThreshold    max size of the queue for reader thread backpressure
     * @param timeout               maximum time {@link #readOrWait(YieldStrategy)} will wait when the buffer is full;
     *                              must be non-null and positive
     */
    public AsyncInputStream(
            @NonNull final DataInputStream inputStream, final int queueSizeThreshold, @NonNull final Duration timeout) {
        this.inputStream = Objects.requireNonNull(inputStream, "inputStream must not be null");
        Objects.requireNonNull(timeout, "timeout must not be null");

        if (queueSizeThreshold <= 0) {
            throw new IllegalArgumentException("queueSizeThreshold must be greater than 0");
        }
        if (!timeout.isPositive()) {
            throw new IllegalArgumentException("timeout must be positive");
        }

        this.queueSizeThreshold = queueSizeThreshold;
        this.timeoutNanos = timeout.toNanos();
    }

    /**
     * Start the background thread that reads from the input stream and populates the internal queue.
     * This method can be called only once.
     *
     * @throws IllegalStateException if background thread is already started or terminated
     * @throws MerkleSynchronizationException if background thread cannot be submitted for execution
     */
    public void start(final @NonNull StandardWorkGroup workGroup) {
        Objects.requireNonNull(workGroup, "workGroup must not be null");

        if (!status.compareAndSet(Status.NOT_STARTED, Status.RUNNING)) {
            throw new IllegalStateException("Stream status has already been set: " + status.get());
        }

        try {
            workGroup.fork(THREAD_NAME, this::run);
        } catch (Exception e) {
            status.set(Status.DONE);
            throw e;
        }
    }

    /**
     * Background thread loop. Continuously reads length-prefixed messages from the stream and
     * enqueues them. A negative length value serves as a termination marker.
     */
    private void run() {
        logger.debug(RECONNECT.getMarker(), "Background reader thread started");

        try {
            while (!Thread.currentThread().isInterrupted()) {
                final int len = inputStream.readInt();
                if (len < 0) {
                    logger.info(RECONNECT.getMarker(), "Async input stream is done");
                    return;
                } else if (len > MAX_MESSAGE_SIZE) {
                    throw new MerkleSynchronizationException(
                            "Message size exceeds maximum size of " + MAX_MESSAGE_SIZE);
                }

                final byte[] messageBytes = new byte[len];
                inputStream.readFully(messageBytes, 0, len);
                inputQueue.add(messageBytes);

                if (inputQueueSize.incrementAndGet() >= queueSizeThreshold) {
                    while (inputQueueSize.get() >= queueSizeThreshold
                            && !Thread.currentThread().isInterrupted()) {
                        Thread.onSpinWait();
                    }
                }
            }
        } catch (final IOException e) {
            logger.warn(RECONNECT.getMarker(), "Async input stream failed due to I/O error", e);
            throw new UncheckedIOException(e);
        } finally {
            status.set(Status.DONE);
            logger.debug(RECONNECT.getMarker(), "Background reader thread stopped");
        }
    }

    /**
     * @return current lifecycle status of the background reader thread. Visible for tests.
     */
    Status getStatus() {
        return status.get();
    }

    /**
     * @return current input queue size
     */
    int getQueueSize() {
        return inputQueueSize.get();
    }

    /**
     * Non-blocking poll of the internal queue.
     *
     * @return the next message bytes, or {@code null} if the queue is empty
     */
    @Nullable
    private byte[] readOrNull() {
        final byte[] msg = inputQueue.poll();
        if (msg != null) {
            inputQueueSize.decrementAndGet();
        }
        return msg;
    }

    /**
     * Read the next raw message bytes from the queue, waiting with the given yield strategy until
     * a message is available, the background reader has finished and drained, or the calling
     * thread is interrupted.
     *
     * @param yield how the calling thread should wait between polls
     * @return the next message bytes, or {@code null} if the caller was interrupted or the stream is permanently done and no messages available in the queue
     * @throws MerkleSynchronizationException if timeout on waiting for the message when buffer is empty
     */
    @Nullable
    public byte[] readOrWait(@NonNull final YieldStrategy yield) {
        final long start = System.nanoTime();
        while (!Thread.currentThread().isInterrupted()) {
            final byte[] msg = readOrNull();
            if (msg != null) {
                return msg;
            }
            if (status.get() != Status.RUNNING) {
                // Drain race: the producer may have enqueued one final message
                // between our last poll and the background reader transitioning to done as it exits.
                return readOrNull();
            }
            yield.yield();
            if (System.nanoTime() - start > timeoutNanos) {
                throw new MerkleSynchronizationException("Timed out waiting for message from the input stream");
            }
        }
        return null;
    }
}
