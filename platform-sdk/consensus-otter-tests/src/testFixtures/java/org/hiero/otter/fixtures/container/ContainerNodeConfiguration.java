// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collections;
import java.util.Map;
import java.util.function.Supplier;
import org.hiero.otter.fixtures.NodeConfiguration;
import org.hiero.otter.fixtures.internal.AbstractNode.LifeCycle;
import org.hiero.otter.fixtures.internal.AbstractNodeConfiguration;

/**
 * An implementation of {@link NodeConfiguration} for a container environment.
 */
public class ContainerNodeConfiguration extends AbstractNodeConfiguration {

    /**
     * Constructor for the {@link ContainerNodeConfiguration} class.
     *
     * @param lifecycleSupplier a supplier that provides the current lifecycle state of the node
     */
    public ContainerNodeConfiguration(@NonNull final Supplier<LifeCycle> lifecycleSupplier) {
        super(lifecycleSupplier);
        overriddenProperties.put("event.eventsLogDir", "/opt/DockerApp/hgcapp");
    }

    /**
     * Returns the overridden properties for this node configuration.
     *
     * @return an unmodifiable map of overridden properties
     */
    @NonNull
    Map<String, String> overriddenProperties() {
        return Collections.unmodifiableMap(overriddenProperties);
    }
}
