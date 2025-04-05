package org.hiero.otter.fixtures.turtle;

import com.swirlds.logging.api.Logger;
import com.swirlds.logging.api.Loggers;
import org.hiero.otter.fixtures.InstrumentedNode;

/**
 * An implementation of {@link InstrumentedNode} for the Turtle framework.
 */
public class TurtleInstrumentedNode extends TurtleNode implements InstrumentedNode {

    private final Logger log = Loggers.getLogger(TurtleInstrumentedNode.class);

    /**
     * {@inheritDoc}
     */
    @Override
    public void setBranchingProbability(final double probability) {
        log.warn("Setting branching probability is not implemented yet.");
    }
}
