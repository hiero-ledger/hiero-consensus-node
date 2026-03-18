// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.config;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import org.hiero.consensus.monitoring.FallenBehindMonitor;

/**
 * Configuration for the {@link FallenBehindMonitor}.
 *
 * @param fallenBehindThreshold                  The fraction of neighbors needed to tell us we have fallen behind
 *                                               before we initiate a reconnect.
 */
@ConfigData("fallen.behind")
public record FallenBehindConfig(
        @ConfigProperty(defaultValue = "0.50") double fallenBehindThreshold) {}
