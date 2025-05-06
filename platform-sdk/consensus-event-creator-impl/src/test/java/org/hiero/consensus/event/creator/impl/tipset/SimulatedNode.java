// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl.tipset;

import com.swirlds.platform.event.orphan.OrphanBuffer;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.event.creator.impl.EventCreator;
import org.hiero.consensus.model.node.NodeId;

/**
 * A "node" with the classes required for testing event creation and verifying the results.
 *
 * @param nodeId                 the node ID of the simulated node
 * @param tipsetTracker          tracks tipsets of events
 * @param eventCreator           the event creator for the simulated node
 * @param tipsetWeightCalculator used to sanity check event creation logic
 */
public record SimulatedNode(
        @NonNull NodeId nodeId,
        @NonNull OrphanBuffer orphanBuffer,
        @NonNull TipsetTracker tipsetTracker,
        @NonNull EventCreator eventCreator,
        @NonNull TipsetWeightCalculator tipsetWeightCalculator) {}
