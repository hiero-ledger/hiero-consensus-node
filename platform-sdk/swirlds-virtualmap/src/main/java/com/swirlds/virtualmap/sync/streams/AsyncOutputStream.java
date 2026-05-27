// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.sync.streams;

import static com.swirlds.logging.legacy.LogMarker.RECONNECT;

import com.swirlds.virtualmap.sync.MerkleSynchronizationException;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;

/**
 * <p>
 * Allows producer threads to asynchronously send length-prefixed byte array messages over a stream.
 * </p>
 *
 * <p>
 * A background thread continuously dequeues messages from an internal bounded buffer and writes
 * length-prefixed frames to the underlying {@link DataOutputStream}. Producers enqueue messages with
 * {@link #sendAsync(byte[])} (which blocks up to {@code timeout} when the buffer is full) and signal
 * end-of-stream by calling {@link #done()}. After {@link #done()}, the background thread drains any
 * remaining queued messages, writes a {@code -1} termination marker, flushes, and exits.
 * </p>
 *
 * <p>
 * This object is thread safe. Multiple producers may call {@link #sendAsync(byte[])} in parallel and
 * messages enqueued by a single producer are written to the stream in submission order. Ordering
 * across producers is not guaranteed beyond what the underlying {@link BlockingQueue} provides.
 * </p>
 *
 * <p>
 * Lifecycle is tracked by {@link Status}: {@link Status#NOT_STARTED} → {@link Status#RUNNING} (set by
 * {@link #start()}) → {@link Status#FINISHING} (set by {@link #done()}, while the background thread
 * is still draining) → {@link Status#DONE} (set by the background thread when it exits — drain
 * complete or I/O error). {@link #done()} only signals; observers wait for actual termination via the
 * {@link StandardWorkGroup}. {@link #sendAsync(byte[])} only accepts new work while the status is
 * {@link Status#RUNNING}; any call during {@link Status#FINISHING} or after fails with
 * {@link IllegalStateException}. An I/O error is reported through the work group rather than the
 * status, so {@link Status#DONE} does not distinguish clean shutdown from a failure — callers that
 * care should consult {@link StandardWorkGroup#hasExceptions()}.
 * </p>
 */
public class AsyncOutputStream {

    private static final Logger logger = LogManager.getLogger(AsyncOutputStream.class);

    private static final String THREAD_NAME = "async-output-stream";

    /** Lifecycle states of the background writer thread. Transitions are monotonic. */
    public enum Status {
        /** {@link #start()} has not been called yet. */
        NOT_STARTED,
        /** The background writer thread is running and {@link #sendAsync(byte[])} accepts new work. */
        RUNNING,
        /** {@link #done()} has been called; the background thread is draining the queue before exit. */
        FINISHING,
        /** The background writer thread has exited (drain complete, I/O error, or interrupt). */
        DONE
    }

    private final DataOutputStream outputStream;

    /** Bounded buffer providing backpressure for {@link #sendAsync(byte[])}. */
    private final BlockingQueue<byte[]> streamQueue;

    /** Maximum time the background thread waits for a new message before flushing buffered data. */
    private final Duration flushInterval;

    /** Maximum time {@link #sendAsync(byte[])} will wait when the buffer is full. */
    private final Duration timeout;

    private final StandardWorkGroup workGroup;

    // Single writer per transition: start() sets RUNNING under sync; done() sets FINISHING under
    // sync (guarded — no-op unless RUNNING); the background thread sets DONE on exit.
    // Volatile is sufficient for visibility.
    private volatile Status status = Status.NOT_STARTED;

    /**
     * Constructs a new instance.
     *
     * @param outputStream  the stream all serialized messages are written to
     * @param workGroup     the work group that executes the background writer thread
     * @param bufferSize    capacity of the internal queue; must be {@code > 0}
     * @param flushInterval maximum time the background thread waits for a new message before flushing
     *                      buffered data; must be non-null and positive
     * @param timeout       maximum time {@link #sendAsync(byte[])} will wait when the buffer is full;
     *                      must be non-null and positive
     */
    public AsyncOutputStream(
            @NonNull final DataOutputStream outputStream,
            @NonNull final StandardWorkGroup workGroup,
            final int bufferSize,
            @NonNull final Duration flushInterval,
            @NonNull final Duration timeout) {
        this.outputStream = Objects.requireNonNull(outputStream, "outputStream must not be null");
        this.workGroup = Objects.requireNonNull(workGroup, "workGroup must not be null");
        Objects.requireNonNull(flushInterval, "flushInterval must not be null");
        Objects.requireNonNull(timeout, "timeout must not be null");

        if (bufferSize <= 0) {
            throw new IllegalArgumentException("bufferSize must be greater than 0");
        }
        if (flushInterval.isNegative() || flushInterval.isZero()) {
            throw new IllegalArgumentException("flushInterval must be positive");
        }
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }

