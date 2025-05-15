// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.consensus;

import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.config.api.Configuration;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.config.EventConfig;
import org.hiero.consensus.model.event.AncientMode;
import org.hiero.consensus.model.hashgraph.EventWindow;

/**
 * Utility class for creating instances of {@link EventWindow}.
 */
public final class EventWindowUtils {

    /**
     * Private constructor to prevent instantiation.
     */
    private EventWindowUtils() {}

    /**
     * Same as {@link #createEventWindow(ConsensusSnapshot, AncientMode, int)} but uses the configuration to get the
     * {@code ancientMode} and {@code roundsNonAncient}.
     */
    public static @NonNull EventWindow createEventWindow(
            @NonNull final ConsensusSnapshot snapshot, @NonNull final Configuration configuration) {
        return createEventWindow(
                snapshot,
                configuration.getConfigData(EventConfig.class).getAncientMode(),
                configuration.getConfigData(ConsensusConfig.class).roundsNonAncient());
    }

    /**
     * Creates a new instance of {@link EventWindow} with the specified parameters.
     *
     * @param snapshot         the snapshot of the consensus state
     * @param ancientMode      the ancient mode to use
     * @param roundsNonAncient the number of rounds that are considered non-ancient
     * @return a new instance of {@link EventWindow}
     */
    public static @NonNull EventWindow createEventWindow(
            @NonNull final ConsensusSnapshot snapshot,
            @NonNull final AncientMode ancientMode,
            final int roundsNonAncient) {
        final long ancientThreshold = RoundCalculationUtils.getAncientThreshold(roundsNonAncient, snapshot);
        return new EventWindow(
                snapshot.round(),
                // by default, we set the birth round to the pending round
                snapshot.round() + 1,
                ancientThreshold,
                ancientThreshold,
                ancientMode);
    }
}
