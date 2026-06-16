// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NetworkProperty;
import com.hedera.node.config.NodeProperty;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.validation.annotation.Min;
import java.time.Duration;

/**
 *
 */
@ConfigData("tss")
public record TssConfig(
        @ConfigProperty(defaultValue = "60s") @NetworkProperty
        Duration bootstrapHintsKeyGracePeriod,

        @ConfigProperty(defaultValue = "300s") @NetworkProperty
        Duration transitionHintsKeyGracePeriod,

        @ConfigProperty(defaultValue = "10s") @NetworkProperty
        Duration wrapsMessageGracePeriod,

        @ConfigProperty(defaultValue = "300s") @NetworkProperty
        Duration bootstrapProofKeyGracePeriod,

        @ConfigProperty(defaultValue = "300s") @NetworkProperty
        Duration transitionProofKeyGracePeriod,

        @ConfigProperty(defaultValue = "10s") @NetworkProperty
        Duration crsUpdateContributionTime,

        @ConfigProperty(defaultValue = "5s") @NetworkProperty
        Duration crsFinalizationDelay,

        @ConfigProperty(defaultValue = "data/keys/tss") @NodeProperty
        String tssKeysPath,

        @ConfigProperty(defaultValue = "false") @NetworkProperty
        boolean useDeterministicHintsSignatures,

        @ConfigProperty(defaultValue = "true") @NetworkProperty
        boolean hintsEnabled,

        @ConfigProperty(defaultValue = "true") @NetworkProperty
        boolean historyEnabled,
        // Whether to switch to the WrapsHistoryProver after the genesis block
        @ConfigProperty(defaultValue = "true") @NetworkProperty
        boolean wrapsEnabled,
        // Must be true if enabling TSS while also using an override network,
        // to give express consent for breaking the address book chain of trust
        @ConfigProperty(defaultValue = "false") @NetworkProperty
        boolean forceHandoffs,
        // Denominator used to compute signing threshold: totalWeight / signingThresholdDivisor
        @ConfigProperty(defaultValue = "2") @Min(1) @NetworkProperty
        int signingThresholdDivisor,

        @ConfigProperty(defaultValue = "10") @Min(0) @NetworkProperty
        int maxWrapsRetries,

        @ConfigProperty(defaultValue = "5s") Duration wrapsVoteJitterPerRank,

        // Whether to double-check aggregate hinTS signature during block signing
        @ConfigProperty(defaultValue = "false") @NetworkProperty
        boolean validateBlockSignatures,

        // Whether to force BlockProof#signed_block_proof.proof fields to SHA-384 hash of block hash; true
        // in prod until release that fully cuts over to streamMode=BLOCKS
        @ConfigProperty(defaultValue = "false") @NetworkProperty
        boolean forceMockSignatures,

        @ConfigProperty(defaultValue = "true") @NetworkProperty
        boolean wrapsProvingKeyDownloadEnabled,

        @ConfigProperty(defaultValue = "data/keys/wraps") @NodeProperty
        String wrapsProvingKeyPath,

        @ConfigProperty(
                defaultValue =
                        "620cbcf69098d31a0893081cb76113ee0f72091b3417e601178cdc376c81e5c2407c1827d123df44bccb78ad4bb11fb3")
        @NetworkProperty
        String wrapsProvingKeyHash,

        @ConfigProperty(defaultValue = "https://builds.hedera.com/tss/hiero/wraps/v1.0/wraps-v1.0.0.tar.gz")
        @NetworkProperty
        String wrapsProvingKeyDownloadUrl,

        @ConfigProperty(defaultValue = "300s") @NetworkProperty
        Duration wrapsProvingKeyRetryInterval) {}
