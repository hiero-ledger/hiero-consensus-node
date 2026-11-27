// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.intake.impl;

import java.util.List;
import org.hiero.base.crypto.SigningSchema;
import org.hiero.consensus.main.model.NodeId;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.hiero.otter.fixtures.falcon.recording.FalconRecorder;
import org.hiero.otter.fixtures.falcon.recording.NodeRecording;
import org.hiero.otter.fixtures.falcon.recording.Recording;
import org.hiero.otter.fixtures.falcon.recording.RecordingSpec;
import org.hiero.otter.fixtures.falcon.recording.Step;
import org.hiero.otter.fixtures.falcon.recording.Step.Received;

/**
 * The first node of a Falcon recording of 28 nodes with the ping times and transactions of mainnet and RSA signatures,
 * as on mainnet. Its steps hold no echoes: the simulated network sends a node its own events back, which a real
 * network does not, so {@link #record()} removes them.
 *
 * <p>The recorded configuration is not passed on: it runs some intake components on direct schedulers, which suits the
 * simulation but not a measurement.
 */
final class RecordedNode {

    /** The seed of the simulation. The same seed produces the same events. */
    private static final long SEED = 0;

    /** The number of nodes in the simulated network. */
    private static final int NUMBER_OF_NODES = 28;

    /** The number of events the recorded node releases from its orphan buffer, its own included. */
    private static final int NUMBER_OF_EVENTS = 115_000;

    private final NodeId nodeId;
    private final RosterWrapper roster;
    private final List<Step> steps;

    private RecordedNode(final NodeId nodeId, final RosterWrapper roster, final List<Step> steps) {
        this.nodeId = nodeId;
        this.roster = roster;
        this.steps = steps;
    }

    /**
     * Simulates the network and records its first node, without the echoes of the node's own events. This takes
     * minutes.
     *
     * @return the recorded node
     */
    static RecordedNode record() {
        final Recording recording = FalconRecorder.record(RecordingSpec.builder(NUMBER_OF_NODES, SEED, NUMBER_OF_EVENTS)
                .signingSchema(SigningSchema.RSA)
                .build());
        final NodeRecording node = recording.nodes().getFirst();
        final List<Step> steps = node.steps().stream()
                .filter(step -> !isEcho(step, node.nodeId()))
                .toList();
        return new RecordedNode(node.nodeId(), recording.roster(), steps);
    }

    /**
     * @return the ID of the recorded node
     */
    NodeId nodeId() {
        return nodeId;
    }

    /**
     * @return the nodes of the network
     */
    RosterWrapper roster() {
        return roster;
    }

    /**
     * @return the recorded steps of the node without echoes, in the order of their times; unmodifiable
     */
    List<Step> steps() {
        return steps;
    }

    /**
     * Tells whether a step is an echo: the node receiving one of its own events.
     */
    private static boolean isEcho(final Step step, final NodeId nodeId) {
        return step instanceof final Received received
                && received.event().getCreatorId().equals(nodeId);
    }
}
