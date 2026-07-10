// SPDX-License-Identifier: Apache-2.0
package com.x;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

@ConfigData("fix.a")
public record FixtureConfig(
        @ConfigProperty(defaultValue = "5") int alpha,
        @ConfigProperty(defaultValue = "20s") Duration beta,
        @ConfigProperty(defaultValue = "x") String gamma,
        @ConfigProperty(defaultValue = FixtureConfig.CONST) int delta,
        @ConfigProperty(defaultValue = "") List<String> listy,
        @ConfigProperty(defaultValue = "true") boolean undocumented) {
    static final String CONST = "7";
}
