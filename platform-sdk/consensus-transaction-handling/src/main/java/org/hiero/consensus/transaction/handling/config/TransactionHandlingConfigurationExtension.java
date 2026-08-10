// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.transaction.handling.config;

import com.swirlds.config.api.ConfigurationExtension;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Set;
import org.hiero.consensus.wiring.framework.schedulers.builders.TaskSchedulerConfiguration;

/**
 * Registers configuration types for the transaction handling module.
 */
public class TransactionHandlingConfigurationExtension implements ConfigurationExtension {

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public Set<Class<? extends Record>> getConfigDataTypes() {
        return Set.of(TransactionHandlingWiringConfig.class);
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
