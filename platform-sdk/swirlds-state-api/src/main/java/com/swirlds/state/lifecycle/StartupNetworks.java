// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.lifecycle;

import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;

/**
 * Encapsulates the {@code Network} information that may or may not be present on disk
 * when starting a node.
 *
 * @param <T> the type of network information
 */
public interface StartupNetworks<T> {
    /**
     * Called by a node that finds itself with a completely empty state.
     *
     * @return the network information that should be used to populate the node's genesis state
     */
    T genesisNetworkOrThrow(@NonNull Configuration platformConfig);

    /**
     * Called by a node at a restart boundary to check if there is an override {@code Network}
     * that applies to the current round. This permits transplanting the state of one network
     * onto another nt network with a different roster and TSS keys.
     *
     * @param roundNumber the round number to check for an override
     * @param platformConfig the current node's configuration
     */
    Optional<T> overrideNetworkFor(long roundNumber, Configuration platformConfig);

    /**
     * Called by a node after applying override network details to the state from a given round.
     *
     * @param roundNumber the round number for which the override was applied
     */
    void setOverrideRound(long roundNumber);

    /**
     * Archives any assets used in constructing this instance. Maybe a no-op.
     */
    void archiveStartupNetworks();

    /**
     * Called by a node at an upgrade boundary that finds itself with an empty Roster service state, and is
     * thus at the migration boundary for adoption of the roster proposal.
     *
     * @param platformConfig the current node's configuration
     * @return the network information that should be used to populate the Roster service state
     * @throws UnsupportedOperationException if these startup assets do not support migration to the roster proposal
     */
    @Deprecated
    T migrationNetworkOrThrow(final Configuration platformConfig);
}
