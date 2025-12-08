// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NetworkProperty;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

/**
 * Configuration for the CLPR interledger communication protocol.
 *
 * @param connectionFrequency the frequency at which connections are made to other ledgers, in milliseconds
 * @param publicizeClprEndpoints whether the node should advertise its CLPR endpoints
 * @param devModeEnabled toggle to enable development-mode behaviours (auto-bootstrap, relaxed validation)
 */
@ConfigData("clpr")
public record ClprConfig(
        @ConfigProperty(defaultValue = "5000") @NetworkProperty int connectionFrequency,
        @ConfigProperty(defaultValue = "true") @NetworkProperty boolean publicizeClprEndpoints,
        @ConfigProperty(defaultValue = "true") @NetworkProperty boolean devModeEnabled) {}
