// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.config;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import org.hiero.consensus.wiring.framework.schedulers.builders.TaskSchedulerConfiguration;

/**
 * Contains configuration values for the {@link org.hiero.consensus.event.creator.EventCreatorModule}'s internal wiring.
 *
 * @param eventCreationManager configuration for the event creation manager scheduler
 */
@ConfigData("event.creation.wiring")
public record EventCreationWiringConfig(
        @ConfigProperty(defaultValue = "SEQUENTIAL CAPACITY(500) FLUSHABLE SQUELCHABLE UNHANDLED_TASK_METRIC")
        TaskSchedulerConfiguration eventCreationManager) {}
