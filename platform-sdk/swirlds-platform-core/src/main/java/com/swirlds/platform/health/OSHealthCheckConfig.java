// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.health;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

/**
 * Configurations for the operating system health checks.
 *
 * @param minClockCallsPerSec
 * 		the minimum number of calls per second to the OS clock source that is required. If this value is not met at
 * 		startup, a warning is logged.
 * @param entropyTimeoutMillis
 * 		the maximum number of milliseconds to wait for the OS entropy check to complete before timing out. If the
 * 		operation times out, a warning is logged.
 * @param maxRandomNumberGenerationMillis
 * 		the maximum number of milliseconds allowed for a single random number to be generated. If this value is
 * 		exceeded at startup, a warning is logged.
 * @param fileReadTimeoutMillis
 * 		the maximum number of milliseconds to wait for a file to be opened and read a single byte. If this value is
 * 		exceeded at startup, a warning is logged.
 * @param maxFileReadMillis
 * 		the maximum number of milliseconds allowed to open a file and read the first byte. If this value is exceeded at
 * 		startup, a warning is logged.
 */
@ConfigData("os.health")
public record OSHealthCheckConfig(
        @ConfigProperty(value = "minClockCallsPerSec", defaultValue = "5000000") long minClockCallsPerSec,
        @ConfigProperty(value = "entropyTimeoutMillis", defaultValue = "10") long entropyTimeoutMillis,
        @ConfigProperty(value = "maxRandomNumberGenerationMillis", defaultValue = "10")
                long maxRandomNumberGenerationMillis,
        @ConfigProperty(value = "fileReadTimeoutMillis", defaultValue = "50") long fileReadTimeoutMillis,
        @ConfigProperty(value = "maxFileReadMillis", defaultValue = "10") long maxFileReadMillis) {}
