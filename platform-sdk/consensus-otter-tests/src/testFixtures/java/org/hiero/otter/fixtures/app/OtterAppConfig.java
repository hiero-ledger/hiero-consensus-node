// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import java.util.List;

/**
 * Configuration for the Otter application.
 *
 * @param services A comma-separated list of service class names to include in the Otter application.
 */
@ConfigData("event")
public record OtterAppConfig(
        @ConfigProperty(
                        defaultValue = "org.hiero.otter.fixtures.app.services.consistency.ConsistencyService,"
                                + "org.hiero.otter.fixtures.app.services.iss.IssService")
                List<String> services) {}
