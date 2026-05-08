// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.consensus;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.function.Consumer;
import org.hiero.consensus.hashgraph.impl.test.fixtures.consensus.framework.ConsensusTestNode;
import org.hiero.consensus.hashgraph.impl.test.fixtures.consensus.framework.validation.ConsensusOutputValidator;
import org.hiero.consensus.hashgraph.impl.test.fixtures.consensus.framework.validation.ConsensusRoundValidator;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.generator.GraphGenerator;
import org.hiero.consensus.model.event.EventConstants;
import org.hiero.consensus.model.node.NodeId;

/** A type which orchestrates the generation of events and the validation of the consensus output */
public class ConsensusTestOrchestrator {
    private final Configuration configuration;
    private final Metrics metrics;
    private final List<ConsensusTestNode> nodes;
    private long currentSequence = 0;
    private final List<Long> weights;
    private final int totalEventNum;

    public ConsensusTestOrchestrator(
            final Configuration configuration,
            final Metrics metrics,
            final List<ConsensusTestNode> nodes,
            final List<Long> weights,
            final int totalEventNum) {
        this.configuration = configuration;
        this.metrics = metrics;
        this.nodes = nodes;
        this.weights = weights;
        this.totalEventNum = totalEventNum;
    }

    /**
     * Adds a new node to the test context by simulating a reconnect
     *
     * @param configuration the configuration to use for the new node
     * @param metrics the metrics to use for the new node
     * @param time the time to use for the new node
     */
    public void addReconnectNode(
            @NonNull final Configuration configuration, @NonNull final Metrics metrics, @NonNull final Time time) {
        final ConsensusTestNode node = nodes.get(0).reconnect(configuration, metrics, time);
        node.getEventEmitter().setCheckpoint(currentSequence);
        node.addEvents(currentSequence);
        nodes.add(node);
    }

    private void generateEvents(final int numEvents) {
        currentSequence += numEvents;
        nodes.forEach(node -> node.getEventEmitter().setCheckpoint(currentSequence));
        nodes.forEach(node -> node.addEvents(numEvents));
    }

    /** Generates all events defined in the input */
    public ConsensusTestOrchestrator generateAllEvents() {
        return generateEvents(1d);
    }

    /**
     * Generates a fraction of the total events defined in the input
     *
     * @param fractionOfTotal the fraction of events to generate
     * @return this
     */
    public ConsensusTestOrchestrator generateEvents(final double fractionOfTotal) {
        generateEvents(getEventFraction(fractionOfTotal));
        return this;
    }

    /**
     * Returns a fraction of the total events defined in the input
     *
     * @param fractionOfTotal the fraction of events to generate
     * @return the number of events that corresponds to the given fraction
     */
    public int getEventFraction(final double fractionOfTotal) {
        return (int) (totalEventNum * fractionOfTotal);
    }

    /**
     * Validates the output of all nodes against the given validations and clears the output
     *
     * @param consensusOutputValidator the validator to run all neeeded validations
     */
    public void validateAndClear(final ConsensusOutputValidator consensusOutputValidator) {
        validate(consensusOutputValidator);
        clearOutput();
    }

    /**
     * Validates the output of all nodes against the given validations
     *
     * @param consensusOutputValidator the validator to run
     */
    public void validate(final ConsensusOutputValidator consensusOutputValidator) {
        for (final ConsensusTestNode node : nodes) {
            ConsensusRoundValidator.validate(node.getOutput().getConsensusRounds());
        }

        final ConsensusTestNode node1 = nodes.getFirst();
        for (int i = 1; i < nodes.size(); i++) {
            final ConsensusTestNode otherNode = nodes.get(i);

            consensusOutputValidator.validate(node1.getOutput(), otherNode.getOutput());
            ConsensusRoundValidator.validate(
                    node1.getOutput().getConsensusRounds(),
                    otherNode.getOutput().getConsensusRounds());
        }
    }

    /** Clears the output of all nodes */
    public void clearOutput() {
        nodes.forEach(n -> n.getOutput().clear());
    }

    /**
     * Restarts all nodes with events and generations stored in the signed state. This is the currently implemented
     * restart, it discards all non-consensus events.
     */
    public void restartAllNodes() {
        final long lastRoundDecided = nodes.getFirst().getLatestRound();
        if (lastRoundDecided < EventConstants.MINIMUM_ROUND_CREATED) {
            System.out.println("Cannot restart, no consensus reached yet");
            return;
        }
        System.out.println("Restarting at round " + lastRoundDecided);
        for (final ConsensusTestNode node : nodes) {
            node.restart();
            node.getEventEmitter().setCheckpoint(currentSequence);
            node.addEvents(currentSequence);
        }
    }

    /**
     * Simulates removing a node from the network at restart
     * @param nodeId the node to remove
     */
    public void removeNode(final NodeId nodeId) {
        nodes.forEach(node -> node.removeNode(nodeId));
    }

    /**
     * Configures the graph generators of all nodes with the given configurator. This must be done for all nodes so that
     * the generators generate the same graphs
     */
    public ConsensusTestOrchestrator configGenerators(final Consumer<GraphGenerator> configurator) {
        for (final ConsensusTestNode node : nodes) {
            configurator.accept(node.getEventEmitter().getGraphGenerator());
        }
        return this;
    }

    /**
     * Calls {@link org.hiero.consensus.hashgraph.impl.test.fixtures.event.source.EventSource#setNewEventWeight(double)}
     */
    public void setNewEventWeight(final int nodeIndex, final double eventWeight) {
        for (final ConsensusTestNode node : nodes) {
            node.getEventEmitter()
                    .getGraphGenerator()
                    .getSourceByIndex(nodeIndex)
                    .setNewEventWeight(eventWeight);
        }
    }

    /** Calls {@link GraphGenerator#setOtherParentAffinity(List)} */
    public void setOtherParentAffinity(final List<List<Double>> matrix) {
        for (final ConsensusTestNode node : nodes) {
            node.getEventEmitter().getGraphGenerator().setOtherParentAffinity(matrix);
        }
    }

    /**
     * Execute the given consumer for each node
     * @param consumer the consumer to execute
     */
    public void forEachNode(@NonNull final Consumer<ConsensusTestNode> consumer) {
        for (final ConsensusTestNode node : nodes) {
            consumer.accept(node);
        }
    }

    public List<Long> getWeights() {
        return weights;
    }

    public List<ConsensusTestNode> getNodes() {
        return nodes;
    }

    public Roster getRoster() {
        return nodes.get(0).getEventEmitter().getGraphGenerator().getRoster();
    }

    @NonNull
    public Configuration getConfiguration() {
        return configuration;
    }

    @NonNull
    public Metrics getMetrics() {
        return metrics;
    }
}
