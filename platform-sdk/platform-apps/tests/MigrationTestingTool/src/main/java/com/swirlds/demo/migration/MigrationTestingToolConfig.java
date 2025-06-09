// SPDX-License-Identifier: Apache-2.0
package com.swirlds.demo.migration;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

@ConfigData("migrationTestingToolConfig")
public record MigrationTestingToolConfig(
        @ConfigProperty(defaultValue = "false") boolean generateFreezeState,
        @ConfigProperty(defaultValue = "400") long targetFreezeRoundAfter) {}
