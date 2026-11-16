// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl;

import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.LEDGER_CONFIGURATION_STATE_ID;
import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.MAX_QUEUE_DEPTH_HIGH_WATER_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.hapi.node.state.primitives.ProtoLong;
import com.hedera.node.app.service.clpr.ReadableLedgerConfigurationStore;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Provides read-only access to the CLPR ledger configuration singleton in state.
 */
public class ReadableLedgerConfigurationStoreImpl implements ReadableLedgerConfigurationStore {

    /** The underlying singleton state holding the configuration. */
    private final ReadableSingletonState<ClprLedgerConfiguration> configState;

    private final ReadableStates states;

    /**
     * Create a new {@link ReadableLedgerConfigurationStoreImpl} instance.
     *
     * @param states the state to use
     */
    public ReadableLedgerConfigurationStoreImpl(@NonNull final ReadableStates states) {
        this.states = requireNonNull(states);
        this.configState = states.getSingleton(LEDGER_CONFIGURATION_STATE_ID);
    }

    @Override
    @NonNull
    public ClprLedgerConfiguration getConfiguration() {
        return requireNonNull(configState.get());
    }

    @Override
    public int getMaxQueueDepthHighWater() {
        final int currentLimit =
                getConfiguration().throttlesOrElse(ClprThrottles.DEFAULT).maxQueueDepth();
        // Reads of pre-upgrade state may precede initialization of the new local singleton.
        if (!states.contains(MAX_QUEUE_DEPTH_HIGH_WATER_STATE_ID)) {
            return currentLimit;
        }
        final var highWater = states.<ProtoLong>getSingleton(MAX_QUEUE_DEPTH_HIGH_WATER_STATE_ID)
                .get();
        return highWater == null ? currentLimit : Math.max(currentLimit, Math.toIntExact(highWater.value()));
    }
}
