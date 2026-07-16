// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.wiring;

import com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfiguration;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

/**
 * Contains configuration values for the platform schedulers.
 *
 * @param platformMonitor                      configuration for the platform monitor scheduler
 */
@ConfigData("platformSchedulers")
public record PlatformSchedulersConfig(
        @ConfigProperty(defaultValue = "SEQUENTIAL CAPACITY(500) FLUSHABLE UNHANDLED_TASK_METRIC")
        TaskSchedulerConfiguration platformMonitor) {}
