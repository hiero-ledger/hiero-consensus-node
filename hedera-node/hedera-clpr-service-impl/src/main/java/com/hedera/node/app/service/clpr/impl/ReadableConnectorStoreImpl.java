// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl;

import static com.hedera.node.app.service.clpr.impl.schemas.V0780ClprSchema.CONNECTORS_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.clpr.ClprConnector;
import com.hedera.hapi.node.state.clpr.ClprConnectorKey;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.service.clpr.ReadableConnectorStore;
import com.hedera.node.app.service.entityid.ReadableEntityCounters;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Provides read-only methods for interacting with CLPR connectors in state.
 */
public class ReadableConnectorStoreImpl implements ReadableConnectorStore {

    /** The underlying data storage class that holds connector data. */
    private final ReadableKVState<ClprConnectorKey, ClprConnector> connectorState;

    /** The entity counters, which hold the number of connectors. */
    private final ReadableEntityCounters entityCounters;

    /**
     * Create a new {@link ReadableConnectorStoreImpl} instance.
     *
     * @param states the state to use
     * @param entityCounters the entity counters
     */
    public ReadableConnectorStoreImpl(
            @NonNull final ReadableStates states, @NonNull final ReadableEntityCounters entityCounters) {
        requireNonNull(states);
        this.connectorState = states.get(CONNECTORS_STATE_ID);
        this.entityCounters = requireNonNull(entityCounters);
    }

    @Override
    @Nullable
    public ClprConnector getConnector(@NonNull final ClprConnectorKey key) {
        requireNonNull(key);
        return connectorState.get(key);
    }

    @Override
    public long sizeOfState() {
        return entityCounters.getCounterFor(EntityType.CLPR_CONNECTOR);
    }

    protected <T extends ReadableKVState<ClprConnectorKey, ClprConnector>> T connectorState() {
        return (T) connectorState;
    }
}
