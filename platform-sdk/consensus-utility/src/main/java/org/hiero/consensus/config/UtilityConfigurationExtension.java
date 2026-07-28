// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.config;

import com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfiguration;
import com.swirlds.config.api.ConfigurationExtension;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import org.hiero.consensus.status.StatusMonitorWiringConfig;

/**
 * Registers configuration types for the consensus utility module.
 */
public class UtilityConfigurationExtension implements ConfigurationExtension {

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Set<Class<? extends Record>> getConfigDataTypes() {
        return Set.of(
                PathsConfig.class,
                PlatformStatusConfig.class,
                RecycleBinConfig.class,
                StatusMonitorWiringConfig.class,
                UptimeConfig.class);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Set<ConverterPair<?>> getConverters() {
        return Set.of(new ConverterPair<>(TaskSchedulerConfiguration.class, TaskSchedulerConfiguration::parse));
    }
}
