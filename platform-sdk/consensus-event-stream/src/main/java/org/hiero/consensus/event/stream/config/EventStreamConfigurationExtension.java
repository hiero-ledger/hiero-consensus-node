// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.stream.config;

import com.swirlds.config.api.ConfigurationExtension;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Registers configuration types for the consensus utility module.
 */
public class EventStreamConfigurationExtension implements ConfigurationExtension {

    /**
     * {@inheritDoc}
     */
    @NonNull
    public Set<Class<? extends Record>> getConfigDataTypes() {
        return Set.of(EventStreamConfig.class, EventStreamWiringConfig.class);
    }
}
