// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status.monitor.config;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import org.hiero.consensus.wiring.framework.schedulers.builders.TaskSchedulerConfiguration;

/**
 * Contains configuration values for the status monitor.
 *
 * @param statusMonitor configuration for the status monitor scheduler
 */
@ConfigData("status.monitor")
public record StatusMonitorWiringConfig(
        @ConfigProperty(defaultValue = "SEQUENTIAL CAPACITY(500) FLUSHABLE UNHANDLED_TASK_METRIC")
        TaskSchedulerConfiguration statusMonitor) {}
