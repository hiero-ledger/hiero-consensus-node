// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.consistency;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import java.time.Duration;

/**
 * Config for consistency testing tool
 *
 * @param logfileDirectory   the directory where consistency information is stored, relative to
 * @param freezeAfterGenesis if not 0, describes a moment in time, relative to genesis, when a freeze is scheduled
 */
@ConfigData("consistencyTestingTool")
public record ConsistencyTestingToolConfig(
        @ConfigProperty(defaultValue = "consistency-test") String logfileDirectory,
        @ConfigProperty(defaultValue = "0") Duration freezeAfterGenesis) {}
