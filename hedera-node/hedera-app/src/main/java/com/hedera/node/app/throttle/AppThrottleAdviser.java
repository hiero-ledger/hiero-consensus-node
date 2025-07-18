// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.throttle;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.node.app.spi.throttle.ThrottleAdviser;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;

/**
 * Default implementation of {@link ThrottleAdviser}.
 */
public class AppThrottleAdviser implements ThrottleAdviser {

    private final NetworkUtilizationManager networkUtilizationManager;
    private final Instant consensusNow;

    public AppThrottleAdviser(
            @NonNull final NetworkUtilizationManager networkUtilizationManager, @NonNull final Instant consensusNow) {
        this.networkUtilizationManager = requireNonNull(networkUtilizationManager);
        this.consensusNow = requireNonNull(consensusNow);
    }

    @Override
    public boolean shouldThrottleNOfUnscaled(int n, @NonNull final HederaFunctionality function) {
        requireNonNull(function);
        return networkUtilizationManager.shouldThrottleNOfUnscaled(n, function, consensusNow);
    }

    @Override
    public long availableOpsDurationCapacity() {
        return networkUtilizationManager.availableOpsDurationCapacity(consensusNow);
    }

    @Override
    public void consumeOpsDurationThrottleCapacity(final long opsDurationUnitsToConsume) {
        networkUtilizationManager.consumeOpsDurationThrottleCapacity(opsDurationUnitsToConsume, consensusNow);
    }
}
