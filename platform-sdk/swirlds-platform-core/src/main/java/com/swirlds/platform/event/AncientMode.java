/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
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

package com.swirlds.platform.event;

import static com.swirlds.platform.consensus.ConsensusConstants.ROUND_FIRST;
import static com.swirlds.platform.system.events.EventConstants.FIRST_GENERATION;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.eventhandling.EventConfig;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * The strategy used to determine if an event is ancient. There are currently two types: one bound by generations and
 * one bound by birth rounds. The original definition of ancient used generations. The new definition for ancient uses
 * birth rounds. Once migration has been completed to birth rounds, support for the generation defined ancient threshold
 * will be removed.
 */
public enum AncientMode {
    /**
     * The ancient threshold is defined by generations.
     */
    GENERATION_THRESHOLD,
    /**
     * The ancient threshold is defined by birth rounds.
     */
    BIRTH_ROUND_THRESHOLD;

    /**
     * Get the currently configured ancient mode
     *
     * @param platformContext the platform context
     * @return the currently configured ancient mode
     */
    public static AncientMode getAncientMode(@NonNull final PlatformContext platformContext) {
        return platformContext
                .getConfiguration()
                .getConfigData(EventConfig.class)
                .getAncientMode();
    }

    /**
     * Depending on the ancient mode, select the appropriate indicator.
     *
     * @param generationIndicator the indicator to use if in generation mode
     * @param birthRoundIndicator the indicator to use if in birth round mode
     * @return the selected indicator
     */
    public long selectIndicator(final long generationIndicator, final long birthRoundIndicator) {
        return switch (this) {
            case GENERATION_THRESHOLD -> generationIndicator;
            case BIRTH_ROUND_THRESHOLD -> birthRoundIndicator;
        };
    }

    /**
     * Depending on the ancient mode, select the appropriate indicator for events created at genesis.
     *
     * @return the selected indicator
     */
    public long getGenesisIndicator() {
        return switch (this) {
            case GENERATION_THRESHOLD -> FIRST_GENERATION;
            case BIRTH_ROUND_THRESHOLD -> ROUND_FIRST;
        };
    }
}
