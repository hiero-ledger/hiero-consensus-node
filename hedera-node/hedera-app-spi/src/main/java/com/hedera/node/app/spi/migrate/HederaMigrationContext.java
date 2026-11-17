// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.migrate;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.state.lifecycle.MigrationContext;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * A {@link MigrationContext} for use when migrating a node from a previous version of Hedera.
 */
public interface HederaMigrationContext extends MigrationContext<SemanticVersion> {

    /**
     * Returns the startup networks in use.
     */
    @NonNull
    StartupNetworks startupNetworks();

    /**
     * Returns whether this migration is running to initialize a state learned by reconnecting. Such a state
     * already reflects the network's consensus history, so schemas must not apply node-local startup assets
     * to it; doing so would diverge this node's state from the rest of the network.
     */
    boolean isReconnect();
}
