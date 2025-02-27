// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.api.internal.configuration;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import java.time.Duration;

/**
 * Configuration for the internal logging system.
 *
 * @param reloadConfigPeriod the update period for the logging system. The logging system will check for updates regarding
 *                      levels and markers every updatePeriode.
 */
@ConfigData("logging")
public record InternalLoggingConfig(@ConfigProperty(defaultValue = "10s") Duration reloadConfigPeriod) {}
