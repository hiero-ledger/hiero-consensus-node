// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.builder;

import static org.hiero.consensus.platformstate.PlatformStateUtils.roundOf;

import com.swirlds.state.merkle.VirtualMapState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.OptionalLong;
import org.hiero.consensus.model.event.EventHashFactory;
import org.hiero.consensus.model.hashgraph.ConsensusConstants;
import org.hiero.consensus.platformstate.PlatformStateUtils;

/**
 * A utility class for calculating the event cutover round based on the initial state, upgrade status, and event cutover
 * configuration.
 */
public class EventCutoverCalculator {

    /**
     * Determines whether the event cutover value in state must change. The value in state only changes when starting
     * from genesis or after an upgrade.
     *
     * @param initialState       the state the node is starting from
     * @param isUpgrade          true if the node is starting after an upgrade
     * @param eventCutoverActive true if the event cutover is enabled in the config
     * @return the event cutover value to record in state, or empty if the value in state must not change here
     */
    @NonNull
    public static OptionalLong calculateEventCutoverRound(
            @NonNull final VirtualMapState initialState, final boolean isUpgrade, final boolean eventCutoverActive) {
        final boolean isGenesis = PlatformStateUtils.isGenesisStateOf(initialState);
        final long eventCutoverMinBirthRound = PlatformStateUtils.eventCutoverMinBirthRoundOf(initialState);

        if (!isGenesis && !isUpgrade) {
            // Restarting the same version. Use the value in state, which can only change at genesis or an upgrade.
            EventHashFactory.initialize(eventCutoverMinBirthRound > 0 ? eventCutoverMinBirthRound : Long.MAX_VALUE);
            return OptionalLong.empty();
        }

        if (eventCutoverActive) {
            if (isGenesis) {
                // Every event is post cutover. A genesis state has no platform state to modify yet, so
                // DefaultTransactionHandler records this value in state when it handles the first round.
                EventHashFactory.initialize(ConsensusConstants.ROUND_FIRST);
                return OptionalLong.empty();
            }
            if (eventCutoverMinBirthRound <= 0) {
                // It's time to do the cutover now. Update the value in state to the first birth round post cutover.
                // The initial state is a freeze state.
                final long cutoverBirthRound = roundOf(initialState) + 1;
                EventHashFactory.initialize(cutoverBirthRound);
                return OptionalLong.of(cutoverBirthRound);
            }
            // The cutover has already happened and the flag has not been reset. Initialize the
            // EventHashFactory with the value in state so that it uses the correct hash type for events.
            EventHashFactory.initialize(eventCutoverMinBirthRound);
            return OptionalLong.empty();
        }

        if (eventCutoverMinBirthRound > 0) {
            // The cutover has already happened, but the config says it is not active. Time to reset the value in state
            // to 0. By now every non-ancient event is SHA-256.
            EventHashFactory.initialize(0);
            return OptionalLong.of(0);
        }

        // The cutover is not active and has not happened.
        EventHashFactory.initialize(Long.MAX_VALUE);
        return OptionalLong.empty();
    }
}
