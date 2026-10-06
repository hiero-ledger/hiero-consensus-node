// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.stream.config;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import org.hiero.consensus.wiring.framework.schedulers.builders.TaskSchedulerConfiguration;

/**
 * Contains configuration values for the consensus event stream wiring.
 *
 * @param consensusEventStream configuration for the consensus event stream scheduler
 */
@ConfigData("event.stream.wiring")
public record EventStreamWiringConfig(
        @ConfigProperty(defaultValue = "DIRECT_THREADSAFE") TaskSchedulerConfiguration consensusEventStream) {}
