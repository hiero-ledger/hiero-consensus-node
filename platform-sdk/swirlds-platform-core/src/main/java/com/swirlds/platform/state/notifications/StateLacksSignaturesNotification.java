// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.notifications;

import com.swirlds.platform.state.signed.SignedState;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.notification.AbstractNotification;
import org.hiero.consensus.model.notification.Notification;

/**
 * A {@link Notification Notification} that a signed state fails to collect sufficient
 * signatures before being ejected from memory. State is guaranteed to hold a reservation until callback is finished.
 */
public class StateLacksSignaturesNotification extends AbstractNotification {

    private final SignedState signedState;

    // FUTURE WORK:
    //  this field can be removed once PlatformContext maintains a single notification engine per platform instance
    private final NodeId selfId;

    /**
     * Create a notification for a state that was unable to collect enough signatures.
     *
     * @param signedState the state that just became signed
     * @param selfId      the ID of this node
     */
    public StateLacksSignaturesNotification(final SignedState signedState, final NodeId selfId) {
        this.signedState = signedState;
        this.selfId = selfId;
    }

    /**
     * Get the signed state that was unable to collect sufficient signatures.
     */
    public SignedState getSignedState() {
        return signedState;
    }

    /**
     * The ID of this node.
     */
    public NodeId getSelfId() {
        return selfId;
    }
}
