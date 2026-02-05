// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.chaosbot;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.test.fixtures.Randotron;
import org.hiero.otter.fixtures.Network;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.network.Partition;

/**
 * An experiment that creates a network partition for a duration.
 *
 * @param minDuration the minimum duration of the network partition
 * @param maxDuration the maximum duration of the network partition
 * @param maxPartitions the maximum number of partitions allowed in the network
 * @param minPartitionFraction the minimum fraction of nodes to include in the partition
 * @param maxPartitionFraction the maximum fraction of nodes to include in the partition
 */
public record NetworkPartitionExperiment(
        @NonNull Duration minDuration,
        @NonNull Duration maxDuration,
        int maxPartitions,
        double minPartitionFraction,
        double maxPartitionFraction)
        implements Experiment {

    private static final Logger log = LogManager.getLogger();

    private static final Duration DEFAULT_MIN_DURATION = Duration.ofMinutes(2L);
    private static final Duration DEFAULT_MAX_DURATION = Duration.ofMinutes(5L);
    private static final int DEFAULT_MAX_PARTITIONS = 3;
    private static final double DEFAULT_MIN_PARTITION_FRACTION = 0.2;
    private static final double DEFAULT_MAX_PARTITION_FRACTION = 0.8;

    /**
     * Creates a new NetworkPartitionExperiment with a default configuration.
     *
     * <p>The default duration range is 2 to 5 minutes, the default maximum number of partitions is 3,
     * and the default partition fraction range is 20% to 80%.
     */
    public NetworkPartitionExperiment() {
        this(
                DEFAULT_MIN_DURATION,
                DEFAULT_MAX_DURATION,
                DEFAULT_MAX_PARTITIONS,
                DEFAULT_MIN_PARTITION_FRACTION,
                DEFAULT_MAX_PARTITION_FRACTION);
    }

    /**
     * Creates a new NetworkPartitionExperiment with the specified configuration.
     *
     * @param minDuration the minimum duration of the network partition
     * @param maxDuration the maximum duration of the network partition
     * @param maxPartitions the maximum number of partitions allowed in the network
     * @param minPartitionFraction the minimum fraction of nodes to include in the partition
     * @param maxPartitionFraction the maximum fraction of nodes to include in the partition
     * @throws NullPointerException if any argument is {@code null}
     */
    public NetworkPartitionExperiment(
            @NonNull final Duration minDuration,
            @NonNull final Duration maxDuration,
            final int maxPartitions,
            final double minPartitionFraction,
            final double maxPartitionFraction) {
        this.minDuration = requireNonNull(minDuration);
        this.maxDuration = requireNonNull(maxDuration);
        this.maxPartitions = maxPartitions;
        this.minPartitionFraction = minPartitionFraction;
        this.maxPartitionFraction = maxPartitionFraction;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public String name() {
        return "Network Partition";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public List<Step> start(
            @NonNull final Network network, @NonNull final Instant now, @NonNull final Randotron randotron) {
        if (network.networkPartitions().size() >= maxPartitions) {
            log.info(
                    "Network has already the maximum number ({}) of partitions. Skipping network partition experiment.",
                    maxPartitions);
            return List.of();
        }
        final List<Node> nodes;
        if (network.networkPartitions().isEmpty()) {
            nodes = network.nodes();
        } else {
            // Select the largest existing partition to split
            nodes = new ArrayList<>(network.networkPartitions().stream()
                    .map(Partition::nodes)
                    .max(Comparator.comparing(Set::size))
                    .orElseThrow());
        }
        final double partitionFraction =
                minPartitionFraction + (randotron.nextDouble() * (maxPartitionFraction - minPartitionFraction));
        final int partitionSize = (int) Math.ceil(nodes.size() * partitionFraction);
        final List<Node> partitionNodes = randotron
                .ints(partitionSize, 0, nodes.size())
                .mapToObj(nodes::get)
                .toList();
        final Duration duration = randotron.nextDuration(minDuration, maxDuration);

        log.info(
                "Starting network partition experiment with partition size {} and duration {}.",
                partitionSize,
                duration);
        final Partition partition = network.createNetworkPartition(partitionNodes);

        return List.of(new Step(now.plus(duration), () -> {
            log.info("Ending network partition experiment.");
            network.removeNetworkPartition(partition);
        }));
    }
}
