// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.fees.congestion;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.store.ReadableStoreFactory;
import com.hedera.node.app.throttle.annotations.GasThrottleMultiplier;
import com.hedera.node.app.workflows.TransactionInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * An implementation for congestion multipliers that uses two types of multiplier implementation (
 * {@link UtilizationScaledThrottleMultiplier} and a {@link ThrottleMultiplier})
 * to determine the current congestion multiplier.
 */
@Singleton
public class CongestionMultipliers {
    private final UtilizationScaledThrottleMultiplier utilizationScaledThrottleMultiplier;
    private final ThrottleMultiplier gasThrottleMultiplier;

    @Inject
    public CongestionMultipliers(
            @NonNull final UtilizationScaledThrottleMultiplier utilizationScaledThrottleMultiplier,
            @NonNull @GasThrottleMultiplier final ThrottleMultiplier gasThrottleMultiplier) {
        this.utilizationScaledThrottleMultiplier =
                requireNonNull(utilizationScaledThrottleMultiplier, "entityUtilizationMultiplier must not be null");
        this.gasThrottleMultiplier = requireNonNull(gasThrottleMultiplier, "throttleMultiplier must not be null");
    }

    /**
     * Updates the congestion multipliers for the given consensus time.
     *
     * @param consensusTime the consensus time
     */
    public void updateMultiplier(@NonNull final Instant consensusTime) {
        gasThrottleMultiplier.updateMultiplier(consensusTime);
        utilizationScaledThrottleMultiplier.updateMultiplier(consensusTime);
    }

    /**
     * Returns the maximum congestion multiplier of the gas and entity utilization based multipliers.
     *
     * @param txnInfo transaction info needed for entity utilization based multiplier
     * @param storeFactory  provide the stores needed for entity utilization based multiplier
     *
     * @return the max congestion multiplier
     */
    public long maxCurrentMultiplier(
            @NonNull final TransactionInfo txnInfo, @NonNull final ReadableStoreFactory storeFactory) {
        return maxCurrentMultiplier(txnInfo.txBody(), txnInfo.functionality(), storeFactory);
    }

    public long maxCurrentMultiplier(
            @NonNull final TransactionBody body,
            @NonNull final HederaFunctionality functionality,
            @NonNull final ReadableStoreFactory storeFactory) {
        return Math.max(
                gasThrottleMultiplier.currentMultiplier(),
                utilizationScaledThrottleMultiplier.currentMultiplier(body, functionality, storeFactory));
    }

    /**
     * Returns the congestion level starts for the entity utilization based multiplier.
     *
     * @return the  congestion level starts
     */
    @NonNull
    public Instant[] entityUtilizationCongestionStarts() {
        return utilizationScaledThrottleMultiplier.congestionLevelStarts();
    }

    /**
     * Returns the congestion level starts for the throttle multiplier.
     *
     * @return the congestion level starts
     */
    @NonNull
    public Instant[] gasThrottleMultiplierCongestionStarts() {
        return gasThrottleMultiplier.congestionLevelStarts();
    }

    /**
     * Resets the congestion level starts for the entity utilization based multiplier.
     *
     * @param startTimes the congestion level starts
     */
    public void resetUtilizationScaledThrottleMultiplierStarts(@NonNull final Instant[] startTimes) {
        utilizationScaledThrottleMultiplier.resetCongestionLevelStarts(startTimes);
    }

    /**
     * Resets the congestion level starts for the throttle multiplier.
     *
     * @param startTimes the congestion level starts
     */
    public void resetGasThrottleMultiplierStarts(@NonNull final Instant[] startTimes) {
        gasThrottleMultiplier.resetCongestionLevelStarts(startTimes);
    }

    /**
     * Resets the state of the underlying congestion multipliers.
     */
    public void resetExpectations() {
        gasThrottleMultiplier.resetExpectations();
        utilizationScaledThrottleMultiplier.resetExpectations();
    }
}
