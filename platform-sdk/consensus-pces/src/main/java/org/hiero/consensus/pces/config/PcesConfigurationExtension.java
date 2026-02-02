// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pces.config;

import com.swirlds.config.api.ConfigurationExtension;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Registers configuration types for the pces module.
 */
public class PcesConfigurationExtension implements ConfigurationExtension {

    /**
     * {@inheritDoc}
     */
    @NonNull
    public Set<Class<? extends Record>> getConfigDataTypes() {
        return Set.of(PcesConfig.class, PcesWiringConfig.class);
    }
}
