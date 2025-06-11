// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.container;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.InstrumentedNode;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.TransactionGenerator;
import org.hiero.otter.fixtures.internal.AbstractNetwork;
import org.hiero.otter.fixtures.internal.RegularTimeManager;

/**
 * An implementation of {@link Network} for the container environment.
 * This class provides a basic structure for a container network, but does not implement all functionalities yet.
 */
public class ContainerNetwork extends AbstractNetwork implements Network {

    private static final Logger log = LogManager.getLogger();

    private static final Duration DEFAULT_START_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration DEFAULT_FREEZE_TIMEOUT = Duration.ofMinutes(1);
    private static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofMinutes(1);

    private final org.testcontainers.containers.Network network = org.testcontainers.containers.Network.newNetwork();
    private final RegularTimeManager timeManager;
    private final ContainerTransactionGenerator transactionGenerator;
    private final List<ContainerNode> nodes = new ArrayList<>();
    private final List<Node> publicNodes = Collections.unmodifiableList(nodes);

    private long nextNodeId = 1L;

    /**
     * Constructor for SoloNetwork.
     *
     * @param timeManager the time manager to use
     * @param transactionGenerator the transaction generator to use
     */
    public ContainerNetwork(
            @NonNull final RegularTimeManager timeManager,
            @NonNull final ContainerTransactionGenerator transactionGenerator) {
        super(DEFAULT_START_TIMEOUT, DEFAULT_FREEZE_TIMEOUT, DEFAULT_SHUTDOWN_TIMEOUT);
        this.timeManager = requireNonNull(timeManager);
        this.transactionGenerator = requireNonNull(transactionGenerator);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    protected TimeManager timeManager() {
        return timeManager;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    protected byte[] createFreezeTransaction(@NonNull final Instant freezeTime) {
        throw new UnsupportedOperationException("Not implemented yet!");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    protected TransactionGenerator transactionGenerator() {
        return transactionGenerator;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public List<Node> addNodes(final int count) {
        throwIfInState(State.RUNNING, "Cannot add nodes while the network is running.");

        final List<ContainerNode> newNodes =
                IntStream.range(0, count).mapToObj(i -> createNode()).toList();
        nodes.addAll(newNodes);
        sendUpdatedRosterToNodes();

        //noinspection unchecked
        return (List) newNodes;
    }

    private ContainerNode createNode() {
        final NodeId selfId = NodeId.of(nextNodeId++);
        return new ContainerNode(selfId, network);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public InstrumentedNode addInstrumentedNode() {
        throw new UnsupportedOperationException("InstrumentedNode is not implemented yet!");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public List<Node> getNodes() {
        return publicNodes;
    }

    private void sendUpdatedRosterToNodes() {
        final List<RosterEntry> rosterEntries =
                nodes.stream().map(ContainerNode::rosterEntry).toList();
        final Roster roster = Roster.newBuilder().rosterEntries(rosterEntries).build();
        for (final ContainerNode node : nodes) {
            node.setRoster(roster);
        }
    }

    /**
     * Shuts down the network and cleans up resources. Once this method is called, the network cannot be started again.
     * This method is idempotent and can be called multiple times without any side effects.
     *
     * @throws InterruptedException if the thread is interrupted while the network is being destroyed
     */
    void destroy() throws InterruptedException {
        log.info("Destroying network...");
        transactionGenerator.stop();
        for (final ContainerNode node : nodes) {
            node.destroy();
        }
    }
}
