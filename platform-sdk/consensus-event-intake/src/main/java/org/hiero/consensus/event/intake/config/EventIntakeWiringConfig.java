// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.intake.config;

import com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfiguration;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

/**
 * Contains configuration values for the {@link EventIntakeWiringConfig}'s internal wiring.
 *
 * @param eventHasher configuration for the event hasher scheduler
 * @param internalEventValidator configuration for the internal event validator scheduler
 */
@ConfigData("event.intake.wiring")
public record EventIntakeWiringConfig(
        @ConfigProperty(defaultValue = "CONCURRENT CAPACITY(500) FLUSHABLE UNHANDLED_TASK_METRIC")
                TaskSchedulerConfiguration eventHasher,
        @ConfigProperty(defaultValue = "CONCURRENT CAPACITY(500) FLUSHABLE UNHANDLED_TASK_METRIC")
                TaskSchedulerConfiguration internalEventValidator) {}
