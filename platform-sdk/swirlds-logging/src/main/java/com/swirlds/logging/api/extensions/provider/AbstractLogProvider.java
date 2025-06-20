// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.api.extensions.provider;

import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;

/**
 * An abstract log provider. This class provides some basic functionality that is used by all log providers.
 */
public abstract class AbstractLogProvider implements LogProvider {

    private static final String PROPERTY_PROVIDER_ENABLED = "logging.provider.%s.enabled";

    /**
     * The configuration key of the log provider. This is used to create configuration keys for the log provider.
     */
    private final String configKey;

    /**
     * The configuration.
     */
    private final Configuration configuration;

    /**
     * Creates a new log provider.
     *
     * @param configKey     the configuration key
     * @param configuration the configuration
     */
    public AbstractLogProvider(@NonNull final String configKey, @NonNull final Configuration configuration) {
        this.configKey = Objects.requireNonNull(configKey, "configKey must not be null");
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
    }

    @Override
    public boolean isActive() {
        return Boolean.TRUE.equals(
                configuration.getValue(PROPERTY_PROVIDER_ENABLED.formatted(configKey), Boolean.class, false));
    }
}
