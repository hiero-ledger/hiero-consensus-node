// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.transaction.handling.config;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

@ConfigData("transactionHandling")
public record TransactionHandlingConfig(
        @ConfigProperty(defaultValue = "25") double lowTpsTargetRounds) {}
