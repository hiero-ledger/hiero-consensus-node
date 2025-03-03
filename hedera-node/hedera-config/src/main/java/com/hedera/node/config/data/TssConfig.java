// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.data;

import com.hedera.node.config.NetworkProperty;
import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import java.time.Duration;

/**
 * Configuration for the TSS subsystem.
 */
@ConfigData("tss")
public record TssConfig(
        @ConfigProperty(defaultValue = "60s") @NetworkProperty Duration bootstrapHintsKeyGracePeriod,
        @ConfigProperty(defaultValue = "300s") @NetworkProperty Duration transitionHintsKeyGracePeriod,
        @ConfigProperty(defaultValue = "60s") @NetworkProperty Duration bootstrapProofKeyGracePeriod,
        @ConfigProperty(defaultValue = "300s") @NetworkProperty Duration transitionProofKeyGracePeriod,
        @ConfigProperty(defaultValue = "10s") @NetworkProperty Duration crsUpdateContributionTime,
        @ConfigProperty(defaultValue = "5s") @NetworkProperty Duration crsFinalizationDelay,
        @ConfigProperty(defaultValue = "1024") @NetworkProperty int initialCrsParties,
        @ConfigProperty(defaultValue = "false") @NetworkProperty boolean hintsEnabled,
        @ConfigProperty(defaultValue = "false") @NetworkProperty boolean historyEnabled,
        @ConfigProperty(defaultValue = "false") @NetworkProperty boolean crsEnabled) {}
