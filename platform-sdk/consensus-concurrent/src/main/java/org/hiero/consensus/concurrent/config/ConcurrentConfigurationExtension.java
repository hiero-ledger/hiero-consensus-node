// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.concurrent.config;

import com.swirlds.config.api.ConfigurationExtension;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Registers configuration types for the consensus concurrent module.
 */
public class ConcurrentConfigurationExtension implements ConfigurationExtension {

    /**
     * {@inheritDoc}
     */
    @NonNull
    public Set<Class<? extends Record>> getConfigDataTypes() {
        return Set.of(BasicCommonConfig.class);
    }
}
