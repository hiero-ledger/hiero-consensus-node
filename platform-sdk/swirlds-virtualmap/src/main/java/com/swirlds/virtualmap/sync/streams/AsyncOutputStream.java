// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap.sync.streams;

import static com.swirlds.logging.legacy.LogMarker.RECONNECT;

import com.swirlds.virtualmap.sync.MerkleSynchronizationException;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.base.concurrent.jctools.queues.MpscVarHandleArrayQueue;
import org.hiero.base.concurrent.pool.StandardWorkGroup;

/// Allows producer threads to asynchronously send length-prefixed byte array messages over a stream.
///
/// A background thread continuously dequeues messages from an internal bounded buffer and writes
/// length-prefixed frames to the underlying [DataOutputStream]. Producers enqueue messages with
/// [#sendAsync(byte[])] (which blocks up to `timeout` when the buffer is full) and signal
/// end-of-stream by calling [#done()]. After [#done()], the background thread drains any
/// remaining queued messages, writes a `-1` termination marker, flushes, and exits.
///
/// This object is thread safe. Multiple producers may call [#sendAsync(byte[])] in parallel and
/// messages enqueued by a single producer are written to the stream in submission order. Ordering
/// across producers is not guaranteed beyond what the underlying multi-producer queue provides.
///
/// Lifecycle is tracked by [Status]: [Status#NOT_STARTED] → [Status#RUNNING] (set by
/// [#start(StandardWorkGroup)]) → [Status#FINISHING] (set by [#done()], while the background thread
/// is still draining) → [Status#DONE] (set by the background thread when it exits — drain
/// complete or I/O error). [#done()] only signals; observers wait for actual termination via the
/// [StandardWorkGroup]. [#sendAsync(byte[])] only accepts new work while the status is
/// [Status#RUNNING]; any call during [Status#FINISHING] or after fails with
/// [IllegalStateException]. An I/O error is reported through the work group rather than the
/// status, so [Status#DONE] does not distinguish clean shutdown from a failure.
public class AsyncOutputStream {

    private static final Logger logger = LogManager.getLogger(AsyncOutputStream.class);

    private static final String THREAD_NAME = "async-output-stream";

    /// Lifecycle states of the background writer thread. Transitions are monotonic.
    public enum Status {
        /// [#start(StandardWorkGroup)] has not been called yet.
        NOT_STARTED,
        /// The background writer thread is running and [#sendAsync(byte[])] accepts new work.
        RUNNING,
        /// [#done()] has been called; the background thread is draining the queue before exit.
        FINISHING,
        /// The background writer thread has exited (drain complete, I/O error, or interrupt).
        DONE
    }

    private final DataOutputStream outputStream;

    /// Bounded buffer providing backpressure for [#sendAsync(byte[])]. Multi-producer / single-consumer:
    /// any thread may `offer` (via [#sendAsync]); ONLY the background writer thread ([#run()])
    /// may call `poll`/`peek`/`relaxedPoll`/`drain`/`clear`/`iterator`.
    /// A second consumer silently corrupts the queue. `size()` is approximate: metrics only.
    /// Effective capacity is the next power of two `>= bufferSize`, because the lock-free ring buffer
    /// indexes slots with a bit mask. The default `asyncStreamBufferSize` of 10000 therefore gives 16384
    /// slots. That default has no strict derivation, so the larger capacity is accepted instead of
    /// enforcing the exact bound with `offerIfBelowThreshold`.
    private final MpscVarHandleArrayQueue<byte[]> outputQueue;

    // Backoff tuning. The values are heuristics, not derived from a model; OutputQueueBench (base-concurrent)
    // was used to check that they don't limit throughput.

    /// Spin iterations before parking in [#backOff(int, int)]. 128 [Thread#onSpinWait()] hints take a few
    /// microseconds, which covers the usual gap between two messages; parking costs a system call and a
    /// scheduler wake-up (tens of microseconds on Linux), so it is only worth it once the wait gets longer.
    private static final int SPIN_LIMIT = 128;
    /// Producers waiting for space: parks grow 1µs, 2µs, ... up to `1µs << 7` (128µs). A slot frees up
    /// as soon as the writer takes one message, so producers must notice quickly; 128µs keeps that delay
    /// small while 16 waiting producers don't burn CPU. With this cap the slow-consumer benchmark still runs
    /// the writer at its full rate.
    private static final int PRODUCER_MAX_PARK_SHIFT = 7;
    /// Idle writer: parks grow up to `1µs << 10` (about 1ms). It must stay well below `flushInterval`
    /// (8ms by default), the delay already accepted for buffered data, so waking up to 1ms late adds little
    /// latency; going lower would only add idle wake-ups that burn CPU for nothing.
    private static final int WRITER_MAX_PARK_SHIFT = 10;

