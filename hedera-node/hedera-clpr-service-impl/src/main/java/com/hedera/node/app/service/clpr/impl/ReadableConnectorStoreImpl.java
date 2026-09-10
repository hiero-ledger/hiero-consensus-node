// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl;

import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.CONNECTORS_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.clpr.ClprConnector;
import com.hedera.hapi.node.state.clpr.ClprConnectorKey;
import com.hedera.node.app.service.clpr.ReadableConnectorStore;
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

    /**
     * Create a new {@link ReadableConnectorStoreImpl} instance.
     *
     * @param states the state to use
     */
    public ReadableConnectorStoreImpl(@NonNull final ReadableStates states) {
        requireNonNull(states);
        this.connectorState = states.get(CONNECTORS_STATE_ID);
    }

    @Override
    @Nullable
    public ClprConnector getConnector(@NonNull final ClprConnectorKey key) {
        requireNonNull(key);
        return connectorState.get(key);
    }

    @Override
    public long sizeOfState() {
        return connectorState.size();
    }

    protected <T extends ReadableKVState<ClprConnectorKey, ClprConnector>> T connectorState() {
        return (T) connectorState;
    }
}
