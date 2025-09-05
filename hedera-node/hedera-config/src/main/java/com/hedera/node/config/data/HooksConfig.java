// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NetworkProperty;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

@ConfigData("hooks")
public record HooksConfig(
        @ConfigProperty(defaultValue = "10") @NetworkProperty int maxLambdaSStoreUpdates,
        @ConfigProperty(defaultValue = "false") @NetworkProperty boolean hooksEnabled) {}
