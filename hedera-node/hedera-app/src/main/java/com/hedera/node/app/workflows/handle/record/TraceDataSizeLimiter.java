// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.record;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Tracks an estimated serialized contract trace data size and marks builders exceeded once the configured limit is hit. */
public class TraceDataSizeLimiter {
    private static final Logger log = LogManager.getLogger(TraceDataSizeLimiter.class);

    /** A limit value that will not be exceeded by any positive PBJ record size estimate. */
    public static final int NO_LIMIT = Integer.MAX_VALUE;

    /** Clipping state that can be shared by paired record/block stream delegates for the same transaction. */
    public static class ClippingState {
        private boolean traceDataSizeLimitExceeded;
    }

    private final int maxSerializedTraceDataBytes;
    private final ClippingState clippingState;
    private long serializedTraceDataBytes;

    /**
     * Constructs a limiter with its own independent clipping state.
     *
     * @param maxSerializedTraceDataBytes the maximum estimated serialized trace data size in bytes
     * @throws IllegalArgumentException if {@code maxSerializedTraceDataBytes} is negative
     */
    public TraceDataSizeLimiter(final int maxSerializedTraceDataBytes) {
        this(maxSerializedTraceDataBytes, new ClippingState());
    }

    /**
     * Constructs a limiter with shared clipping state.
     *
     * @param maxSerializedTraceDataBytes the maximum estimated serialized trace data size in bytes
     * @param clippingState the clipping state to share with a paired limiter for the same transaction
     * @throws IllegalArgumentException if {@code maxSerializedTraceDataBytes} is negative
     * @throws NullPointerException if {@code clippingState} is {@code null}
     */
    public TraceDataSizeLimiter(final int maxSerializedTraceDataBytes, @NonNull final ClippingState clippingState) {
        if (maxSerializedTraceDataBytes < 0) {
            throw new IllegalArgumentException("maxSerializedTraceDataBytes must be non-negative");
        }
        this.maxSerializedTraceDataBytes = maxSerializedTraceDataBytes;
        this.clippingState = requireNonNull(clippingState);
    }

    /**
     * Attempts to add newly accumulated trace data to the running size estimate.
     *
     * @param serializedBytes the estimated serialized size of the new trace data
     * @return {@code true} if the bytes were accepted and counted; {@code false} if the limit had already been
     *     exceeded, the estimate was invalid, or adding the bytes exceeded the limit
     */
    public boolean tryAdd(final int serializedBytes) {
        return tryReplace(0, serializedBytes);
    }

    /**
     * Attempts to replace already-counted trace data with a new estimate.
     *
     * <p>This is useful for block stream trace data where a list can be updated repeatedly. If the replacement is
     * accepted, {@code previousSerializedBytes} is subtracted from the running total and {@code newSerializedBytes} is
     * added. If the replacement is rejected, this limiter is marked exceeded and all future attempts return
     * {@code false}.
     *
     * @param previousSerializedBytes the estimate already included in the running total
     * @param newSerializedBytes the replacement estimate to include in the running total
     * @return {@code true} if the replacement was accepted and counted; {@code false} if the limit had already been
     *     exceeded, either estimate was invalid, or the replacement exceeded the limit
     */
    public boolean tryReplace(final int previousSerializedBytes, final int newSerializedBytes) {
        if (clippingState.traceDataSizeLimitExceeded) {
            return false;
        }
        if (previousSerializedBytes < 0 || newSerializedBytes < 0) {
            return markExceeded(previousSerializedBytes, newSerializedBytes, serializedTraceDataBytes);
        }
        final var newTotal = serializedTraceDataBytes - previousSerializedBytes + newSerializedBytes;
        if (newTotal < 0 || newTotal > maxSerializedTraceDataBytes) {
            return markExceeded(previousSerializedBytes, newSerializedBytes, newTotal);
        }
        serializedTraceDataBytes = newTotal;
        return true;
    }

    /**
     * Checks whether the current estimate plus additional trace data would stay within the limit.
     *
     * <p>Unlike {@link #tryAdd(int)}, this method does not add {@code additionalSerializedBytes} to the running total
     * when the check succeeds. If the check fails, this limiter is marked exceeded and all future attempts return
     * {@code false}.
     *
     * @param additionalSerializedBytes the additional estimated bytes to consider
     * @return {@code true} if the current estimate plus the additional bytes is within the limit; {@code false} if the
     *     limit had already been exceeded, the additional estimate was invalid, or the combined estimate exceeded the
     *     limit
     */
    public boolean ensureWithinLimitWith(final long additionalSerializedBytes) {
        if (clippingState.traceDataSizeLimitExceeded) {
            return false;
        }
        if (additionalSerializedBytes < 0) {
            return markExceeded(0, additionalSerializedBytes, serializedTraceDataBytes);
        }
        final var newTotal = serializedTraceDataBytes + additionalSerializedBytes;
        if (newTotal < 0 || newTotal > maxSerializedTraceDataBytes) {
            return markExceeded(0, additionalSerializedBytes, newTotal);
        }
        return true;
    }

    /**
     * Returns whether this limiter has rejected trace data for size reasons.
     *
     * @return {@code true} once the limit has been exceeded; after this becomes {@code true}, all accumulation attempts
     *     return {@code false}
     */
    public boolean hasExceededTraceDataSizeLimit() {
        return clippingState.traceDataSizeLimitExceeded;
    }

    private boolean markExceeded(
            final long previousSerializedBytes, final long newSerializedBytes, final long attemptedTotal) {
        log.warn(
                "Clearing contract trace data because estimated serialized size {} bytes exceeds "
                        + "contracts.maxSerializedTraceDataBytes={} bytes; previousSerializedBytes={}, "
                        + "newSerializedBytes={}",
                attemptedTotal,
                maxSerializedTraceDataBytes,
                previousSerializedBytes,
                newSerializedBytes);
        clippingState.traceDataSizeLimitExceeded = true;
        serializedTraceDataBytes = 0;
        return false;
    }
}
