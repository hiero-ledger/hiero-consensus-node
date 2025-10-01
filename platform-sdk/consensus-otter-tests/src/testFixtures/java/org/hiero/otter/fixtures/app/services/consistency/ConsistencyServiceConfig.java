package org.hiero.otter.fixtures.app.services.consistency;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

/**
 * Configuration for the Consistency Service
 *
 * @param logfileDirectory the directory where the consistency log files will be stored
 */
@ConfigData("consistencyTestingTool")
public record ConsistencyServiceConfig(
        @ConfigProperty(defaultValue = "consistency-test") String historyFileDirectory,
        @ConfigProperty(defaultValue = "ConsistencyTestLog.csv") String historyFileName) {
}
