// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect.network;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Coordinates one refined-A1 socket direction without storing payload bytes or granting sender credit.
 *
 * <p>The output observer publishes compact ordered range metadata before each bounded raw write. The opposite input
 * gate asks this controller how much of that ordered prefix is eligible, reads no more than the returned allowance,
 * and then reports the actual byte count consumed. The real socket remains the only payload store and the only
 * capacity/backpressure authority.
 */
final class SocketVisibilityController {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long MAX_SAFE_DURATION_NANOS = Long.MAX_VALUE / 4;
    private static final int DEFAULT_MAX_PENDING_RANGES = 65_536;
    private static final long DEFAULT_MAX_PENDING_BYTES = 1L << 30;

    /** Receives a connection-wide failure after the controller lock has been released. */
    @FunctionalInterface
    interface AbortHandler {
        void abort(IOException failure);
    }

    /** Monotonic clock seam used by deterministic schedule tests. */
    @FunctionalInterface
    interface NanoClock {
        long nanoTime();
    }

    /** Condition-wait seam used by interruption and fake-clock tests. */
    @FunctionalInterface
    interface ConditionAwaiter {
        long awaitNanos(Condition condition, long nanos) throws InterruptedException;
    }

    /** Metadata for a bounded range published immediately before the corresponding raw socket write. */
    record Reservation(
            long startOffset,
            int byteCount,
            long observedAtNanos,
            long serializationStartNanos,
            long serializationEndNanos,
            long serializationDurationNanos,
            long targetSerializationDurationNanos) {}

    /** Maximum prefix that the gate may consume in one raw read. */
    record ReadAllowance(int byteCount, long eligibleAtNanos) {}

    private static final class PendingRange {
        private final Reservation reservation;
        private final long eligibleAtNanos;
        private int consumed;
        private boolean releaseRecorded;
        private boolean firstReturnRecorded;

        private PendingRange(final Reservation reservation, final long eligibleAtNanos) {
            this.reservation = reservation;
            this.eligibleAtNanos = eligibleAtNanos;
        }

        private int remaining() {
            return reservation.byteCount() - consumed;
        }
    }

    /** Fixed-memory base-two histogram; percentiles are conservative bucket upper bounds. */
    private static final class LogHistogram {
        private final long[] buckets = new long[65];
        private long count;
        private long maximum;

        private void record(final long value) {
            final long nonNegative = Math.max(0, value);
            final int bucket = nonNegative == 0 ? 0 : 64 - Long.numberOfLeadingZeros(nonNegative);
            buckets[bucket]++;
            count++;
            maximum = Math.max(maximum, nonNegative);
        }

        private long percentileUpperBound(final double percentile) {
            if (count == 0) {
                return 0;
            }
            final long target = Math.max(1, (long) Math.ceil(count * percentile));
            long cumulative = 0;
            for (int index = 0; index < buckets.length; index++) {
                cumulative += buckets[index];
                if (cumulative >= target) {
                    if (index == 0) {
                        return 0;
                    }
                    if (index == 64) {
                        return maximum;
                    }
                    return Math.min(maximum, (1L << index) - 1);
                }
            }
            return maximum;
        }

        private long count() {
            return count;
        }
    }

    private final long configuredLatencyNanos;
    private final long configuredBandwidthBytesPerSecond;
    private final long latencyNanos;
    private final long bandwidthBytesPerSecond;
    private final long releaseQuantumNanos;
    private final int maxRangeBytes;
    private final int maxPendingRanges;
    private final long maxPendingBytesLimit;
    private final NanoClock clock;
    private final ConditionAwaiter conditionAwaiter;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final ArrayDeque<PendingRange> pending = new ArrayDeque<>();

    private long nextOffset;
    private long pendingBytes;
    private long serializationCursorNanos;
    private long serializationRemainder;
    private boolean serializationCursorInitialized;
    private boolean cleanup;
    private IOException failure;

