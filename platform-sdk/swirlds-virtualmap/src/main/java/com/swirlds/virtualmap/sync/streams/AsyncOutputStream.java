// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.sync.streams;

import static com.swirlds.logging.legacy.LogMarker.RECONNECT;

import com.swirlds.virtualmap.sync.MerkleSynchronizationException;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.concurrent.jctools.queues.MpscArrayQueue;
import org.hiero.consensus.concurrent.pool.StandardWorkGroup;

/**
 * <p>
 * Allows producer threads to asynchronously send length-prefixed byte array messages over a stream.
 * </p>
 *
 * <p>
 * A background thread continuously dequeues messages from an internal bounded buffer and writes
 * length-prefixed frames to the underlying {@link DataOutputStream}. Producers enqueue messages with
 * {@link #sendAsync(byte[])} (which spin-waits up to {@code timeout} when the buffer is full) and signal
 * end-of-stream by calling {@link #done()}. After {@link #done()}, the background thread drains any
 * remaining queued messages, writes a {@code -1} termination marker, flushes, and exits.
 * </p>
 *
 * <p>
 * This object is thread safe. Multiple producers may call {@link #sendAsync(byte[])} in parallel and
 * messages enqueued by a single producer are written to the stream in submission order. Ordering
 * across producers is not guaranteed beyond what the underlying multi-producer/single-consumer queue
 * provides.
 * </p>
 *
 * <p>
 * The internal buffer is a lock-free {@link MpscArrayQueue}: the producer threads calling
 * {@link #sendAsync(byte[])} are the multiple producers, and the single background writer thread is the
 * only consumer. This invariant (exactly one poller) must be preserved or the queue corrupts silently.
 * </p>
 *
 * <p>
 * Lifecycle is tracked by {@link Status}: {@link Status#NOT_STARTED} → {@link Status#RUNNING} (set by
 * {@link #start(StandardWorkGroup)}) → {@link Status#FINISHING} (set by {@link #done()}, while the background thread
 * is still draining) → {@link Status#DONE} (set by the background thread when it exits — drain
 * complete or I/O error). {@link #done()} only signals; observers wait for actual termination via the
 * {@link StandardWorkGroup}. {@link #sendAsync(byte[])} only accepts new work while the status is
 * {@link Status#RUNNING}; any call during {@link Status#FINISHING} or after fails with
 * {@link IllegalStateException}. An I/O error is reported through the work group rather than the
 * status, so {@link Status#DONE} does not distinguish clean shutdown from a failure.
 * </p>
 */
public class AsyncOutputStream {

    private static final Logger logger = LogManager.getLogger(AsyncOutputStream.class);

    private static final String THREAD_NAME = "async-output-stream";

    /** Lifecycle states of the background writer thread. Transitions are monotonic. */
    public enum Status {
        /** {@link #start(StandardWorkGroup)} has not been called yet. */
        NOT_STARTED,
        /** The background writer thread is running and {@link #sendAsync(byte[])} accepts new work. */
        RUNNING,
        /** {@link #done()} has been called; the background thread is draining the queue before exit. */
        FINISHING,
        /** The background writer thread has exited (drain complete, I/O error, or interrupt). */
        DONE
    }

    private final DataOutputStream outputStream;

    /**
     * Bounded lock-free multi-producer/single-consumer buffer providing backpressure for
     * {@link #sendAsync(byte[])}. Only the single background writer thread may poll it.
     */
    private final MpscArrayQueue<byte[]> outputQueue;

    /** Maximum time the background thread waits for a new message before flushing buffered data. */
    private final Duration flushInterval;

    /** Maximum time {@link #sendAsync(byte[])} will wait when the buffer is full. */
    private final long timeoutNanos;

    // Single writer per transition: start() sets RUNNING atomically; done() sets FINISHING atomically
    // (guarded — no-op unless RUNNING); the background thread sets DONE on exit.
    private final AtomicReference<Status> status = new AtomicReference<>(Status.NOT_STARTED);

    // ---- Diagnostics (writer-thread only for the plain longs, so no synchronization needed) ----

    private final LongAdder bytesWritten = new LongAdder();

