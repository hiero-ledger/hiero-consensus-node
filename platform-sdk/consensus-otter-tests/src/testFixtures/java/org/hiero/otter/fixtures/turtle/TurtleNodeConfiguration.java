package org.hiero.otter.fixtures.turtle;

import com.swirlds.logging.api.Logger;
import com.swirlds.logging.api.Loggers;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.otter.fixtures.NodeConfiguration;

/**
 * {@link NodeConfiguration} implementation for a Turtle node.
 */
public class TurtleNodeConfiguration implements NodeConfiguration<TurtleNodeConfiguration> {

    private static final Logger log = Loggers.getLogger(TurtleNodeConfiguration.class);

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
