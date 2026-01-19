// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph;

import com.swirlds.config.api.ConfigurationExtension;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Registers configuration types for the hashgraph module.
 */
public class HashgraphConfigurationExtension implements ConfigurationExtension {

    /**
     * {@inheritDoc}
     */
    @NonNull
    public Set<Class<? extends Record>> getConfigDataTypes() {
        return Set.of(ConsensusConfig.class);
    }
}
