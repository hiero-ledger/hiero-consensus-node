// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NetworkProperty;
import com.hedera.node.config.NodeProperty;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import java.time.Duration;

/**
 *
 */
@ConfigData("tss")
public record TssConfig(
        @ConfigProperty(defaultValue = "60s") @NetworkProperty Duration bootstrapHintsKeyGracePeriod,
        @ConfigProperty(defaultValue = "300s") @NetworkProperty Duration transitionHintsKeyGracePeriod,
        @ConfigProperty(defaultValue = "60s") @NetworkProperty Duration bootstrapProofKeyGracePeriod,
        @ConfigProperty(defaultValue = "300s") @NetworkProperty Duration transitionProofKeyGracePeriod,
        @ConfigProperty(defaultValue = "10s") @NetworkProperty Duration crsUpdateContributionTime,
        @ConfigProperty(defaultValue = "5s") @NetworkProperty Duration crsFinalizationDelay,
        @ConfigProperty(defaultValue = "data/keys/tss") @NodeProperty String tssKeysPath,
        @ConfigProperty(defaultValue = "512") @NetworkProperty short initialCrsParties,
        @ConfigProperty(defaultValue = "false") @NetworkProperty boolean hintsEnabled,
        @ConfigProperty(defaultValue = "false") @NetworkProperty boolean historyEnabled,
        // Whether to use recursive chain-of-trust proofs
        @ConfigProperty(defaultValue = "false") @NetworkProperty boolean wrapsEnabled,
        // Must be true if enabling TSS while also using an override network,
        // to give express consent for breaking the address book chain of trust
        @ConfigProperty(defaultValue = "false") @NetworkProperty boolean forceHandoffs) {}
