// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.platform.components.transaction.system.ScopedSystemTransaction;
import com.swirlds.platform.state.iss.IssDetector;
import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.system.state.notifications.IssNotification;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * A helper class for testing the {@link com.swirlds.platform.state.iss.IssDetector}.
 */
public class IssDetectorTestHelper {
    private int selfIssCount = 0;
    private int catastrophicIssCount = 0;

    private final List<IssNotification> issNotificationList = new ArrayList<>();

    private final IssDetector issDetector;

    public IssDetectorTestHelper(@NonNull final IssDetector issDetector) {
        this.issDetector = Objects.requireNonNull(issDetector);
    }

    public void handleStateSignatureTransactions(
            @NonNull final Collection<ScopedSystemTransaction<StateSignatureTransaction>> systemTransactions) {
        trackIssNotifications(issDetector.handleStateSignatureTransactions(systemTransactions));
    }

    public void handleState(@NonNull final ReservedSignedState reservedSignedState) {
        trackIssNotifications(issDetector.handleState(reservedSignedState));
    }

    public void overridingState(@NonNull final ReservedSignedState state) {
        trackIssNotifications(issDetector.overridingState(state));
    }

    /**
     * Keeps track of all ISS notifications passed to this method over the course of a test, for the sake of validation
     *
     * @param notifications the list of ISS notifications to track. permitted to be null.
     */
    private void trackIssNotifications(@Nullable final List<IssNotification> notifications) {
        if (notifications == null) {
            return;
        }

        notifications.forEach(notification -> {
            if (notification.getIssType() == IssNotification.IssType.SELF_ISS) {
                selfIssCount++;
            } else if (notification.getIssType() == IssNotification.IssType.CATASTROPHIC_ISS) {
                catastrophicIssCount++;
            }

            issNotificationList.add(notification);
        });
    }

    /**
     * Get the number of self ISS notifications that have been observed.
     *
     * @return the number of self ISS notifications
     */
    public int getSelfIssCount() {
        return selfIssCount;
    }

    /**
     * Get the number of catastrophic ISS notifications that have been observed.
     *
     * @return the number of catastrophic ISS notifications
     */
    public int getCatastrophicIssCount() {
        return catastrophicIssCount;
    }

    /**
     * Get the list of all ISS notifications that have been observed.
     *
     * @return the list of all ISS notifications
     */
    public List<IssNotification> getIssNotificationList() {
        return issNotificationList;
    }
}
