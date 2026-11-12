// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NetworkProperty;
import com.hedera.node.config.NodeProperty;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.validation.ConfigViolation;
import com.swirlds.config.api.validation.annotation.ConstraintMethod;
import com.swirlds.config.api.validation.annotation.Min;
import com.swirlds.config.extensions.validators.DefaultConfigViolation;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
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

        // Denominator used to compute signing threshold: totalWeight / signingThresholdDivisor
        @ConfigProperty(defaultValue = "2") @Min(1) @NetworkProperty @ConstraintMethod("witnessSigningThreshold")
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

        // Whether to sign blocks with a mock signature (the block hash, hashed with blockStream.digestType)
        // instead of a real TSS signature
        @ConfigProperty(defaultValue = "false") @NetworkProperty
        boolean forceMockSignatures,

        // Whether to build a fresh genesis WRAPS proof for the current roster in the first round after an
        // upgrade, replacing the active proof; e.g., after a TSS library change that cannot extend proofs
        // made by its predecessor. Applies to every upgrade while set, and has no effect once block proofs
        // carry the chain of trust
        @ConfigProperty(defaultValue = "false") @NetworkProperty
        boolean needsFreshGenesisWrapsProof) {
    /** Real hinTS history needs attainable block signatures with strictly more than one third of the weight. */
    public @Nullable ConfigViolation witnessSigningThreshold(@NonNull final Configuration configuration) {
        if (hintsEnabled
                && historyEnabled
                && !forceMockSignatures
                && (signingThresholdDivisor < 2 || signingThresholdDivisor > 3)) {
            return new DefaultConfigViolation(
                    "tss.signingThresholdDivisor",
                    Integer.toString(signingThresholdDivisor),
                    true,
                    "Real hinTS history requires signingThresholdDivisor of 2 or 3 for durable witnesses");
        }
        return null;
    }
}
