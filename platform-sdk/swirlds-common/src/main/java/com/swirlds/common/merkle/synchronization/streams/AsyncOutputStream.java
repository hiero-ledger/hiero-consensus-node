// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.synchronization.streams;

import static com.swirlds.logging.legacy.LogMarker.RECONNECT;

import com.swirlds.common.merkle.synchronization.utility.MerkleSynchronizationException;
import com.swirlds.common.utility.StopWatch;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;
import org.hiero.consensus.reconnect.config.ReconnectConfig;

/**
 * <p>
 * Allows a thread to asynchronously send data over a stream.
 * </p>
 *
 * <p>
 * Only one type of message is allowed to be sent using an instance of this class. Originally this class was capable of
 * supporting arbitrary message types, but there was a significant memory footprint optimization that was made possible
 * by switching to single message type.
 * </p>
 *
 * <p>
 * This object is not thread safe. Only one thread should attempt to send data over this stream at any point in time.
 * </p>
 */
public class AsyncOutputStream {

    private static final Logger logger = LogManager.getLogger(AsyncOutputStream.class);

    /**
     * The stream which all data is written to.
     */
    private final DataOutputStream outputStream;

    /**
     * A queue that needs to be written to the output stream. It contains either message
     * bytes (byte array) or some code to run (Runnable).
     */
    private final BlockingQueue<Object> streamQueue;

    /**
     * The time that has elapsed since the last flush was attempted.
     */
    private final StopWatch timeSinceLastFlush;

    /**
     * The maximum amount of time that is permitted to pass without a flush being attempted.
     */
    private final Duration flushInterval;

    /**
     * The number of messages that have been written to the stream but have not yet been flushed
     */
    private int bufferedMessageCount;

    /**
     * The maximum amount of time to wait when writing a message.
     */
    private final Duration timeout;

    private final StandardWorkGroup workGroup;

    /**
     * A condition to check whether it's time to terminate this output stream.
     */
    private final AtomicBoolean isDone = new AtomicBoolean(false);

    /**
     * Constructs a new instance using the given underlying {@link DataOutputStream} and
     * {@link StandardWorkGroup}.
     *
     * @param outputStream the outputStream to which all objects are written
     * @param workGroup    the work group that should be used to execute this thread
     * @param config       the reconnect configuration
     */
    public AsyncOutputStream(
            @NonNull final DataOutputStream outputStream,
            @NonNull final StandardWorkGroup workGroup,
            @NonNull final ReconnectConfig config) {
        Objects.requireNonNull(config, "config must not be null");

        this.outputStream = Objects.requireNonNull(outputStream, "outputStream must not be null");
        this.workGroup = Objects.requireNonNull(workGroup, "workGroup must not be null");
        this.streamQueue = new LinkedBlockingQueue<>(config.asyncStreamBufferSize());
        this.timeSinceLastFlush = new StopWatch();
        this.timeSinceLastFlush.start();
        this.flushInterval = config.asyncOutputStreamFlush();
        this.timeout = config.asyncStreamTimeout();
    }

    /**
     * Start the thread that writes to the stream.
     */
    public void start() {
        workGroup.execute("async-output-stream", this::run);
    }

    /**
     * Background thread loop. Drains the message queue, writes length-prefixed messages to the
     * underlying stream, and flushes periodically. On termination, writes a {@code -1} marker.
     */
    public void run() {
        logger.debug(RECONNECT.getMarker(), Thread.currentThread().getName() + " run");
        try {
            while ((!isDone.get() || !streamQueue.isEmpty())
                    && !Thread.currentThread().isInterrupted()) {
                flushIfRequired();
                boolean workDone = handleQueuedMessages();
                if (!workDone) {
                    workDone = flush();
                    if (!workDone) {
                        Thread.onSpinWait();
                    }
                }
            }
            // Handle remaining queued messages
            boolean wasNotEmpty = true;
            while (wasNotEmpty) {
                wasNotEmpty = handleQueuedMessages();
            }
            flush();
            try {
                logger.info(RECONNECT.getMarker(), Thread.currentThread().getName() + " closing stream");
                // Send reconnect termination marker
                outputStream.writeInt(-1);
                outputStream.flush();
            } catch (final IOException e) {
                throw new MerkleSynchronizationException(e);
            }
        } catch (final Exception e) {
            workGroup.handleError(e);
        }
        logger.debug(RECONNECT.getMarker(), Thread.currentThread().getName() + " done");
    }

    /**
     * Send a pre-serialized message asynchronously. Messages are guaranteed to be delivered
     * in the order sent.
     *
     * @param messageBytes the serialized message bytes
     * @throws InterruptedException if interrupted while waiting to enqueue
     */
    public void sendAsync(@NonNull final byte[] messageBytes) throws InterruptedException {
        enqueue(messageBytes);
    }

    /**
     * Schedule to run a given runnable, when all messages currently scheduled in this async
     * stream are serialized into the underlying output stream.
     */
    public void whenCurrentMessagesProcessed(final Runnable run) throws InterruptedException {
        enqueue(run);
    }

    /**
     * Enqueues an item into the stream queue. Blocks until space is available or the
     * configured timeout expires.
     *
     * @param item the item to enqueue
     * @throws InterruptedException if interrupted while waiting
     * @throws MerkleSynchronizationException if the operation times out
     */
    private void enqueue(final Object item) throws InterruptedException {
        final boolean success = streamQueue.offer(item, timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!success) {
            try {
                outputStream.close();
            } catch (final IOException e) {
                throw new MerkleSynchronizationException("Unable to close stream", e);
            }
            throw new MerkleSynchronizationException("Timed out waiting to send data");
        }
    }

    /**
     * Send the next message if possible.
     *
     * @return true if a message was sent.
     */
    private boolean handleQueuedMessages() {
        Object item = streamQueue.poll();
        if (item == null) {
            return false;
        }
        try {
            while (item != null) {
                switch (item) {
                    case Runnable runItem -> runItem.run();
                    case byte[] messageItem -> {
                        writeMessage(messageItem);
                        bufferedMessageCount += 1;
                    }
                    default -> throw new RuntimeException("Unknown item type");
                }
                item = streamQueue.poll();
            }
        } catch (final IOException e) {
            throw new MerkleSynchronizationException(e);
        }
        return true;
    }

    /**
     * Writes a single length-prefixed message to the underlying output stream. Called on
     * the writer thread for each dequeued message.
     *
     * @param messageBytes the serialized message bytes
     * @throws IOException if writing to the stream fails
     */
    protected void writeMessage(@NonNull final byte[] messageBytes) throws IOException {
        outputStream.writeInt(messageBytes.length);
        outputStream.write(messageBytes);
    }

    /**
     * Flushes the underlying output stream if any messages have been written since the last flush.
     *
     * @return {@code true} if a flush was performed
     */
    private boolean flush() {
        timeSinceLastFlush.reset();
        timeSinceLastFlush.start();
        if (bufferedMessageCount > 0) {
            try {
                outputStream.flush();
            } catch (final IOException e) {
                throw new MerkleSynchronizationException(e);
            }
            bufferedMessageCount = 0;
            return true;
        }
        return false;
    }

    /**
     * Flush the stream if necessary.
     */
    private void flushIfRequired() {
        if (timeSinceLastFlush.getElapsedTimeNano() > flushInterval.toNanos()) {
            flush();
        }
    }

    /**
     * Marks this async output stream as done. All messages currently enqueued are processed,
     * then the thread behind this object is exited. In the end, the stream sends a reconnect
     * termination marker to the underlying output stream.
     */
    public void done() {
        isDone.set(true);
    }
}
