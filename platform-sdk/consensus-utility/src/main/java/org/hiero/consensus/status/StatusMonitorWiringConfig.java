// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status;

import com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfiguration;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

/**
 * Contains configuration values for the status monitor.
 *
 * @param statusMonitor configuration for the status monitor scheduler
 */
@ConfigData("status.monitor")
public record StatusMonitorWiringConfig(
        @ConfigProperty(defaultValue = "SEQUENTIAL CAPACITY(500) FLUSHABLE UNHANDLED_TASK_METRIC")
        TaskSchedulerConfiguration statusMonitor) {}
