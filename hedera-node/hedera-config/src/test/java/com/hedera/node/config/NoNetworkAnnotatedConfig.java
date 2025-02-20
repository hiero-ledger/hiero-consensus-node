// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

@ConfigData
public record NoNetworkAnnotatedConfig(
        @ConfigProperty(defaultValue = "true") boolean notSureProperty,
        @ConfigProperty(defaultValue = "true") @NodeProperty boolean nodeProperty) {}
