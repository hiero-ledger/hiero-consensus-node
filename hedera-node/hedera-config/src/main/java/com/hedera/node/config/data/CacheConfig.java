// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NetworkProperty;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

@ConfigData("cache")
public record CacheConfig(
        @ConfigProperty(value = "records.ttl", defaultValue = "180") @NetworkProperty int recordsTtl,
        @ConfigProperty(value = "warmThreads", defaultValue = "30") @NetworkProperty int warmThreads) {}
