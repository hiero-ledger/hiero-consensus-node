// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.state.config;

import com.swirlds.config.api.ConfigurationExtension;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Registers configuration types for the consensus-state module.
 */
public class StateConfigurationExtension implements ConfigurationExtension {

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Set<Class<? extends Record>> getConfigDataTypes() {
        return Set.of(StateConfig.class);
    }
}
