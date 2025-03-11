// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NetworkProperty;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

@ConfigData("topics")
public record TopicsConfig(
        @ConfigProperty(defaultValue = "1000000") @NetworkProperty long maxNumber,
        @ConfigProperty(defaultValue = "10") @NetworkProperty int maxCustomFeeEntriesForTopics,
        @ConfigProperty(defaultValue = "10") @NetworkProperty int maxEntriesForFeeExemptKeyList) {}
