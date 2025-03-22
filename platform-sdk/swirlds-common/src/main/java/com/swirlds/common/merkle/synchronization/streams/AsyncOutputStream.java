// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.synchronization.streams;

import static com.swirlds.logging.legacy.LogMarker.RECONNECT;

import com.swirlds.common.merkle.synchronization.config.ReconnectConfig;
import com.swirlds.common.merkle.synchronization.utility.MerkleSynchronizationException;
import com.swirlds.common.threading.pool.StandardWorkGroup;
import com.swirlds.common.utility.StopWatch;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.io.SelfSerializable;
import org.hiero.consensus.model.io.streams.SerializableDataOutputStream;

/**
 * <p>
 * Allows a thread to asynchronously send data over a SerializableDataOutputStream.
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
 *
 * @param <T> the type of the message to send
 */
public class AsyncOutputStream<T extends SelfSerializable> implements AutoCloseable {

    private static final Logger logger = LogManager.getLogger(AsyncOutputStream.class);

    /**
     * The stream which all data is written to.
     */
    private final SerializableDataOutputStream outputStream;

    /**
     * A queue of messages that need to be written to the output stream.
     */
    private final BlockingQueue<T> outgoingMessages;

    /**
     * The time that has elapsed since the last flush was attempted.
     */
    private final StopWatch timeSinceLastFlush;

    /**
     * The maximum amount of time that is permitted to pass without a flush being attempted.
     */
    private final Duration flushInterval;

    /**
     * If this becomes false then this object's worker thread will stop transmitting messages.
     */
    private volatile boolean alive;

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
     * Constructs a new instance using the given underlying {@link SerializableDataOutputStream} and
     * {@link StandardWorkGroup}.
     *
     * @param outputStream the outputStream to which all objects are written
     * @param workGroup    the work group that should be used to execute this thread
     * @param config       the reconnect configuration
     */
    public AsyncOutputStream(
            @NonNull final SerializableDataOutputStream outputStream,
            @NonNull final StandardWorkGroup workGroup,
            @NonNull final ReconnectConfig config) {
        Objects.requireNonNull(config, "config must not be null");

        this.outputStream = Objects.requireNonNull(outputStream, "outputStream must not be null");
        this.workGroup = Objects.requireNonNull(workGroup, "workGroup must not be null");
        this.outgoingMessages = new LinkedBlockingQueue<>(config.asyncStreamBufferSize());
        this.alive = true;
        this.timeSinceLastFlush = new StopWatch();
        this.timeSinceLastFlush.start();
        this.flushInterval = config.asyncOutputStreamFlush();
        this.timeout = config.asyncStreamTimeout();
    }

    /**
     * Start the thread that reads from the stream.
     */
    public void start() {
        workGroup.execute("async-output-stream", this::run);
    }

    /**
     * Returns true if the message pump is still running or false if the message pump has terminated or will terminate.
     *
     * @return true if the message pump is still running; false if the message pump has terminated or will terminate
     */
    public boolean isAlive() {
        return alive;
    }

    protected SerializableDataOutputStream getOutputStream() {
        return outputStream;
    }

    /**
     * This is exposed to allow test classes to simulate latency.
     */
    protected BlockingQueue<T> getOutgoingMessages() {
        return outgoingMessages;
    }

    public void run() {
        while ((isAlive() || !outgoingMessages.isEmpty())
                && !Thread.currentThread().isInterrupted()) {
            flushIfRequired();
            boolean workDone = handleNextMessage();
            if (!workDone) {
                workDone = flush();
                if (!workDone) {
                    try {
                        Thread.sleep(0, 1);
                    } catch (final InterruptedException e) {
                        logger.warn(RECONNECT.getMarker(), "AsyncOutputStream interrupted");
                        alive = false;
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
        flush();
    }

    /**
     * Send a message asynchronously. Messages are guaranteed to be delivered in the order sent.
     */
    public void sendAsync(final T message) throws InterruptedException {
        if (!isAlive()) {
            throw new MerkleSynchronizationException("Messages can not be sent after close has been called.");
        }

        final boolean success = outgoingMessages.offer(message, timeout.toMillis(), TimeUnit.MILLISECONDS);

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
     * Close this buffer and release resources. If there are still messages awaiting transmission then resources will
     * not be immediately freed.
     */
    @Override
    public void close() {
        alive = false;
    }

    /**
     * Send the next message if possible.
     *
     * @return true if a message was sent.
     */
    private boolean handleNextMessage() {
        if (!outgoingMessages.isEmpty()) {
            final T message = outgoingMessages.remove();
            try {
                serializeMessage(message);
            } catch (final IOException e) {
                throw new MerkleSynchronizationException(e);
            }

            bufferedMessageCount += 1;
            return true;
        }
        return false;
    }

    protected void serializeMessage(final T message) throws IOException {
        message.serialize(outputStream);
    }

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
}
