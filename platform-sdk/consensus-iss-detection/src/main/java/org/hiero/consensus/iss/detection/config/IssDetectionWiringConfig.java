// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.iss.detection.config;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import org.hiero.consensus.wiring.framework.schedulers.builders.TaskSchedulerConfiguration;

/**
 * Contains configuration values for the {@link org.hiero.consensus.iss.detection.IssDetectionModule}'s internal wiring.
 *
 * @param issDetector configuration for the ISS detector scheduler
 * @param issHandler configuration for the ISS handler scheduler
 */
@ConfigData("iss.detection.wiring")
public record IssDetectionWiringConfig(
        @ConfigProperty(defaultValue = "SEQUENTIAL CAPACITY(500) UNHANDLED_TASK_METRIC")
        TaskSchedulerConfiguration issDetector,

        @ConfigProperty(defaultValue = "DIRECT") TaskSchedulerConfiguration issHandler) {}
