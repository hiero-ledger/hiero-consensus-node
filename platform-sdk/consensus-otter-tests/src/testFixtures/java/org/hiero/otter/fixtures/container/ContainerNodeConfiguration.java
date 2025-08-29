// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container;

import static org.hiero.otter.fixtures.container.utils.ContainerConstants.CONTAINER_APP_WORKING_DIR;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.function.Supplier;
import org.hiero.consensus.config.EventConfig_;
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
        overriddenProperties.put(EventConfig_.EVENTS_LOG_DIR, CONTAINER_APP_WORKING_DIR + "/eventsStreams");
    }
}
