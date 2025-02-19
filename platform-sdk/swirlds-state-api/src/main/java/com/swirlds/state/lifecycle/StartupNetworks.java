/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.state.lifecycle;

import com.hedera.node.internal.network.Network;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;

/**
 * Encapsulates the {@link Network} information that may or may not be present on disk
 * when starting a node.
 */
public interface StartupNetworks {
    /**
     * Called by a node that finds itself with a completely empty state.
     *
     * @return the network information that should be used to populate the node's genesis state
     */
    Network genesisNetworkOrThrow(@NonNull Configuration platformConfig);

    /**
     * Called by a node at a restart boundary to check if there is an override {@link Network}
     * that applies to the current round. This permits transplanting the state of one network
     * onto another nt network with a different roster and TSS keys.
     *
     * @param roundNumber the round number to check for an override
     * @param platformConfig
     */
    Optional<Network> overrideNetworkFor(long roundNumber, Configuration platformConfig);

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
    Network migrationNetworkOrThrow(final Configuration platformConfig);
}
