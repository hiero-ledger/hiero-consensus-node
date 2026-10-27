// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.throttle;

import static com.hedera.node.app.spi.throttle.ThrottleAdviser.ConsensusThrottleRejection.BEYOND_CAPACITY;
import static com.hedera.node.app.spi.throttle.ThrottleAdviser.ConsensusThrottleRejection.NONE;
import static com.hedera.node.app.spi.throttle.ThrottleAdviser.ConsensusThrottleRejection.TEMPORARILY_BUSY;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.node.app.spi.throttle.ThrottleAdviser;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;

/**
 * Default implementation of {@link ThrottleAdviser}.
 */
public class AppThrottleAdviser implements ThrottleAdviser {

    private final NetworkUtilizationManager networkUtilizationManager;
    private final Instant consensusNow;

    @Nullable
    private RejectionCapture openCapture;

    public AppThrottleAdviser(
            @NonNull final NetworkUtilizationManager networkUtilizationManager, @NonNull final Instant consensusNow) {
        this.networkUtilizationManager = requireNonNull(networkUtilizationManager);
        this.consensusNow = requireNonNull(consensusNow);
    }

    @Override
    public boolean shouldThrottleNOfUnscaled(int n, @NonNull final HederaFunctionality function) {
        requireNonNull(function);
        final boolean shouldThrottle = networkUtilizationManager.shouldThrottleNOfUnscaled(n, function, consensusNow);
        if (shouldThrottle) {
            recordConsensusThrottleRejection(
                    networkUtilizationManager.wouldThrottleNOfUnscaledWithEmptyBuckets(n, function, consensusNow));
        }
        return shouldThrottle;
    }

    @Override
    public long availableOpsDurationCapacity() {
        return networkUtilizationManager.availableOpsDurationCapacity(consensusNow);
    }

    @Override
    public void consumeOpsDurationThrottleCapacity(final long opsDurationUnitsToConsume) {
        networkUtilizationManager.consumeOpsDurationThrottleCapacity(opsDurationUnitsToConsume, consensusNow);
    }

    @Override
    public int highVolumeThrottleUtilization(@NonNull final HederaFunctionality function) {
        requireNonNull(function);
        return networkUtilizationManager.highVolumeThrottleUtilization(function, consensusNow);
    }

    @NonNull
    @Override
    public ConsensusThrottleRejectionCapture captureConsensusThrottleRejections() {
        if (openCapture != null) {
            throw new IllegalStateException("A consensus throttle rejection capture is already open");
        }
        openCapture = new RejectionCapture();
        return openCapture;
    }

    @Override
    public void recordConsensusThrottleRejection(final boolean beyondCapacity) {
        if (openCapture != null) {
            openCapture.record(beyondCapacity);
        }
    }

    /**
     * The capture opened by {@link #captureConsensusThrottleRejections()}; a temporarily busy rejection is never
     * downgraded.
     */
    private final class RejectionCapture implements ConsensusThrottleRejectionCapture {
        private ConsensusThrottleRejection rejection = NONE;

        @NonNull
        @Override
        public ConsensusThrottleRejection rejection() {
            return rejection;
        }

        @Override
        public void close() {
            if (openCapture == this) {
                openCapture = null;
            }
        }

        private void record(final boolean beyondCapacity) {
            if (!beyondCapacity) {
                rejection = TEMPORARILY_BUSY;
            } else if (rejection == NONE) {
                rejection = BEYOND_CAPACITY;
            }
        }
    }
}
