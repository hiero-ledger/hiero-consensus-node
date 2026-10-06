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
        // Must be true if enabling TSS while also using an override network,
        // to give express consent for breaking the address book chain of trust
        @ConfigProperty(defaultValue = "false") @NetworkProperty
        boolean forceHandoffs,
        // Denominator used to compute signing threshold: totalWeight / signingThresholdDivisor
        @ConfigProperty(defaultValue = "2") @Min(1) @NetworkProperty
        int signingThresholdDivisor,

        @ConfigProperty(defaultValue = "10") @Min(0) @NetworkProperty
        int maxWrapsRetries,

        // How long each node waits, per rank it has among the source nodes (where nodes that published R1 messages for
        // the construction rank first), before computing its own WRAPS proof instead of voting congruent with a valid
        // proof from a higher-ranked node; should exceed the time to compute a proof and reach consensus on its vote
        @ConfigProperty(defaultValue = "30s") Duration wrapsVoteJitterPerRank,

        // Whether to double-check aggregate hinTS signature during block signing
        @ConfigProperty(defaultValue = "false") @NetworkProperty
        boolean validateBlockSignatures,

        // Whether to force BlockProof#signed_block_proof.proof fields to SHA-384 hash of block hash; true
        // in prod until release that fully cuts over to streamMode=BLOCKS
        @ConfigProperty(defaultValue = "true") @NetworkProperty
        boolean forceMockSignatures,

        // Whether to build a fresh genesis WRAPS proof for the current roster in the first round after an
        // upgrade, replacing the active proof; e.g., after a TSS library change that cannot extend proofs
        // made by its predecessor. Applies to every upgrade while set, and has no effect once block proofs
        // carry the chain of trust
        @ConfigProperty(defaultValue = "false") @NetworkProperty
        boolean needsFreshGenesisWrapsProof) {}