        this.streamQueue = new LinkedBlockingQueue<>(bufferSize);
        this.flushInterval = flushInterval;
        this.timeout = timeout;
    }

    /**
     * Start the background writer thread. This method can be called only once.
     *
     * @throws IllegalStateException          if the stream has already been started or terminated
     * @throws MerkleSynchronizationException if the background thread cannot be submitted for execution
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
            throw new MerkleSynchronizationException("Background writing thread cannot be submitted for execution", e);
        }
    }

    /**
     * Signal that no more messages will be sent. The background thread drains any remaining queued
     * messages, writes a {@code -1} termination marker, flushes, and exits. This method only signals;
     * observers wait for actual termination via {@link StandardWorkGroup#waitForTermination()}.
     *
     * <p>Calls made before {@link #start()} or after the stream has reached {@link Status#FINISHING}
     * or {@link Status#DONE} are silent no-ops.
     */
    public synchronized void done() {
        if (status == Status.RUNNING) {
            status = Status.FINISHING;
        }
    }

    /**
     * Send a pre-serialized message asynchronously. Messages from a single producer are written to
     * the underlying stream in submission order; ordering across producers is not guaranteed. When
     * the internal buffer is full this call blocks for up to {@code timeout} and then throws.
     *
     * @param messageBytes the serialized message bytes
     * @throws InterruptedException           if the caller is interrupted while waiting to enqueue
     * @throws IllegalStateException          if the stream is not in {@link Status#RUNNING}
     * @throws MerkleSynchronizationException if the enqueue timed out because the buffer stayed full
     */
    public void sendAsync(@NonNull final byte[] messageBytes) throws InterruptedException {
        if (status != Status.RUNNING) {
            throw new IllegalStateException("Stream is not running: " + status);
        }
        final boolean success = streamQueue.offer(messageBytes, timeout.toNanos(), TimeUnit.NANOSECONDS);
        if (!success) {
            throw new MerkleSynchronizationException("Timed out waiting to send data");
        }
    }

    /**
     * Returns {@code true} while the background writer thread is running, which includes both
     * {@link Status#RUNNING} (accepting new work) and {@link Status#FINISHING} (draining).
     */
    public boolean isAlive() {
        return status == Status.RUNNING || status == Status.FINISHING;
    }

    /**
     * @return current lifecycle status of the background writer thread. Visible for tests.
     */
    Status getStatus() {
        return status;
    }

    /**
     * Background thread loop. Waits up to {@code flushInterval} for the next message, writes it as
     * a length-prefixed frame, and flushes when {@code flushInterval} has elapsed since the last
     * flush with buffered data pending. The wall-clock check is independent of the poll timeout, so
     * a continuously-populated queue still gets periodic flushes. On shutdown, drains remaining
     * messages, writes a {@code -1} termination marker, and flushes.
     */
    private void run() {
        logger.debug(RECONNECT.getMarker(), "Background writer thread started");
        final long flushIntervalNanos = flushInterval.toNanos();
        long lastFlushNanos = System.nanoTime();
        boolean dirty = false;
        try {
            while (status == Status.RUNNING && !Thread.currentThread().isInterrupted()) {
                final byte[] msg = streamQueue.poll(flushIntervalNanos, TimeUnit.NANOSECONDS);
                if (msg != null) {
                    writeMessage(msg);
                    dirty = true;
                }
                if (dirty && (System.nanoTime() - lastFlushNanos) >= flushIntervalNanos) {
                    outputStream.flush();
                    dirty = false;
                    lastFlushNanos = System.nanoTime();
                }
            }

            // Drain any remaining queued messages submitted before done() was called.
            byte[] msg;
            while ((msg = streamQueue.poll()) != null) {
                writeMessage(msg);
            }

            // Termination marker
            outputStream.writeInt(-1);
            outputStream.flush();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.debug(RECONNECT.getMarker(), "Background writer thread interrupted");
        } catch (final IOException e) {
            logger.warn(RECONNECT.getMarker(), "Async output stream failed due to I/O error", e);
            workGroup.handleError(e);
        } finally {
            status = Status.DONE;
            logger.debug(RECONNECT.getMarker(), "Background writer thread stopped");
        }
    }

    /**
     * Writes a single length-prefixed message to the underlying output stream. Called on the
     * <b>writer thread</b> for each dequeued message. Exposed as {@code protected} so test doubles
     * (e.g. simulated network latency) can override per-message behavior.
     *
     * @param messageBytes the serialized message bytes
     * @throws IOException if writing to the stream fails
     */
    protected void writeMessage(@NonNull final byte[] messageBytes) throws IOException {
        outputStream.writeInt(messageBytes.length);
        outputStream.write(messageBytes);
    }
}
