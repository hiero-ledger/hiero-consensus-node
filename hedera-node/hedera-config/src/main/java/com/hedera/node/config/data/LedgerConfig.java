// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NetworkProperty;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.validation.annotation.Min;

/**
 * Ledger configuration properties.
 * @param numReservedSystemEntities the max file number of reserved system special files.
 */
@ConfigData("ledger")
public record LedgerConfig(
        @ConfigProperty(defaultValue = "5000") @NetworkProperty int maxAutoAssociations,
        @ConfigProperty(defaultValue = "100") int numSystemAccounts,
        @ConfigProperty(defaultValue = "5000000000000000000") @Min(0) long totalTinyBarFloat,
        @ConfigProperty(defaultValue = "0x00") Bytes id,
        @ConfigProperty(value = "changeHistorian.memorySecs", defaultValue = "20") @NetworkProperty
                int changeHistorianMemorySecs,
        @ConfigProperty(value = "autoRenewPeriod.maxDuration", defaultValue = "8000001") @NetworkProperty
                long autoRenewPeriodMaxDuration,
        @ConfigProperty(value = "autoRenewPeriod.minDuration", defaultValue = "2592000") @NetworkProperty
                long autoRenewPeriodMinDuration,
        @ConfigProperty(value = "xferBalanceChanges.maxLen", defaultValue = "20") @NetworkProperty
                int xferBalanceChangesMaxLen,
        @ConfigProperty(defaultValue = "98") @NetworkProperty long fundingAccount,
        @ConfigProperty(value = "transfers.maxLen", defaultValue = "10") @NetworkProperty int transfersMaxLen,
        @ConfigProperty(value = "tokenRejects.maxLen", defaultValue = "10") @NetworkProperty int tokenRejectsMaxLen,
        @ConfigProperty(value = "tokenTransfers.maxLen", defaultValue = "10") @NetworkProperty int tokenTransfersMaxLen,
        @ConfigProperty(value = "nftTransfers.maxLen", defaultValue = "10") @NetworkProperty int nftTransfersMaxLen,
        @ConfigProperty(value = "records.maxQueryableByAccount", defaultValue = "180") @NetworkProperty
                int recordsMaxQueryableByAccount,
        @ConfigProperty(value = "schedule.txExpiryTimeSecs", defaultValue = "1800") @NetworkProperty
                int scheduleTxExpiryTimeSecs,
        @ConfigProperty(defaultValue = "750") @NetworkProperty long numReservedSystemEntities) {}
