/*
 * Copyright (C) 2023-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
        @ConfigProperty(defaultValue = "false") @NetworkProperty boolean historyEnabled) {}
