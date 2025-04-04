// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import org.hiero.otter.fixtures.Node;

/**
 * A node in the turtle network.
 *
 * <p>This class implements the {@link Node} interface and provides methods to control the state of the node.
 */
public class TurtleNode implements Node {

    /**
     * {@inheritDoc}
     */
    @Override
    public void kill(@NonNull final Duration timeout) {
        throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void revive(@NonNull final Duration timeout) {
        throw new UnsupportedOperationException("Not implemented");
    }
}
