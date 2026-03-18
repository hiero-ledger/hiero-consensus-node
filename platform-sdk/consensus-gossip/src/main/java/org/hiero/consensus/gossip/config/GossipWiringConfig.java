// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.config;

import com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfiguration;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import org.hiero.consensus.gossip.GossipModule;

/**
 * Contains configuration values for the {@link GossipModule}'s internal wiring.
 *
 * @param gossip configuration for the gossip scheduler
 */
@ConfigData("event.creation.wiring")
public record GossipWiringConfig(
        @ConfigProperty(defaultValue = "SEQUENTIAL CAPACITY(500) FLUSHABLE UNHANDLED_TASK_METRIC")
        TaskSchedulerConfiguration gossip) {}
