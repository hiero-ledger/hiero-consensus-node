// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import com.swirlds.logging.api.Logger;
import com.swirlds.logging.api.Loggers;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.time.TimeTickReceiver;

/**
 * A node in the turtle network.
 *
 * <p>This class implements the {@link Node} interface and provides methods to control the state of the node.
 */
public class TurtleNode implements Node, TimeTickReceiver {

    private static final Logger log = Loggers.getLogger(TurtleNode.class);

    /**
     * {@inheritDoc}
     */
    @Override
    public void kill(@NonNull final Duration timeout) {
        log.warn("Killing a node has not been implemented yet.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void revive(@NonNull final Duration timeout) {
        log.warn("Reviving a node has not been implemented yet.");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void tick(@NonNull Instant now) {
        // Not implemented. Logs no warning as this method is called with high frequency.
    }
}
