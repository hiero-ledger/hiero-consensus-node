// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.chaosbot.internal;

import static org.hiero.otter.fixtures.chaosbot.internal.RandomUtil.randomGaussianDuration;

import com.swirlds.common.test.fixtures.Randotron;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.TestEnvironment;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.network.Partition;

/**
 * An experiment that creates a network partition for a duration.
 */
public class NetworkPartitionExperiment extends Experiment {

    private static final Logger log = LogManager.getLogger();

    // These values will become configurable in the future.
    private static final int MAX_PARTITIONS = 3;
    private static final double MIN_PARTITION_FRACTION = 0.2;
    private static final double MAX_PARTITION_FRACTION = 0.8;
    private static final Duration MEAN_DURATION = Duration.ofMinutes(2L);
    private static final Duration DURATION_DEVIATION = Duration.ofSeconds(30L);

    private final Partition partition;

    private NetworkPartitionExperiment(
            @NonNull final Network network, @NonNull final Instant endTime, @NonNull final Partition partition) {
        super(network, endTime);
        this.partition = partition;
    }

    /**
     * Creates and starts a node failure experiment.
     *
     * @param env the test environment
     * @param randotron the random number generator
     * @return the created experiment, or {@code null} if there are already too many partitions
     */
    @Nullable
    public static NetworkPartitionExperiment create(
            @NonNull final TestEnvironment env, @NonNull final Randotron randotron) {
        final TimeManager timeManager = env.timeManager();
        final Network network = env.network();
        if (network.networkPartitions().size() >= MAX_PARTITIONS) {
            log.info(
                    "Network has already the maximum number ({}) of partitions. Skipping network partition experiment.",
                    MAX_PARTITIONS);
            return null;
        }
        final List<Node> nodes;
        if (network.networkPartitions().isEmpty()) {
            nodes = network.nodes();
        } else {
            nodes = new ArrayList<>(network.networkPartitions().stream()
                    .map(Partition::nodes)
                    .max(Comparator.comparing(Set::size))
                    .orElseThrow());
        }
        final double partitionFraction =
                MIN_PARTITION_FRACTION + (randotron.nextDouble() * (MAX_PARTITION_FRACTION - MIN_PARTITION_FRACTION));
        final int partitionSize = (int) Math.ceil(nodes.size() * partitionFraction);
        final List<Node> partitionNodes = randotron
                .ints(partitionSize, 0, nodes.size())
                .mapToObj(nodes::get)
                .toList();
        final Partition partition = network.createNetworkPartition(partitionNodes);
        final Duration duration = randomGaussianDuration(randotron, MEAN_DURATION, DURATION_DEVIATION);
        log.info(
                "Starting network partition experiment with partition size {} and duration {}.",
                partitionSize,
                duration);
        return new NetworkPartitionExperiment(network, timeManager.now().plus(duration), partition);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void end() {
        network.removeNetworkPartition(partition);
        log.info("Ended network partition experiment.");
    }
}
