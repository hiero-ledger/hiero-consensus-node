package org.hiero.consensus.transaction.handling.config;

import com.swirlds.component.framework.schedulers.builders.TaskSchedulerConfiguration;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

/**
 * Contains configuration values for the transaction handling wiring.
 *
 * @param prehandler configuration for the application transaction prehandler scheduler
 */
@ConfigData("transaction.handling.wiring")
public record TransactionHandlingWiringConfig (
    @ConfigProperty(defaultValue = "CONCURRENT CAPACITY(500) FLUSHABLE UNHANDLED_TASK_METRIC")
    TaskSchedulerConfiguration prehandler
    ) {}
