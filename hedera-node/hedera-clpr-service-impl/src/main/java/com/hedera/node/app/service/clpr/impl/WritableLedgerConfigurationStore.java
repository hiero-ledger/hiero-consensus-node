// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import com.hedera.hapi.node.state.clpr.ClprThrottles;
import com.hedera.hapi.node.state.primitives.ProtoLong;
import com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Provides write access to the CLPR ledger configuration singleton in state.
 */
public class WritableLedgerConfigurationStore extends ReadableLedgerConfigurationStoreImpl {

    /** The underlying writable singleton state. */
    private final WritableStates states;

    /**
     * Create a new {@link WritableLedgerConfigurationStore} instance.
     *
     * @param states the writable state to use
     */
    public WritableLedgerConfigurationStore(@NonNull final WritableStates states) {
        super(states);
        this.states = requireNonNull(states);
    }

    /**
     * Persists a {@link ClprLedgerConfiguration} into state.
     *
     * @param configuration the configuration to persist
     */
    public void put(@NonNull final ClprLedgerConfiguration configuration) {
        requireNonNull(configuration);
        final var singleton =
                states.<ClprLedgerConfiguration>getSingleton(V0780ClprSchema.LEDGER_CONFIGURATION_STATE_ID);
        // Capture prior advertised credit before replacing the config, including on the first
        // post-upgrade admin or synthetic file update. Both writes share the transaction savepoint.
        final var highWater = states.<ProtoLong>getSingleton(V0780ClprSchema.MAX_QUEUE_DEPTH_HIGH_WATER_STATE_ID);
        final int retainedLimit = Math.max(
                getMaxQueueDepthHighWater(),
                configuration.throttlesOrElse(ClprThrottles.DEFAULT).maxQueueDepth());
        final var retainedValue = new ProtoLong(retainedLimit);
        if (!retainedValue.equals(highWater.get())) {
            highWater.put(retainedValue);
        }
        singleton.put(configuration);
    }
}
