// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.Map;
import org.hiero.otter.fixtures.NodeConfiguration;
import org.hiero.otter.fixtures.internal.AbstractNodeConfiguration;

import static com.swirlds.config.extensions.export.ConfigExport.getPropertiesForConfigDataRecords;

/**
 * An implementation of {@link NodeConfiguration} for a container environment.
 */
public class ContainerNodeConfiguration extends AbstractNodeConfiguration<ContainerNodeConfiguration> {

    /** A map of properties that the node is currently running with. Initialized after node startup. */
    private final Map<String, String> nodeDefaults = new HashMap<>();

    public ContainerNodeConfiguration() {
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        getPropertiesForConfigDataRecords(configuration).forEach(
                (key, value) -> nodeDefaults.put(key, value.toString()));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected ContainerNodeConfiguration self() {
        return this;
    }

    @NonNull
    public Map<String, String> overriddenProperties() {
        return overriddenProperties;
    }

    /**
     * {@inheritDoc}
     */
    protected String get(@NonNull final String key) {
        if (overriddenProperties.containsKey(key)) {
            return overriddenProperties.get(key);
        }
        if (nodeDefaults.containsKey(key)) {
            return nodeDefaults.get(key);
        }
        throw new IllegalArgumentException(String.format("Configuration key '%s' does not exist", key));
    }
}