    private long observedBytes;
    private long scheduledBytes;
    private long returnedBytes;
    private long rangeCount;
    private int maxPendingRangeCount;
    private long maxPendingByteCount;
    private long maxSerializationBacklogBytes;
    private long maxSerializationBacklogNanos;
    private long timingWakeCount;
    private long metadataWaitNanos;
    private long latencyWaitNanos;
    private long bandwidthWaitNanos;
    private long releasesOverQuarterLatency;
    private long observerToFirstReturnSampleCount;
    private long rawReadCount;
    private long rawReadWaitNanos;
    private long maxRawReadWaitNanos;
    private long failedRawReads;
    private long rawWriteCount;
    private long rawWriteDurationNanos;
    private long maxRawWriteDurationNanos;
    private long rawWriteBytesOverQuarterLatency;
    private long rawWriteBytesOverSerializationDuration;
    private long rawWriteBytesOverEitherTarget;
    private long failedRawWrites;

    private final LogHistogram rangeSizes = new LogHistogram();
    private final LogHistogram releaseLateness = new LogHistogram();
    private final LogHistogram observerToFirstReturn = new LogHistogram();
    private final LogHistogram rawReadSizes = new LogHistogram();
    private final LogHistogram rawWriteDurations = new LogHistogram();

    SocketVisibilityController(final SocketNetworkConfig config) {
        this(
                config.configuredLatencyNanos(),
                config.configuredBandwidthBytesPerSecond(),
                config.modeledLatencyNanos(),
                config.modeledBandwidthBytesPerSecond(),
                config.releaseQuantumNanos(),
                config.maxObservedRangeBytes(),
                DEFAULT_MAX_PENDING_RANGES,
                DEFAULT_MAX_PENDING_BYTES,
                System::nanoTime,
                Condition::awaitNanos);
    }

    SocketVisibilityController(
            final long latencyNanos,
            final long bandwidthBytesPerSecond,
            final long releaseQuantumNanos,
            final int maxRangeBytes,
            final int maxPendingRanges,
            final long maxPendingBytes,
            final NanoClock clock,
            final ConditionAwaiter conditionAwaiter) {
        this(
                latencyNanos,
                bandwidthBytesPerSecond,
                latencyNanos,
                bandwidthBytesPerSecond,
                releaseQuantumNanos,
                maxRangeBytes,
                maxPendingRanges,
                maxPendingBytes,
                clock,
                conditionAwaiter);
    }

    private SocketVisibilityController(
            final long configuredLatencyNanos,
            final long configuredBandwidthBytesPerSecond,
            final long latencyNanos,
            final long bandwidthBytesPerSecond,
            final long releaseQuantumNanos,
            final int maxRangeBytes,
            final int maxPendingRanges,
            final long maxPendingBytes,
            final NanoClock clock,
            final ConditionAwaiter conditionAwaiter) {
        if (configuredLatencyNanos < 0 || configuredLatencyNanos > MAX_SAFE_DURATION_NANOS) {
            throw new IllegalArgumentException("configuredLatencyNanos is outside the safe nanoTime difference range");
        }
        if (configuredBandwidthBytesPerSecond <= 0) {
            throw new IllegalArgumentException("configuredBandwidthBytesPerSecond must be positive");
        }
        if (latencyNanos < 0 || latencyNanos > MAX_SAFE_DURATION_NANOS) {
            throw new IllegalArgumentException("latencyNanos is outside the safe nanoTime difference range");
        }
        if (bandwidthBytesPerSecond <= 0) {
            throw new IllegalArgumentException("bandwidthBytesPerSecond must be positive");
        }
        if (releaseQuantumNanos <= 0 || releaseQuantumNanos > MAX_SAFE_DURATION_NANOS) {
            throw new IllegalArgumentException("releaseQuantumNanos must be positive and safely comparable");
        }
        if (maxRangeBytes <= 0) {
            throw new IllegalArgumentException("maxRangeBytes must be positive");
        }
        if (maxPendingRanges <= 0 || maxPendingBytes <= 0) {
            throw new IllegalArgumentException("metadata high-water limits must be positive");
        }
        this.configuredLatencyNanos = configuredLatencyNanos;
        this.configuredBandwidthBytesPerSecond = configuredBandwidthBytesPerSecond;
        this.latencyNanos = latencyNanos;
        this.bandwidthBytesPerSecond = bandwidthBytesPerSecond;
        this.releaseQuantumNanos = releaseQuantumNanos;
        this.maxRangeBytes = maxRangeBytes;
        this.maxPendingRanges = maxPendingRanges;
        this.maxPendingBytesLimit = maxPendingBytes;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.conditionAwaiter = Objects.requireNonNull(conditionAwaiter, "conditionAwaiter must not be null");
    }

