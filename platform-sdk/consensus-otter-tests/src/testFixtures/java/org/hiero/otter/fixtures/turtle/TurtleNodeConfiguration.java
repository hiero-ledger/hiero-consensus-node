// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.otter.fixtures.NodeConfiguration;

/**
 * {@link NodeConfiguration} implementation for a Turtle node.
 */
public class TurtleNodeConfiguration implements NodeConfiguration<TurtleNodeConfiguration> {

    private static final Logger log = LogManager.getLogger(TurtleNodeConfiguration.class);

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public TurtleNodeConfiguration set(@NonNull String key, boolean value) {
        log.warn("Setting a node configuration property has not been implemented yet.");
        return this;
    }
}
