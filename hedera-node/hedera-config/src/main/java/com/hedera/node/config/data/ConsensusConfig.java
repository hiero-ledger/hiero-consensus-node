// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NetworkProperty;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

@ConfigData("consensus")
public record ConsensusConfig(
        @ConfigProperty(value = "message.maxBytesAllowed", defaultValue = "1024") @NetworkProperty
                int messageMaxBytesAllowed,
        @ConfigProperty(value = "handle.maxPrecedingRecords", defaultValue = "3") @NetworkProperty
                int handleMaxPrecedingRecords,
        @ConfigProperty(value = "handle.maxFollowingRecords", defaultValue = "50") @NetworkProperty
                int handleMaxFollowingRecords) {}
