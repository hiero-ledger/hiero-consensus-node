// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.app.hapi.utils.sysfiles.domain.throttling.ScaleFactor;
import com.hedera.node.config.NetworkProperty;
import com.hedera.node.config.types.HederaFunctionalitySet;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

// Spotless requires way too many newlines, and ends up breaking the string because it forces too many indents.
// spotless:off
@ConfigData("scheduling")
public record SchedulingConfig(
        @ConfigProperty(defaultValue = "1:10") ScaleFactor schedulableCapacityFraction,
        @ConfigProperty(defaultValue = "true") @NetworkProperty boolean longTermEnabled,
        @ConfigProperty(defaultValue = "100") @NetworkProperty int maxExecutionsPerUserTxn,
        @ConfigProperty(defaultValue = "100") @NetworkProperty int maxTxnPerSec,
        @ConfigProperty(defaultValue = "1000") @NetworkProperty int consTimeSeparationNanos,
        @ConfigProperty(defaultValue = "100") @NetworkProperty int reservedSystemTxnNanos,
        @ConfigProperty(defaultValue = "10000000") @NetworkProperty long maxNumber,
        @ConfigProperty(defaultValue = "5356800") @NetworkProperty long maxExpirationFutureSeconds,
        @ConfigProperty(defaultValue =
            "ConsensusSubmitMessage,CryptoTransfer,TokenCreate,TokenUpdate,TokenMint,TokenBurn,CryptoCreate,CryptoUpdate,"
                + "FileUpdate,SystemDelete,SystemUndelete,Freeze,ContractCall,ContractCreate,ContractUpdate,"
                + "ContractDelete,CryptoApproveAllowance,NodeCreate,NodeUpdate,NodeDelete")
                @NetworkProperty HederaFunctionalitySet whitelist) {}
// spotless:on
