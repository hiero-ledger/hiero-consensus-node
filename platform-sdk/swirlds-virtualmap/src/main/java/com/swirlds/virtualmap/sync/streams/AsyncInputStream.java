// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.sync.streams;

import static com.swirlds.logging.legacy.LogMarker.RECONNECT;

import com.swirlds.virtualmap.sync.MerkleSynchronizationException;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
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
 * {@link #start()}) → {@link Status#DONE} (set by the background thread when it exits — normal EOF marker, I/O error,
 * or interrupt). Shutdown is driven by either an {@link IOException} (EOF marker, socket timeout, etc.) on the stream
 * or by an interrupt delivered through {@link StandardWorkGroup} (e.g. {@code handleError} → {@code shutdownNow}). An
 * I/O error is reported through the work group rather than the status, so {@link Status#DONE} does not distinguish
 * clean shutdown from a failure — callers that care should consult {@link StandardWorkGroup#hasExceptions()}.
 * </p>
 */
public class AsyncInputStream {

    private static final Logger logger = LogManager.getLogger(AsyncInputStream.class);

    private static final String THREAD_NAME = "async-input-stream";

    // maximum message size in bytes - 8mb
    public static final int MAX_MESSAGE_SIZE = 8 * 1024 * 1024;

    /** Lifecycle states of the background reader thread. Transitions are monotonic. */
    public enum Status {
        /** {@link #start()} has not been called yet. */
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

    // Single writer per transition: start() sets RUNNING under sync; the background
    // thread sets DONE on exit. Volatile is sufficient for visibility.
    private volatile Status status = Status.NOT_STARTED;

    private final StandardWorkGroup workGroup;

    private final int queueSizeThreshold;

    /**
     * Create a new async input stream.
     *
     * @param inputStream the base stream to read from
     * @param workGroup the work group that is managing this stream's thread
     * @param queueSizeThreshold max size of the queue for reader thread backpressure
     */
    public AsyncInputStream(
            @NonNull final DataInputStream inputStream,
            @NonNull final StandardWorkGroup workGroup,
            final int queueSizeThreshold) {
        this.inputStream = Objects.requireNonNull(inputStream, "inputStream must not be null");
        this.workGroup = Objects.requireNonNull(workGroup, "workGroup must not be null");

        if (queueSizeThreshold <= 0) {
            throw new IllegalArgumentException("queueSizeThreshold must be greater than 0");
        }
        this.queueSizeThreshold = queueSizeThreshold;
    }

    /**
     * Start the background thread that reads from the input stream and populates the internal queue.
     * This method can be called only once.
     *
     * @throws IllegalStateException if background thread is already started or terminated
     * @throws MerkleSynchronizationException if background thread cannot be submitted for execution
     */
    public synchronized void start() {
        if (status != Status.NOT_STARTED) {
            throw new IllegalStateException("Stream status has already been set: " + status);
        }
        status = Status.RUNNING;

        try {
            workGroup.execute(THREAD_NAME, this::run);
        } catch (Exception e) {
            status = Status.DONE;
            workGroup.handleError(e); // terminate other tasks that already running
            throw new MerkleSynchronizationException("Background reading thread cannot be submitted for execution", e);
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
            workGroup.handleError(e);
        } finally {
            status = Status.DONE;
            logger.debug(RECONNECT.getMarker(), "Background reader thread stopped");
        }
    }

    /**
     * Returns {@code true} while the background reader thread is running.
     * Even if background reader is not alive, consumers may still try to read queued messages
     * until {@link #readOrWait(YieldStrategy)} doesn't return {@code null}.
     */
    public boolean isAlive() {
        return status == Status.RUNNING;
    }

    /**
     * @return current lifecycle status of the background reader thread. Visible for tests.
     */
    Status getStatus() {
        return status;
    }

    /**
     * @return current input queue size
     */
    int getInputQueueSize() {
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
     */
    @Nullable
    public byte[] readOrWait(@NonNull final YieldStrategy yield) {
        while (true) {
            final byte[] msg = readOrNull();
            if (msg != null) {
                return msg;
            }
            if (!isAlive()) {
                // Drain race: the producer may have enqueued one final message
                // between our last poll and flipping alive=false in its finally.
                return readOrNull();
            }
            if (Thread.currentThread().isInterrupted()) {
                return null;
            }
            yield.yield();
        }
    }
}
