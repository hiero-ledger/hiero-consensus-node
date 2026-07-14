// SPDX-License-Identifier: Apache-2.0
package com.y;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

@ConfigData("fix.c")
public record MigratedConfig(
        @ConfigProperty(defaultValue = "1") int goneKey,
        @ConfigProperty(defaultValue = "2") int legacyGoneKey,
        @ConfigProperty(defaultValue = "3") int unrelatedThing) {}
