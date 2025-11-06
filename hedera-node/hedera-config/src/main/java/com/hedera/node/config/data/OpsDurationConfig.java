// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NetworkProperty;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import java.util.List;

@ConfigData("contracts.ops.duration")
public record OpsDurationConfig(
        @ConfigProperty(
                        defaultValue =
                                "0,123,105,93,100,116,212,208,290,262,307,106,0,0,0,0,55,56,77,77,63,35,91,92,92,85,63,136,149,131,0,0,693,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,23,270,23,23,23,69,28,161,29,243,23,271,349,30,106,279,49,23,30,29,23,30,23,32,0,0,0,0,0,0,0,0,20,77,102,78,260,713,143,155,29,30,29,5,0,0,0,0,21,20,20,21,20,20,21,21,21,21,27,21,21,21,21,23,21,21,22,23,22,22,22,22,22,22,23,23,22,22,23,23,17,17,17,17,17,17,17,17,17,18,18,18,18,18,18,18,27,27,28,27,28,28,28,28,28,29,28,29,29,29,29,29,109,677,734,808,959,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,26552,98859,2011,0,1596,11291,0,0,0,0,2091,0,0,0,0,0")
                @NetworkProperty
                List<Long> opsDurationByOpCode,
        @ConfigProperty(defaultValue = "3332") @NetworkProperty long accountLazyCreationOpsDurationMultiplier,
        @ConfigProperty(defaultValue = "1575") @NetworkProperty long opsGasBasedDurationMultiplier,
        @ConfigProperty(defaultValue = "1575") @NetworkProperty long precompileGasBasedDurationMultiplier,
        @ConfigProperty(defaultValue = "1575") @NetworkProperty long systemContractGasBasedDurationMultiplier) {}