    /// Maximum time buffered data waits for a flush while messages keep arriving. When the queue runs dry the
    /// writer flushes right away, before it starts parking.
    private final Duration flushInterval;

    /// Maximum time [#sendAsync(byte[])] will wait when the buffer is full.
    private final long timeoutNanos;

    // Single writer per transition: start() sets RUNNING atomically; done() sets FINISHING atomically
    // (guarded — no-op unless RUNNING); the background thread sets DONE on exit.
    private final AtomicReference<Status> status = new AtomicReference<>(Status.NOT_STARTED);

    /// Constructs a new instance.
    ///
    /// @param outputStream  the stream all serialized messages are written to
    /// @param bufferSize    capacity of the internal queue; must be `> 0`. The effective capacity is
    ///                      rounded up to the next power of two
    /// @param flushInterval maximum time the background thread waits for a new message before flushing
    ///                      buffered data; must be non-null and positive
    /// @param timeout       maximum time [#sendAsync(byte[])] will wait when the buffer is full;
    ///                      must be non-null and positive
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

        this.outputQueue = new MpscVarHandleArrayQueue<>(bufferSize);
        this.flushInterval = flushInterval;
        this.timeoutNanos = timeout.toNanos();
    }

    /// Start the background writer thread. This method can be called only once.
    ///
    /// @throws IllegalStateException if the stream has already been started or terminated
    /// @throws RuntimeException if the background thread cannot be submitted for execution
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

    /// Signal that no more messages will be sent. The background thread drains any remaining queued
    /// messages, writes a `-1` termination marker, flushes, and exits. This method only signals;
    /// observers wait for actual termination via [StandardWorkGroup#join()].
    ///
    /// Calls made before [#start(StandardWorkGroup)] or after the stream has reached [Status#FINISHING]
    /// or [Status#DONE] are silent no-ops.
    public void done() {
        status.compareAndSet(Status.RUNNING, Status.FINISHING);
    }

    /// Send a pre-serialized message asynchronously. Messages from a single producer are written to
    /// the underlying stream in submission order; ordering across producers is not guaranteed. When
    /// the internal buffer is full this call blocks for up to `timeout` and then throws.
    ///
    /// @param messageBytes the serialized message bytes
    /// @throws InterruptedException           if the caller is interrupted while waiting to enqueue
    /// @throws IllegalStateException          if the stream is not in [Status#RUNNING], either on entry or
    ///                                        while waiting for space because the writer has stopped
    /// @throws MerkleSynchronizationException if the enqueue timed out because the buffer stayed full
    public void sendAsync(@NonNull final byte[] messageBytes) throws InterruptedException {
        if (status.get() != Status.RUNNING) {
            throw new IllegalStateException("Stream is not running: " + status);
        }
        // LinkedBlockingQueue.offer(e, timeout, unit) throws on entry if already interrupted; keep that.
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
        if (outputQueue.offer(messageBytes)) {
            return;
        }
        final long deadlineNanos = System.nanoTime() + timeoutNanos;
        int idle = 0;
        while (!outputQueue.offer(messageBytes)) {
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            // The writer is the only consumer: once it stops (I/O error, interrupt, or done()), no slot
            // will ever be freed, so fail now instead of waiting for the timeout.
            final Status current = status.get();
            if (current != Status.RUNNING) {
                throw new IllegalStateException("Stream stopped while waiting to send data: " + current);
            }
            if (System.nanoTime() - deadlineNanos >= 0) {
                throw new MerkleSynchronizationException("Timed out waiting to send data");
            }
            idle = backOff(idle, PRODUCER_MAX_PARK_SHIFT);
        }
    }

    /// @return current lifecycle status of the background writer thread. Visible for tests.
    Status getStatus() {
        return status.get();
    }

    /// @return current output queue size
    int getQueueSize() {
        return outputQueue.size();
    }

    /// Background thread loop. Polls the queue without blocking (backing off while nothing is available) and
    /// writes each message as a length-prefixed frame. Buffered data is flushed in two cases: when the queue
    /// runs dry (nothing arrived during the spin phase of the backoff, so the burst is over), and when
    /// `flushInterval` has elapsed since the last flush. The second is a wall-clock check independent of
    /// polling, so a continuously-populated queue still gets periodic flushes. On shutdown, drains remaining
    /// messages, writes a `-1` termination marker, and flushes. If interrupted, returns without draining and
    /// without the terminator.
    private void run() {
        logger.debug(RECONNECT.getMarker(), "Background writer thread started");
        final long flushIntervalNanos = flushInterval.toNanos();
        long lastFlushNanos = System.nanoTime();
        boolean dirty = false;
        int idle = 0;
        try {
            while (status.get() == Status.RUNNING && !Thread.currentThread().isInterrupted()) {
                // relaxedPoll(): returns null instead of spinning when a producer has claimed a slot but not yet
                // published its message; the loop simply retries, and the final drain below uses poll().
                final byte[] msg = outputQueue.relaxedPoll();
                if (msg != null) {
                    writeMessage(msg);
                    dirty = true;
                    idle = 0;
                } else {
                    if (dirty && idle >= SPIN_LIMIT) {
                        // Nothing arrived while spinning: the burst is over, so send the buffered data now
                        // instead of letting it wait up to flushInterval. Bursts still get batched.
                        outputStream.flush();
                        dirty = false;
                        lastFlushNanos = System.nanoTime();
                    }
                    idle = backOff(idle, WRITER_MAX_PARK_SHIFT);
                }
                if (dirty && (System.nanoTime() - lastFlushNanos) >= flushIntervalNanos) {
                    outputStream.flush();
                    dirty = false;
                    lastFlushNanos = System.nanoTime();
                }
            }

            if (Thread.currentThread().isInterrupted()) {
                // Aborting: no drain, no terminator, so the peer doesn't see a graceful end. Flag stays set.
                logger.debug(RECONNECT.getMarker(), "Background writer thread interrupted");
                return;
            }

            // Drain any remaining queued messages submitted before done() was called.
            // poll(), not relaxedPoll(): returns null only when the queue is truly empty.
            byte[] msg;
            while ((msg = outputQueue.poll()) != null) {
                writeMessage(msg);
            }

            // Termination marker
            outputStream.writeInt(-1);
            outputStream.flush();
        } catch (final IOException e) {
            logger.warn(RECONNECT.getMarker(), "Async output stream failed due to I/O error", e);
            throw new UncheckedIOException(e);
        } finally {
            status.set(Status.DONE);
            logger.debug(RECONNECT.getMarker(), "Background writer thread stopped");
        }
    }

    /// Writes a single length-prefixed message to the underlying output stream. Called on the
    /// **writer thread** for each dequeued message. Exposed as `protected` so test doubles
    /// (e.g. simulated network latency) can override per-message behavior.
    ///
    /// @param messageBytes the serialized message bytes
    /// @throws IOException if writing to the stream fails
    protected void writeMessage(@NonNull final byte[] messageBytes) throws IOException {
        outputStream.writeInt(messageBytes.length);
        outputStream.write(messageBytes);
    }

    /// Progressive backoff: [#SPIN_LIMIT] spin hints, then parks of growing length, capped at
    /// `1µs << maxParkShift`.
    /// [LockSupport#parkNanos] returns immediately if the thread is interrupted, so callers
    /// observe interrupts on their next check.
    private static int backOff(final int idle, final int maxParkShift) {
        if (idle < SPIN_LIMIT) {
            // first SPIN_LIMIT calls: stay on the CPU, the next message is likely microseconds away
            Thread.onSpinWait();
        } else {
            // after that, park for 1µs << n, where n counts the parks so far: 1µs, 2µs, 4µs, ...
            // capped at 1µs << maxParkShift
            LockSupport.parkNanos(1_000L << Math.min(idle - SPIN_LIMIT, maxParkShift));
        }
        // the caller passes the result back in on the next call. Capping it keeps the park length at its
        // maximum and stops the counter from growing (and eventually overflowing) during a long wait
        return Math.min(idle + 1, SPIN_LIMIT + maxParkShift);
    }
}