    /**
     * Publishes the next bounded sequence range. The returned byte count can be smaller than requested and is the
     * exact number the observer must pass to the next raw write.
     */
    Reservation reserveRange(final int requestedBytes) throws IOException {
        if (requestedBytes <= 0) {
            throw new IllegalArgumentException("requestedBytes must be positive");
        }

        final int byteCount = Math.min(requestedBytes, maxRangeBytes);
        IOException terminalFailure = null;
        Reservation reservation = null;
        lock.lock();
        try {
            throwIfUnavailableLocked();
            if (pending.size() >= maxPendingRanges || pendingBytes > maxPendingBytesLimit - byteCount) {
                terminalFailure = new IOException("Refined-A1 metadata high-water limit exceeded: pendingRanges="
                        + pending.size() + ", pendingBytes=" + pendingBytes);
                failLocked(terminalFailure);
            } else {
                final long now = clock.nanoTime();
                final long startOffset = nextOffset;
                try {
                    nextOffset = Math.addExact(nextOffset, byteCount);
                    observedBytes = Math.addExact(observedBytes, byteCount);
                    pendingBytes = Math.addExact(pendingBytes, byteCount);
                } catch (final ArithmeticException e) {
                    terminalFailure = new IOException("Refined-A1 byte sequence overflow", e);
                    failLocked(terminalFailure);
                }

                if (terminalFailure == null) {
                    final long serializationStart;
                    final long serializationEnd;
                    final long serializationDuration;
                    final long targetSerializationDuration;
                    try {
                        if (bandwidthBytesPerSecond == Long.MAX_VALUE) {
                            serializationStart = now;
                            serializationEnd = now;
                            serializationDuration = 0;
                        } else {
                            resetSerializationCursorIfIdle(now);
                            serializationStart = roundedCursorNanos();
                            advanceSerializationCursor(byteCount);
                            serializationEnd = roundedCursorNanos();
                            serializationDuration = serializationDurationNanos(byteCount);
                        }
                        targetSerializationDuration = configuredBandwidthBytesPerSecond == Long.MAX_VALUE
                                ? 0
                                : serializationDurationNanos(byteCount, configuredBandwidthBytesPerSecond);

                        reservation = new Reservation(
                                startOffset,
                                byteCount,
                                now,
                                serializationStart,
                                serializationEnd,
                                serializationDuration,
                                targetSerializationDuration);
                        final long eligibleAt = addDuration(serializationEnd, latencyNanos);
                        pending.addLast(new PendingRange(reservation, eligibleAt));
                        scheduledBytes = saturatedAdd(scheduledBytes, byteCount);
                        rangeCount++;
                        rangeSizes.record(byteCount);
                        maxPendingRangeCount = Math.max(maxPendingRangeCount, pending.size());
                        maxPendingByteCount = Math.max(maxPendingByteCount, pendingBytes);
                        if (bandwidthBytesPerSecond != Long.MAX_VALUE) {
                            final long serializationBacklogNanos = positiveDifference(serializationEnd, now);
                            final long serializationBacklogBytes = serializedBytesForDurationCapped(
                                    serializationBacklogNanos, bandwidthBytesPerSecond, pendingBytes);
                            maxSerializationBacklogBytes =
                                    Math.max(maxSerializationBacklogBytes, serializationBacklogBytes);
                            maxSerializationBacklogNanos =
                                    Math.max(maxSerializationBacklogNanos, serializationBacklogNanos);
                        }
                        changed.signalAll();
                    } catch (final IOException | IllegalArgumentException e) {
                        terminalFailure = e instanceof IOException ioException
                                ? ioException
                                : new IOException("Refined-A1 schedule arithmetic failure", e);
                        failLocked(terminalFailure);
                    }
                }
            }
        } finally {
            lock.unlock();
        }

        if (terminalFailure != null) {
            throw terminalFailure;
        }
        return reservation;
    }

