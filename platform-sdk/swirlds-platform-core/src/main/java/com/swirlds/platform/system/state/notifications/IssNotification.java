// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.state.notifications;

import com.swirlds.common.notification.AbstractNotification;
import com.swirlds.common.platform.NodeId;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Objects;

/**
 * This {@link com.swirlds.common.notification.Notification Notification} is triggered when there is an ISS (i.e. an
 * Invalid State Signature). State is guaranteed to hold a reservation until the callback completes.
 */
public class IssNotification extends AbstractNotification {

    private final long round;

    public enum IssType {
        /**
         * Another node is in disagreement with the consensus hash.
         */
        OTHER_ISS,
        /**
         * This node is in disagreement with the consensus hash.
         */
        SELF_ISS,
        /**
         * There exists no consensus hash because of severe hash disagreement.
         */
        CATASTROPHIC_ISS
    }

    private final IssType issType;

    /**
     * Create a new ISS notification.
     *
     * @param round       the round when the ISS occurred
     * @param issType     the type of the ISS
     */
    public IssNotification(final long round, @NonNull final IssType issType) {
        this.issType = Objects.requireNonNull(issType, "issType must not be null");
        this.round = round;
    }

    /**
     * @deprecated this method always returns null and will be removed in a future release
     */
    @Nullable
    @Deprecated(forRemoval = true)
    public NodeId getOtherNodeId() {
        return null;
    }

    /**
     * Get the round of the ISS.
     */
    public long getRound() {
        return round;
    }

    /**
     * The type of the ISS.
     */
    public IssType getIssType() {
        return issType;
    }
}
