// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.migration;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

/**
 * @param applyFreezeTimeInRound if this value is higher than 0, the freeze time will be set 10 seconds after handling this round.
 */
@ConfigData("migrationTestingToolConfig")
public record MigrationTestingToolConfig(@ConfigProperty(defaultValue = "-1") long applyFreezeTimeInRound) {}
