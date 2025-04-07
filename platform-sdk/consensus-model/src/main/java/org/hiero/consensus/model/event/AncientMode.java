// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.model.event;

import static org.hiero.consensus.model.event.EventConstants.FIRST_GENERATION;
import static org.hiero.consensus.model.hashgraph.ConsensusConstants.ROUND_FIRST;

import com.hedera.hapi.platform.event.EventDescriptor;
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
     * Depending on the ancient mode, select the appropriate indicator.
     *
     * @param event the event to use for the selection
     * @return the selected indicator
     */
    public long selectIndicator(@NonNull final PlatformEvent event) {
        // an event may not have been hashes, and so its descriptor may not be available
        // so we use the generation and birth round directly
        return switch (this) {
            case GENERATION_THRESHOLD -> event.getGeneration();
            case BIRTH_ROUND_THRESHOLD -> event.getBirthRound();
        };
    }

    /**
     * Depending on the ancient mode, select the appropriate indicator.
     *
     * @param eventDescriptor the event descriptor to use for the selection
     * @return the selected indicator
     */
    public long selectIndicator(@NonNull final EventDescriptorWrapper eventDescriptor) {
        return selectIndicator(eventDescriptor.eventDescriptor());
    }

    /**
     * Depending on the ancient mode, select the appropriate indicator.
     *
     * @param eventDescriptor the event descriptor to use for the selection
     * @return the selected indicator
     */
    public long selectIndicator(@NonNull final EventDescriptor eventDescriptor) {
        return switch (this) {
            case GENERATION_THRESHOLD -> eventDescriptor.generation();
            case BIRTH_ROUND_THRESHOLD -> eventDescriptor.birthRound();
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
