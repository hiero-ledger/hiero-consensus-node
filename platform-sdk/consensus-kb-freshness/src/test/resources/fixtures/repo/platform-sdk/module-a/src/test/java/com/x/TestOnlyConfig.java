// SPDX-License-Identifier: Apache-2.0
package com.x;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

/** Fixture: a config record outside src/main/java — must never reach the tunables catalog scan. */
@ConfigData("fix.testonly")
public record TestOnlyConfig(@ConfigProperty(defaultValue = "1") int neverDocumented) {}
