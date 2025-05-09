// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NetworkProperty;
import com.hedera.node.config.types.HederaFunctionalitySet;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

@ConfigData("atomicBatch")
public record AtomicBatchConfig(
        @ConfigProperty(defaultValue = "false") @NetworkProperty boolean isEnabled,
        @ConfigProperty(defaultValue = "0") @NetworkProperty long maxNumberOfTransactions,
        @ConfigProperty(defaultValue = "Freeze,AtomicBatch") @NetworkProperty HederaFunctionalitySet blacklist) {}
