// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.wiring;

import com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfiguration;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

/**
 * Contains configuration values for the platform schedulers.
 *
 * @param consensusEngine                      configuration for the consensus engine scheduler
 * @param pcesSequencer                        configuration for the preconsensus event sequencer scheduler
 * @param roundDurabilityBuffer                configuration for the round durability buffer scheduler
 * @param platformMonitor                      configuration for the platform monitor scheduler
 * @param transactionPool                      configuration for the transaction pool scheduler
 */
@ConfigData("platformSchedulers")
public record PlatformSchedulersConfig(
        @ConfigProperty(
                defaultValue =
                        "SEQUENTIAL_THREAD CAPACITY(500) FLUSHABLE SQUELCHABLE UNHANDLED_TASK_METRIC BUSY_FRACTION_METRIC")
        TaskSchedulerConfiguration consensusEngine,

        @ConfigProperty(defaultValue = "SEQUENTIAL CAPACITY(500) FLUSHABLE UNHANDLED_TASK_METRIC BUSY_FRACTION_METRIC")
        TaskSchedulerConfiguration futureEventBuffer,

        @ConfigProperty(defaultValue = "DIRECT") TaskSchedulerConfiguration pcesSequencer,

        @ConfigProperty(defaultValue = "SEQUENTIAL CAPACITY(5) FLUSHABLE UNHANDLED_TASK_METRIC")
        TaskSchedulerConfiguration roundDurabilityBuffer,

        @ConfigProperty(defaultValue = "SEQUENTIAL CAPACITY(500) FLUSHABLE UNHANDLED_TASK_METRIC")
        TaskSchedulerConfiguration platformMonitor,

        @ConfigProperty(defaultValue = "DIRECT_THREADSAFE") TaskSchedulerConfiguration transactionPool) {}
