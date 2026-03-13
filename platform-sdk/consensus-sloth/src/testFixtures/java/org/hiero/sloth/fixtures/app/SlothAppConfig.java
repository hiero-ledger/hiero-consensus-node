// SPDX-License-Identifier: Apache-2.0
package org.hiero.sloth.fixtures.app;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import java.util.List;

/**
 * Configuration for the benchmark application.
 *
 * @param services A comma-separated list of service class names to include in the benchmark application.
 */
@ConfigData("slothApp")
public record SlothAppConfig(
        @ConfigProperty(defaultValue = "") List<String> services) {}
