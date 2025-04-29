// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.app.hapi.utils.sysfiles.domain.throttling.ScaleFactor;
import com.hedera.node.config.NetworkProperty;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;

@ConfigData("tokens")
public record TokensConfig(
        @ConfigProperty(defaultValue = "200000000") @NetworkProperty long maxAggregateRels,
        @ConfigProperty(defaultValue = "1000000") @NetworkProperty long maxNumber,
        @ConfigProperty(defaultValue = "1000") @NetworkProperty int maxPerAccount,
        @ConfigProperty(defaultValue = "100") @NetworkProperty int maxSymbolUtf8Bytes,
        @ConfigProperty(defaultValue = "100") @NetworkProperty int maxTokenNameUtf8Bytes,
        @ConfigProperty(defaultValue = "10") @NetworkProperty int maxCustomFeesAllowed,
        @ConfigProperty(defaultValue = "2") @NetworkProperty int maxCustomFeeDepth,
        @ConfigProperty(defaultValue = "1000") @NetworkProperty int maxRelsPerInfoQuery,
        @ConfigProperty(value = "reject.enabled", defaultValue = "true") @NetworkProperty boolean tokenRejectEnabled,
        @ConfigProperty(value = "nfts.maxMetadataBytes", defaultValue = "100") @NetworkProperty
                int nftsMaxMetadataBytes,
        @ConfigProperty(value = "nfts.maxBatchSizeBurn", defaultValue = "10") @NetworkProperty int nftsMaxBatchSizeBurn,
        @ConfigProperty(value = "nfts.maxBatchSizeWipe", defaultValue = "10") @NetworkProperty int nftsMaxBatchSizeWipe,
        @ConfigProperty(value = "nfts.maxBatchSizeMint", defaultValue = "10") @NetworkProperty int nftsMaxBatchSizeMint,
        @ConfigProperty(value = "nfts.maxAllowedMints", defaultValue = "100000000") @NetworkProperty
                long nftsMaxAllowedMints,
        @ConfigProperty(value = "nfts.maxQueryRange", defaultValue = "100") @NetworkProperty long nftsMaxQueryRange,
        @ConfigProperty(value = "nfts.mintThrottleScaleFactor", defaultValue = "5:2")
                ScaleFactor nftsMintThrottleScaleFactor,
        @ConfigProperty(defaultValue = "20000000") @NetworkProperty long maxAllowedPendingAirdrops,
        @ConfigProperty(value = "maxAllowedPendingAirdropsToClaim", defaultValue = "10") @NetworkProperty
                int maxAllowedPendingAirdropsToClaim,
        @ConfigProperty(value = "maxAllowedPendingAirdropsToCancel", defaultValue = "10") @NetworkProperty
                int maxAllowedPendingAirdropsToCancel,
        @ConfigProperty(value = "maxAllowedAirdropTransfersPerTx", defaultValue = "10") @NetworkProperty
                int maxAllowedAirdropTransfersPerTx,
        @ConfigProperty(value = "maxMetadataBytes", defaultValue = "100") @NetworkProperty int tokensMaxMetadataBytes,
        @ConfigProperty(value = "balancesInQueries.enabled", defaultValue = "true") @NetworkProperty
                boolean balancesInQueriesEnabled,
        @ConfigProperty(value = "airdrops.enabled", defaultValue = "true") @NetworkProperty boolean airdropsEnabled,
        @ConfigProperty(value = "airdrops.cancel.enabled", defaultValue = "true") @NetworkProperty
                boolean cancelTokenAirdropEnabled,
        @ConfigProperty(value = "airdrops.claim.enabled", defaultValue = "true") @NetworkProperty
                boolean airdropsClaimEnabled,
        @ConfigProperty(value = "nfts.maxBatchSizeUpdate", defaultValue = "10") @NetworkProperty
                int nftsMaxBatchSizeUpdate,
        @ConfigProperty(defaultValue = "true") @NetworkProperty boolean countingGetBalanceThrottleEnabled) {}
