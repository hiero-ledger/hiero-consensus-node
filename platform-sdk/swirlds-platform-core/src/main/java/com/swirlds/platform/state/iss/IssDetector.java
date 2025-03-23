// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.iss;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.component.framework.component.InputWireLabel;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.system.status.actions.CatastrophicFailureAction;
import com.swirlds.platform.system.status.actions.PlatformStatusAction;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.hiero.consensus.model.notification.IssNotification;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;

/**
 * Keeps track of the state hashes reported by all network nodes. Responsible for detecting ISS events.
 */
public interface IssDetector {

    /**
     * Use this constant if the consensus hash manager should not ignore any rounds.
     */
    int DO_NOT_IGNORE_ROUNDS = -1;

    /**
     * This method is called once all preconsensus events have been replayed.
     */
    void signalEndOfPreconsensusReplay();

    /**
     * Called when a round has been completed and contains state signature transactions, but no state is created.
     *
     * @param systemTransactions the state signature transactions to be handled
     * @return a list of ISS notifications, or null if no ISS occurred
     */
    @InputWireLabel("post consensus state signatures")
    @Nullable
    List<IssNotification> handleStateSignatureTransactions(
            @NonNull Collection<ScopedSystemTransaction<StateSignatureTransaction>> systemTransactions);

    @InputWireLabel("hashed states")
    @Nullable
    List<IssNotification> handleState(@NonNull ReservedSignedState reservedSignedState);

    /**
     * Called when an overriding state is obtained, i.e. via reconnect or state loading.
     * <p>
     * Expects the input state to have been reserved by the caller for this method. This method will release the state
     * reservation when it is done with it.
     *
     * @param state the state that was loaded
     * @return a list of ISS notifications, or null if no ISS occurred
     */
    @Nullable
    List<IssNotification> overridingState(@NonNull ReservedSignedState state);

    /**
     * Given an ISS notification, produce the appropriate status action.
     *
     * @param notification the ISS notification
     * @return the status action, or null if no action is needed
     */
    @Nullable
    default PlatformStatusAction getStatusAction(final IssNotification notification) {
        if (Set.of(IssNotification.IssType.SELF_ISS, IssNotification.IssType.CATASTROPHIC_ISS)
                .contains(notification.getIssType())) {
            return new CatastrophicFailureAction();
        }
        // don't change status for other types of ISS
        return null;
    }
}
