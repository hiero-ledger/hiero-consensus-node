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
 * @param eventDeduplicator configuration for the event deduplicator scheduler
 * @param eventSignatureValidator configuration for the event signature validator scheduler
 * @param orphanBuffer configuration for the orphan buffer scheduler
 */
@ConfigData("event.intake.wiring")
public record EventIntakeWiringConfig(
        @ConfigProperty(defaultValue = "CONCURRENT CAPACITY(500) FLUSHABLE UNHANDLED_TASK_METRIC")
                TaskSchedulerConfiguration eventHasher,
        @ConfigProperty(defaultValue = "CONCURRENT CAPACITY(500) FLUSHABLE UNHANDLED_TASK_METRIC")
                TaskSchedulerConfiguration internalEventValidator,
        @ConfigProperty(defaultValue = "SEQUENTIAL CAPACITY(5000) FLUSHABLE UNHANDLED_TASK_METRIC")
                TaskSchedulerConfiguration eventDeduplicator,
        @ConfigProperty(defaultValue = "CONCURRENT CAPACITY(500) FLUSHABLE UNHANDLED_TASK_METRIC")
                TaskSchedulerConfiguration eventSignatureValidator,
        @ConfigProperty(defaultValue = "SEQUENTIAL CAPACITY(500) FLUSHABLE UNHANDLED_TASK_METRIC")
                TaskSchedulerConfiguration orphanBuffer) {}
