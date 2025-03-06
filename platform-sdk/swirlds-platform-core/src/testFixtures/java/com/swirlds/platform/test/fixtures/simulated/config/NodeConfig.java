// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.simulated.config;

import com.swirlds.platform.test.fixtures.simulated.Latency;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;

/**
 * Configuration for a node in a network simulation.
 *
 * @param createEventEvery create an event at this interval
 * @param customLatency    set the network latency for this node to this value
 * @param intakeQueueDelay the amount of time an event sits in the intake queue before being processed.
 */
public record NodeConfig(
        @NonNull Duration createEventEvery, @NonNull Latency customLatency, @NonNull Duration intakeQueueDelay) {}
