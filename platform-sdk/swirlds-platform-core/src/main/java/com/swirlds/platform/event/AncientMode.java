// SPDX-License-Identifier: Apache-2.0
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
