// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.falcon.recording;

import static java.util.Objects.requireNonNull;

import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.List;
import org.hiero.consensus.model.node.NodeId;

/**
 * The steps of one recorded node, and what a benchmark needs to rebuild the node's event creator exactly.
 *
 * @param nodeId the ID of the node
 * @param configuration the configuration the node built its components from
 * @param startTime the simulated time at which the node built its event creator; the creator takes it as the time of
 * its latest event window, so it goes into the creation time of the node's first events
 * @param creatorSeed the seed of the event creator's random; the creator gets
 * {@code new SecureRandomBuilder(creatorSeed).get()} ({@link org.hiero.otter.fixtures.util.SecureRandomBuilder})
 * @param steps the steps, in the order they happened
 */
public record NodeRecording(
        @NonNull NodeId nodeId,
        @NonNull Configuration configuration,
        @NonNull Instant startTime,
        long creatorSeed,
        @NonNull List<Step> steps) {

    /**
     * Creates a node recording.
     *
     * @param nodeId the ID of the node
     * @param configuration the configuration the node built its components from
     * @param startTime the simulated time at which the node built its event creator
     * @param creatorSeed the seed of the event creator's random
     * @param steps the steps, in the order they happened
     */
    public NodeRecording {
        requireNonNull(nodeId);
        requireNonNull(configuration);
        requireNonNull(startTime);
        steps = List.copyOf(steps);
    }
}
