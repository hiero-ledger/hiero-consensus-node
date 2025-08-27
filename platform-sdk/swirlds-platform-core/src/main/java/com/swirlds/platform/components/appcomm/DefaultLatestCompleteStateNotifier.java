// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.components.appcomm;

import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.system.state.notifications.NewSignedStateNotification;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.consensus.model.hashgraph.ConsensusConstants;

/**
 * Default implementation of {@link LatestCompleteStateNotifier}.
 */
public class DefaultLatestCompleteStateNotifier implements LatestCompleteStateNotifier {
    /**
     * The round of the latest state provided to the application
     */
    private long latestStateProvidedRound = ConsensusConstants.ROUND_UNDEFINED;

    /**
     * {@inheritDoc}
     */
    @Override
    @Nullable
    public CompleteStateNotificationWithCleanup latestCompleteStateHandler(
            @NonNull final ReservedSignedState reservedSignedState) {
        if (reservedSignedState.get().getRound() <= latestStateProvidedRound) {
            // this state is older than the latest state provided to the application, no need to notify
            reservedSignedState.close();
            return null;
        }
        latestStateProvidedRound = reservedSignedState.get().getRound();
        final NewSignedStateNotification notification = new NewSignedStateNotification(
                reservedSignedState.get().getState(),
                reservedSignedState.get().getRound(),
                reservedSignedState.get().getConsensusTimestamp());

        return new CompleteStateNotificationWithCleanup(notification, r -> reservedSignedState.close());
    }
}
