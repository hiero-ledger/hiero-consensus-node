// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * This class contains the current configuration of the node at the time it was requested via
 * {@link Node#configuration()}. It can also be used to modify the configuration.
 */
@SuppressWarnings("UnusedReturnValue")
public interface NodeConfiguration extends Configurable<NodeConfiguration> {

    /**
     * Returns the current configuration of the node including all overridden properties.
     *
     * @return the current configuration of the node
     */
    @NonNull
    Configuration current();
}
