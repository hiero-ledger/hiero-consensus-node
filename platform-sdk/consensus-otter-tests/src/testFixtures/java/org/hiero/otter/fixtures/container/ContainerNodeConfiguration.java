// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.Map;
import org.hiero.otter.fixtures.NodeConfiguration;
import org.hiero.otter.fixtures.internal.AbstractNodeConfiguration;

/**
 * An implementation of {@link NodeConfiguration} for a container environment.
 */
public class ContainerNodeConfiguration extends AbstractNodeConfiguration<ContainerNodeConfiguration> {

    /** A map of properties that the node is currently running with. Initialized after node startup. */
    private final Map<String, String> nodeProperties = new HashMap<>();

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
     * Sets all the node properties at once.
     *
     * @param nodeProperties a map of properties to set for the node
     */
    public void setNodeProperties(@NonNull final Map<String, String> nodeProperties) {
        this.nodeProperties.clear();
        this.nodeProperties.putAll(nodeProperties);
    }

    /**
     * {@inheritDoc}
     */
    protected String get(@NonNull final String key) {
        if (overriddenProperties.containsKey(key)) {
            return overriddenProperties.get(key);
        }
        if (nodeProperties.containsKey(key)) {
            return nodeProperties.get(key);
        }
        throw new IllegalArgumentException(String.format("Configuration key '%s' does not exist", key));
    }
}
