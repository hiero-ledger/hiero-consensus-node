// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr;

import com.hedera.hapi.node.state.clpr.ClprConnector;
import com.hedera.hapi.node.state.clpr.ClprConnectorKey;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Provides read-only methods for interacting with CLPR connectors in state.
 */
public interface ReadableConnectorStore {

    /**
     * Gets a connector by its connector key.
     *
     * @param key the connector key (channel_id, connector_id)
     * @return the connector, or null if not found
     */
    @Nullable
    ClprConnector getConnector(@NonNull ClprConnectorKey key);

    /**
     * Gets the number of connectors in the state.
     *
     * @return the number of connectors
     */
    long sizeOfState();
}