    /** Records how long the matching bounded raw write took. */
    void recordRawWrite(final Reservation reservation, final long durationNanos, final boolean accepted) {
        Objects.requireNonNull(reservation, "reservation must not be null");
        final long duration = Math.max(0, durationNanos);
        lock.lock();
        try {
            rawWriteCount++;
            rawWriteDurationNanos = saturatedAdd(rawWriteDurationNanos, duration);
            maxRawWriteDurationNanos = Math.max(maxRawWriteDurationNanos, duration);
            rawWriteDurations.record(duration);
            if (!accepted) {
                failedRawWrites++;
            }
            final boolean overQuarterLatency = configuredLatencyNanos > 0 && duration > configuredLatencyNanos / 4;
            final boolean overSerializationDuration = reservation.targetSerializationDurationNanos() > 0
                    && duration > reservation.targetSerializationDurationNanos();
            if (overQuarterLatency) {
                rawWriteBytesOverQuarterLatency =
                        saturatedAdd(rawWriteBytesOverQuarterLatency, reservation.byteCount());
            }
            if (overSerializationDuration) {
                rawWriteBytesOverSerializationDuration =
                        saturatedAdd(rawWriteBytesOverSerializationDuration, reservation.byteCount());
            }
            if (overQuarterLatency || overSerializationDuration) {
                rawWriteBytesOverEitherTarget = saturatedAdd(rawWriteBytesOverEitherTarget, reservation.byteCount());
            }
        } finally {
            lock.unlock();
        }
    }

    /** Waits for metadata and the next Q-bounded eligible prefix. */
    ReadAllowance awaitReadable(
            final int requestedBytes, final long logicalDeadlineNanos, final boolean deadlineEnabled)
            throws IOException {
        if (requestedBytes <= 0) {
            throw new IllegalArgumentException("requestedBytes must be positive");
        }

        while (true) {
            IOException terminalFailure = null;
            ReadAllowance allowance = null;
            lock.lock();
            try {
                if (failure != null) {
                    throw failure;
                }

                final long now = clock.nanoTime();
                final int eligible = eligibleBytesLocked(requestedBytes, now);
                if (eligible > 0) {
                    allowance = new ReadAllowance(eligible, latestEligibleDeadlineLocked(eligible));
                } else if (cleanup) {
                    terminalFailure = new IOException("Refined-A1 visibility controller is closed");
                } else if (deadlineEnabled && hasReached(now, logicalDeadlineNanos)) {
                    terminalFailure = new SocketTimeoutException("Timed out waiting for refined-A1 byte eligibility");
                    failLocked(terminalFailure);
                } else {
                    final PendingRange head = pending.peekFirst();
                    final boolean waitingForMetadata = head == null;
                    long waitNanos = waitingForMetadata
                            ? Long.MAX_VALUE
                            : Math.max(1, positiveDifference(head.eligibleAtNanos, now));
                    if (deadlineEnabled) {
                        waitNanos = Math.min(waitNanos, Math.max(1, positiveDifference(logicalDeadlineNanos, now)));
                    }

                    final long waitStarted = now;
                    try {
                        conditionAwaiter.awaitNanos(changed, waitNanos);
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        final InterruptedIOException interrupted =
                                new InterruptedIOException("Interrupted while waiting for refined-A1 byte eligibility");
                        interrupted.initCause(e);
                        terminalFailure = interrupted;
                        failLocked(terminalFailure);
                    }
                    final long waited = Math.max(0, clock.nanoTime() - waitStarted);
                    if (waitingForMetadata) {
                        metadataWaitNanos = saturatedAdd(metadataWaitNanos, waited);
                    } else {
                        timingWakeCount++;
                        recordEligibilityWaitLocked(head, waitStarted, waited);
                        recordEligibleReleaseLatenessLocked(clock.nanoTime());
                    }
                }
            } finally {
                lock.unlock();
            }

            if (terminalFailure != null) {
                throw terminalFailure;
            }
            if (allowance != null) {
                return allowance;
            }
        }
    }