    private long pollNanos = 0; // time draining the queue (poll), writer thread only
    private long socketWriteNanos = 0; // time in writeMessage + flush (the socket path), writer thread only

    // Diagnostic: CPU time actually consumed by the writer thread. The thread is dedicated to run(),
    // so getCurrentThreadCpuTime() read at exit == CPU spent writing. If writerCpuMs << socketWriteMs,
    // the thread is blocked/descheduled inside write() (starved or back-pressured), NOT computing.
    private static final ThreadMXBean THREAD_MX = ManagementFactory.getThreadMXBean();
    private static final boolean CPU_TIME_OK = enableThreadCpuTime();

    private static boolean enableThreadCpuTime() {
        try {
            if (THREAD_MX.isThreadCpuTimeSupported() && !THREAD_MX.isThreadCpuTimeEnabled()) {
                THREAD_MX.setThreadCpuTimeEnabled(true);
            }
            return THREAD_MX.isCurrentThreadCpuTimeSupported() && THREAD_MX.isThreadCpuTimeEnabled();
        } catch (final Exception e) {
            return false;
        }
    }

    /**
     * Constructs a new instance.
     *
     * @param outputStream  the stream all serialized messages are written to
     * @param bufferSize    capacity of the internal queue; must be {@code > 0} (rounded up to a power of two)
     * @param flushInterval maximum time the background thread waits for a new message before flushing
     *                      buffered data; must be non-null and positive
     * @param timeout       maximum time {@link #sendAsync(byte[])} will wait when the buffer is full;
     *                      must be non-null and positive
     */
    public AsyncOutputStream(
            @NonNull final DataOutputStream outputStream,
            final int bufferSize,
            @NonNull final Duration flushInterval,
            @NonNull final Duration timeout) {
        this.outputStream = Objects.requireNonNull(outputStream, "outputStream must not be null");
        Objects.requireNonNull(flushInterval, "flushInterval must not be null");
        Objects.requireNonNull(timeout, "timeout must not be null");

        if (bufferSize <= 0) {
            throw new IllegalArgumentException("bufferSize must be greater than 0");
        }
        if (!flushInterval.isPositive()) {
            throw new IllegalArgumentException("flushInterval must be positive");
        }
        if (!timeout.isPositive()) {
            throw new IllegalArgumentException("timeout must be positive");
        }

        // Capacity is rounded up to a power of two by MpscArrayQueue.
        this.outputQueue = new MpscArrayQueue<>(bufferSize);
        this.flushInterval = flushInterval;
        this.timeoutNanos = timeout.toNanos();
    }

    /**
     * Start the background writer thread. This method can be called only once.
     *
     * @throws IllegalStateException if the stream has already been started or terminated
     * @throws RuntimeException if the background thread cannot be submitted for execution
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
     * Signal that no more messages will be sent. The background thread drains any remaining queued
     * messages, writes a {@code -1} termination marker, flushes, and exits. This method only signals;
     * observers wait for actual termination via {@link StandardWorkGroup#join()}.
     *
     * <p>Calls made before {@link #start(StandardWorkGroup)} or after the stream has reached {@link Status#FINISHING}
     * or {@link Status#DONE} are silent no-ops.
     */
    public void done() {
        status.compareAndSet(Status.RUNNING, Status.FINISHING);
    }

    /**
     * Send a pre-serialized message asynchronously. Messages from a single producer are written to
     * the underlying stream in submission order; ordering across producers is not guaranteed. When
     * the internal buffer is full this call spin-waits for up to {@code timeout} and then throws.
     *
     * @param messageBytes the serialized message bytes
     * @throws InterruptedException           if the caller is interrupted while spin-waiting to enqueue
     * @throws IllegalStateException          if the stream is not in {@link Status#RUNNING}
     * @throws MerkleSynchronizationException if the enqueue timed out because the buffer stayed full
     */
    public void sendAsync(@NonNull final byte[] messageBytes) throws InterruptedException {
        if (status.get() != Status.RUNNING) {
            throw new IllegalStateException("Stream is not running: " + status);
        }
        // MpscArrayQueue is bounded and non-blocking: offer() returns false immediately when full.
        // Spin-wait for room, preserving the back-pressure that the bounded queue provides (producers
        // wait for the single drain thread to catch up). Honor interrupts (so an aborting reconnect can
        // unblock a producer) and a deadline derived from the configured timeout (so a genuinely stuck
        // reconnect still fails).
        final long deadlineNanos = System.nanoTime() + timeoutNanos;
        while (!outputQueue.offer(messageBytes)) {
            if (Thread.interrupted()) {
                throw new InterruptedException("Interrupted while waiting to send data");
            }
            if (System.nanoTime() > deadlineNanos) {
                throw new MerkleSynchronizationException("Timed out waiting to send data");
            }
            Thread.onSpinWait();
        }
    }

