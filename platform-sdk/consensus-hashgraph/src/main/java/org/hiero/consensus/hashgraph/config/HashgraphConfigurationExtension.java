// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.config;

import com.swirlds.config.api.ConfigurationExtension;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Registers configuration types for the event creator module.
 */
public class HashgraphConfigurationExtension implements ConfigurationExtension {

    /**
     * {@inheritDoc}
     */
    @NonNull
    public Set<Class<? extends Record>> getConfigDataTypes() {
        return Set.of(HashgraphWiringConfig.class, ConsensusConfig.class);
    }
}
