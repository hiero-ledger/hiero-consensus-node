// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NetworkProperty;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

@ConfigData("expiry")
public record ExpiryConfig(
        @ConfigProperty(defaultValue = "expiry-throttle.json") @NetworkProperty String throttleResource
        // @ConfigProperty(defaultValue =
        // "ACCOUNTS_GET,ACCOUNTS_GET_FOR_MODIFY,STORAGE_GET,STORAGE_GET,STORAGE_REMOVE,STORAGE_PUT")
        // List<MapAccessType> minCycleEntryCapacity
        ) {}