    /** Records and removes exactly the bytes returned by the raw input. */
    void consume(final ReadAllowance allowance, final int bytesRead, final long rawReadDurationNanos)
            throws IOException {
        Objects.requireNonNull(allowance, "allowance must not be null");
        if (bytesRead <= 0 || bytesRead > allowance.byteCount()) {
            throw new IOException("Raw read consumed outside its refined-A1 allowance: bytesRead=" + bytesRead
                    + ", allowance=" + allowance.byteCount());
        }

        lock.lock();
        try {
            throwIfUnavailableLocked();
            final long returnedAtNanos = clock.nanoTime();
            int remaining = bytesRead;
            while (remaining > 0) {
                final PendingRange head = pending.peekFirst();
                if (head == null || !hasReached(returnedAtNanos, head.eligibleAtNanos)) {
                    throw new IOException("Refined-A1 metadata/byte consumption lost sequence alignment");
                }
                if (!head.firstReturnRecorded) {
                    head.firstReturnRecorded = true;
                    observerToFirstReturn.record(Math.max(0, returnedAtNanos - head.reservation.observedAtNanos()));
                    observerToFirstReturnSampleCount++;
                }
                final int consumed = Math.min(remaining, head.remaining());
                head.consumed += consumed;
                remaining -= consumed;
                pendingBytes -= consumed;
                if (head.remaining() == 0) {
                    pending.removeFirst();
                }
            }
            returnedBytes = saturatedAdd(returnedBytes, bytesRead);
            rawReadCount++;
            rawReadSizes.record(bytesRead);
            final long duration = Math.max(0, rawReadDurationNanos);
            rawReadWaitNanos = saturatedAdd(rawReadWaitNanos, duration);
            maxRawReadWaitNanos = Math.max(maxRawReadWaitNanos, duration);
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Records a raw read that failed after bytes were already eligible. */
    void recordRawReadFailure(final ReadAllowance allowance, final long durationNanos) {
        Objects.requireNonNull(allowance, "allowance must not be null");
        lock.lock();
        try {
            failedRawReads++;
            final long duration = Math.max(0, durationNanos);
            rawReadWaitNanos = saturatedAdd(rawReadWaitNanos, duration);
            maxRawReadWaitNanos = Math.max(maxRawReadWaitNanos, duration);
        } finally {
            lock.unlock();
        }
    }

    /** Returns the currently eligible prefix without waiting or consuming it. */
    int eligibleBytesNow(final int requestedBytes) {
        if (requestedBytes <= 0) {
            return 0;
        }
        lock.lock();
        try {
            if (failure != null || cleanup) {
                return 0;
            }
            return eligibleBytesLocked(requestedBytes, clock.nanoTime());
        } finally {
            lock.unlock();
        }
    }

    /** Signals normal transport cleanup and wakes metadata/timer waiters. */
    void beginCleanup() {
        lock.lock();
        try {
            cleanup = true;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Records a local connection-wide abort and wakes metadata/timer waiters. */
    void abort(final IOException cause) {
        Objects.requireNonNull(cause, "cause must not be null");
        lock.lock();
        try {
            failLocked(cause);
        } finally {
            lock.unlock();
        }
    }

    /** Takes a consistent diagnostics snapshot without nesting locks across directions. */
    SocketVisibilityStats stats() {
        lock.lock();
        try {
            final String state = failure != null
                    ? "ABORTED: " + failure.getClass().getSimpleName() + ": " + failure.getMessage()
                    : cleanup ? "CLOSED" : "ACTIVE";
            return new SocketVisibilityStats(
                    configuredLatencyNanos,
                    configuredBandwidthBytesPerSecond,
                    latencyNanos,
                    bandwidthBytesPerSecond,
                    releaseQuantumNanos,
                    maxRangeBytes,
                    observedBytes,
                    scheduledBytes,
                    returnedBytes,
                    rangeCount,
                    rangeSizes.percentileUpperBound(0.50),
                    rangeSizes.percentileUpperBound(0.99),
                    rangeSizes.maximum,
                    pending.size(),
                    pendingBytes,
                    maxPendingRangeCount,
                    maxPendingByteCount,
                    maxSerializationBacklogBytes,
                    maxSerializationBacklogNanos,
                    timingWakeCount,
                    metadataWaitNanos,
                    latencyWaitNanos,
                    bandwidthWaitNanos,
                    releaseLateness.count(),
                    releaseLateness.percentileUpperBound(0.50),
                    releaseLateness.percentileUpperBound(0.99),
                    releaseLateness.maximum,
                    releasesOverQuarterLatency,
                    observerToFirstReturnSampleCount,
                    observerToFirstReturn.percentileUpperBound(0.50),
                    observerToFirstReturn.percentileUpperBound(0.99),
                    observerToFirstReturn.maximum,
                    rawReadCount,
                    rawReadSizes.percentileUpperBound(0.50),
                    rawReadSizes.percentileUpperBound(0.99),
                    rawReadSizes.maximum,
                    rawReadWaitNanos,
                    maxRawReadWaitNanos,
                    failedRawReads,
                    rawWriteCount,
                    rawWriteDurationNanos,
                    rawWriteDurations.percentileUpperBound(0.99),
                    maxRawWriteDurationNanos,
                    rawWriteBytesOverQuarterLatency,
                    rawWriteBytesOverSerializationDuration,
                    rawWriteBytesOverEitherTarget,
                    failedRawWrites,
                    state);
        } finally {
            lock.unlock();
        }
    }

    long nanoTime() {
        return clock.nanoTime();
    }

    private void throwIfUnavailableLocked() throws IOException {
        if (failure != null) {
            throw failure;
        }
        if (cleanup) {
            throw new IOException("Refined-A1 visibility controller is closed");
        }
    }

    private void failLocked(final IOException cause) {
        if (failure == null) {
            failure = cause;
        }
        changed.signalAll();
    }

    private void resetSerializationCursorIfIdle(final long now) {
        if (!serializationCursorInitialized || hasReached(now, roundedCursorNanos())) {
            serializationCursorNanos = now;
            serializationRemainder = 0;
            serializationCursorInitialized = true;
        }
    }

    private void advanceSerializationCursor(final int byteCount) throws IOException {
        final long numerator;
        try {
            numerator = Math.multiplyExact((long) byteCount, NANOS_PER_SECOND);
        } catch (final ArithmeticException e) {
            throw new IOException("Refined-A1 serialization arithmetic overflow", e);
        }
        final long wholeNanos = numerator / bandwidthBytesPerSecond;
        final long newRemainder = numerator % bandwidthBytesPerSecond;

        long carry = 0;
        if (newRemainder != 0 && serializationRemainder >= bandwidthBytesPerSecond - newRemainder) {
            carry = 1;
            serializationRemainder -= bandwidthBytesPerSecond - newRemainder;
        } else {
            serializationRemainder += newRemainder;
        }
        final long increment = wholeNanos + carry;
        if (increment > MAX_SAFE_DURATION_NANOS) {
            throw new IOException("Refined-A1 serialization duration exceeds safe nanoTime range");
        }
        serializationCursorNanos += increment;
    }

    private long serializationDurationNanos(final int byteCount) throws IOException {
        return serializationDurationNanos(byteCount, bandwidthBytesPerSecond);
    }

    private static long serializationDurationNanos(final int byteCount, final long bytesPerSecond) throws IOException {
        final long numerator;
        try {
            numerator = Math.multiplyExact((long) byteCount, NANOS_PER_SECOND);
        } catch (final ArithmeticException e) {
            throw new IOException("Refined-A1 serialization arithmetic overflow", e);
        }
        final long quotient = numerator / bytesPerSecond;
        final long duration = quotient + (numerator % bytesPerSecond == 0 ? 0 : 1);
        if (duration > MAX_SAFE_DURATION_NANOS) {
            throw new IOException("Refined-A1 serialization duration exceeds safe nanoTime range");
        }
        return duration;
    }

    private long roundedCursorNanos() {
        return serializationCursorNanos + (serializationRemainder == 0 ? 0 : 1);
    }

    private int eligibleBytesLocked(final int requestedBytes, final long now) {
        int eligible = 0;
        final Iterator<PendingRange> iterator = pending.iterator();
        while (iterator.hasNext() && eligible < requestedBytes) {
            final PendingRange range = iterator.next();
            if (!hasReached(now, range.eligibleAtNanos)) {
                break;
            }
            eligible += Math.min(requestedBytes - eligible, range.remaining());
        }
        return eligible;
    }

    /** Records scheduler error only after an actual timed wait, not when the application begins reading late. */
    private void recordEligibleReleaseLatenessLocked(final long now) {
        for (final PendingRange range : pending) {
            if (!hasReached(now, range.eligibleAtNanos)) {
                break;
            }
            if (!range.releaseRecorded) {
                range.releaseRecorded = true;
                final long lateness = Math.max(0, now - range.eligibleAtNanos);
                releaseLateness.record(lateness);
                if (latencyNanos > 0 && lateness > latencyNanos / 4) {
                    releasesOverQuarterLatency++;
                }
            }
        }
    }

    private long latestEligibleDeadlineLocked(final int byteCount) {
        int remaining = byteCount;
        long latest = 0;
        for (final PendingRange range : pending) {
            latest = range.eligibleAtNanos;
            remaining -= Math.min(remaining, range.remaining());
            if (remaining == 0) {
                return latest;
            }
        }
        throw new IllegalStateException("eligible byte count exceeds pending metadata");
    }

    private void recordEligibilityWaitLocked(
            final PendingRange range, final long waitStartedNanos, final long waitedNanos) {
        if (waitedNanos <= 0) {
            return;
        }
        // Serialization (including an existing cursor backlog) precedes this range's one-way latency. Do not count
        // scheduler overshoot after eligibility as modeled wait.
        final long modeledWait = Math.min(waitedNanos, positiveDifference(range.eligibleAtNanos, waitStartedNanos));
        final long bandwidthPart =
                Math.min(modeledWait, positiveDifference(range.reservation.serializationEndNanos(), waitStartedNanos));
        final long latencyPart = modeledWait - bandwidthPart;
        latencyWaitNanos = saturatedAdd(latencyWaitNanos, latencyPart);
        bandwidthWaitNanos = saturatedAdd(bandwidthWaitNanos, bandwidthPart);
    }

    /** Returns ceil(duration * bytesPerSecond / 1 second), capped before any intermediate can overflow. */
    private static long serializedBytesForDurationCapped(
            final long durationNanos, final long bytesPerSecond, final long cap) {
        if (durationNanos <= 0 || cap <= 0) {
            return 0;
        }

        final long wholeSeconds = durationNanos / NANOS_PER_SECOND;
        final long fractionalNanos = durationNanos % NANOS_PER_SECOND;
        long result = 0;
        if (wholeSeconds > 0) {
            if (bytesPerSecond > cap / wholeSeconds) {
                return cap;
            }
            result = bytesPerSecond * wholeSeconds;
        }

        final long wholeBytesPerNanosecond = bytesPerSecond / NANOS_PER_SECOND;
        final long fractionalBytesPerSecond = bytesPerSecond % NANOS_PER_SECOND;
        final long remainingCap = cap - result;
        if (wholeBytesPerNanosecond > 0) {
            if (fractionalNanos > remainingCap / wholeBytesPerNanosecond) {
                return cap;
            }
            result += wholeBytesPerNanosecond * fractionalNanos;
        }

        final long fractionalProduct = fractionalBytesPerSecond * fractionalNanos;
        final long fractionalBytes = fractionalProduct / NANOS_PER_SECOND;
        final long roundedFractionalBytes = fractionalBytes + (fractionalProduct % NANOS_PER_SECOND == 0 ? 0 : 1);
        return roundedFractionalBytes > cap - result ? cap : result + roundedFractionalBytes;
    }

    private static long addDuration(final long base, final long duration) {
        if (duration < 0 || duration > MAX_SAFE_DURATION_NANOS) {
            throw new IllegalArgumentException("duration is outside the safe nanoTime difference range");
        }
        // nanoTime values may wrap. Difference-based comparisons remain valid for durations below half the range.
        return base + duration;
    }

    private static boolean hasReached(final long now, final long deadline) {
        return now - deadline >= 0;
    }

    private static long positiveDifference(final long later, final long earlier) {
        final long difference = later - earlier;
        return difference > 0 ? difference : 0;
    }

    private static long saturatedAdd(final long left, final long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
