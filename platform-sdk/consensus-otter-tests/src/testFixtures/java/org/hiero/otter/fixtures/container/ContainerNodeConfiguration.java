// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container;

import static org.hiero.otter.fixtures.container.utils.ContainerConstants.CONTAINER_APP_WORKING_DIR;

import edu.umd.cs.findbugs.annotations.NonNull;
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
        overriddenProperties.put("hedera.recordStream.logDir", CONTAINER_APP_WORKING_DIR + "/recordStream");
        overriddenProperties.put("blockStream.blockFileDir", CONTAINER_APP_WORKING_DIR + "/blockStreams");
        overriddenProperties.put("event.eventsLogDir", CONTAINER_APP_WORKING_DIR + "/eventsStreams");
    }
}
