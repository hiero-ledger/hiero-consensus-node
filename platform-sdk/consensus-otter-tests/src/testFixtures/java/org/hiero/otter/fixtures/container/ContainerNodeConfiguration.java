// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collections;
import java.util.Map;
import org.hiero.otter.fixtures.NodeConfiguration;
import org.hiero.otter.fixtures.internal.AbstractNodeConfiguration;

/**
 * An implementation of {@link NodeConfiguration} for a container environment.
 */
public class ContainerNodeConfiguration extends AbstractNodeConfiguration<ContainerNodeConfiguration> {

    private final Map<String, String> publicOverriddenProperties = Collections.unmodifiableMap(overriddenProperties);

    /**
     * {@inheritDoc}
     */
    @Override
    protected ContainerNodeConfiguration self() {
        return this;
    }

    @NonNull
    public Map<String, String> overriddenProperties() {
        return publicOverriddenProperties;
    }
}