    /**
     * @return current lifecycle status of the background writer thread. Visible for tests.
     */
    Status getStatus() {
        return status.get();
    }

    /**
     * @return current output queue size
     */
    int getQueueSize() {
        return outputQueue.size();
    }

    /**
     * Background thread loop. Non-blocking-polls the internal queue, writes each message as a
     * length-prefixed frame, and flushes when {@code flushInterval} has elapsed since the last flush
     * with buffered data pending. The wall-clock flush check is independent of the poll, so a
     * continuously-populated queue still gets periodic flushes. On shutdown, drains remaining
     * messages, writes a {@code -1} termination marker, and flushes.
     */
    private void run() {
        logger.debug(RECONNECT.getMarker(), "Background writer thread started");
        final long flushIntervalNanos = flushInterval.toNanos();
        long lastFlushNanos = System.nanoTime();
        boolean dirty = false;
        long writerBusyNanos = 0;
        long writerIdleNanos = 0;
        try {
            while (status.get() == Status.RUNNING && !Thread.currentThread().isInterrupted()) {
                final long t0 = System.nanoTime();
                final byte[] msg = outputQueue.poll();
                pollNanos += System.nanoTime() - t0;
                if (msg != null) {
                    final long w0 = System.nanoTime();
                    writeMessage(msg);
                    socketWriteNanos += System.nanoTime() - w0;
                    dirty = true;
                }
                if (dirty && (System.nanoTime() - lastFlushNanos) >= flushIntervalNanos) {
                    final long f0 = System.nanoTime();
                    outputStream.flush();
                    socketWriteNanos += System.nanoTime() - f0;
                    dirty = false;
                    lastFlushNanos = System.nanoTime();
                }
                if (msg == null) {
                    writerIdleNanos += System.nanoTime() - t0;
                    Thread.onSpinWait();
                } else {
                    writerBusyNanos += System.nanoTime() - t0;
                }
            }

            // Normal shutdown (done() → FINISHING). On interrupt (abort) we skip the drain/marker and
            // fall straight through to the finally, mirroring the previous behaviour.
            if (!Thread.currentThread().isInterrupted()) {
                byte[] msg;
                while ((msg = outputQueue.poll()) != null) {
                    final long w0 = System.nanoTime();
                    writeMessage(msg);
                    socketWriteNanos += System.nanoTime() - w0;
                }
                // Termination marker
                outputStream.writeInt(-1);
                outputStream.flush();
            }

            final long totalBytes = bytesWritten.sum();
            final long writerCpuNanos = CPU_TIME_OK ? THREAD_MX.getCurrentThreadCpuTime() : -1L;
            logger.info(
                    RECONNECT.getMarker(),
                    "AsyncOutputStream summary: bytesWritten={} writerBusyMs={} writerIdleMs={} pollMs={} "
                            + "socketWriteMs={} writerCpuMs={}",
                    totalBytes,
                    TimeUnit.NANOSECONDS.toMillis(writerBusyNanos),
                    TimeUnit.NANOSECONDS.toMillis(writerIdleNanos),
                    TimeUnit.NANOSECONDS.toMillis(pollNanos),
                    TimeUnit.NANOSECONDS.toMillis(socketWriteNanos),
                    writerCpuNanos < 0 ? -1L : TimeUnit.NANOSECONDS.toMillis(writerCpuNanos));
        } catch (final IOException e) {
            logger.warn(RECONNECT.getMarker(), "Async output stream failed due to I/O error", e);
            throw new UncheckedIOException(e);
        } finally {
            status.set(Status.DONE);
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
        bytesWritten.add(4L + messageBytes.length);
    }
}
