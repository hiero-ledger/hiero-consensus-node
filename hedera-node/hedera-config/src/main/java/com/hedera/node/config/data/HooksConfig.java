// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NetworkProperty;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

@ConfigData("hooks")
public record HooksConfig(
        @ConfigProperty(defaultValue = "10") @NetworkProperty
        int maxHookStoreUpdates,

        @ConfigProperty(defaultValue = "10") @NetworkProperty
        int maxHookInvocationsPerTransaction,

        @ConfigProperty(defaultValue = "true") @NetworkProperty
        boolean hooksEnabled,

        @ConfigProperty(defaultValue = "5000000") @NetworkProperty
        long maxNumberOfHooks,

        @ConfigProperty(defaultValue = "100000000") @NetworkProperty
        long maxEvmHookStorageSlots,

        @ConfigProperty(value = "evm.intrinsicGasCost", defaultValue = "1000") @NetworkProperty
        int evmHookIntrinsicGasCost,

        @ConfigProperty(value = "hookInvocationCostTinyCents", defaultValue = "50000000") @NetworkProperty
        long hookInvocationCostTinyCents) {}
