// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr;

import com.hedera.hapi.node.state.clpr.ClprLedgerConfiguration;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Provides read-only access to the CLPR ledger configuration singleton in state.
 */
public interface ReadableLedgerConfigurationStore {

    /**
     * Gets the current CLPR ledger configuration. The configuration is always
     * present — it is created at genesis from configuration properties.
     *
     * @return the configuration
     */
    @NonNull
    ClprLedgerConfiguration getConfiguration();

    /**
     * Gets the locally retained queue credit, at least the current queue limit.
     * This historical reserve is not part of the configuration proved to peers.
     *
     * @return the largest locally configured queue depth since tracking began
     */
    int getMaxQueueDepthHighWater();
}
