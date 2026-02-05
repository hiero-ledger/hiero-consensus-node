// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.pces.config;

import com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfiguration;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import org.hiero.consensus.pces.PcesModule;

/**
 * Contains configuration values for the {@link PcesModule}'s internal wiring.
 *
 * @param pcesInlineWriter configuration for the pces inline writer
 */
@ConfigData("event.intake.wiring")
public record PcesWiringConfig(
        @ConfigProperty(defaultValue = "SEQUENTIAL CAPACITY(500) FLUSHABLE UNHANDLED_TASK_METRIC BUSY_FRACTION_METRIC")
        TaskSchedulerConfiguration pcesInlineWriter) {}
