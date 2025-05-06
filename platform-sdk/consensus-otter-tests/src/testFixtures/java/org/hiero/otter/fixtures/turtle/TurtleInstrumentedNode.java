// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.turtle;

import com.swirlds.base.time.Time;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.platform.test.fixtures.turtle.gossip.SimulatedNetwork;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.AddressBook;
import org.hiero.otter.fixtures.InstrumentedNode;

/**
 * An implementation of {@link InstrumentedNode} for the Turtle framework.
 */
public class TurtleInstrumentedNode extends TurtleNode implements InstrumentedNode {

    private final Logger log = LogManager.getLogger(TurtleInstrumentedNode.class);

    /**
     * Constructor for TurtleInstrumentedNode.
     *
     * @param randotron
     * @param time
     * @param nodeId
     * @param addressBook
     * @param privateKey
     * @param network
     * @param rootOutputDirectory
     */
    public TurtleInstrumentedNode(
            @NonNull final Randotron randotron,
            @NonNull final Time time,
            @NonNull final NodeId nodeId,
            @NonNull final AddressBook addressBook,
            @NonNull final KeysAndCerts privateKey,
            @NonNull final SimulatedNetwork network,
            @NonNull final Path rootOutputDirectory) {
        super(randotron, time, nodeId, addressBook, privateKey, network, rootOutputDirectory);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setBranchingProbability(final double probability) {
        log.warn("Setting branching probability is not implemented yet.");
    }
}
