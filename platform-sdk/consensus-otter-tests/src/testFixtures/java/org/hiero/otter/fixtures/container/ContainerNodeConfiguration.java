// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.function.Supplier;
import org.hiero.otter.fixtures.NodeConfiguration;
import org.hiero.otter.fixtures.internal.AbstractNode.LifeCycle;
import org.hiero.otter.fixtures.internal.AbstractNodeConfiguration;
import org.hiero.otter.fixtures.turtle.TurtleNodeConfiguration;

/**
 * An implementation of {@link NodeConfiguration} for a container environment.
 */
public class ContainerNodeConfiguration extends AbstractNodeConfiguration<ContainerNodeConfiguration> {

    /**
     * Constructor for the {@link TurtleNodeConfiguration} class.
     *
     * @param outputDirectory the directory where the node output will be stored, like saved state and so on
     */
    public ContainerNodeConfiguration(
            @NonNull final Supplier<LifeCycle> lifecycleSupplier, @NonNull final Path outputDirectory) {
        super(lifecycleSupplier, outputDirectory);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected ContainerNodeConfiguration self() {
        return this;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    public Map<String, String> overriddenProperties() {
        return Collections.unmodifiableMap(overriddenProperties);
    }
}
