// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.clpr.ClprConnector;
import com.hedera.hapi.node.state.clpr.ClprConnectorKey;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Provides write methods for modifying CLPR connectors in state.
 */
public class WritableConnectorStore extends ReadableConnectorStoreImpl {

    /**
     * Create a new {@link WritableConnectorStore} instance.
     *
     * @param states the state to use
     */
    public WritableConnectorStore(@NonNull final WritableStates states) {
        super(states);
    }

    @Override
    protected WritableKVState<ClprConnectorKey, ClprConnector> connectorState() {
        return super.connectorState();
    }

    /**
     * Persists a {@link ClprConnector} into state. If a connector with the same
     * key already exists, it will be overwritten.
     *
     * @param connector the connector to persist
     */
    public void put(@NonNull final ClprConnector connector) {
        requireNonNull(connector);
        final var key = new ClprConnectorKey(connector.channelId(), connector.connectorId());
        connectorState().put(key, connector);
    }

    /**
     * Removes a connector from state.
     *
     * @param key the key identifying the connector to remove
     */
    public void remove(@NonNull final ClprConnectorKey key) {
        requireNonNull(key);
        connectorState().remove(key);
    }
}
