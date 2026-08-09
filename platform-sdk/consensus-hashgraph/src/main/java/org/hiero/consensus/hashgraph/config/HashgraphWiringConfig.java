// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.config;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import org.hiero.consensus.wiring.framework.schedulers.builders.TaskSchedulerConfiguration;

/**
 * Contains configuration values for the {@link org.hiero.consensus.hashgraph.HashgraphModule}'s internal wiring.
 *
 * @param consensusEngine configuration for the consensus engine scheduler
 */
@ConfigData("hashgraph.wiring")
public record HashgraphWiringConfig(
        @ConfigProperty(
                defaultValue =
                        "SEQUENTIAL_THREAD CAPACITY(500) FLUSHABLE SQUELCHABLE UNHANDLED_TASK_METRIC BUSY_FRACTION_METRIC")
        TaskSchedulerConfiguration consensusEngine) {}
