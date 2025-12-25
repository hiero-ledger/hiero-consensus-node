// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.config;

import com.google.auto.service.AutoService;
import com.swirlds.config.api.ConfigurationExtension;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;

/**
 * Registers configuration types for the event creator module.
 */
@AutoService(ConfigurationExtension.class)
public class EventCreatorConfigurationExtension implements ConfigurationExtension {

    /**
     * {@inheritDoc}
     */
    @NonNull
    public Set<Class<? extends Record>> getConfigDataTypes() {
        return Set.of(EventCreationConfig.class, EventCreationWiringConfig.class);
    }
}
