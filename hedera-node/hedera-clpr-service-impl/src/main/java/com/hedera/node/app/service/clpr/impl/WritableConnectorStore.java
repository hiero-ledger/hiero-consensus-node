// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.clpr.ClprConnector;
import com.hedera.hapi.node.state.clpr.ClprConnectorKey;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.service.entityid.WritableEntityCounters;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Provides write methods for modifying CLPR connectors in state.
 */
public class WritableConnectorStore extends ReadableConnectorStoreImpl {

    /** The entity counters, which hold the number of connectors. */
    private final WritableEntityCounters entityCounters;

    /**
     * Create a new {@link WritableConnectorStore} instance.
     *
     * @param states the state to use
     * @param entityCounters the entity counters
     */
    public WritableConnectorStore(
            @NonNull final WritableStates states, @NonNull final WritableEntityCounters entityCounters) {
        super(states, entityCounters);
        this.entityCounters = entityCounters;
    }

    @Override
    protected WritableKVState<ClprConnectorKey, ClprConnector> connectorState() {
        return super.connectorState();
    }

    /**
     * Persists a {@link ClprConnector} into state. If a connector with the same
     * key already exists, it will be overwritten; otherwise the connector count is incremented.
     *
     * @param connector the connector to persist
     */
    public void put(@NonNull final ClprConnector connector) {
        requireNonNull(connector);
        final var key = new ClprConnectorKey(connector.channelId(), connector.connectorId());
        if (connectorState().get(key) == null) {
            entityCounters.incrementEntityTypeCount(EntityType.CLPR_CONNECTOR);
        }
        connectorState().put(key, connector);
    }

    /**
     * Removes a connector from state, decrementing the connector count if it exists.
     *
     * @param key the key identifying the connector to remove
     */
    public void remove(@NonNull final ClprConnectorKey key) {
        requireNonNull(key);
        if (connectorState().get(key) != null) {
            entityCounters.decrementEntityTypeCounter(EntityType.CLPR_CONNECTOR);
            connectorState().remove(key);
        }
    }
}
