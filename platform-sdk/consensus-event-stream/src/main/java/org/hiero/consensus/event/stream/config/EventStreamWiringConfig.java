// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.stream.config;

import com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfiguration;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

/**
 * Contains configuration values for the consensus event stream wiring.
 *
 * @param consensusEventStream configuration for the consensus event stream scheduler
 */
@ConfigData("event.stream.wiring")
public record EventStreamWiringConfig(
        @ConfigProperty(defaultValue = "DIRECT_THREADSAFE") TaskSchedulerConfiguration consensusEventStream) {}
