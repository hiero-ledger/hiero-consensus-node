// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app.services.consistency;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

/**
 * Configuration for the Consistency Service
 *
 * @param historyFileDirectory the directory where the history file will be stored
 * @param historyFileName      the name of the history file
 */
@ConfigData("consistencyTestingTool")
public record ConsistencyServiceConfig(
        @ConfigProperty(defaultValue = "consistency-test") String historyFileDirectory,
        @ConfigProperty(defaultValue = "ConsistencyTestLog.csv") String historyFileName) {}
